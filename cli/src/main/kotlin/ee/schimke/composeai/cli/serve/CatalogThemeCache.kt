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
  private val byteCount = AtomicLong(0)
  private val evictionCount = AtomicLong(0)
  private val state = AtomicReference("waiting")
  private val startedAt = AtomicLong(0)
  private val completedAt = AtomicLong(0)

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
  }
}
