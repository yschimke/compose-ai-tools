package ee.schimke.composeai.rcplayer.trace

import kotlin.time.TimeSource

/** One completed span. Times are nanoseconds since the owning [RcTraceRecorder] was created. */
public data class RcTraceEvent(
  public val category: String,
  public val name: String,
  public val startNanos: Long,
  public val durationNanos: Long,
)

/** Aggregate timings for every span sharing a [name]. Durations are nanoseconds. */
public data class RcTraceSectionStats(
  public val category: String,
  public val name: String,
  public val count: Int,
  public val totalNanos: Long,
  public val meanNanos: Long,
  public val medianNanos: Long,
  public val p95Nanos: Long,
  public val maxNanos: Long,
)

/**
 * A common-code span collector, installed via [RcTrace.recorder].
 *
 * It exists alongside the platform tracer rather than instead of it. The platform tracer is the
 * right thing for a timeline, but it is a no-op on Apple targets, lives inside the browser on
 * wasmJs, and on desktop produces a Perfetto protobuf that needs `TraceProcessor` to read back. A
 * profiling *run* wants neither of those: it wants "how long did the draw pass take, over 120
 * frames, at the median and the 95th percentile", on whichever target it happens to be running.
 * That is what [summary] answers, identically on every target.
 *
 * Recording is unsynchronized. The player composes, measures, and draws on one thread, which is the
 * only thread that opens spans; a recorder shared across threads would need external locking.
 */
public class RcTraceRecorder(
  /**
   * Upper bound on retained events. A 60fps document opens a handful of spans per frame, so a long
   * capture is bounded rather than unbounded — past the cap, [summary] statistics keep accumulating
   * but individual events stop being retained, and [droppedEvents] reports how many were shed.
   */
  private val maxEvents: Int = DEFAULT_MAX_EVENTS
) {
  private val origin = TimeSource.Monotonic.markNow()
  private val retained = mutableListOf<RcTraceEvent>()
  private val durationsByName = mutableMapOf<String, MutableList<Long>>()
  private val categoryByName = mutableMapOf<String, String>()
  private val counters = mutableMapOf<String, Long>()
  private val instants = mutableListOf<RcTraceEvent>()

  /** Events discarded because [maxEvents] was reached. Their durations still count in [summary]. */
  public var droppedEvents: Int = 0
    private set

  /** Nanoseconds since this recorder was created. */
  public fun elapsedNanos(): Long = origin.elapsedNow().inWholeNanoseconds

  internal fun record(category: String, name: String, startNanos: Long, endNanos: Long) {
    val duration = (endNanos - startNanos).coerceAtLeast(0L)
    durationsByName.getOrPut(name) { mutableListOf() }.add(duration)
    categoryByName[name] = category
    if (retained.size < maxEvents) {
      retained +=
        RcTraceEvent(
          category = category,
          name = name,
          startNanos = startNanos,
          durationNanos = duration,
        )
    } else {
      droppedEvents += 1
    }
  }

  internal fun recordInstant(category: String, name: String) {
    if (instants.size < maxEvents) {
      instants +=
        RcTraceEvent(
          category = category,
          name = name,
          startNanos = elapsedNanos(),
          durationNanos = 0L,
        )
    }
  }

  internal fun recordCounter(name: String, value: Long) {
    counters[name] = value
  }

  /** Retained completed spans, in the order they closed. */
  public fun events(): List<RcTraceEvent> = retained.toList()

  /** Last sampled value of every counter, by name. */
  public fun counters(): Map<String, Long> = counters.toMap()

  /**
   * Per-section aggregates over **every** span recorded, including ones shed by [maxEvents],
   * ordered by total time descending — the profile's headline ordering.
   */
  public fun summary(): List<RcTraceSectionStats> =
    durationsByName
      .map { (name, durations) ->
        val sorted = durations.sorted()
        RcTraceSectionStats(
          category = categoryByName.getValue(name),
          name = name,
          count = sorted.size,
          totalNanos = sorted.sum(),
          meanNanos = sorted.sum() / sorted.size,
          medianNanos = sorted.percentile(50),
          p95Nanos = sorted.percentile(95),
          maxNanos = sorted.last(),
        )
      }
      .sortedByDescending { it.totalNanos }

  /** Drop everything recorded so far and restart the clock, so one recorder can serve many runs. */
  public fun reset() {
    retained.clear()
    durationsByName.clear()
    categoryByName.clear()
    counters.clear()
    instants.clear()
    droppedEvents = 0
  }

  /**
   * The retained events as a Chrome Trace Event JSON document — the format `ui.perfetto.dev` and
   * `chrome://tracing` both open, and the same shape the daemon's `render/composeAiTrace` data
   * product emits, so one viewer serves both.
   *
   * Hand-rolled rather than serialized: this module is the base of the player's dependency graph
   * and stays dependency-free apart from the platform tracer, and the document is a flat array of
   * four-field objects.
   */
  public fun toChromeTraceJson(processName: String): String = buildString {
    append("{\"displayTimeUnit\":\"ms\",\"traceEvents\":[")
    append("{\"name\":\"process_name\",\"ph\":\"M\",\"pid\":1,\"tid\":1,\"args\":{\"name\":\"")
    appendJsonEscaped(processName)
    append("\"}}")
    retained.forEach { event ->
      append(",{\"name\":\"")
      appendJsonEscaped(event.name)
      append("\",\"cat\":\"")
      appendJsonEscaped(event.category)
      append("\",\"ph\":\"X\",\"pid\":1,\"tid\":1,\"ts\":")
      append(event.startNanos / 1000.0)
      append(",\"dur\":")
      append(event.durationNanos / 1000.0)
      append('}')
    }
    instants.forEach { event ->
      append(",{\"name\":\"")
      appendJsonEscaped(event.name)
      append("\",\"cat\":\"")
      appendJsonEscaped(event.category)
      append("\",\"ph\":\"i\",\"s\":\"p\",\"pid\":1,\"tid\":1,\"ts\":")
      append(event.startNanos / 1000.0)
      append('}')
    }
    append("]}")
  }

  private companion object {
    const val DEFAULT_MAX_EVENTS = 200_000
  }
}

/**
 * Nearest-rank percentile over an already-sorted, non-empty list. Nearest-rank rather than an
 * interpolating definition so a reported p95 is always a duration that was actually observed.
 */
internal fun List<Long>.percentile(percentile: Int): Long {
  val rank = ((percentile / 100.0) * size).toInt().coerceIn(0, size - 1)
  return this[rank]
}

private fun StringBuilder.appendJsonEscaped(value: String) {
  value.forEach { character ->
    when (character) {
      '"' -> append("\\\"")
      '\\' -> append("\\\\")
      '\n' -> append("\\n")
      '\r' -> append("\\r")
      '\t' -> append("\\t")
      else ->
        if (character < ' ') append("\\u").append(character.code.toString(16).padStart(4, '0'))
        else append(character)
    }
  }
}
