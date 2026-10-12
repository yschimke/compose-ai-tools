package ee.schimke.composeai.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

class RenderTimingsTest {

  private fun finished(tookMs: Long, steps: Map<String, Long> = emptyMap(), heapMb: Long? = null) =
    buildJsonObject {
      put("tookMs", tookMs)
      if (steps.isNotEmpty())
        putJsonObject("workTrace") {
          putJsonObject("stepMs") { steps.forEach { (k, v) -> put(k, v) } }
        }
      if (heapMb != null) putJsonObject("metrics") { put("heapAfterGcMb", heapMb) }
    }

  @Test
  fun `summarises per-preview percentiles and per-step costs, most expensive first`() {
    val timings = RenderTimings()
    for (i in 1..20) {
      timings.recordFinished(
        "P$i",
        finished(
          tookMs = i * 100L,
          steps = mapOf("compose/semantics" to 10L, "compose/figma-svg" to i * 50L),
          heapMb = 400L + i,
        ),
      )
    }
    timings.recordFailed()

    val lines = timings.summaryLines(wallMs = 125_000, daemons = 2, window = 8)

    assertEquals(
      listOf(
        "render timings: 20 finished, 1 failed in 2m05s across 2 daemon(s), window 8",
        "  per preview: p50 1000ms, p95 1900ms, max 2000ms, sum 21.0s; peak heap after GC 420MB",
        "  per step (p50/p95/total): compose/figma-svg 500/950ms/10.5s; compose/semantics 10/10ms/200ms",
        "  slowest: P20 2000ms, P19 1900ms, P18 1800ms, P17 1700ms, P16 1600ms",
      ),
      lines,
    )
  }

  @Test
  fun `an older daemon's bare renderFinished still yields the headline and percentiles`() {
    val timings = RenderTimings()
    timings.recordFinished("A", buildJsonObject { put("id", "A") })

    val lines = timings.summaryLines(wallMs = 900, daemons = 1, window = 8, unsubmitted = 3)

    assertEquals(
      listOf(
        "render timings: 1 finished, 0 failed, 3 never submitted in 900ms across 1 daemon(s), window 8",
        "  per preview: p50 0ms, p95 0ms, max 0ms, sum 0ms",
      ),
      lines,
    )
  }

  @Test
  fun `percentile is nearest-rank`() {
    val values = (1L..10L).toList()
    assertEquals(5L, RenderTimings.percentile(values, 0.5))
    assertEquals(10L, RenderTimings.percentile(values, 0.95))
    assertEquals(1L, RenderTimings.percentile(listOf(1L), 0.95))
  }
}
