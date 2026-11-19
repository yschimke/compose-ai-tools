package ee.schimke.composeai.cli.serve

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A daemon that dies mid-command must say how: its exit status and the end of its stderr, not only
 * "closed stdout" (yschimke/watchface-tools#72, where the cause was on a stderr nobody kept).
 */
class DwfApkDaemonDeathTest {
  /** Reads one command, writes [stderrLines] numbered lines to stderr, and exits with [status]. */
  private fun dyingDaemon(
    stderrLines: Int,
    status: Int,
  ): DwfApkDaemonBridge.SimulatorDaemonProcess {
    val script =
      """
      IFS= read -r line
      i=1
      while [ ${'$'}i -le $stderrLines ]; do echo "stderr line ${'$'}i" >&2; i=${'$'}((i+1)); done
      exit $status
      """
        .trimIndent()
    val proc = ProcessBuilder("sh", "-c", script).start()
    return DwfApkDaemonBridge.SimulatorDaemonProcess.attach(File("runtime.apk"), proc)
  }

  @Test
  fun `a daemon that dies reports its exit status and the last 40 lines of its stderr`() {
    dyingDaemon(stderrLines = 50, status = 7).use { daemon ->
      val e = assertFailsWith<IllegalStateException> { daemon.command("watchface") }
      val message = e.message!!
      assertTrue("closed stdout unexpectedly while answering `watchface`" in message, message)
      assertTrue("exited with status 7" in message, message)
      assertTrue("Last 40 lines of its stderr" in message, message)
      assertTrue(message.endsWith("stderr line 50"), message)
      assertTrue("stderr line 11\n" in message, message)
      // Bounded: the first ten lines fell out of the tail.
      assertFalse("stderr line 10\n" in message, message)
      assertFalse(daemon.alive)
    }
  }

  @Test
  fun `a daemon killed by a signal says so`() {
    dyingDaemon(stderrLines = 0, status = 137).use { daemon ->
      val message =
        assertFailsWith<IllegalStateException> { daemon.command("screenshot") }.message!!
      assertTrue(
        "status 137 (killed by signal 9, SIGKILL: often the OOM killer)" in message,
        message,
      )
      assertTrue("it wrote nothing to stderr" in message, message)
    }
  }

  @Test
  fun `the tail keeps only the newest lines and cuts long ones`() {
    val tail = DwfApkDaemonBridge.StderrTail(capacity = 3, maxLineLength = 5)
    listOf("a", "b", "c", "d", "0123456789").forEach(tail::add)
    assertEquals(listOf("c", "d", "01234…"), tail.snapshot())
  }

  @Test
  fun `the daemon heap defaults to an eighth of the machine clamped to 768m-2g`() {
    val gb = 1L shl 30
    assertEquals("768m", DwfApkDaemonBridge.defaultMaxHeap(null))
    assertEquals("768m", DwfApkDaemonBridge.defaultMaxHeap(4 * gb))
    assertEquals("1536m", DwfApkDaemonBridge.defaultMaxHeap(12 * gb))
    assertEquals("2048m", DwfApkDaemonBridge.defaultMaxHeap(64 * gb))
  }

  @Test
  fun `the daemon heap override wins, system property first, and malformed values are ignored`() {
    val gb = 1L shl 30
    assertEquals("3g", DwfApkDaemonBridge.daemonMaxHeap("3g", "1g", 64 * gb))
    assertEquals("1g", DwfApkDaemonBridge.daemonMaxHeap(null, "1g", 64 * gb))
    assertEquals("1g", DwfApkDaemonBridge.daemonMaxHeap("lots", " 1g ", 64 * gb))
    assertEquals("2048m", DwfApkDaemonBridge.daemonMaxHeap("", null, 64 * gb))
  }
}
