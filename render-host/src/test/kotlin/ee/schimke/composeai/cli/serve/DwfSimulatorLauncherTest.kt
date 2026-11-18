package ee.schimke.composeai.cli.serve

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class DwfSimulatorLauncherTest {
  /**
   * A shell "daemon" speaking the simulator's JSON-lines protocol: noise lines, then a response per
   * command. `fail` answers `ok:false`; `quiet` answers with no `result`; anything else echoes the
   * line back as `result.line`.
   */
  private fun fakeDaemon(): DwfApkDaemonBridge.SimulatorDaemonProcess {
    val script =
      """
      while IFS= read -r line; do
        id=${'$'}(printf '%s' "${'$'}line" | sed -n 's/.*"id":\([0-9]*\).*/\1/p')
        echo "log noise that is not json"
        echo '{"id":999,"ok":true,"result":"someone else"}'
        case "${'$'}line" in
          *'"cmd":"fail"'*) echo "{\"id\":${'$'}id,\"ok\":false,\"error\":\"boom\"}" ;;
          *'"cmd":"quiet"'*) echo "{\"id\":${'$'}id,\"ok\":true}" ;;
          *) esc=${'$'}(printf '%s' "${'$'}line" | sed 's/"/\\"/g')
             echo "{\"id\":${'$'}id,\"ok\":true,\"result\":{\"line\":\"${'$'}esc\"}}" ;;
        esac
      done
      """
        .trimIndent()
    val proc = ProcessBuilder("sh", "-c", script).redirectErrorStream(false).start()
    return DwfApkDaemonBridge.SimulatorDaemonProcess(
      File("runtime.apk"),
      proc,
      BufferedWriter(OutputStreamWriter(proc.outputStream, Charsets.UTF_8)),
      BufferedReader(InputStreamReader(proc.inputStream, Charsets.UTF_8)),
    )
  }

  @Test
  fun `a command carries its payload and returns the matching result`() {
    fakeDaemon().use { daemon ->
      assertTrue(daemon.alive)
      val result = daemon.command("watchface", buildJsonObject { put("op", "status") })!!.jsonObject
      val line = result["line"]!!.jsonPrimitive.content
      assertTrue(""""cmd":"watchface"""" in line, line)
      assertTrue(""""op":"status"""" in line, line)
    }
  }

  @Test
  fun `a response with no result is null and ids do not cross`() {
    fakeDaemon().use { daemon ->
      assertNull(daemon.command("quiet"))
      assertNotNull(daemon.command("again"))
    }
  }

  @Test
  fun `an ok false answer is an exception naming the command and the daemon's error`() {
    fakeDaemon().use { daemon ->
      val e =
        assertFailsWith<IllegalStateException> { daemon.command("fail", JsonObject(emptyMap())) }
      assertEquals("fail failed: boom", e.message)
      // The channel is still usable: a failed command does not kill the daemon.
      assertTrue(daemon.alive)
    }
  }

  @Test
  fun `closing ends the process and alive follows it`() {
    val daemon = fakeDaemon()
    daemon.close()
    // destroyForcibly is asynchronous; give the process a moment to be reaped.
    repeat(50) {
      if (!daemon.alive) return@repeat
      Thread.sleep(20)
    }
    assertFalse(daemon.alive)
  }

  @Test
  fun `connect without a configured runtime says why and starts nothing`() {
    val saved = System.getProperty(DwfApkWatchFaceRendererBackend.APK_PATH_SYS_PROP)
    System.setProperty(DwfApkWatchFaceRendererBackend.APK_PATH_SYS_PROP, "/nonexistent/runtime.apk")
    try {
      if (DwfApkWatchFaceRendererBackend.configuredApkFile() != null) return // env pins a real one
      val reason = DwfSimulatorLauncher.unavailableReason()
      assertNotNull(reason)
      val e = assertFailsWith<IllegalStateException> { DwfSimulatorLauncher.connect(File("x.apk")) }
      assertEquals(reason, e.message)
    } finally {
      if (saved == null) System.clearProperty(DwfApkWatchFaceRendererBackend.APK_PATH_SYS_PROP)
      else System.setProperty(DwfApkWatchFaceRendererBackend.APK_PATH_SYS_PROP, saved)
    }
  }
}
