package ee.schimke.composeai.cli

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.ceil
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Collects the per-render timings a daemon reports on `renderFinished` and summarises them in a few
 * log lines, so a `bundle pack --with-semantics` run can be profiled from its log alone.
 *
 * Reads, tolerantly: `tookMs` (the render's wall time), `workTrace.stepMs` (per data kind /
 * post-capture processor, daemons from 3.11.0 on) and `metrics.heapAfterGcMb`. Anything an older
 * daemon doesn't send is simply missing from the summary. Thread-safe: several daemons report into
 * one instance.
 */
internal class RenderTimings {
  private val tookMsById = ConcurrentHashMap<String, Long>()
  private val stepMs = ConcurrentHashMap<String, MutableList<Long>>()
  private val failed = AtomicInteger()
  private val stalled = AtomicInteger()
  private val unacknowledged = AtomicInteger()
  private val peakHeapAfterGcMb = AtomicLong(-1)

  fun recordFinished(id: String, params: JsonObject) {
    val took = params["tookMs"]?.longOrNullSafe() ?: 0L
    tookMsById[id] = took
    runCatching { params["workTrace"]?.jsonObject?.get("stepMs")?.jsonObject }
      .getOrNull()
      ?.forEach { (kind, value) ->
        val ms = value.longOrNullSafe() ?: return@forEach
        stepMs.computeIfAbsent(kind) { java.util.Collections.synchronizedList(ArrayList()) }.add(ms)
      }
    runCatching { params["metrics"]?.jsonObject?.get("heapAfterGcMb") }
      .getOrNull()
      ?.longOrNullSafe()
      ?.let { heap -> peakHeapAfterGcMb.accumulateAndGet(heap, ::maxOf) }
  }

  fun recordFailed() {
    failed.incrementAndGet()
  }

  /** [count] renders were in flight when a daemon stopped making progress. */
  fun recordStalled(count: Int) {
    stalled.addAndGet(count)
  }

  /** [count] renders were in flight when `renderNow` itself failed. */
  fun recordUnacknowledged(count: Int) {
    unacknowledged.addAndGet(count)
  }

  /**
   * The summary: one headline, one line of per-preview percentiles, one of per-step costs (most
   * expensive first) and the slowest previews. Nothing per preview, since catalog logs are huge.
   */
  fun summaryLines(wallMs: Long, daemons: Int, window: Int, unsubmitted: Int = 0): List<String> =
    buildList {
      val took = tookMsById.values.sorted()
      add(
        buildString {
          append("render timings: ${took.size} finished, ${failed.get()} failed")
          if (stalled.get() > 0) append(", ${stalled.get()} stalled")
          if (unacknowledged.get() > 0) append(", ${unacknowledged.get()} unacknowledged")
          if (unsubmitted > 0) append(", $unsubmitted never submitted")
          append(" in ${formatMs(wallMs)} across $daemons daemon(s), window $window")
        }
      )
      if (took.isNotEmpty()) {
        add(
          buildString {
            append(
              "  per preview: p50 ${percentile(took, 0.50)}ms, p95 ${percentile(took, 0.95)}ms"
            )
            append(", max ${took.last()}ms, sum ${formatMs(took.sum())}")
            val heap = peakHeapAfterGcMb.get()
            if (heap >= 0) append("; peak heap after GC ${heap}MB")
          }
        )
      }
      val steps =
        stepMs.entries
          .map { (kind, values) -> kind to synchronized(values) { values.sorted() } }
          .filter { it.second.isNotEmpty() }
          .sortedWith(
            compareByDescending<Pair<String, List<Long>>> { it.second.sum() }.thenBy { it.first }
          )
      if (steps.isNotEmpty()) {
        add(
          "  per step (p50/p95/total): " +
            steps.joinToString("; ") { (kind, values) ->
              "$kind ${percentile(values, 0.50)}/${percentile(values, 0.95)}ms/" +
                formatMs(values.sum())
            }
        )
      }
      if (took.size > SLOWEST_COUNT) {
        add(
          "  slowest: " +
            tookMsById.entries
              .sortedWith(
                compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key }
              )
              .take(SLOWEST_COUNT)
              .joinToString(", ") { "${it.key} ${it.value}ms" }
        )
      }
    }

  companion object {
    private const val SLOWEST_COUNT = 5

    /** Nearest-rank percentile of an ascending, non-empty list. */
    fun percentile(sorted: List<Long>, p: Double): Long {
      require(sorted.isNotEmpty())
      val rank = ceil(p * sorted.size).toInt().coerceIn(1, sorted.size)
      return sorted[rank - 1]
    }

    /** `850ms`, `12.3s`, `14m02s`: short enough for a one-line summary. */
    fun formatMs(ms: Long): String =
      when {
        ms < 1_000 -> "${ms}ms"
        ms < 60_000 -> "%.1fs".format(java.util.Locale.ROOT, ms / 1000.0)
        else -> "%dm%02ds".format(java.util.Locale.ROOT, ms / 60_000, (ms / 1000) % 60)
      }

    private fun kotlinx.serialization.json.JsonElement.longOrNullSafe(): Long? = runCatching {
      jsonPrimitive.longOrNull
    }
      .getOrNull()
  }
}
