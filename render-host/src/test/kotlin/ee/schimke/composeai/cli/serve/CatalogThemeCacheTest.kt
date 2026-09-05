package ee.schimke.composeai.cli.serve

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
    // Occupancy only. The same snapshot also carries read counters, and asserting the whole object
    // would make this eviction test fail whenever a read is added to it.
    val snapshot = cache.renderCacheSnapshot()
    assertEquals(2, snapshot.entries)
    assertEquals(6, snapshot.bytes)
    assertEquals(6, snapshot.maxBytes)
    assertEquals(1, snapshot.evictions)
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

  /**
   * `Busy` means "ask again", and for a warming daemon that is right — but with no ceiling it is
   * indistinguishable from "never". meshcore-mobile sat at `paused 288/372, failed: 0` across two
   * server lifetimes on exactly that: 84 targets that answered `Busy`, were left unmarked, and so
   * were never counted, never reported, and never given up on.
   */
  @Test
  fun `a long run of background Busy latches with a readable reason`() {
    val cache = CatalogThemeCache()

    repeat(CatalogThemeCache.BUSY_LATCH - 1) {
      assertEquals(false, cache.recordBackgroundBusy("k"), "a contended key must survive a run")
      assertNull(cache.failureReason("k"))
    }

    assertEquals(true, cache.recordBackgroundBusy("k"))
    val reason = cache.failureReason("k")
    assertNotNull(reason, "a latched key must answer the request lane terminally")
    assertTrue(reason.contains("busy or absent"), "the reason names the condition: $reason")
  }

  @Test
  fun `Busy tolerance is far looser than the render-failure latch`() {
    // Otherwise a daemon that is merely slow to warm would be reported as permanently broken.
    assertTrue(CatalogThemeCache.BUSY_LATCH > CatalogThemeCache.FAILURE_LATCH)
    val cache = CatalogThemeCache()
    repeat(CatalogThemeCache.FAILURE_LATCH) { cache.recordBackgroundBusy("k") }
    assertNull(cache.failureReason("k"), "a Busy run as long as FAILURE_LATCH is not yet terminal")
  }

  @Test
  fun `a successful render clears the Busy run`() {
    val cache = CatalogThemeCache()
    repeat(CatalogThemeCache.BUSY_LATCH) { cache.recordBackgroundBusy("k") }
    assertNotNull(cache.failureReason("k"))

    cache.put("k", byteArrayOf(1, 2, 3))

    assertNull(cache.failureReason("k"), "a key that eventually rendered is never penalised")
    assertEquals(false, cache.recordBackgroundBusy("k"), "and the run restarts from zero")
  }

  @Test
  fun `a latched Busy key is reported in the failed count and ends the pass degraded`() {
    // The point of latching: /status names the stuck previews instead of showing `failed: 0`
    // beside a `remaining` that never moves, and the state stops reading like ordinary throttling.
    val cache = CatalogThemeCache()
    cache.configureTargets(listOf("stuck", "fine"))
    cache.put("fine", byteArrayOf(1))
    repeat(CatalogThemeCache.BUSY_LATCH) { cache.recordBackgroundBusy("stuck") }

    cache.markPassFinished(1_000)

    val snapshot = cache.snapshot()
    assertEquals(1, snapshot.failed)
    assertEquals(1, snapshot.remaining)
    assertEquals("degraded", snapshot.state)
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
    cache.recordGateWait(20_000) // the gate withheld the turn
    cache.recordPermitWait(10_000) // then it queued behind other catalogs for a permit
    cache.recordBatch(width = 5, millis = 24_000)
    cache.recordWarm(6_000) // a cold daemon, paid once and producing nothing
    cache.recordProduced(5)
    cache.recordTurnYielded()
    repeat(5) { cache.put("k${it + 1}", byteArrayOf(1)) }

    val s = cache.snapshot()
    assertEquals(5, s.cached)
    // 5 entries over 60s of ACTIVE time = 5/min; 5 remaining at that rate = 60s.
    assertEquals(5.0, s.entriesPerMinute)
    assertEquals(60L, s.etaSeconds)
    // The split is the diagnostic: half the time rendering, half waiting — and BOTH halves split
    // again, because within each pair the two causes have opposite fixes and the sum cannot tell
    // them apart. Cold-start cost vs per-entry render cost; gate withholding vs permit contention.
    assertEquals(30_000L, s.renderMillis)
    assertEquals(24_000L, s.batchMillis)
    assertEquals(6_000L, s.warmMillis)
    assertEquals(30_000L, s.waitingMillis)
    assertEquals(20_000L, s.gateWaitMillis)
    assertEquals(10_000L, s.permitWaitMillis)
    assertEquals(1, s.turnsGranted)
    assertEquals(1, s.turnsYielded)
    // Width is daemons that ran concurrently, so a batch collapsing onto one host is visible.
    assertEquals(5, s.lastBatchWidth)
    assertEquals(5, s.maxBatchWidth)
  }

  /**
   * Measured on the deployed box: 14 catalogs all optimizing at once, every one reporting waiting
   * at 3–6× its render time. The total said "not render-bound" and stopped there — it could not say
   * whether the gate was withholding turns (loosen the quiet window) or the catalogs were starving
   * each other for render permits (prefetch fewer at once). Those two shapes must read differently.
   */
  @Test
  fun `a permit-starved pass and a gate-starved pass are distinguishable`() {
    fun snapshotOf(gate: Long, permit: Long) =
      CatalogThemeCache()
        .apply {
          configureTargets(listOf("a"))
          recordGateWait(gate)
          recordPermitWait(permit)
          recordBatch(width = 1, millis = 10_000)
        }
        .snapshot()

    val gateStarved = snapshotOf(gate = 90_000, permit = 0)
    val permitStarved = snapshotOf(gate = 0, permit = 90_000)

    // Identical on the old instrumentation …
    assertEquals(gateStarved.waitingMillis, permitStarved.waitingMillis)
    assertEquals(gateStarved.renderMillis, permitStarved.renderMillis)
    // … and opposite on the new.
    assertEquals(90_000L, gateStarved.gateWaitMillis)
    assertEquals(0L, gateStarved.permitWaitMillis)
    assertEquals(0L, permitStarved.gateWaitMillis)
    assertEquals(90_000L, permitStarved.permitWaitMillis)
  }

  /**
   * The companion to the wait split. Measured on the deployed box, catalogs reported 88–201s of
   * `renderMillis` against a known warm p50 of 238–1111ms — which reads as a slow renderer until
   * you notice a cold Android warm is 34–68s and each pass had only 3–4 turns. Back the warm out
   * and the per-entry cost is sub-second. "Buy a faster renderer" and "stop paying for cold starts"
   * are not the same project, and the total cannot tell you which one you have.
   */
  @Test
  fun `a cold-start-dominated pass and a render-dominated pass are distinguishable`() {
    fun snapshotOf(batch: Long, warm: Long) =
      CatalogThemeCache()
        .apply {
          configureTargets(listOf("a"))
          recordBatch(width = 5, millis = batch)
          recordWarm(warm)
        }
        .snapshot()

    val coldStarts = snapshotOf(batch = 10_000, warm = 110_000)
    val slowRenders = snapshotOf(batch = 110_000, warm = 10_000)

    // Identical on the old instrumentation …
    assertEquals(coldStarts.renderMillis, slowRenders.renderMillis)
    // … and opposite on the new.
    assertEquals(110_000L, coldStarts.warmMillis)
    assertEquals(10_000L, coldStarts.batchMillis)
    assertEquals(10_000L, slowRenders.warmMillis)
    assertEquals(110_000L, slowRenders.batchMillis)
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
    cache.recordGateWait(60_000)

    // Five entries arrive from foreground requests; the optimizer produced none of them.
    repeat(5) { cache.put("k${it + 1}", byteArrayOf(1)) }

    val s = cache.snapshot()
    assertEquals(5, s.cached, "they are cached, and `cached` should say so")
    assertNull(s.entriesPerMinute, "but the prefetcher produced nothing, so it has no rate")
    assertNull(s.etaSeconds)
  }
}
