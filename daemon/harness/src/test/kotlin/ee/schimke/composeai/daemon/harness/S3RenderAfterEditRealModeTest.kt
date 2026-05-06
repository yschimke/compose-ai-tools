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
 * Real-mode counterpart to [S3RenderAfterEditTest] — verifies the daemon serves the *new* preview
 * source after an "edit" of which composable is bound to the protocol-level previewId.
 *
 * **Mode divergence (load-bearing).** Real mode cannot deterministically swap source code without a
 * recompile. So instead of editing a `.kt` file inside the test (the v1 fake-mode pattern: swap
 * `<previewId>.png` bytes), we pre-build two composables — `RedSquare` and `BlueSquare` — and the
 * "edit" maps to swapping which preview the *render request* references. Two separate
 * `renderNow(["red-square"])` and `renderNow(["blue-square"])` calls; assert the second renders
 * blue.
 *
 * **Gap parity with fake mode.** B2.2 phase 2 wired `discoveryUpdated` emission on
 * `fileChanged({kind: source})`; the fake-mode [S3RenderAfterEditTest] now asserts presence. The
 * real-mode flow doesn't yet send a `fileChanged` notification because the render bypasses any
 * file-watching path entirely (no real source `.kt` is being edited mid-test) — adding that
 * assertion would require a recompile-on-the-fly fixture similar to
 * `S3_5RecompileSaveLoopRealModeTest`. Out of scope for v1; this test stays the "different
 * previewId → different bytes" round-trip check.
 *
 * Captured baselines: reuses `red-square.png` from v1.5a's S1 (no duplication) and adds
 * `blue-square.png`. Both live under `daemon/harness/baselines/desktop/s3/`.
 */
class S3RenderAfterEditRealModeTest {

  @Test
  fun s3_render_after_edit_real_mode() {
    Assume.assumeTrue(
      "Skipping S3RenderAfterEditRealModeTest — set -Pharness.host=real to enable.",
      HarnessTestSupport.harnessHost() == "real",
    )
    Assume.assumeTrue(
      "Skipping S3RenderAfterEditRealModeTest — desktop variant; set -Ptarget=desktop (default).",
      HarnessTestSupport.harnessTarget() == "desktop",
    )

    val redId = "red-square"
    val blueId = "blue-square"
    val paths =
      realModeScenario(
        name = "s3-real",
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

      // 1. First render — RedSquare. The "edit" hasn't happened yet.
      val redStart = System.currentTimeMillis()
      val rnRed = client.renderNow(previews = listOf(redId), tier = RenderTier.FAST)
      assertEquals(listOf(redId), rnRed.queued)
      val finishedRed = client.pollRenderFinishedFor(redId, timeout = 60.seconds)
      val redFinishedAt = System.currentTimeMillis()
      val redPath =
        finishedRed["params"]?.jsonObject?.get("pngPath")?.jsonPrimitive?.contentOrNull
          ?: error("renderFinished missing pngPath: $finishedRed")
      val redBytes = File(redPath).readBytes()
      diffOrCaptureBaseline(
        actualBytes = redBytes,
        baseline = HarnessTestSupport.baselineFile("s3", "red-square.png"),
        reportsDir = paths.reportsDir,
        scenario = "S3RenderAfterEditRealModeTest[red]",
        stderrSupplier = { client.dumpStderr() },
      )

      // 2. "Edit" — issue renderNow against a *different* previewId resolving to BlueSquare.
      //    Real-mode mode divergence: in fake mode S3 swaps the bytes of <previewId>.png on disk;
      //    here we swap the previewId itself. Both forms verify the same end-to-end shape: the
      //    second render produces different pixels than the first.
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
        scenario = "S3RenderAfterEditRealModeTest[blue]",
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
      val exitCode = client.shutdownAndExit(timeout = 30.seconds)
      assertEquals("Daemon must exit cleanly. Stderr=\n${client.dumpStderr()}", 0, exitCode)

      val recorder = LatencyRecorder(csvFile = HarnessTestSupport.LATENCY_CSV)
      recorder.record(
        scenario = "s3-real",
        preview = "$redId@v1",
        actualMs = redFinishedAt - redStart,
        notes = "S3 real: pre-edit render",
      )
      recorder.record(
        scenario = "s3-real",
        preview = "$blueId@v2",
        actualMs = blueFinishedAt - blueStart,
        notes = "S3 real: post-edit render (previewId swap)",
      )
    } catch (t: Throwable) {
      System.err.println(
        "S3RenderAfterEditRealModeTest failed; daemon stderr:\n${client.dumpStderr()}"
      )
      throw t
    } finally {
      try {
        client.close()
      } catch (_: Throwable) {}
    }
  }
}
