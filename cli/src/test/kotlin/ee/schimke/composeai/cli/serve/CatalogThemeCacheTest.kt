package ee.schimke.composeai.cli.serve

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CatalogThemeCacheTest {
  @Test
  fun `render cache evicts least recently used entries at its byte cap`() {
    val cache = CatalogThemeCache(maxBytes = 6)
    cache.put("a", byteArrayOf(1, 1, 1))
    cache.put("b", byteArrayOf(2, 2, 2))
    assertContentEquals(byteArrayOf(1, 1, 1), cache.get("a")) // a is now newest

    cache.put("c", byteArrayOf(3, 3, 3))

    assertNull(cache.get("b"))
    assertContentEquals(byteArrayOf(1, 1, 1), cache.get("a"))
    assertContentEquals(byteArrayOf(3, 3, 3), cache.get("c"))
    assertEquals(
      CatalogRenderCacheSnapshot(entries = 2, bytes = 6, maxBytes = 6, evictions = 1),
      cache.renderCacheSnapshot(),
    )
  }

  @Test
  fun `a render larger than the byte cap is not retained`() {
    val cache = CatalogThemeCache(maxBytes = 2)
    cache.put("large", byteArrayOf(1, 2, 3))

    assertNull(cache.get("large"))
    assertEquals(0, cache.renderCacheSnapshot().entries)
    assertEquals(0, cache.renderCacheSnapshot().bytes)
  }

  /**
   * A preview the daemon can never render (a `painterResource` whose drawable was pruned out of the
   * bundle) must stop being re-attempted, or every request pays a render-lock wait that pushes the
   * rest of the grid into a Busy back-off.
   */
  @Test
  fun `a run of render failures latches, and the reason is readable`() {
    val cache = CatalogThemeCache()

    assertEquals(false, cache.recordRenderFailure("k", "boom"))
    assertNull(cache.failureReason("k"), "one failure may still be a cold-start blip")
    assertEquals(false, cache.recordRenderFailure("k", "boom"))
    assertNull(cache.failureReason("k"))

    assertEquals(true, cache.recordRenderFailure("k", "NotFoundException: ic_play.xml"))
    assertEquals("NotFoundException: ic_play.xml", cache.failureReason("k"))
  }

  @Test
  fun `a successful render clears the latch and its failure count`() {
    val cache = CatalogThemeCache()
    repeat(CatalogThemeCache.FAILURE_LATCH) { cache.recordRenderFailure("k", "boom") }
    assertEquals("boom", cache.failureReason("k"))

    cache.put("k", byteArrayOf(1, 2, 3))

    assertNull(cache.failureReason("k"), "a render that worked un-latches the key")
    // ...and the count restarts, so it takes a fresh run of failures to latch again.
    assertEquals(false, cache.recordRenderFailure("k", "boom"))
  }

  /**
   * The optimizer gives up after a bounded number of attempts, and it gives up on a key the daemon
   * was merely too busy to reach just as it does on one that threw. Only the latter may be reported
   * to a visitor as terminal — otherwise a preview that happened to be contended during the
   * optimization pass 409s forever.
   */
  @Test
  fun `an optimizer miss with no captured failure stays retryable`() {
    val cache = CatalogThemeCache()

    cache.markFailed("busy-only")
    assertNull(cache.failureReason("busy-only"), "running out of attempts is not a render failure")
    // It still counts toward the /status `failed` metric, which is what markFailed is for.
    cache.configureTargets(listOf("busy-only"))
    assertEquals(1, cache.snapshot().failed)

    cache.markFailed("really-broken", "NotFoundException: ic_play.xml")
    assertEquals("NotFoundException: ic_play.xml", cache.failureReason("really-broken"))
    assertNull(cache.failureReason("never-seen"))
  }

  /** A reason recorded before the latch closes must not make the key terminal on its own. */
  @Test
  fun `a reason without the full run of failures is not yet terminal`() {
    val cache = CatalogThemeCache()
    cache.recordRenderFailure("k", "boom")
    assertNull(cache.failureReason("k"))
  }

  /**
   * The instrumentation exists because `cached`/`remaining` alone cannot answer the question that
   * actually matters — is the pass keeping up, and if not, is it render-bound or gate-bound. Two
   * throughput readings against the live server were wrong before this existed: one measured a
   * different lane entirely, one divided by lifetime instead of active time.
   */
  @Test
  fun `optimizer stats report rate, ETA, time split and observed batch width`() {
    val cache = CatalogThemeCache()
    cache.configureTargets((1..10).map { "k$it" })

    cache.recordTurnGranted()
    cache.recordWaiting(30_000) // half the active time spent waiting for a turn
    cache.recordBatch(width = 5, millis = 30_000)
    cache.recordProduced(5)
    cache.recordTurnYielded()
    repeat(5) { cache.put("k${it + 1}", byteArrayOf(1)) }

    val s = cache.snapshot()
    assertEquals(5, s.cached)
    // 5 entries over 60s of ACTIVE time = 5/min; 5 remaining at that rate = 60s.
    assertEquals(5.0, s.entriesPerMinute)
    assertEquals(60L, s.etaSeconds)
    // The split is the diagnostic: half the time rendering, half waiting at the gate.
    assertEquals(30_000L, s.renderMillis)
    assertEquals(30_000L, s.waitingMillis)
    assertEquals(1, s.turnsGranted)
    assertEquals(1, s.turnsYielded)
    // Width is what actually ran, so a batch collapsing to 1 is visible rather than assumed.
    assertEquals(5, s.lastBatchWidth)
    assertEquals(5, s.maxBatchWidth)
  }

  @Test
  fun `rate and ETA stay null before the pass has done anything to divide by`() {
    val cache = CatalogThemeCache()
    cache.configureTargets(listOf("a", "b"))
    val s = cache.snapshot()
    assertNull(s.entriesPerMinute)
    assertNull(s.etaSeconds)
    assertEquals(0, s.maxBatchWidth)
  }

  /**
   * Codex review on #3373. Foreground renders land in this same cache via `cacheCatalogRender`, so
   * counting them toward the rate reports a prefetch throughput the prefetcher never achieved —
   * against a denominator made only of optimizer time. The numerator has to be optimizer output.
   */
  @Test
  fun `foreground-filled entries do not inflate the prefetch rate`() {
    val cache = CatalogThemeCache()
    cache.configureTargets((1..10).map { "k$it" })
    cache.recordWaiting(60_000)

    // Five entries arrive from foreground requests; the optimizer produced none of them.
    repeat(5) { cache.put("k${it + 1}", byteArrayOf(1)) }

    val s = cache.snapshot()
    assertEquals(5, s.cached, "they are cached, and `cached` should say so")
    assertNull(s.entriesPerMinute, "but the prefetcher produced nothing, so it has no rate")
    assertNull(s.etaSeconds)
  }
}
