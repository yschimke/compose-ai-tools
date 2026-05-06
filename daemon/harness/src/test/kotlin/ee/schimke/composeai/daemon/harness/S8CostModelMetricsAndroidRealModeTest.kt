package ee.schimke.composeai.daemon.harness

import ee.schimke.composeai.daemon.protocol.RenderMetrics
import ee.schimke.composeai.daemon.protocol.RenderTier
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test

/**
 * D-harness.v2 Android counterpart of [S8CostModelMetricsRealModeTest] — verifies that the Android
 * daemon's wire-level `renderFinished.tookMs` carries the timing the engine measured, and that the
 * four post-B2.3 cost-model fields populate `renderFinished.metrics`.
 *
 * **Same wire shape as desktop.** Both backends populate `RenderResult.metrics` from
 * `SandboxMeasurement.collect`; `JsonRpcServer.renderFinishedFromResult` (in `:daemon:core`)
 * translates the flat map into the structured [RenderMetrics] for the wire. On Android the
 * `SandboxLifecycleStats` lives inside the Robolectric sandbox (per-sandbox lifetime), so the first
 * render's `sandboxAgeRenders` is 1.
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
      assertEquals(2, client.initialize().protocolVersion)
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

      // 2. Wire-level metrics: B2.3 — the four cost-model fields must populate
      //    `renderFinished.metrics` on the Android target too.
      val wireMetrics = params["metrics"]
      assertNotNull("renderFinished.metrics must be populated post-B2.3 (android)", wireMetrics)
      assertNotEquals(
        "renderFinished.metrics must not be JsonNull (android)",
        JsonNull,
        wireMetrics,
      )
      val metricsObj = wireMetrics!!.jsonObject
      val heapAfterGcMb =
        metricsObj[RenderMetrics.KEY_HEAP_AFTER_GC_MB]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
      val nativeHeapMb =
        metricsObj[RenderMetrics.KEY_NATIVE_HEAP_MB]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
      val sandboxAgeRenders =
        metricsObj[RenderMetrics.KEY_SANDBOX_AGE_RENDERS]
          ?.jsonPrimitive
          ?.contentOrNull
          ?.toLongOrNull()
      val sandboxAgeMs =
        metricsObj[RenderMetrics.KEY_SANDBOX_AGE_MS]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
      assertNotNull("metrics.heapAfterGcMb must be a Long", heapAfterGcMb)
      assertNotNull("metrics.nativeHeapMb must be a Long", nativeHeapMb)
      assertNotNull("metrics.sandboxAgeRenders must be a Long", sandboxAgeRenders)
      assertNotNull("metrics.sandboxAgeMs must be a Long", sandboxAgeMs)
      assertTrue(
        "metrics.heapAfterGcMb in [1, 8192] for a real android render: got $heapAfterGcMb",
        heapAfterGcMb!! in 1L..8192L,
      )
      assertTrue(
        "metrics.nativeHeapMb >= 0 (0 if non-HotSpot fallback): got $nativeHeapMb",
        nativeHeapMb!! >= 0L,
      )
      assertEquals("first render's sandboxAgeRenders must be 1", 1L, sandboxAgeRenders)
      assertTrue(
        "metrics.sandboxAgeMs must be non-negative: got $sandboxAgeMs",
        sandboxAgeMs!! >= 0L,
      )

      val recorder = LatencyRecorder(csvFile = HarnessTestSupport.LATENCY_CSV)
      recorder.record(
        scenario = "s8-android",
        preview = previewId,
        actualMs = wallClockMs,
        notes =
          "S8 android: wire tookMs=$tookMs (engine-measured); B2.3 metrics " +
            "heapAfterGcMb=$heapAfterGcMb nativeHeapMb=$nativeHeapMb " +
            "sandboxAgeRenders=$sandboxAgeRenders sandboxAgeMs=$sandboxAgeMs",
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
