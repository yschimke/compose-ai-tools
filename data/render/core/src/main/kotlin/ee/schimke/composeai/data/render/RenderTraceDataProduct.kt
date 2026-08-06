package ee.schimke.composeai.data.render

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * `render/trace` — per-preview render phase timings.
 *
 * ## v2
 *
 * v1 could only report `tookMs`, so it synthesised a single `render` phase spanning the whole
 * render: the shape of a trace with none of the content. v2 carries what the engines have been
 * measuring all along — every `trace.section(...)` they open, nested, with per-name aggregates —
 * because those spans now ride to the daemon on `RenderResult.trace` instead of only reaching disk
 * behind the Perfetto opt-in.
 *
 * **The v1 fields are unchanged and still populated**, so a client written against v1 keeps working
 * against a v2 payload without knowing it. `phases[]` is the same array of `{ name, startMs,
 * durationMs }`; it just contains the real phases now. v2 adds:
 * - `category` and `depth` on each phase,
 * - `startUs` / `durationUs` alongside the millisecond fields, because most phases of a fast render
 *   round to `0ms` and a table of zeroes is worse than no table,
 * - a `sections[]` array of per-name aggregates,
 * - `source`, which says whether the phases are real (`"spans"`) or the v1 single-phase fallback
 *   (`"metrics"`) — a client should not have to infer that from `phases.size == 1`,
 * - `droppedSpans`, non-zero only if a pathologically long render blew the recorder's cap.
 */
object RenderTraceDataProduct {
  const val KIND: String = "render/trace"
  const val SCHEMA_VERSION: Int = 2

  /** `source` value when [payloadFrom] had real spans to work from. */
  const val SOURCE_SPANS: String = "spans"

  /** `source` value for the v1-shaped fallback: one synthetic phase covering `tookMs`. */
  const val SOURCE_METRICS: String = "metrics"

  /**
   * Build the payload for one render.
   *
   * [trace] is the engine's recorded phases; when it is null or empty the result is the v1 payload
   * — a single `render` phase over `metrics["tookMs"]`. That fallback is not dead code: hosts that
   * don't run the recorder (sandbox worker replies that carry metrics but no spans, and any future
   * backend) still get a usable, if coarse, answer.
   */
  @JvmOverloads
  fun payloadFrom(metrics: Map<String, Long>, trace: RenderTrace? = null): JsonElement {
    val totalMs = metrics["tookMs"]?.coerceAtLeast(0L) ?: 0L
    val spans = trace?.spans.orEmpty()
    return buildJsonObject {
      put("totalMs", totalMs)
      put("source", if (spans.isEmpty()) SOURCE_METRICS else SOURCE_SPANS)
      if (trace != null) {
        put("backend", trace.backend)
        put("totalUs", trace.totalMicros)
        if (trace.droppedSpans > 0) put("droppedSpans", trace.droppedSpans)
      }
      put(
        "phases",
        buildJsonArray {
          if (spans.isEmpty()) {
            add(
              buildJsonObject {
                put("name", "render")
                put("startMs", 0L)
                put("durationMs", totalMs)
                put("startUs", 0L)
                put("durationUs", totalMs * 1_000L)
                put("category", "render")
                put("depth", 0)
              }
            )
          } else {
            spans.forEach { span ->
              add(
                buildJsonObject {
                  put("name", span.name)
                  put("startMs", span.startMicros / 1_000L)
                  put("durationMs", span.durationMicros / 1_000L)
                  put("startUs", span.startMicros)
                  put("durationUs", span.durationMicros)
                  put("category", span.category)
                  put("depth", span.depth)
                }
              )
            }
          }
        },
      )
      if (spans.isNotEmpty()) {
        put(
          "sections",
          buildJsonArray {
            trace?.sections?.forEach { section ->
              add(
                buildJsonObject {
                  put("name", section.name)
                  put("category", section.category)
                  put("count", section.count)
                  put("totalUs", section.totalMicros)
                  put("meanUs", section.meanMicros)
                  put("maxUs", section.maxMicros)
                }
              )
            }
          },
        )
      }
      putJsonObject("metrics") {
        metrics.toSortedMap().forEach { (name, value) -> put(name, value) }
      }
    }
  }
}
