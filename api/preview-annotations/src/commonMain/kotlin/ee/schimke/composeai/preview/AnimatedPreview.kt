package ee.schimke.composeai.preview

/**
 * Opts a `@Preview` composable into animation capture.
 *
 * Discovery picks this up by FQN, mirroring the [ScrollingPreview] split: consumers that want to
 * use the annotation in their own code depend on `ee.schimke.composeai:preview-annotations`.
 *
 * The renderer drives a paused `mainClock.advanceTimeBy([frameIntervalMs])` across the captured
 * window, capturing each frame and encoding the sequence at `renders/<id>.gif` — or
 * `renders/<id>.apng` when [format] asks for it.
 *
 * This is the **self-driven** half of motion capture; [InteractionPreview] is the pointer-driven
 * half, and both publish into a catalog component's Motion section. Use this one when the component
 * animates without being touched (an indeterminate progress indicator, an `InfiniteTransition`, a
 * shimmer) and [InteractionPreview] when a gesture is what makes it move.
 *
 * When [showCurves] is true (the default) the GIF is composed of two stacked panels per frame — the
 * preview composable on top, a curve strip plotting each tracked animation's value vs. time below,
 * with a dot marking the current frame's position on each curve. This path requires Compose UI
 * Tooling 1.10.6+ on the test classpath; earlier versions fail at render time with a clear message
 * naming the missing class. Set [showCurves] = `false` to emit a screenshot-only GIF.
 *
 * When [durationMs] is `0` (the default), the renderer asks `PreviewAnimationClock` how long the
 * discovered animations actually run and uses that as the GIF duration — a 600ms tween renders a
 * 600ms GIF rather than 900ms of "settled at target" padding. Set a positive value to override
 * (`InfiniteTransition` has no inherent duration; sometimes a longer dwell is wanted for review).
 */
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION)
@MustBeDocumented
annotation class AnimatedPreview(
  /**
   * Total animation window to capture, in milliseconds.
   *
   * `0` (the default) asks the renderer to auto-detect the duration via
   * `PreviewAnimationClock.getMaxDuration()`. Falls back to [DEFAULT_ANIMATION_DURATION_MS] when no
   * Compose animations are discovered (e.g. a static `@Preview` accidentally annotated, or a
   * hand-rolled `withFrameNanos` loop the inspector can't see).
   *
   * A positive value overrides the auto-detected duration. Capped at 5000ms regardless to keep GIF
   * size bounded.
   */
  val durationMs: Int = AUTO_DETECT_DURATION_MS,
  /**
   * Per-frame delay, in milliseconds. Drives both the virtual-time stepping and the GIF's per-frame
   * `delayTime`. Snaps to GIF's 10ms timing resolution at encode time. Default 33ms ≈ 30fps.
   */
  val frameIntervalMs: Int = DEFAULT_ANIMATION_FRAME_INTERVAL_MS,
  /**
   * When `true` (the default), the captured GIF is composed of two stacked panels per frame: the
   * preview on top, and a curve strip below plotting each discovered animated property's value over
   * time with a moving dot marking the current frame's position.
   *
   * Set `false` to emit a screenshot-only GIF — useful for visual diff workflows that compare
   * against earlier non-instrumented renders.
   */
  val showCurves: Boolean = true,
  // Appended rather than slotted in beside the fields they read best next to: a parameter inserted
  // ahead of an existing one silently re-points a positional call, and `@AnimatedPreview(1500, 33)`
  // is a shape consumers already write.
  /**
   * One line describing what the reader should watch for, shown under the capture in a catalog
   * component's Motion section. Empty (the default) publishes the capture unlabelled.
   *
   * Only consulted when the annotated preview is also a catalog component (or a variant of one) — a
   * standalone `@AnimatedPreview` has no page to carry the line.
   */
  val caption: String = "",
  /**
   * Container format. Defaults to [MotionFormat.Gif] — the historical output, kept so existing
   * captures keep their bytes and their `renders/<id>.gif` paths.
   *
   * Set [MotionFormat.Apng] for a capture that documents colour or runs faster than 50fps; a GIF
   * cannot represent either (see [MotionFormat.Apng]). A 60fps spinner is the motivating case: at
   * [frameIntervalMs] `= 16` a GIF quantises every delay to 20ms and plays at an uneven ~50fps,
   * while the APNG plays the 1/60 it was captured at.
   */
  val format: MotionFormat = MotionFormat.Gif,
)

/**
 * Sentinel `durationMs` value asking the renderer to auto-detect the animation duration via
 * `PreviewAnimationClock`. See [AnimatedPreview.durationMs].
 */
const val AUTO_DETECT_DURATION_MS: Int = 0

/**
 * Fallback total animation window for `@AnimatedPreview` when auto- detection is requested but no
 * Compose animations were discovered.
 */
const val DEFAULT_ANIMATION_DURATION_MS: Int = 1500

/** Default per-frame delay for `@AnimatedPreview`. */
const val DEFAULT_ANIMATION_FRAME_INTERVAL_MS: Int = 33
