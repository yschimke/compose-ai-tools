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
 * SIGKILL runs no hook; nothing in this process can help with that.
 */
internal fun ProcessBuilder.runTiedToLauncher(): Int {
  val process = start()
  val hook = Thread { destroyTree(process) }
  Runtime.getRuntime().addShutdownHook(hook)
  val exit = process.waitFor()
  try {
    Runtime.getRuntime().removeShutdownHook(hook)
  } catch (_: IllegalStateException) {
    // Shutdown already began; the hook finds the child gone and returns.
  }
  return exit
}

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
