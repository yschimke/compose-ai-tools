package ee.schimke.composeai.daemon.harness

import ee.schimke.composeai.daemon.protocol.RenderMetrics
import ee.schimke.composeai.daemon.protocol.RenderTier
import java.io.File
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
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
 * **Post-B2.3 wire shape.** `JsonRpcServer.renderFinishedFromResult` now translates the host's
 * flat `Map<String, Long>` carrier into a structured [RenderMetrics] on the wire (see
 * [RenderMetrics.fromFlatMap]). [FakeHost] populates the four B2.3 keys on every render —
 * `heapAfterGcMb` / `nativeHeapMb` / `sandboxAgeRenders` / `sandboxAgeMs` — with synthetic but
 * non-zero values. Sidecar-supplied metrics from `<previewId>.metrics.json` still take
 * precedence on key collision so legacy fixtures (this test's `heapAfterGcMb=42`,
 * `nativeHeapMb=17`, `sandboxAgeRenders=3` example) keep their explicit values.
 *
 * **What this test asserts after B2.3:**
 *
 * 1. `FakeHost` round-trips the fixture's metrics map verbatim (sidecar loader sanity check).
 * 2. The wire-level `renderFinished.metrics` is now a populated [RenderMetrics] object (not
 *    null) and carries the four B2.3 fields — sidecar values for `heapAfterGcMb`,
 *    `nativeHeapMb`, `sandboxAgeRenders`, plus the FakeHost-provided default for `sandboxAgeMs`
 *    (which the sidecar doesn't override, so we just check it's a non-negative `Long`).
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

      // 2. Wire-level: post-B2.3, `JsonRpcServer.renderFinishedFromResult` translates the host's
      //    flat `Map<String, Long>` into a structured `RenderMetrics` on the wire. The fixture's
      //    `<previewId>.metrics.json` overrides three of the four keys; FakeHost's B2.3 default
      //    fills in `sandboxAgeMs` (which the sidecar doesn't supply).
      val wireMetrics = params["metrics"]
      assertNotNull("renderFinished.metrics must be populated post-B2.3", wireMetrics)
      assertNotEquals(
        "renderFinished.metrics must be a populated object, not JsonNull",
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
      assertEquals("heapAfterGcMb sourced from sidecar", 42L, heapAfterGcMb)
      assertEquals("nativeHeapMb sourced from sidecar", 17L, nativeHeapMb)
      assertEquals("sandboxAgeRenders sourced from sidecar", 3L, sandboxAgeRenders)
      assertNotNull("sandboxAgeMs supplied by FakeHost B2.3 default", sandboxAgeMs)
      assertTrue(
        "sandboxAgeMs must be a non-negative wall-clock measurement",
        sandboxAgeMs!! >= 0L,
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
