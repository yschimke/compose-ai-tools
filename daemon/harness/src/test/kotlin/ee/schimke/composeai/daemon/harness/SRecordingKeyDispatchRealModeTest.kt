package ee.schimke.composeai.daemon.harness

import ee.schimke.composeai.daemon.protocol.RecordingScriptEvent
import ee.schimke.composeai.daemon.protocol.RecordingScriptEventStatus
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.time.Duration.Companion.seconds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test

/**
 * Scenario **SRecordingKeyDispatch (real mode, desktop)** — closes the deferred real-mode harness
 * item from issue #1203 for the `record_preview` surface. Mirrors
 * [SInteractiveKeyDispatchRealModeTest]'s boot path but exercises the scripted recording pipeline
 * instead of `interactive/input`.
 *
 * Wire sequence:
 * 1. `initialize` / `initialized` handshake.
 * 2. `recording/start` against `KeyPressColorSquare` → `recordingId`.
 * 3. `recording/script` notification with a single `input.keyDown` event at `tMs = 0` carrying
 *    `keyCode = "29"` (Android `KEYCODE_A`).
 * 4. `recording/stop` → frame count + framesDir + per-event evidence.
 * 5. Assert `frame-00000.png` paints green (the keyDown drained before the first render tick,
 *    flipping the held composition to `pressed = true`).
 * 6. Assert the `input.keyDown` evidence reports `APPLIED`, NOT the pre-#1203 `UNSUPPORTED`. This
 *    is the contract the `input.keyboard` data extension's `supported = true` flag advertises to
 *    clients (`MCP record_preview`, the VS Code recording flow).
 * 7. `shutdown` / `exit`.
 *
 * **Skipped under fake mode.** Same `harnessHost == "real"` gate as the interactive variant.
 *
 * **In-process counterpart.**
 * `DesktopRecordingSessionTest.scripted_keyDown_flips_state_and_emits_applied_evidence` exercises
 * the same script registry and Skiko translation table without paying the subprocess cost. The
 * real-mode version proves the wire framing for `recording/script` + `recording/stop` round-trips
 * the per-event evidence correctly.
 */
class SRecordingKeyDispatchRealModeTest {

  @Test
  fun real_mode_scripted_keyDown_emits_green_frame_and_applied_evidence() {
    Assume.assumeTrue(
      "Skipping SRecordingKeyDispatchRealModeTest — set -Pharness.host=real to enable.",
      HarnessTestSupport.harnessHost() == "real",
    )
    Assume.assumeTrue(
      "Skipping SRecordingKeyDispatchRealModeTest — desktop variant; set -Ptarget=desktop (default).",
      HarnessTestSupport.harnessTarget() == "desktop",
    )

    val moduleBuildDir = File("build")
    val rendersDir =
      File(moduleBuildDir, "daemon-harness/renders/skey-recording-real").apply {
        deleteRecursively()
        mkdirs()
      }
    val recordingsRoot =
      File(moduleBuildDir, "daemon-harness/recordings/skey-recording-real").apply {
        deleteRecursively()
        mkdirs()
      }
    val manifestFile =
      File(moduleBuildDir, "daemon-harness/manifests/skey-recording-previews.json").apply {
        parentFile.mkdirs()
      }
    manifestFile.writeText(
      """
      {
        "previews": [
          {
            "id": "key-press-color-square",
            "className": "ee.schimke.composeai.daemon.RedFixturePreviewsKt",
            "functionName": "KeyPressColorSquare",
            "widthPx": 64,
            "heightPx": 64,
            "density": 1.0,
            "showBackground": true,
            "outputBaseName": "skey-recording"
          }
        ]
      }
      """
        .trimIndent()
    )

    val classpath =
      System.getProperty("java.class.path")
        .split(File.pathSeparator)
        .filter { it.isNotBlank() }
        .map { File(it) }

    val launcher =
      RealDesktopHarnessLauncher(
        rendersDir = rendersDir,
        previewsManifest = manifestFile,
        classpath = classpath,
        // The desktop host's `recording/start` honours this sysprop for the per-recording
        // frames directory. Pointing at a per-test temp folder keeps consecutive runs from
        // racing on shared paths.
        extraJvmArgs = listOf("-Dcomposeai.daemon.recordingsDir=${recordingsRoot.absolutePath}"),
      )

    val client = HarnessClient.start(launcher)
    try {
      val initResult = client.initialize()
      assertEquals(2, initResult.protocolVersion)
      client.sendInitialized()

      // 1. Start a 30 fps recording. The default scale (1.0) keeps frames at native size so the
      // dominant-fraction assertion isn't smeared by resampling.
      val startResult = client.recordingStart(previewId = PREVIEW_ID, fps = 30, scale = 1.0f)
      val recordingId = startResult.recordingId

      // 2. Post a single keyDown at tMs = 0. The tick loop drains pending events before each
      // frame's render, so this lands BEFORE the first frame and frame 0 paints green.
      client.recordingScript(
        recordingId = recordingId,
        events = listOf(RecordingScriptEvent(tMs = 0L, kind = "input.keyDown", keyCode = "29")),
      )

      // 3. Stop — daemon flushes frames + returns evidence.
      val stopResult = client.recordingStop(recordingId)
      assertTrue("recording must emit ≥ 1 frame", stopResult.frameCount >= 1)

      val frame0 = File(stopResult.framesDir, "frame-00000.png")
      assertTrue("frame-00000.png must exist: ${frame0.absolutePath}", frame0.isFile)
      val frame0Img = ImageIO.read(frame0)
      assertNotNull("frame-00000.png must decode", frame0Img)
      assertTrue(
        "frame 0 must be mostly green (pressed = true after input.keyDown@0); got " +
          "dominantGreen=${dominantFraction(frame0Img!!, 0x66BB6A)}. Load-bearing #1203 assertion " +
          "for the recording wire: the desktop script registry must dispatch keyDown.",
        dominantFraction(frame0Img, 0x66BB6A) > 0.9,
      )

      // 4. Per-event evidence must report APPLIED. This is the contract clients (MCP, VS Code
      // recording panel) read off `RecordingStopResult.scriptEvents`; a regression that flips
      // it back to UNSUPPORTED would silently break agent-authored playback scripts.
      val keyEvidence = stopResult.scriptEvents.find { it.kind == "input.keyDown" }
      assertNotNull(
        "every scripted event must have evidence; got ${stopResult.scriptEvents}",
        keyEvidence,
      )
      assertEquals(
        "input.keyDown evidence must be APPLIED on desktop post-#1203 — got " +
          "${keyEvidence!!.status} (message=${keyEvidence.message})",
        RecordingScriptEventStatus.APPLIED,
        keyEvidence.status,
      )

      val exitCode = client.shutdownAndExit(timeout = 30.seconds)
      assertEquals("daemon must exit cleanly. Stderr=\n${client.dumpStderr()}", 0, exitCode)
    } catch (t: Throwable) {
      System.err.println(
        "SRecordingKeyDispatchRealModeTest failed; stderr from daemon:\n" + client.dumpStderr()
      )
      throw t
    } finally {
      try {
        client.close()
      } catch (_: Throwable) {}
    }
  }

  private fun dominantFraction(img: java.awt.image.BufferedImage, expectedRgb: Int): Double {
    val expR = (expectedRgb shr 16) and 0xFF
    val expG = (expectedRgb shr 8) and 0xFF
    val expB = expectedRgb and 0xFF
    var matching = 0
    val total = img.width.toLong() * img.height.toLong()
    for (y in 0 until img.height) {
      for (x in 0 until img.width) {
        val argb = img.getRGB(x, y)
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        if (abs(r - expR) <= 16 && abs(g - expG) <= 16 && abs(b - expB) <= 16) matching++
      }
    }
    return matching.toDouble() / total.toDouble()
  }

  companion object {
    private const val PREVIEW_ID = "key-press-color-square"
  }
}
