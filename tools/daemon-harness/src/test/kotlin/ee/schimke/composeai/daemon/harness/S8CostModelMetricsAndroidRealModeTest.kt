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
 * D-harness.v2 Android counterpart of [S8CostModelMetricsRealModeTest] — verifies that the Android
 * daemon's wire-level `renderFinished.tookMs` carries the timing the engine measured, and pins the
 * same B2.3-unimplemented gap on the structured metrics map.
 *
 * **Same wire shape as desktop.** Both backends populate `RenderResult.metrics["tookMs"]` from
 * `System.nanoTime()` deltas around their respective render bodies (B1.4 / B-desktop.1.4);
 * `JsonRpcServer.emitRenderFinished` (in `:renderer-daemon-core`) reads the value and forwards it
 * as the wire-level `tookMs`. The `RenderFinishedParams.metrics` (typed as `RenderMetrics?`) is
 * still null on both targets — B2.3 unimplemented.
 *
 * **No baseline PNG.** Test asserts on the wire shape only.
 */
class S8CostModelMetricsAndroidRealModeTest {

  @Test
  fun s8_cost_model_metrics_real_mode_android() {
    Assume.assumeTrue(
      "Skipping S8CostModelMetricsAndroidRealModeTest — set -Pharness.host=real to enable.",
      HarnessTestSupport.harnessHost() == "real",
    )
    Assume.assumeTrue(
      "Skipping S8CostModelMetricsAndroidRealModeTest — android variant; set -Ptarget=android.",
      HarnessTestSupport.harnessTarget() == "android",
    )

    val previewId = "red-square"
    val paths =
      realAndroidModeScenario(
        name = "s8-android",
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
      val finished = client.pollRenderFinishedFor(previewId, timeout = 120.seconds)
      val wallClockMs = System.currentTimeMillis() - start
      val params =
        finished["params"]?.jsonObject ?: error("renderFinished missing params: $finished")

      // 1. Wire-level tookMs must be present + non-null + reflect the engine's measured render
      //    body wall-clock. JsonRpcServer.emitRenderFinished pulls `tookMs` out of
      //    `RenderResult.metrics["tookMs"]`, which RenderEngine populates from `System.nanoTime()`
      //    deltas around its render-body invocation. A real Android render of a single
      //    solid-colour Box takes >0ms; the upper bound is generous to absorb cold-start jitter
      //    on slow CI machines (Robolectric sandbox bootstrap is ~3-10s, but happens *before*
      //    the engine starts measuring).
      val tookMsField = params["tookMs"]
      assertNotNull("renderFinished.tookMs must be present", tookMsField)
      val tookMs = tookMsField!!.jsonPrimitive.contentOrNull?.toLongOrNull()
      assertNotNull("renderFinished.tookMs must parse as Long: $tookMsField", tookMs)
      assertTrue(
        "renderFinished.tookMs must be in [1, 60000] now that the wire path is plumbed: " +
          "got $tookMs (wall-clock the harness measured: $wallClockMs ms)",
        tookMs!! in 1L..60_000L,
      )

      // 2. Wire-level metrics: B2.3 unimplemented → null today on both targets.
      val wireMetrics = params["metrics"]
      val wireMetricsIsNullOrAbsent = wireMetrics == null || wireMetrics is JsonNull
      assertTrue(
        "v1 daemon reality (android): renderFinished.metrics is null today (B2.3 unimplemented; " +
          "gap with TEST-HARNESS § 3, same as desktop). When B2.3 lands cost-model fields, this " +
          "test should tighten to assert each field's presence + sane range.",
        wireMetricsIsNullOrAbsent,
      )

      val recorder = LatencyRecorder(csvFile = HarnessTestSupport.LATENCY_CSV)
      recorder.record(
        scenario = "s8-android",
        preview = previewId,
        actualMs = wallClockMs,
        notes =
          "S8 android: wire tookMs=$tookMs (engine-measured); structured metrics=null (B2.3 gap)",
      )

      val exitCode = client.shutdownAndExit(timeout = 60.seconds)
      assertEquals("Daemon must exit cleanly. Stderr=\n${client.dumpStderr()}", 0, exitCode)
    } catch (t: Throwable) {
      System.err.println(
        "S8CostModelMetricsAndroidRealModeTest failed; daemon stderr:\n${client.dumpStderr()}"
      )
      throw t
    } finally {
      try {
        client.close()
      } catch (_: Throwable) {}
    }
  }
}
