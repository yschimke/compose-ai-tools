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

/**
 * Theme PNGs and optimization progress shared by every host incarnation of one catalog generation.
 *
 * A live catalog host is normally suspended after an idle window. Keeping this object in
 * [ServeSessionState] lets the optimized PNGs survive that daemon suspension and be reused when the
 * catalog resumes. A catalog refresh builds a fresh session state and therefore a fresh cache.
 */
class CatalogThemeCache {
  private val renders = ConcurrentHashMap<String, ByteArray>()
  private val targetKeys = ConcurrentHashMap.newKeySet<String>()
  private val failedKeys = ConcurrentHashMap.newKeySet<String>()
  private val byteCount = AtomicLong(0)
  private val state = AtomicReference("waiting")
  private val startedAt = AtomicLong(0)
  private val completedAt = AtomicLong(0)

  fun configureTargets(keys: Collection<String>) {
    targetKeys += keys
    refreshCompletion()
  }

  fun get(key: String): ByteArray? = renders[key]

  fun put(key: String, png: ByteArray) {
    if (renders.putIfAbsent(key, png) == null) byteCount.addAndGet(png.size.toLong())
    failedKeys.remove(key)
    refreshCompletion()
  }

  fun markRunning(nowMillis: Long) {
    startedAt.compareAndSet(0, nowMillis)
    state.set("running")
  }

  fun markPaused() {
    if (!snapshot().fullyOptimized) state.set("paused")
  }

  fun markFailed(key: String) {
    failedKeys += key
  }

  fun markPassFinished(nowMillis: Long) {
    if (targetKeys.all(renders::containsKey)) {
      completedAt.compareAndSet(0, nowMillis)
      state.set("complete")
    } else {
      state.set(if (failedKeys.isEmpty()) "paused" else "degraded")
    }
  }

  fun snapshot(): ThemeOptimizationSnapshot {
    val cachedTargets = targetKeys.count(renders::containsKey)
    val total = targetKeys.size
    val complete = total > 0 && cachedTargets == total
    return ThemeOptimizationSnapshot(
      state = if (complete) "complete" else state.get(),
      total = total,
      cached = cachedTargets,
      remaining = (total - cachedTargets).coerceAtLeast(0),
      failed = failedKeys.count { it in targetKeys && !renders.containsKey(it) },
      cachedBytes = byteCount.get(),
      fullyOptimized = complete,
      startedAtEpochMillis = startedAt.get().takeIf { it > 0 },
      completedAtEpochMillis = completedAt.get().takeIf { it > 0 },
    )
  }

  private fun refreshCompletion() {
    if (targetKeys.isNotEmpty() && targetKeys.all(renders::containsKey)) {
      state.set("complete")
    }
  }
}
