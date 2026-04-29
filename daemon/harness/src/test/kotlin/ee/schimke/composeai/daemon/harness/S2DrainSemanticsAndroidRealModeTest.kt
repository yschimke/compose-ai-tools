package ee.schimke.composeai.daemon.harness

import ee.schimke.composeai.daemon.protocol.RenderTier
import java.io.File
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test

/**
 * D-harness.v2 Android counterpart of [S2DrainSemanticsRealModeTest] — verifies the daemon's drain
 * semantics (`shutdown` does not resolve until the in-flight `renderFinished` arrives) against the
 * real `:daemon:android` rather than `:daemon:desktop`.
 *
 * Uses [`SlowSquare`][ee.schimke.composeai.daemon.SlowSquare] from the android testFixtures source
 * set (D-harness.v2 promoted them from `src/test/...`); the composable calls `Thread.sleep(500)`
 * inside the composition so the harness can race `shutdown` against the in-flight render.
 *
 * Real-mode wall-clock is bounded below by the 500ms sleep + Robolectric sandbox bootstrap on cold
 * + capture pipeline; expect 4-12s on the dev box. Pixel-diff baseline lands at
 *   `daemon/harness/baselines/android/s2/slow-square.png`.
 */
class S2DrainSemanticsAndroidRealModeTest {

  @Test
  fun s2_drain_semantics_real_mode_android() {
    Assume.assumeTrue(
      "Skipping S2DrainSemanticsAndroidRealModeTest — set -Pharness.host=real to enable.",
      HarnessTestSupport.harnessHost() == "real",
    )
    Assume.assumeTrue(
      "Skipping S2DrainSemanticsAndroidRealModeTest — android variant; set -Ptarget=android.",
      HarnessTestSupport.harnessTarget() == "android",
    )

    val previewId = "slow-square"
    val paths =
      realAndroidModeScenario(
        name = "s2-android",
        previews =
          listOf(
            RealModePreview(
              id = previewId,
              className = "ee.schimke.composeai.daemon.RedFixturePreviewsKt",
              functionName = "SlowSquare",
            )
          ),
      )

    val client = HarnessClient.start(paths.launcher)
    try {
      assertEquals(1, client.initialize().protocolVersion)
      client.sendInitialized()

      // 1. renderNow + measure latency.
      val renderNowStartMs = System.currentTimeMillis()
      val rn = client.renderNow(previews = listOf(previewId), tier = RenderTier.FAST)
      assertEquals(listOf(previewId), rn.queued)

      // 2. Send shutdown without waiting — the whole point of the scenario.
      val shutdownId = client.sendShutdownAsync()

      // 3. renderFinished must arrive *before* the shutdown response. 120s timeout for cold-start
      //    + 500ms sleep + capture.
      val finished = client.pollRenderFinishedFor(previewId, timeout = 120.seconds)
      val finishedReceivedAtMs = System.currentTimeMillis()

      // 4. Now the shutdown response is allowed to arrive — and must.
      val shutdownResponse = client.awaitResponse(shutdownId, timeout = 60.seconds)
      val shutdownReceivedAtMs = System.currentTimeMillis()
      assertNotNull("shutdown must return a response object", shutdownResponse)
      assertTrue(
        "shutdown response must NOT precede renderFinished " +
          "(finished=${finishedReceivedAtMs}, shutdown=${shutdownReceivedAtMs})",
        shutdownReceivedAtMs >= finishedReceivedAtMs,
      )

      // 5. The reported PNG must exist and pixel-diff against the baseline (or be captured).
      val reportedPath =
        finished["params"]?.jsonObject?.get("pngPath")?.jsonPrimitive?.contentOrNull
      assertNotNull("renderFinished.pngPath must be present", reportedPath)
      val reportedPng = File(reportedPath!!)
      assertTrue("renderFinished.pngPath must exist: $reportedPath", reportedPng.exists())
      assertTrue("rendered PNG must be non-empty", reportedPng.length() > 0)

      diffOrCaptureBaseline(
        actualBytes = reportedPng.readBytes(),
        baseline = HarnessTestSupport.baselineFile("s2", "slow-square.png"),
        reportsDir = paths.reportsDir,
        scenario = "S2DrainSemanticsAndroidRealModeTest",
        stderrSupplier = { client.dumpStderr() },
      )

      // 6. exit + clean process exit.
      val exitCode = client.sendExitAndWait(timeout = 60.seconds)
      assertEquals("Daemon must exit cleanly. Stderr=\n${client.dumpStderr()}", 0, exitCode)

      val tookMs = finishedReceivedAtMs - renderNowStartMs
      System.err.println(
        "S2DrainSemanticsAndroidRealModeTest: renderNow → renderFinished in ${tookMs}ms"
      )
      val recorder = LatencyRecorder(csvFile = HarnessTestSupport.LATENCY_CSV)
      recorder.record(
        scenario = "s2-android",
        preview = previewId,
        actualMs = tookMs,
        notes = "S2 android: includes 500ms SlowSquare sleep + Robolectric sandbox bootstrap",
      )
    } catch (t: Throwable) {
      System.err.println(
        "S2DrainSemanticsAndroidRealModeTest failed; daemon stderr:\n${client.dumpStderr()}"
      )
      throw t
    } finally {
      try {
        client.close()
      } catch (_: Throwable) {}
    }
  }
}
