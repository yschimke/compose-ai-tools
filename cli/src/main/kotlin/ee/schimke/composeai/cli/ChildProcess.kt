package ee.schimke.composeai.cli

import java.util.concurrent.TimeUnit
import kotlin.streams.toList

/**
 * Run a launcher's child to completion, taking it down with the launcher.
 *
 * The launchers ([ServeCommand], `mcp serve`) exec the server's start script as a child, and that
 * script starts a JVM of its own. When something kills the launcher (a SessionStart hook's timeout,
 * an MCP host closing, Ctrl-C), nothing told the child, and the server outlived its launcher. A
 * shutdown hook runs on SIGTERM, SIGINT and SIGHUP, and takes the whole tree down: descendants
 * first, because the start script's JVM is a grandchild, and ending the script does not end it.
 *
 * SIGKILL runs no hook, so on POSIX an [orphan watchdog][startOrphanWatchdog] outside this JVM
 * covers it: a launcher killed outright no longer leaves `serve` running under PID 1
 * (yschimke/compose-ai-tools#6364).
 */
internal fun ProcessBuilder.runTiedToLauncher(): Int {
  val process = start()
  val hook = Thread { destroyTree(process) }
  Runtime.getRuntime().addShutdownHook(hook)
  val watchdog = startOrphanWatchdog(process)
  val exit =
    try {
      process.waitFor()
    } finally {
      // The child is done: a watchdog left running could later signal a reused PID.
      watchdog?.destroy()
    }
  try {
    Runtime.getRuntime().removeShutdownHook(hook)
  } catch (_: IllegalStateException) {
    // Shutdown already began; the hook finds the child gone and returns.
  }
  return exit
}

/**
 * Start a `sh` sidecar that ends [child]'s process tree once this launcher is gone, however it
 * went. The sidecar blocks reading a pipe only this JVM holds the write end of; the kernel closes
 * that end when the JVM exits for any reason, SIGKILL included, so the read returns at once and no
 * polling is involved. A launcher that outlives its child destroys the sidecar first. Returns null
 * on Windows or when `sh` cannot start: the shutdown hook still covers every catchable signal.
 *
 * The returned process must stay referenced (and its stdin open) for as long as [child] runs.
 */
internal fun startOrphanWatchdog(child: Process): Process? {
  if (System.getProperty("os.name").startsWith("Windows")) return null
  return runCatching {
    ProcessBuilder("sh", "-c", ORPHAN_WATCHDOG_SCRIPT, "compose-preview-watchdog", "${child.pid()}")
      .redirectOutput(ProcessBuilder.Redirect.DISCARD)
      .redirectError(ProcessBuilder.Redirect.DISCARD)
      .start()
  }
    .getOrNull()
}

/**
 * `$1` is the child's PID. After stdin reaches EOF, signal the child and every descendant (the
 * start script's JVM is a grandchild), TERM first and KILL after the same grace [destroyTree]
 * gives. `ps -A -o pid= -o ppid=` is POSIX, so this runs on macOS and Linux alike.
 */
internal const val ORPHAN_WATCHDOG_SCRIPT =
  """
read _ignored
child=${'$'}1
kill -0 "${'$'}child" 2>/dev/null || exit 0
tree=${'$'}(ps -A -o pid= -o ppid= | awk -v root="${'$'}child" '
  { parent[${'$'}1] = ${'$'}2 }
  END {
    for (p in parent) {
      q = p; n = 0
      while (q != root && (q in parent) && n++ < 64) q = parent[q]
      if (q == root) print p
    }
  }')
[ -n "${'$'}tree" ] || exit 0
kill -TERM ${'$'}tree 2>/dev/null
sleep 2
kill -KILL ${'$'}tree 2>/dev/null
exit 0
"""

/** Ask the process and its descendants to stop, then force any still running after [grace]. */
internal fun destroyTree(process: Process, grace: Long = 2, unit: TimeUnit = TimeUnit.SECONDS) {
  val tree = process.toHandle().descendants().toList() + process.toHandle()
  tree.forEach { it.destroy() }
  val deadline = System.nanoTime() + unit.toNanos(grace)
  for (handle in tree) {
    val remaining = deadline - System.nanoTime()
    if (remaining > 0) {
      try {
        handle.onExit().get(remaining, TimeUnit.NANOSECONDS)
      } catch (_: Exception) {
        // Timed out or interrupted; forced below.
      }
    }
    if (handle.isAlive) handle.destroyForcibly()
  }
}
