package ee.schimke.composeai.daemon.harness

import ee.schimke.composeai.daemon.protocol.RenderTier
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test

/**
 * Real-mode counterpart to [S8CostModelMetricsTest] — verifies what the real `:renderer-desktop-daemon`
 * actually surfaces in `renderFinished.metrics` today, and pins the gap with TEST-HARNESS § 3's
 * cost-model expectation (B2.3 unimplemented).
 *
 * **What's actually present today:**
 * - `renderFinished.tookMs` is non-null (typed as `Long` in `RenderFinishedParams`). However
 *   `JsonRpcServer.emitRenderFinished` currently passes `tookMs = 0` (line ~416) — the structured
 *   timing flow lives in `B-desktop.1.4`'s `RenderResult.metrics["tookMs"]`, not in the wire-level
 *   `tookMs`. This test asserts the wire field is present and is `0L` today; once the gap closes
 *   (`renderFinishedFromResult` is updated to pass through real timing), the `>= 0L` assertion
 *   tightens to `> 0L`.
 * - `renderFinished.metrics` (typed as `RenderMetrics?`) is `null` today (`renderFinishedFromResult`
 *   sets it to null even when the host returns timing data — same gap as fake-mode S8). B2.3 is
 *   unimplemented; cost-model fields (heap, native heap, sandbox-age, render count) don't exist on
 *   the wire yet.
 *
 * **No baseline PNG.** Test asserts on the wire shape only.
 */
class S8CostModelMetricsRealModeTest {

  @Test
  fun s8_cost_model_metrics_real_mode() {
    Assume.assumeTrue(
      "Skipping S8CostModelMetricsRealModeTest — set -Pharness.host=real to enable.",
      HarnessTestSupport.harnessHost() == "real",
    )

    val previewId = "red-square"
    val paths =
      realModeScenario(
        name = "s8-real",
        previews =
          listOf(
            RealModePreview(
              id = previewId,
              className = "ee.schimke.composeai.daemon.RedFixturePreviewsKt",
              functionName = "RedSquare",
            )
          ),
      )

    val client = HarnessClient.start(paths.launcher)
    try {
      assertEquals(1, client.initialize().protocolVersion)
      client.sendInitialized()

      val start = System.currentTimeMillis()
      val rn = client.renderNow(previews = listOf(previewId), tier = RenderTier.FAST)
      assertEquals(listOf(previewId), rn.queued)
      val finished = client.pollRenderFinishedFor(previewId, timeout = 60.seconds)
      val wallClockMs = System.currentTimeMillis() - start
      val params =
        finished["params"]?.jsonObject ?: error("renderFinished missing params: $finished")

      // 1. Wire-level tookMs must be present + non-null. The numeric value is hardcoded to 0L by
      //    `JsonRpcServer.emitRenderFinished` today (gap; see KDoc) — assert >= 0 to allow the gap
      //    to close without flipping this test red. Sanity-cap the upper bound at 5 minutes so a
      //    wildly-broken value (e.g. negative-as-unsigned) is still caught.
      val tookMsField = params["tookMs"]
      assertNotNull("renderFinished.tookMs must be present", tookMsField)
      val tookMs = tookMsField!!.jsonPrimitive.contentOrNull?.toLongOrNull()
      assertNotNull("renderFinished.tookMs must parse as Long: $tookMsField", tookMs)
      assertTrue(
        "renderFinished.tookMs must be in a sane range [0, 300000]: got $tookMs " +
          "(wall-clock the harness measured: $wallClockMs ms)",
        tookMs!! in 0L..300_000L,
      )

      // 2. Wire-level metrics: B2.3 unimplemented → null today. Documented gap; same as fake-mode
      //    S8. When B2.3 lands and `RenderFinishedParams.metrics` carries heap / native-heap /
      //    sandbox-age fields, this assertion flips and the test should tighten to assert each
      //    field's presence + sane range.
      val wireMetrics = params["metrics"]
      val wireMetricsIsNullOrAbsent = wireMetrics == null || wireMetrics is JsonNull
      assertTrue(
        "v1 daemon reality: renderFinished.metrics is null today (B2.3 unimplemented; gap with " +
          "TEST-HARNESS § 3). If this assertion ever flips green, B2.3 has landed cost-model " +
          "fields and this test should tighten to assert each field's presence + sane range.",
        wireMetricsIsNullOrAbsent,
      )

      val recorder = LatencyRecorder(csvFile = HarnessTestSupport.LATENCY_CSV)
      recorder.record(
        scenario = "s8-real",
        preview = previewId,
        actualMs = wallClockMs,
        notes = "S8 real: wire tookMs=$tookMs (hardcoded 0 today); structured metrics=null (B2.3 gap)",
      )

      val exitCode = client.shutdownAndExit(timeout = 30.seconds)
      assertEquals("Daemon must exit cleanly. Stderr=\n${client.dumpStderr()}", 0, exitCode)
    } catch (t: Throwable) {
      System.err.println(
        "S8CostModelMetricsRealModeTest failed; daemon stderr:\n${client.dumpStderr()}"
      )
      throw t
    } finally {
      try {
        client.close()
      } catch (_: Throwable) {}
    }
  }
}
