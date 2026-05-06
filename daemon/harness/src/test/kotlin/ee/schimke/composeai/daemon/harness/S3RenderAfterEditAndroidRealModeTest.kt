package ee.schimke.composeai.daemon.harness

import ee.schimke.composeai.daemon.protocol.RenderTier
import java.io.File
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test

/**
 * D-harness.v2 Android counterpart of [S3RenderAfterEditRealModeTest] — verifies the daemon serves
 * the *new* preview source after an "edit" (in real mode: a previewId swap; see
 * [ S3RenderAfterEditRealModeTest] for the full mode-divergence story).
 *
 * Reuses `red-square.png` and `blue-square.png` baselines under
 * `daemon/harness/baselines/android/s3/`.
 */
class S3RenderAfterEditAndroidRealModeTest {

  @Test
  fun s3_render_after_edit_real_mode_android() {
    Assume.assumeTrue(
      "Skipping S3RenderAfterEditAndroidRealModeTest — set -Pharness.host=real to enable.",
      HarnessTestSupport.harnessHost() == "real",
    )
    Assume.assumeTrue(
      "Skipping S3RenderAfterEditAndroidRealModeTest — android variant; set -Ptarget=android.",
      HarnessTestSupport.harnessTarget() == "android",
    )

    val redId = "red-square"
    val blueId = "blue-square"
    val paths =
      realAndroidModeScenario(
        name = "s3-android",
        previews =
          listOf(
            RealModePreview(
              id = redId,
              className = "ee.schimke.composeai.daemon.RedFixturePreviewsKt",
              functionName = "RedSquare",
            ),
            RealModePreview(
              id = blueId,
              className = "ee.schimke.composeai.daemon.RedFixturePreviewsKt",
              functionName = "BlueSquare",
            ),
          ),
      )

    val client = HarnessClient.start(paths.launcher)
    try {
      assertEquals(2, client.initialize().protocolVersion)
      client.sendInitialized()

      // 1. First render — RedSquare. The "edit" hasn't happened yet. 120s timeout for the cold
      //    sandbox bootstrap.
      val redStart = System.currentTimeMillis()
      val rnRed = client.renderNow(previews = listOf(redId), tier = RenderTier.FAST)
      assertEquals(listOf(redId), rnRed.queued)
      val finishedRed = client.pollRenderFinishedFor(redId, timeout = 120.seconds)
      val redFinishedAt = System.currentTimeMillis()
      val redPath =
        finishedRed["params"]?.jsonObject?.get("pngPath")?.jsonPrimitive?.contentOrNull
          ?: error("renderFinished missing pngPath: $finishedRed")
      val redBytes = File(redPath).readBytes()
      diffOrCaptureBaseline(
        actualBytes = redBytes,
        baseline = HarnessTestSupport.baselineFile("s3", "red-square.png"),
        reportsDir = paths.reportsDir,
        scenario = "S3RenderAfterEditAndroidRealModeTest[red]",
        stderrSupplier = { client.dumpStderr() },
      )

      // 2. "Edit" — issue renderNow against a *different* previewId resolving to BlueSquare.
      val blueStart = System.currentTimeMillis()
      val rnBlue = client.renderNow(previews = listOf(blueId), tier = RenderTier.FAST)
      assertEquals(listOf(blueId), rnBlue.queued)
      val finishedBlue = client.pollRenderFinishedFor(blueId, timeout = 60.seconds)
      val blueFinishedAt = System.currentTimeMillis()
      val bluePath =
        finishedBlue["params"]?.jsonObject?.get("pngPath")?.jsonPrimitive?.contentOrNull
          ?: error("renderFinished missing pngPath: $finishedBlue")
      val blueBytes = File(bluePath).readBytes()
      diffOrCaptureBaseline(
        actualBytes = blueBytes,
        baseline = HarnessTestSupport.baselineFile("s3", "blue-square.png"),
        reportsDir = paths.reportsDir,
        scenario = "S3RenderAfterEditAndroidRealModeTest[blue]",
        stderrSupplier = { client.dumpStderr() },
      )

      // 3. Sanity: red and blue must actually differ — otherwise the "edit" was a no-op.
      val sanityDiff = PixelDiff.compare(actual = redBytes, expected = blueBytes)
      assertTrue(
        "S3 sanity: red and blue renders must differ — otherwise the previewId swap was a no-op " +
          "(maxDelta=${sanityDiff.maxDelta})",
        !sanityDiff.ok,
      )

      // 4. Clean shutdown.
      val exitCode = client.shutdownAndExit(timeout = 60.seconds)
      assertEquals("Daemon must exit cleanly. Stderr=\n${client.dumpStderr()}", 0, exitCode)

      val recorder = LatencyRecorder(csvFile = HarnessTestSupport.LATENCY_CSV)
      recorder.record(
        scenario = "s3-android",
        preview = "$redId@v1",
        actualMs = redFinishedAt - redStart,
        notes = "S3 android: pre-edit render (cold; includes Robolectric bootstrap)",
      )
      recorder.record(
        scenario = "s3-android",
        preview = "$blueId@v2",
        actualMs = blueFinishedAt - blueStart,
        notes = "S3 android: post-edit render (warm sandbox)",
      )
    } catch (t: Throwable) {
      System.err.println(
        "S3RenderAfterEditAndroidRealModeTest failed; daemon stderr:\n${client.dumpStderr()}"
      )
      throw t
    } finally {
      try {
        client.close()
      } catch (_: Throwable) {}
    }
  }
}
