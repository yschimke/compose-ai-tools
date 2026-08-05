package ee.schimke.composeai.data.render

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One completed phase of a render, as the engines' own `trace.section(...)` calls recorded it.
 *
 * Times are microseconds relative to the start of the render, which is the resolution the panel and
 * the wire payload both work in — nanoseconds would put jitter in the last digits of a number
 * nobody reads that precisely, and milliseconds would round most of a render's phases to zero.
 */
@Serializable
data class RenderTraceSpan(
  val name: String,
  val category: String,
  @SerialName("startUs") val startMicros: Long,
  @SerialName("durationUs") val durationMicros: Long,
  /**
   * Nesting level, 0 for a top-level phase. The engines nest their sections (`render:once` contains
   * `compose:setContent` contains …), and that structure is most of what makes a trace readable — a
   * flat list of thirteen phases says far less than the same thirteen indented.
   */
  val depth: Int,
)

/**
 * Aggregate timings for every span sharing a name within one render.
 *
 * Repeated phases are the norm, not the exception: an animated preview opens `compose:frame` once
 * per sampled frame, and `dataArtifact:<id>` fires once per active extension. A flat span list
 * makes those hard to compare; this is the "where did the time actually go" view over the same
 * data.
 */
@Serializable
data class RenderTraceSection(
  val name: String,
  val category: String,
  val count: Int,
  @SerialName("totalUs") val totalMicros: Long,
  @SerialName("meanUs") val meanMicros: Long,
  @SerialName("maxUs") val maxMicros: Long,
)

/**
 * A render's phase timings, structured.
 *
 * This is the payload behind `render/trace` v2. Where v1 could only report `tookMs` and synthesised
 * a single phase spanning the whole render, this carries what the engines already measure: every
 * `trace.section(...)` they open, nested, with per-name aggregates alongside.
 *
 * Produced by [PerfettoTraceDataProducer.Recorder.renderTrace] and carried to the daemon on
 * `RenderResult.trace`. Independent of the Perfetto JSON artefact the same recorder can also write
 * to disk: that one is a file for an external viewer and is opt-in behind a system property, this
 * one is structured data on the wire and is always available.
 */
@Serializable
data class RenderTrace(
  /** Which engine produced it — `"desktop"`, `"android"`, `"android-live"`. */
  val backend: String,
  /** Wall time from the first span opening to the last one closing. */
  @SerialName("totalUs") val totalMicros: Long,
  /** Every span, ordered by start time (parents before the children they enclose). */
  val spans: List<RenderTraceSpan>,
  /** Per-name aggregates over [spans], ordered by total time descending. */
  val sections: List<RenderTraceSection>,
  /**
   * Spans dropped because the recorder hit its retention cap. Non-zero means [spans] is truncated;
   * [sections] still accounts for every span, so the aggregates stay honest when the timeline
   * can't.
   */
  val droppedSpans: Int = 0,
) {
  /**
   * A closed section as the recorder captured it.
   *
   * [depth] is recorded at section *entry* rather than derived afterwards from containment: the
   * recorder appends spans as they **close**, so a parent lands after its children and containment
   * would have to be reconstructed. A counter on the section stack is exact and costs nothing.
   */
  data class Recorded(
    val name: String,
    val category: String,
    val startNanos: Long,
    val endNanos: Long,
    val depth: Int,
  )

  companion object {
    /** Build a trace from the recorder's closed sections, rebasing times onto the first span. */
    fun of(backend: String, events: List<Recorded>, droppedSpans: Int = 0): RenderTrace {
      if (events.isEmpty()) {
        return RenderTrace(
          backend = backend,
          totalMicros = 0L,
          spans = emptyList(),
          sections = emptyList(),
          droppedSpans = droppedSpans,
        )
      }
      val originNanos = events.minOf { it.startNanos }
      val endNanos = events.maxOf { it.endNanos }
      // Start time ascending, and where two spans start together the enclosing one first, so the
      // list reads top-down as the nesting does.
      val spans =
        events.sortedWith(compareBy({ it.startNanos }, { it.depth })).map { event ->
          RenderTraceSpan(
            name = event.name,
            category = event.category,
            startMicros = (event.startNanos - originNanos) / 1_000L,
            durationMicros = (event.endNanos - event.startNanos).coerceAtLeast(0L) / 1_000L,
            depth = event.depth,
          )
        }
      val sections =
        spans
          .groupBy { it.name }
          .map { (name, group) ->
            RenderTraceSection(
              name = name,
              category = group.first().category,
              count = group.size,
              totalMicros = group.sumOf { it.durationMicros },
              meanMicros = group.sumOf { it.durationMicros } / group.size,
              maxMicros = group.maxOf { it.durationMicros },
            )
          }
          .sortedByDescending { it.totalMicros }
      return RenderTrace(
        backend = backend,
        totalMicros = (endNanos - originNanos).coerceAtLeast(0L) / 1_000L,
        spans = spans,
        sections = sections,
        droppedSpans = droppedSpans,
      )
    }
  }
}
