package ee.schimke.composeai.cli.serve

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.Serializable

/** Progress for one catalog generation's server-side theme-cache optimization. */
@Serializable
data class ThemeOptimizationSnapshot(
  val state: String,
  val total: Int,
  val cached: Int,
  val remaining: Int,
  val failed: Int,
  val cachedBytes: Long,
  val fullyOptimized: Boolean,
  val startedAtEpochMillis: Long? = null,
  val completedAtEpochMillis: Long? = null,
  /**
   * Entries cached per minute over the pass's lifetime, and the projected seconds to finish at that
   * rate. Null before the pass has done enough to divide by.
   *
   * The point of publishing a RATE rather than only `cached`/`remaining`: a cumulative count read
   * twice looks the same whether the pass is keeping up or crawling, and reading progress off a
   * lifetime average (which includes the server's startup, when the pass is parked) understates the
   * current rate. Both mistakes were made against this catalog before this existed.
   */
  val entriesPerMinute: Double? = null,
  val etaSeconds: Long? = null,
  /**
   * Where the pass's wall-clock actually goes: inside renders vs. waiting. This is the split that
   * says whether throughput is render-bound (nothing to fix without a faster renderer) or
   * wait-bound (a scheduling problem), which `cached` alone cannot distinguish.
   */
  /** [batchMillis] + [warmMillis], kept as the single "rendering" total. */
  val renderMillis: Long = 0,
  /**
   * The render bucket, separated — because a cold start and a steady-state render are not the same
   * cost and the sum reads as though they were.
   *
   * [batchMillis] is time inside theme-render batches: the recurring, per-entry cost, and the only
   * part that scales with how much is left to do. [warmMillis] is time waiting out a cold daemon,
   * paid once per daemon per pass but running to **34–68s** on an Android/Robolectric lane — so on
   * a pass that has had only a handful of turns it can be most of [renderMillis] while producing
   * nothing.
   *
   * Reading only the total, a box whose renders are genuinely slow is indistinguishable from one
   * that is fast but keeps paying for cold starts — and "the renderer is the bottleneck" is the
   * wrong conclusion to draw from the second. It was very nearly drawn from this catalog.
   */
  val batchMillis: Long = 0,
  val warmMillis: Long = 0,
  /** [gateWaitMillis] + [permitWaitMillis], kept as the single "not rendering" total. */
  val waitingMillis: Long = 0,
  /**
   * The two waits, separated — because they have opposite fixes and the sum cannot tell them apart.
   *
   * [gateWaitMillis] is time the idle gate withheld a turn: the box looked busy, so the pass is
   * being deliberately polite and the lever is the quiet window. [permitWaitMillis] is time the
   * pass HAD its turn and queued behind other catalogs for a server-wide render permit: the box was
   * idle and the pass was merely outnumbered, and the lever is how many catalogs prefetch at once.
   *
   * Reading only the total, a deployment where every catalog optimizes simultaneously and starves
   * on permits is indistinguishable from one where a trickle of traffic keeps the gate shut — and
   * loosening the quiet window, the obvious response to the latter, does nothing for the former.
   */
  val gateWaitMillis: Long = 0,
  val permitWaitMillis: Long = 0,
  /** How often the idle gate granted the pass its turn, and how often traffic took it back. */
  val turnsGranted: Int = 0,
  val turnsYielded: Int = 0,
  /**
   * Daemons that actually rendered **concurrently** in the last batch, and the most so far.
   *
   * Deliberately not the batch's job count. The optimizer submits N jobs to an executor and the
   * shared pool hands each one a daemon — but when the live-seat budget affords no replica the pool
   * does not spawn one, it queues the job onto a host already in circulation. So N jobs can be N
   * threads taking turns on a single daemon, and a count of jobs submitted reports that as "N
   * wide". This is the peak concurrent borrow observed inside the batch, which is the number that
   * distinguishes the two.
   */
  val lastBatchWidth: Int = 0,
  val maxBatchWidth: Int = 0,
)

/** Memory occupancy of one catalog generation's rendered-preview cache. */
@Serializable
data class CatalogRenderCacheSnapshot(
  val entries: Int,
  val bytes: Long,
  val maxBytes: Long,
  val evictions: Long,
)

/**
 * Rendered PNGs and theme-optimization progress shared by every host incarnation of one catalog
 * generation.
 *
 * A live catalog host is normally suspended after an idle window. Keeping this object in
 * [ServeSessionState] lets the optimized PNGs survive that daemon suspension and be reused when the
 * catalog resumes. Although the optimizer only targets declared themes, the render map also keeps
 * successful on-demand override renders (knobs, locale, font scale, and so on). A catalog refresh
 * builds a fresh session state and therefore a fresh cache, so entries accumulate for exactly as
 * long as the catalog content they were rendered from remains current.
 */
class CatalogThemeCache(
  maxBytes: Long =
    System.getProperty("composeai.serve.catalogRenderCacheMaxBytes")?.toLongOrNull()
      ?: DEFAULT_MAX_BYTES,
  /**
   * Disk tier for this catalog generation, or null to keep the historical memory-only behaviour.
   *
   * **Memory is a window onto this, not a copy of it.** The in-memory cap is 128 MB and a fully
   * warmed m3-catalog is 10,120 PNGs — several times that — so preloading the generation would just
   * thrash the LRU and, worse, would report `cached` as whatever happened to fit rather than what
   * is actually warm. Instead [get] falls through to disk and promotes, and every count of what is
   * cached asks both tiers. That is what lets `cached` keep climbing past the point where memory
   * alone would have started evicting.
   */
  private val persistence: ThemeCacheStore.Generation? = null,
) {
  val maxBytes: Long = maxBytes.coerceAtLeast(0)
  private val renderLock = Any()
  // Access-order map: the byte cap evicts the least-recently-read render first.
  private val renders = LinkedHashMap<String, ByteArray>(16, 0.75f, true)
  private val targetKeys = ConcurrentHashMap.newKeySet<String>()
  private val failedKeys = ConcurrentHashMap.newKeySet<String>()
  // Consecutive live-render failures per key, and the last reason seen. Both are cleared by a
  // successful [put], so a key only stays latched while it keeps failing.
  private val failureCounts = ConcurrentHashMap<String, Int>()
  private val failureReasons = ConcurrentHashMap<String, String>()
  // Consecutive background `Busy` outcomes per key. Separate from [failureCounts] because the two
  // have very different tolerances — see [BUSY_LATCH]. Cleared by a successful [put] like the rest.
  private val busyCounts = ConcurrentHashMap<String, Int>()
  private val byteCount = AtomicLong(0)
  private val evictionCount = AtomicLong(0)
  private val state = AtomicReference("waiting")
  private val startedAt = AtomicLong(0)
  private val completedAt = AtomicLong(0)
  private val batchMillis = AtomicLong(0)
  private val warmMillis = AtomicLong(0)
  private val gateWaitMillis = AtomicLong(0)
  private val permitWaitMillis = AtomicLong(0)
  private val turnsGranted = java.util.concurrent.atomic.AtomicInteger(0)
  private val turnsYielded = java.util.concurrent.atomic.AtomicInteger(0)
  private val lastBatchWidth = java.util.concurrent.atomic.AtomicInteger(0)
  private val maxBatchWidth = java.util.concurrent.atomic.AtomicInteger(0)
  // Entries the OPTIMIZER produced. The rate's denominator is optimizer time, so its numerator has
  // to be optimizer output: foreground renders land in this same cache via `cacheCatalogRender`,
  // and counting them would report a prefetch rate the prefetcher never achieved.
  private val optimizerProduced = java.util.concurrent.atomic.AtomicInteger(0)

  /** The idle gate handed the pass its turn. */
  fun recordTurnGranted() {
    turnsGranted.incrementAndGet()
  }

  /** Traffic took the turn back. */
  fun recordTurnYielded() {
    turnsYielded.incrementAndGet()
  }

  /** Wall-clock the idle gate withheld a turn because the box looked busy. */
  fun recordGateWait(millis: Long) {
    if (millis > 0) gateWaitMillis.addAndGet(millis)
  }

  /** Wall-clock spent holding a turn but queued behind other catalogs for a render permit. */
  fun recordPermitWait(millis: Long) {
    if (millis > 0) permitWaitMillis.addAndGet(millis)
  }

  /**
   * One batch completed. [width] is the peak number of daemons that rendered **concurrently**, not
   * the job count — see [ThemeOptimizationSnapshot.lastBatchWidth] for why those differ.
   */
  fun recordBatch(width: Int, millis: Long) {
    lastBatchWidth.set(width)
    maxBatchWidth.accumulateAndGet(width, ::maxOf)
    if (millis > 0) batchMillis.addAndGet(millis)
  }

  /** Entries this batch actually produced — the rate's numerator. */
  fun recordProduced(count: Int) {
    if (count > 0) optimizerProduced.addAndGet(count)
  }

  /**
   * A cold daemon warm the optimizer waited out. Real render work, so it counts toward
   * [ThemeOptimizationSnapshot.renderMillis] and the rate's denominator — leaving it out of every
   * bucket made a cold catalog report a rate it was nowhere near. But it is kept apart from
   * [recordBatch] time because it produces no entries and does not recur per entry.
   */
  fun recordWarm(millis: Long) {
    if (millis > 0) warmMillis.addAndGet(millis)
  }

  fun configureTargets(keys: Collection<String>) {
    targetKeys += keys
    refreshCompletion()
  }

  /**
   * The render for [key] from memory, or from disk (promoted into memory), or null.
   *
   * The disk read is on the miss path only, so a warm working set costs exactly what it did before
   * persistence existed.
   */
  fun get(key: String): ByteArray? {
    synchronized(renderLock) { renders[key] }
      ?.let {
        return it
      }
    val fromDisk = persistence?.get(key) ?: return null
    // Promoted through the ordinary write path so it takes part in the LRU and the byte accounting
    // like any other entry — but NOT written back to disk, which is where it just came from.
    remember(key, fromDisk)
    return fromDisk
  }

  /** Whether [key] is warm in either tier, without paying to read the bytes. */
  fun contains(key: String): Boolean =
    synchronized(renderLock) { renders.containsKey(key) } || persistence?.contains(key) == true

  fun put(key: String, png: ByteArray) {
    if (png.size.toLong() > maxBytes) return
    // Disk first. A render that survives the process is worth more than one that does not, and if
    // the two were to disagree the durable copy should be the one that exists.
    persistence?.put(key, png)
    remember(key, png)
    failedKeys.remove(key)
    failureCounts.remove(key)
    failureReasons.remove(key)
    busyCounts.remove(key)
    refreshCompletion()
  }

  /** Hold [png] in the memory tier under the byte cap, evicting least-recently-read first. */
  private fun remember(key: String, png: ByteArray) {
    synchronized(renderLock) {
      if (renders.containsKey(key)) return@synchronized
      if (png.size.toLong() > maxBytes) return@synchronized
      renders[key] = png
      byteCount.addAndGet(png.size.toLong())
      while (byteCount.get() > maxBytes && renders.isNotEmpty()) {
        val eldest = renders.entries.iterator().next()
        renders.remove(eldest.key)
        byteCount.addAndGet(-eldest.value.size.toLong())
        evictionCount.incrementAndGet()
        // An eviction only un-completes the catalog when the entry is gone for good. With a disk
        // tier it is not: the memory cap is smaller than a warmed catalog by design, so treating
        // every eviction as lost progress would park a fully-warmed catalog at `paused` forever and
        // send the optimizer back to re-render what is already on disk.
        if (eldest.key in targetKeys && persistence?.contains(eldest.key) != true) {
          state.set("paused")
          completedAt.set(0)
        }
      }
    }
  }

  /**
   * Check a sample of the persisted generation against what the renderer produces **now**, and drop
   * the whole generation if they disagree.
   *
   * This is the safety net for the one thing [ThemeCacheFingerprint] cannot promise. The
   * fingerprint covers the inputs it was told about; an input nobody thought of — a base image
   * bumped without a release, a render default that never reached the config string — changes the
   * pixels without changing the name, and every entry under that name is then quietly wrong. Wrong
   * pixels matter more here than in an ordinary build cache: a stale build artifact gets caught by
   * a test, a stale preview is shown to an agent as ground truth.
   *
   * [render] returns the freshly rendered bytes for a cache key, or null if it could not render —
   * which is **not** a mismatch and must not drop anything, or a busy daemon would wipe the cache.
   *
   * Returns true if the generation is trustworthy (verified, or nothing to verify).
   */
  fun verifySample(sampleSize: Int = VERIFY_SAMPLE, render: (String) -> ByteArray?): Boolean {
    val store = persistence ?: return true
    val candidates = targetKeys.filter(store::contains).sorted().take(sampleSize)
    if (candidates.isEmpty()) return true
    for (key in candidates) {
      val cached = store.get(key) ?: continue
      val fresh = render(key) ?: continue
      if (!fresh.contentEquals(cached)) {
        store.discard()
        synchronized(renderLock) {
          renders.clear()
          byteCount.set(0)
        }
        state.set("paused")
        completedAt.set(0)
        return false
      }
    }
    return true
  }

  fun markRunning(nowMillis: Long) {
    startedAt.compareAndSet(0, nowMillis)
    state.set("running")
  }

  fun markPaused() {
    if (!snapshot().fullyOptimized) state.set("paused")
  }

  /**
   * Mark [key] as one the optimizer could not fill, for the `/status` `failed` count.
   *
   * This is a **metric**, not a verdict: the optimizer gives up after a bounded number of attempts,
   * and it gives up on a key the daemon was merely too busy to get to just as it does on one that
   * genuinely threw. Only a [reason] — captured from a real [RenderOutcome.Failed] — makes the key
   * terminal for [failureReason]. Without that distinction, three `Busy` outcomes during a warm
   * would tell the next visitor the preview can never render.
   */
  fun markFailed(key: String, reason: String? = null) {
    failedKeys += key
    reason?.let { failureReasons[key] = it }
  }

  /**
   * Record one live-render failure for [key] on the **on-demand** lane and report whether that key
   * has now latched as unrenderable ([FAILURE_LATCH] consecutive failures).
   *
   * Without this, only the background optimizer ever marked a key failed, so a preview the daemon
   * genuinely cannot render — a `painterResource` whose drawable isn't in the bundle, say — was
   * re-attempted on every request forever. Each attempt occupies the daemon's render lock long
   * enough to make *other* previews back off as [RenderOutcome.Busy], so one broken card degrades
   * the whole grid. Latching lets the render lane answer immediately instead.
   *
   * [FAILURE_LATCH] rather than one strike because a first failure can be a cold-start timeout or a
   * daemon restart; a successful [put] clears the count, so only a run of failures latches.
   */
  fun recordRenderFailure(key: String, reason: String): Boolean {
    failureReasons[key] = reason
    val count = failureCounts.merge(key, 1, Int::plus) ?: 1
    if (count < FAILURE_LATCH) return false
    failedKeys += key
    return true
  }

  /**
   * Record one **background** `Busy` outcome for [key] and report whether it has now latched
   * ([BUSY_LATCH] consecutive).
   *
   * `Busy` is "ask again", and for a warming daemon that is exactly right — which is why the
   * optimizer deliberately left it unmarked. But "ask again" with no ceiling is indistinguishable
   * from "never", and the optimizer has no other way to notice: it does not re-enter a finished
   * pass, so a key that answers `Busy` on the one pass it gets is simply abandoned, uncounted.
   *
   * Latching supplies the missing terminal state. Once latched the key gets a [reason], so
   * [failureReason] answers the request lane immediately instead of sending the browser back into a
   * `retry-after` loop it can never win, `markPassFinished` reports `degraded` rather than a
   * `paused` that looks like ordinary throttling, and `/status` shows a non-zero `failed` naming
   * how many previews are stuck. A successful [put] clears the count, so a genuinely contended key
   * that eventually renders is never penalised.
   */
  fun recordBackgroundBusy(key: String): Boolean {
    val count = busyCounts.merge(key, 1, Int::plus) ?: 1
    if (count < BUSY_LATCH) return false
    failedKeys += key
    failureReasons[key] =
      "no live lane produced this theme render after $count attempts (daemon busy or absent)"
    return true
  }

  /**
   * Why [key] cannot be rendered, once it has latched as failed; null while it may still succeed.
   * Callers use this to answer a request without going near the daemon.
   *
   * Requires **both** halves: the key is latched, *and* a real render failure supplied a reason. A
   * key in [failedKeys] with no reason is one the optimizer ran out of attempts on — retryable
   * `Busy`, most often — and must stay retryable for a request, or a preview that was merely
   * contended during the optimization pass would be reported as permanently dead to every later
   * visitor.
   */
  fun failureReason(key: String): String? = if (key in failedKeys) failureReasons[key] else null

  fun markPassFinished(nowMillis: Long) {
    if (targetKeys.all(::contains)) {
      completedAt.compareAndSet(0, nowMillis)
      state.set("complete")
    } else {
      state.set(if (failedKeys.isEmpty()) "paused" else "degraded")
    }
  }

  fun snapshot(): ThemeOptimizationSnapshot {
    // Counted across BOTH tiers. Counting memory alone would report a fully warmed catalog as
    // partially cached the moment the 128 MB window started evicting, which is precisely the
    // condition persistence exists to create.
    val cachedTargets = targetKeys.count(::contains)
    val total = targetKeys.size
    val complete = total > 0 && cachedTargets == total
    // Read each counter ONCE and derive everything from those values. Reading them per-field lets a
    // wait that finishes mid-snapshot land in one field and not another, publishing a row where
    // `waitingMillis < gateWaitMillis + permitWaitMillis` — a self-contradicting diagnostic is
    // worse than a slightly stale one.
    val batch = batchMillis.get()
    val warm = warmMillis.get()
    val render = batch + warm
    val gateWait = gateWaitMillis.get()
    val permitWait = permitWaitMillis.get()
    val waiting = gateWait + permitWait
    val rate = ratePerMinute(render + waiting)
    return ThemeOptimizationSnapshot(
      state =
        if (complete) "complete" else state.get().let { if (it == "complete") "paused" else it },
      total = total,
      cached = cachedTargets,
      remaining = (total - cachedTargets).coerceAtLeast(0),
      failed = failedKeys.count { it in targetKeys && !contains(it) },
      cachedBytes = byteCount.get(),
      fullyOptimized = complete,
      startedAtEpochMillis = startedAt.get().takeIf { it > 0 },
      completedAtEpochMillis = completedAt.get().takeIf { it > 0 },
      // Rate over the pass's ACTIVE time (render + gate wait), not wall-clock since it started:
      // wall-clock includes stretches where the pass held no turn at all, which drags the figure
      // toward zero and hides whether it is keeping up while it runs.
      entriesPerMinute = rate,
      etaSeconds =
        rate
          ?.takeIf { it > 0 }
          ?.let { ((total - cachedTargets).coerceAtLeast(0) / it * 60).toLong() },
      renderMillis = render,
      batchMillis = batch,
      warmMillis = warm,
      waitingMillis = waiting,
      gateWaitMillis = gateWait,
      permitWaitMillis = permitWait,
      turnsGranted = turnsGranted.get(),
      turnsYielded = turnsYielded.get(),
      lastBatchWidth = lastBatchWidth.get(),
      maxBatchWidth = maxBatchWidth.get(),
    )
  }

  fun renderCacheSnapshot(): CatalogRenderCacheSnapshot =
    synchronized(renderLock) {
      CatalogRenderCacheSnapshot(
        entries = renders.size,
        bytes = byteCount.get(),
        maxBytes = maxBytes,
        evictions = evictionCount.get(),
      )
    }

  /** [activeMillis] is passed in so the rate divides by the same numbers the snapshot publishes. */
  private fun ratePerMinute(activeMillis: Long): Double? {
    val produced = optimizerProduced.get()
    if (produced <= 0 || activeMillis <= 0) return null
    return produced / (activeMillis / 60_000.0)
  }

  private fun refreshCompletion() {
    if (targetKeys.isNotEmpty() && targetKeys.all(::contains)) {
      state.set("complete")
    }
  }

  companion object {
    const val DEFAULT_MAX_BYTES: Long = 128L * 1024 * 1024

    /**
     * Persisted renders re-rendered and compared when a generation is adopted.
     *
     * Small on purpose. This is a smoke test for "did the fingerprint miss an input", and an input
     * that changes the renderer changes it for every preview — so a handful of entries answers the
     * question as well as a thousand would, at a cost (a few seconds) that a startup can absorb
     * against the 28 hours of rendering it is protecting.
     */
    const val VERIFY_SAMPLE: Int = 5

    /**
     * Consecutive on-demand render failures before a key is treated as permanently unrenderable.
     */
    const val FAILURE_LATCH = 3

    /**
     * Consecutive `Busy` outcomes on the BACKGROUND lane before a key is treated as one the
     * optimizer cannot fill.
     *
     * Deliberately far looser than [FAILURE_LATCH]: `Busy` really does mean "ask again" for a
     * warming daemon, and a key that is merely contended must survive a long run of them. What it
     * must not have is *no* ceiling. Without one the pass can never converge — `markPassFinished`
     * only reports `complete` when every target is cached, and a key that answers `Busy` forever is
     * neither cached nor failed, so the catalog sits at `paused` with `failed: 0` and nothing ever
     * says which previews are stuck.
     *
     * Observed on meshcore-mobile: 84 of 372 targets (21 previews x 4 declared themes) pinned at
     * `paused 288/372, failed: 0` across two server lifetimes, while all fourteen other catalogs on
     * the same box reached `complete`. On the request lane those same previews answered `503 render
     * busy; retry shortly` on 39 of 39 attempts spread over several minutes — a `retry-after` the
     * server could never honour, which the grid's three retries then burned before showing "Theme
     * preview unavailable".
     */
    const val BUSY_LATCH = 12
  }
}
