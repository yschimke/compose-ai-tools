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
 * Real-mode counterpart to [S8CostModelMetricsTest] — verifies that the wire-level
 * `renderFinished.tookMs` carries the timing the engine measured, and pins the remaining
 * structured-metrics gap with TEST-HARNESS § 3's cost-model expectation (B2.3 unimplemented).
 *
 * **What's present post-B2.3:**
 * - `renderFinished.tookMs` reflects the wall-clock the engine spent in `RenderEngine.render`.
 * - `renderFinished.metrics` (typed as `RenderMetrics?`) carries the four B2.3 cost-model
 *   fields — `heapAfterGcMb` (post-`System.gc()` Runtime delta), `nativeHeapMb` (committed
 *   virtual memory size on HotSpot), `sandboxAgeRenders` (per-sandbox counter), `sandboxAgeMs`
 *   (wall-clock since `DesktopHost`'s construction). The desktop host owns its own
 *   `SandboxLifecycleStats`, so the first render's `sandboxAgeRenders` is exactly 1.
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
    Assume.assumeTrue(
      "Skipping S8CostModelMetricsRealModeTest — desktop variant; set -Ptarget=desktop (default).",
      HarnessTestSupport.harnessTarget() == "desktop",
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

      // 1. Wire-level tookMs must be present + non-null + reflect the engine's measured render
      //    body wall-clock. JsonRpcServer.emitRenderFinished pulls `tookMs` out of
      //    `RenderResult.metrics["tookMs"]`, which RenderEngine populates from
      //    `System.nanoTime()` deltas around its `scene.render()` calls. A real desktop render
      //    of a single solid-colour Box takes >0ms and well under 30s; the upper bound is
      //    generous to absorb cold-start jitter on slow CI machines.
      val tookMsField = params["tookMs"]
      assertNotNull("renderFinished.tookMs must be present", tookMsField)
      val tookMs = tookMsField!!.jsonPrimitive.contentOrNull?.toLongOrNull()
      assertNotNull("renderFinished.tookMs must parse as Long: $tookMsField", tookMs)
      assertTrue(
        "renderFinished.tookMs must be in [1, 30000] now that the wire path is plumbed: " +
          "got $tookMs (wall-clock the harness measured: $wallClockMs ms)",
        tookMs!! in 1L..30_000L,
      )

      // 2. Wire-level metrics: B2.3 lands the four cost-model fields on
      //    `RenderFinishedParams.metrics`. Each must be present + parse as Long + fall in a sane
      //    range for a real desktop render.
      val wireMetrics = params["metrics"]
      assertNotNull("renderFinished.metrics must be populated post-B2.3", wireMetrics)
      assertNotEquals(
        "renderFinished.metrics must not be JsonNull post-B2.3",
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
        metricsObj[RenderMetrics.KEY_SANDBOX_AGE_MS]
          ?.jsonPrimitive
          ?.contentOrNull
          ?.toLongOrNull()
      assertNotNull("metrics.heapAfterGcMb must be a Long", heapAfterGcMb)
      assertNotNull("metrics.nativeHeapMb must be a Long", nativeHeapMb)
      assertNotNull("metrics.sandboxAgeRenders must be a Long", sandboxAgeRenders)
      assertNotNull("metrics.sandboxAgeMs must be a Long", sandboxAgeMs)
      assertTrue(
        "metrics.heapAfterGcMb in [1, 8192] for a real desktop render: got $heapAfterGcMb",
        heapAfterGcMb!! in 1L..8192L,
      )
      assertTrue(
        "metrics.nativeHeapMb >= 0 (0 if non-HotSpot fallback): got $nativeHeapMb",
        nativeHeapMb!! >= 0L,
      )
      assertEquals(
        "first render's sandboxAgeRenders must be 1",
        1L,
        sandboxAgeRenders,
      )
      assertTrue(
        "metrics.sandboxAgeMs must be non-negative: got $sandboxAgeMs",
        sandboxAgeMs!! >= 0L,
      )

      val recorder = LatencyRecorder(csvFile = HarnessTestSupport.LATENCY_CSV)
      recorder.record(
        scenario = "s8-real",
        preview = previewId,
        actualMs = wallClockMs,
        notes =
          "S8 real: wire tookMs=$tookMs (engine-measured); B2.3 metrics " +
            "heapAfterGcMb=$heapAfterGcMb nativeHeapMb=$nativeHeapMb " +
            "sandboxAgeRenders=$sandboxAgeRenders sandboxAgeMs=$sandboxAgeMs",
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
