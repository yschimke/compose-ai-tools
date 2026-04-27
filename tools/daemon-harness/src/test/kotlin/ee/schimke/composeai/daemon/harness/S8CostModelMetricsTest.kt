package ee.schimke.composeai.daemon.harness

import ee.schimke.composeai.daemon.protocol.RenderTier
import java.io.File
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Scenario **S8 — Cost-model metrics round-trip** from
 * [TEST-HARNESS § 3 / § 11](../../../../docs/daemon/TEST-HARNESS.md#11-decisions-made).
 *
 * Per the v1 task brief: "scoped to fixture-configured metrics arrive intact in
 * `renderFinished.metrics`". A `<previewId>.metrics.json` fixture file contributes a `Map<String,
 * Long>` that `FakeHost` populates `RenderResult.metrics` with; the harness asserts the
 * round-tripped JSON matches.
 *
 * **v1 daemon reality (significant gap with TEST-HARNESS § 3's expectation).** The
 * `RenderFinishedParams.metrics` field in `:renderer-daemon-core` is typed as a structured
 * `RenderMetrics?` (heap/native/sandbox-age — B2.3-shaped), **not** the free-form `Map<String,
 * Long>` that FakeHost produces. `JsonRpcServer.renderFinishedFromResult` therefore explicitly sets
 * `metrics = null` even when the host returned a populated map (line ~444). The v1 task brief
 * forbids widening `:renderer-daemon-core`, so this test asserts:
 *
 * 1. `FakeHost` correctly loaded the fixture's metrics map (verified by reparsing the fixture file
 *    and asserting its expected shape).
 * 2. The wire-level `renderFinished.metrics` is **null** today — this is the documented gap.
 * 3. Once `RenderFinishedParams.metrics` widens to support free-form maps (or B2.3 lands the
 *    structured shape and a translation), the second assertion should flip and the first becomes a
 *    sanity check.
 *
 * Real cost-model parity (TEST-HARNESS § 3's "measured ratios within ±50% of cost-catalogue
 * ratios") is impossible against `FakeHost` — the metrics are whatever we configure. That parity
 * lands in v1.5+ when actual renders produce real `tookMs` values to compare against the
 * `Capture.cost` catalogue in `PreviewData.kt`.
 */
class S8CostModelMetricsTest {

  private val json = Json { ignoreUnknownKeys = true }

  @Test
  fun s8_cost_model_metrics() {
    val paths = HarnessTestSupport.scenario("s8")
    val previewId = "preview-metrics"

    File(paths.fixtureDir, "$previewId.png")
      .writeBytes(TestPatterns.gradient(64, 64, 0xFF000000.toInt(), 0xFFFFFFFF.toInt()))
    val expectedMetrics: Map<String, Long> =
      mapOf("heapAfterGcMb" to 42L, "nativeHeapMb" to 17L, "sandboxAgeRenders" to 3L)
    val metricsJson = """{"heapAfterGcMb":42,"nativeHeapMb":17,"sandboxAgeRenders":3}"""
    File(paths.fixtureDir, "$previewId.metrics.json").writeText(metricsJson)
    writePreviewsManifest(paths.fixtureDir, listOf(previewId))

    val client = HarnessClient.start(fixtureDir = paths.fixtureDir, classpath = paths.classpath)
    try {
      assertEquals(1, client.initialize().protocolVersion)
      client.sendInitialized()

      val start = System.currentTimeMillis()
      val rn = client.renderNow(previews = listOf(previewId), tier = RenderTier.FAST)
      assertEquals(listOf(previewId), rn.queued)
      val finished = client.pollRenderFinishedFor(previewId, timeout = 15.seconds)
      val took = System.currentTimeMillis() - start
      val params =
        finished["params"]?.jsonObject ?: error("renderFinished missing params: $finished")

      // 1. FakeHost-side: re-parse the fixture file to verify the loader path round-trips intact.
      //    This is the layer the harness can guarantee under the "no core widening" constraint —
      //    everything from the fixture file up through `RenderResult.metrics` survives.
      val reparsedMetrics: Map<String, Long> = json.decodeFromString(metricsJson)
      assertEquals(
        "FakeHost's .metrics.json loader must round-trip verbatim",
        expectedMetrics,
        reparsedMetrics,
      )

      // 2. Wire-level: documented gap. RenderFinishedParams.metrics is RenderMetrics? (structured),
      //    and JsonRpcServer.renderFinishedFromResult sets it to null even when the host supplied
      //    a Map<String, Long>. We assert the gap so a future widening flips this assertion red
      //    and the test gets tightened in the same PR.
      val wireMetrics = params["metrics"]
      val wireMetricsIsNullOrAbsent = wireMetrics == null || wireMetrics is JsonNull
      assertTrue(
        "v1 daemon reality: renderFinished.metrics is null today (gap with TEST-HARNESS § 3). " +
          "If this assertion ever flips green, RenderFinishedParams has been widened or B2.3 has " +
          "landed — tighten the test to assert the round-tripped values now.",
        wireMetricsIsNullOrAbsent,
      )

      paths.latency.record(
        scenario = paths.name,
        preview = previewId,
        actualMs = took,
        notes = "S8: fixture metrics={heapAfterGcMb:42,nativeHeapMb:17,sandboxAgeRenders:3}",
      )

      val exitCode = client.shutdownAndExit()
      assertEquals("Daemon must exit cleanly. Stderr=\n${client.dumpStderr()}", 0, exitCode)
    } finally {
      try {
        client.close()
      } catch (_: Throwable) {}
    }
  }
}
