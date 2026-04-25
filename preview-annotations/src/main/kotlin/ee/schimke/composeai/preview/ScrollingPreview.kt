package ee.schimke.composeai.preview

/**
 * Opts a `@Preview` composable into scrolling-screenshot capture.
 *
 * The compose-preview Gradle plugin's discovery task picks this up by FQN, independently of whether
 * the annotation artifact is on the consumer's compile classpath at plugin-apply time. Consumers
 * that want to use the annotation in their own code depend on
 * `ee.schimke.composeai:preview-annotations`.
 *
 * Each entry in [modes] fans out into its own capture. TOP / END / LONG produce a single PNG per
 * capture; GIF produces an animated `.gif`. Shared knobs — [axis], [maxScrollPx], [reduceMotion] —
 * apply to every capture produced by a single annotation instance. A single-mode annotation (e.g.
 * `modes = [ScrollMode.END]`) keeps the plain `renders/<id>.<ext>` filename; multi-mode annotations
 * disambiguate siblings with a `_SCROLL_<mode>` suffix (`renders/<id>_SCROLL_top.png`,
 * `renders/<id>_SCROLL_end.png`, `renders/<id>_SCROLL_gif.gif`, …).
 */
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION)
@MustBeDocumented
annotation class ScrollingPreview(
  /**
   * Capture strategies for the scrollable content. Each mode produces one PNG. Defaults to `[END]`
   * so migrating from the pre-0.7 single-mode shape (`mode = ScrollMode.END`) is a one-line edit.
   */
  val modes: Array<ScrollMode> = [ScrollMode.END],
  /**
   * Upper bound on how far we'll scroll, in pixels. `0` means unbounded — drive to the content's
   * natural end. Use a positive value on infinite or very tall scrollers to cap capture size.
   * Applies to [ScrollMode.END], [ScrollMode.LONG], and [ScrollMode.GIF]; ignored for
   * [ScrollMode.TOP].
   */
  val maxScrollPx: Int = 0,
  /**
   * If true, scrolling captures wrap the preview body in a `LocalReduceMotion provides
   * ReduceMotion(true)` to flatten Wear `TransformingLazyColumn` item transforms so slices /
   * end-state captures look consistent.
   */
  val reduceMotion: Boolean = true,
  /** Which axis to drive. Only [ScrollAxis.VERTICAL] is rendered today. */
  val axis: ScrollAxis = ScrollAxis.VERTICAL,
  /**
   * Per-frame delay for [ScrollMode.GIF] output, in milliseconds. Snaps to GIF's 10ms timing
   * resolution at encode time. Default 80ms ≈ 12.5fps — smooth enough for a UI scroll, small enough
   * to keep file size reasonable. Ignored by all other modes.
   */
  val frameIntervalMs: Int = DEFAULT_GIF_FRAME_INTERVAL_MS,
)

/**
 * Default per-frame delay, in milliseconds, for `@ScrollingPreview(modes = [ScrollMode.GIF])`.
 * Exposed as a top-level const because Kotlin annotation classes can't carry a companion object.
 * Referenced as the default for [ScrollingPreview.frameIntervalMs].
 */
const val DEFAULT_GIF_FRAME_INTERVAL_MS: Int = 80

enum class ScrollMode {
  /**
   * Capture the initial (unscrolled) frame. Equivalent to a plain `@Preview` but lets you emit a
   * top-state capture alongside END/LONG from one function.
   */
  TOP,

  /** Scroll to the end of the content, then capture a single frame. */
  END,

  /** Capture the full scrollable content, stitching multiple frames into one tall (or wide) PNG. */
  LONG,

  /**
   * Capture the full scrollable content as an animated GIF showing the scroll from top to bottom.
   * Output lands at `renders/<id>.gif` (or `renders/<id>_SCROLL_gif.gif` for a multi-mode
   * annotation). Frame cadence is controlled by [ScrollingPreview.frameIntervalMs].
   */
  GIF,
}

enum class ScrollAxis {
  VERTICAL,
  HORIZONTAL,
}
