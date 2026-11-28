package ee.schimke.composeai.cli.simulator

import java.util.concurrent.TimeUnit
import kotlin.streams.toList

/**
 * Run a launcher's child to completion, taking it and its descendants down with the launcher on
 * normal shutdown signals or SIGKILL (via a POSIX pipe-bound watchdog).
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
      watchdog?.destroy()
    }
  try {
    Runtime.getRuntime().removeShutdownHook(hook)
  } catch (_: IllegalStateException) {
    // Shutdown already began; the hook finds the child gone and returns.
  }
  return exit
}

private fun startOrphanWatchdog(child: Process): Process? {
  if (System.getProperty("os.name").startsWith("Windows")) return null
  return runCatching {
    ProcessBuilder("sh", "-c", ORPHAN_WATCHDOG_SCRIPT, "compose-preview-watchdog", "${child.pid()}")
      .redirectOutput(ProcessBuilder.Redirect.DISCARD)
      .redirectError(ProcessBuilder.Redirect.DISCARD)
      .start()
  }
    .getOrNull()
}

private const val ORPHAN_WATCHDOG_SCRIPT =
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
