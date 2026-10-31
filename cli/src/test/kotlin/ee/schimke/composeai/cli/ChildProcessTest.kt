package ee.schimke.composeai.cli

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.streams.toList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

class ChildProcessTest {

  @Test
  fun `the child's exit code is returned`() {
    assumeTrue(isPosix())
    assertEquals(7, ProcessBuilder("sh", "-c", "exit 7").runTiedToLauncher())
  }

  /**
   * The server's start script runs its JVM as a child of its own, so ending only the direct child
   * leaves the server running. This is the case a launcher killed by a hook timeout hit.
   */
  @Test
  fun `a grandchild is taken down with the child`() {
    assumeTrue(isPosix())
    val process = ProcessBuilder("sh", "-c", "sleep 300 & wait").start()
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
    while (process.toHandle().children().count() == 0L && System.nanoTime() < deadline) {
      Thread.sleep(20)
    }
    val grandchildren = process.toHandle().descendants().toList()
    assertTrue(grandchildren.isNotEmpty(), "sleep did not start")

    destroyTree(process)

    assertTrue(process.waitFor(5, TimeUnit.SECONDS))
    val stillRunning = grandchildren.filter { running(it.pid()) }
    assertTrue(stillRunning.isEmpty(), "still running: $stillRunning")
  }

  /** A child that ignores SIGTERM is forced once the grace period runs out. */
  @Test
  fun `a child that ignores SIGTERM is forced`() {
    assumeTrue(isPosix())
    val process = ProcessBuilder("sh", "-c", "trap '' TERM; while :; do sleep 1; done").start()
    Thread.sleep(200)

    destroyTree(process, grace = 200, unit = TimeUnit.MILLISECONDS)

    assertTrue(process.waitFor(5, TimeUnit.SECONDS))
  }

  /**
   * A SIGKILLed launcher runs no shutdown hook. The kernel closes the watchdog's stdin when the
   * launcher dies however it dies; closing it here is that same event.
   */
  @Test
  fun `the watchdog takes the child tree down once the launcher's end of its pipe closes`() {
    assumeTrue(isPosix())
    val child = ProcessBuilder("sh", "-c", "sleep 300 & wait").start()
    awaitChildren(child)
    val tree = child.toHandle().descendants().toList() + child.toHandle()
    val watchdog = startOrphanWatchdog(child)
    assertTrue(watchdog != null, "watchdog did not start")

    watchdog.outputStream.close()

    assertTrue(child.waitFor(10, TimeUnit.SECONDS), "child survived its launcher")
    val stillRunning = tree.filter { running(it.pid()) }
    assertTrue(stillRunning.isEmpty(), "still running: $stillRunning")
    assertTrue(watchdog.waitFor(10, TimeUnit.SECONDS))
  }

  /** A launcher that outlives its child stops the watchdog; it must not signal anything. */
  @Test
  fun `a destroyed watchdog leaves the child alone`() {
    assumeTrue(isPosix())
    val child = ProcessBuilder("sh", "-c", "sleep 300").start()
    val watchdog = startOrphanWatchdog(child)!!
    watchdog.destroy()
    assertTrue(watchdog.waitFor(5, TimeUnit.SECONDS))
    Thread.sleep(300)
    assertTrue(child.isAlive, "the watchdog signalled a child it was told to leave")
    destroyTree(child)
  }

  @Test
  fun `the watchdog exits quietly when the child already ended`() {
    assumeTrue(isPosix())
    val child = ProcessBuilder("sh", "-c", "exit 0").start()
    assertTrue(child.waitFor(5, TimeUnit.SECONDS))
    val watchdog = startOrphanWatchdog(child)!!
    watchdog.outputStream.close()
    assertTrue(watchdog.waitFor(5, TimeUnit.SECONDS))
    assertEquals(0, watchdog.exitValue())
  }

  private fun awaitChildren(process: Process) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
    while (process.toHandle().children().count() == 0L && System.nanoTime() < deadline) {
      Thread.sleep(20)
    }
  }

  private fun isPosix() = !System.getProperty("os.name").startsWith("Windows")

  /**
   * Whether [pid] is still executing. An orphan that was killed stays a zombie until PID 1 reaps
   * it, and a container whose PID 1 never does reports it alive to [ProcessHandle.isAlive] forever,
   * so on Linux the state letter decides. Elsewhere [ProcessHandle] is all there is.
   */
  private fun running(pid: Long): Boolean {
    val stat = File("/proc/$pid/stat")
    if (!File("/proc/self/stat").exists()) {
      return ProcessHandle.of(pid).map { it.isAlive }.orElse(false)
    }
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
    while (System.nanoTime() < deadline) {
      val state = runCatching { stat.readText().substringAfterLast(')').trim().first() }.getOrNull()
      if (state == null || state == 'Z' || state == 'X') return false
      Thread.sleep(50)
    }
    return true
  }
}
