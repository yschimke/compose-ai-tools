package ee.schimke.composeai.cli.serve

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RenderPerfStatsTest {

  @Test
  fun emptyStatsSnapshotHasNoSamples() {
    val snap = RenderPerfStats().snapshot()
    assertEquals(0, snap.renders)
    assertNull(snap.firstRenderMs)
    assertNull(snap.minMs)
    assertNull(snap.p50Ms)
    assertEquals(0, snap.windowSize)
  }

  @Test
  fun coldAndWarmRendersAreSeparated() {
    val stats = RenderPerfStats()
    stats.recordOk(30_000, cold = true)
    stats.recordOk(2_000, cold = false)
    stats.recordOk(1_000, cold = false)
    stats.recordCacheHit()
    stats.recordBusy()
    stats.recordFailed(120_000, timeout = true)

    val snap = stats.snapshot()
    assertEquals(4, snap.renders)
    assertEquals(3, snap.ok)
    assertEquals(1, snap.failed)
    assertEquals(1, snap.timedOut)
    assertEquals(1, snap.busy)
    assertEquals(1, snap.cacheHits)
    assertEquals(1, snap.coldRenders)
    assertEquals(30_000, snap.firstRenderMs)
    assertEquals(30_000, snap.coldMaxMs)
    assertEquals(1_000, snap.minMs)
    assertEquals(30_000, snap.maxMs)
    assertEquals(11_000, snap.avgMs)
    // Failed durations don't enter the percentile window; lastMs still reflects the failure.
    assertEquals(120_000, snap.lastMs)
    assertEquals(3, snap.windowSize)
    assertEquals(2_000, snap.p50Ms)
  }

  @Test
  fun percentileWindowIsBoundedToMostRecentSamples() {
    val stats = RenderPerfStats()
    // Overfill the ring: the first (slow) samples must age out of the percentile window while the
    // all-time max keeps them.
    repeat(RenderPerfStats.WINDOW_SIZE) { stats.recordOk(60_000, cold = false) }
    repeat(RenderPerfStats.WINDOW_SIZE) { stats.recordOk(1_000, cold = false) }
    val snap = stats.snapshot()
    assertEquals(RenderPerfStats.WINDOW_SIZE, snap.windowSize)
    assertEquals(1_000, snap.p50Ms)
    assertEquals(1_000, snap.p95Ms)
    assertEquals(60_000, snap.maxMs)
  }

  @Test
  fun aggregateSumsCountsAndReportsWorstFirstRender() {
    val a = RenderPerfStats().apply { recordOk(10_000, cold = true) }.snapshot()
    val b =
      RenderPerfStats()
        .apply {
          recordOk(40_000, cold = true)
          recordOk(2_000, cold = false)
        }
        .snapshot()
    val agg = RenderPerfSnapshot.aggregate(listOf(a, b))!!
    assertEquals(3, agg.ok)
    assertEquals(2, agg.coldRenders)
    // Worst first render across daemons — the cold-start headline number.
    assertEquals(40_000, agg.firstRenderMs)
    assertEquals(2_000, agg.minMs)
    assertEquals(40_000, agg.maxMs)
    // Percentiles don't merge across windows.
    assertNull(agg.p50Ms)
    assertNull(RenderPerfSnapshot.aggregate(emptyList()))
  }
}
