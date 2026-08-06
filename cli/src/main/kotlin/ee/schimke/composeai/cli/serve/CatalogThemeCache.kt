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
   * Where the pass's wall-clock actually goes: inside renders vs. waiting for its turn at the idle
   * gate. This is the split that says whether throughput is render-bound (nothing to fix without a
   * faster renderer) or gate-bound (a scheduling problem), which `cached` alone cannot distinguish.
   */
  val renderMillis: Long = 0,
  val waitingMillis: Long = 0,
  /** How often the idle gate granted the pass its turn, and how often traffic took it back. */
  val turnsGranted: Int = 0,
  val turnsYielded: Int = 0,
  /**
   * Renders actually issued concurrently in the last batch, and the widest so far. Makes "five
   * wide" an observed fact rather than an assumption — a batch that silently collapses to 1 (a
   * single-daemon lane, or a seat budget that affords no replicas) is otherwise invisible.
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
      ?: DEFAULT_MAX_BYTES
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
  private val renderMillis = AtomicLong(0)
  private val waitingMillis = AtomicLong(0)
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

  /** Wall-clock spent waiting at the idle gate rather than rendering. */
  fun recordWaiting(millis: Long) {
    if (millis > 0) waitingMillis.addAndGet(millis)
  }

  /** One batch completed: how wide it actually ran, and how long its renders took. */
  fun recordBatch(width: Int, millis: Long) {
    lastBatchWidth.set(width)
    maxBatchWidth.accumulateAndGet(width, ::maxOf)
    if (millis > 0) renderMillis.addAndGet(millis)
  }

  /** Entries this batch actually produced — the rate's numerator. */
  fun recordProduced(count: Int) {
    if (count > 0) optimizerProduced.addAndGet(count)
  }

  /**
   * A cold daemon warm the optimizer waited out. Real render work and often the most expensive part
   * of the pass, so it belongs in [renderMillis] — leaving it out of both buckets shrank the rate's
   * denominator and made a cold catalog report a rate it was nowhere near.
   */
  fun recordWarm(millis: Long) {
    if (millis > 0) renderMillis.addAndGet(millis)
  }

  fun configureTargets(keys: Collection<String>) {
    targetKeys += keys
    refreshCompletion()
  }

  fun get(key: String): ByteArray? = synchronized(renderLock) { renders[key] }

  fun put(key: String, png: ByteArray) {
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
        if (eldest.key in targetKeys) {
          state.set("paused")
          completedAt.set(0)
        }
      }
    }
    failedKeys.remove(key)
    failureCounts.remove(key)
    failureReasons.remove(key)
    busyCounts.remove(key)
    refreshCompletion()
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
    if (synchronized(renderLock) { targetKeys.all(renders::containsKey) }) {
      completedAt.compareAndSet(0, nowMillis)
      state.set("complete")
    } else {
      state.set(if (failedKeys.isEmpty()) "paused" else "degraded")
    }
  }

  fun snapshot(): ThemeOptimizationSnapshot {
    val cachedTargets = synchronized(renderLock) { targetKeys.count(renders::containsKey) }
    val total = targetKeys.size
    val complete = total > 0 && cachedTargets == total
    return ThemeOptimizationSnapshot(
      state =
        if (complete) "complete" else state.get().let { if (it == "complete") "paused" else it },
      total = total,
      cached = cachedTargets,
      remaining = (total - cachedTargets).coerceAtLeast(0),
      failed =
        synchronized(renderLock) {
          failedKeys.count { it in targetKeys && !renders.containsKey(it) }
        },
      cachedBytes = byteCount.get(),
      fullyOptimized = complete,
      startedAtEpochMillis = startedAt.get().takeIf { it > 0 },
      completedAtEpochMillis = completedAt.get().takeIf { it > 0 },
      // Rate over the pass's ACTIVE time (render + gate wait), not wall-clock since it started:
      // wall-clock includes stretches where the pass held no turn at all, which drags the figure
      // toward zero and hides whether it is keeping up while it runs.
      entriesPerMinute = ratePerMinute(),
      etaSeconds =
        ratePerMinute()
          ?.takeIf { it > 0 }
          ?.let { ((total - cachedTargets).coerceAtLeast(0) / it * 60).toLong() },
      renderMillis = renderMillis.get(),
      waitingMillis = waitingMillis.get(),
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

  private fun ratePerMinute(): Double? {
    val produced = optimizerProduced.get()
    val activeMillis = renderMillis.get() + waitingMillis.get()
    if (produced <= 0 || activeMillis <= 0) return null
    return produced / (activeMillis / 60_000.0)
  }

  private fun refreshCompletion() {
    if (
      targetKeys.isNotEmpty() && synchronized(renderLock) { targetKeys.all(renders::containsKey) }
    ) {
      state.set("complete")
    }
  }

  companion object {
    const val DEFAULT_MAX_BYTES: Long = 128L * 1024 * 1024

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
