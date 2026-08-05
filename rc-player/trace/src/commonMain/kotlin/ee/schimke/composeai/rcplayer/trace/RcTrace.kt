package ee.schimke.composeai.rcplayer.trace

/**
 * Trace categories the player stack opens spans under.
 *
 * Categories exist so a capture can keep the cheap document-lifecycle spans and drop the per-frame
 * ones (or the other way round) without recompiling: `androidx.tracing`'s `TraceDriver` takes an
 * `isCategoryEnabled` predicate, and [RcTracePlatform.isEnabled] is checked before a span is
 * opened, so a disabled category costs one predicate call.
 *
 * Keep the set small. A new category is warranted when a whole phase is noisy enough that someone
 * profiling a *different* phase would want it gone — not merely because a span is new.
 */
public object RcTraceCategory {
  /**
   * Once-per-document work: wire decode, linking, layout-tree construction, inline image and font
   * decode. These fire on load and on document swap, so they are cheap to leave on.
   */
  public const val DOCUMENT: String = "rc-player.document"

  /**
   * Per-frame work: expression evaluation for the frame's time base, and the canvas draw pass. High
   * volume — an animated document opens these ~60 times a second.
   */
  public const val FRAME: String = "rc-player.frame"

  /**
   * Input and action dispatch: click-area hit tests, touch expression updates, and the action
   * blocks they run. Volume follows the user, so this is usually worth leaving on.
   */
  public const val INPUT: String = "rc-player.input"
}

/**
 * An open span. Opaque by design — it carries whatever the platform tracer handed back plus, when a
 * [RcTraceRecorder] is installed, the start mark used to time the span in common code.
 *
 * Instances are created only while something is listening; the disabled path returns `null` and
 * allocates nothing.
 */
public class RcTraceSpan
internal constructor(
  internal val category: String,
  internal val name: String,
  internal val platformToken: Any?,
  internal val recorder: RcTraceRecorder?,
  internal val startNanos: Long,
)

/**
 * The player stack's tracing entry point.
 *
 * Two listeners can be attached independently:
 * - the **platform tracer** ([RcTracePlatform]) — `androidx.tracing` on desktop/JVM, the browser's
 *   User Timing API on wasmJs, nothing on Apple targets. This is what produces a Perfetto trace or
 *   a DevTools performance profile.
 * - an optional **[recorder]** — a common-code span collector. It exists because the platform
 *   tracer is unavailable or unreadable on exactly the targets we most want numbers from (a
 *   Perfetto trace needs a driver and a reader; wasm's User Timing entries live in the browser),
 *   and because summary statistics are the deliverable of a profiling run, not a timeline.
 *
 * Both are off by default, and with both off [begin] returns `null` after a single predicate call.
 */
public object RcTrace {
  /**
   * Optional common-code span collector. Set it before the run you want to measure and read
   * [RcTraceRecorder.summary] afterwards; set it back to `null` to stop collecting.
   *
   * Process-global because the spans it collects are, and because the player is composed from
   * modules that have no other channel to a caller-supplied object.
   */
  public var recorder: RcTraceRecorder? = null

  /** `true` when at least one listener would record a span in [category]. */
  public fun isEnabled(category: String): Boolean =
    recorder != null || RcTracePlatform.isEnabled(category)

  /**
   * Open a span, or return `null` when nothing is listening. Every non-null result must be passed
   * to [end]; prefer [rcTrace], which does that for you.
   */
  public fun begin(category: String, name: String): RcTraceSpan? {
    val recorder = recorder
    val platformEnabled = RcTracePlatform.isEnabled(category)
    if (recorder == null && !platformEnabled) return null
    return RcTraceSpan(
      category = category,
      name = name,
      platformToken = if (platformEnabled) RcTracePlatform.begin(category, name) else null,
      recorder = recorder,
      startNanos = if (recorder != null) recorder.elapsedNanos() else 0L,
    )
  }

  /** Close a span opened by [begin]. A `null` span is ignored, so callers need no branch. */
  public fun end(span: RcTraceSpan?) {
    if (span == null) return
    val recorder = span.recorder
    if (recorder != null) {
      recorder.record(
        category = span.category,
        name = span.name,
        startNanos = span.startNanos,
        endNanos = recorder.elapsedNanos(),
      )
    }
    val token = span.platformToken
    if (token != null) RcTracePlatform.end(token)
  }

  /**
   * Emit a point-in-time marker. Useful for events with no duration — a document swap, a dropped
   * frame, an action firing.
   */
  public fun instant(category: String, name: String) {
    recorder?.recordInstant(category = category, name = name)
    if (RcTracePlatform.isEnabled(category)) RcTracePlatform.instant(category, name)
  }

  /**
   * Emit a counter sample. Perfetto renders these as a track graph; the recorder keeps the last
   * value per name so a summary can report it.
   */
  public fun counter(category: String, name: String, value: Long) {
    recorder?.recordCounter(name = name, value = value)
    if (RcTracePlatform.isEnabled(category)) RcTracePlatform.counter(category, name, value)
  }
}

/**
 * Run [block] inside a trace span named [name] under [category].
 *
 * Inline so a disabled category costs a predicate call and nothing else — no lambda object, no span
 * object, no `try`/`finally` frame beyond what the caller already had.
 *
 * Not usable around `@Composable` calls (the `try`/`finally` and the non-composable lambda type
 * both rule that out). Trace the work a composable *does* — decode, link, measure, draw — rather
 * than the composition itself.
 */
public inline fun <T> rcTrace(category: String, name: String, block: () -> T): T {
  val span = RcTrace.begin(category, name)
  return try {
    block()
  } finally {
    RcTrace.end(span)
  }
}
