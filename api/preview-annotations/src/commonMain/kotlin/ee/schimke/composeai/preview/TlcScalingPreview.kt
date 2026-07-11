package ee.schimke.composeai.preview

/**
 * Opts a Wear `@Preview` **component** into TLC-scaling previews: extra captures that show the
 * component scaled and faded as a `TransformingLazyColumn` row would be at increasing distance from
 * the screen centre — with no list present.
 *
 * A `TransformingLazyColumn` draws its centre row at full size and shrinks + fades rows toward the
 * curved top/bottom edges. An isolated component preview never shows that, so this annotation asks
 * the pipeline to render the same composable at a sweep of simulated on-screen positions, driven by
 * the component's `tlcPosition` preview-override knob (see the wear catalog's `previewTlcScaling`).
 *
 * It produces, from one annotated preview:
 * - **[frames] stills**, evenly stepped from **unscaled** (centred, full scale) to **most scaled**
 *   ([minCenterFraction], riding off the top edge). Four by default.
 * - an **animated GIF** (when [gif]) that scales the component **down and back up** — centre →
 *   [minCenterFraction] → centre — looping, at [frameIntervalMs] per frame.
 *
 * Retention is [AnnotationRetention.BINARY] so the compose-preview discovery task can pick it up by
 * FQN from compiled classes, exactly like [ScrollingPreview]; consumers depend on
 * `ee.schimke.composeai:preview-annotations` to use it.
 */
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION)
@MustBeDocumented
annotation class TlcScalingPreview(
  /**
   * How many still frames to render, stepping from unscaled (centred) to most scaled
   * ([minCenterFraction]). Must be `>= 2` (the two endpoints); the default of 4 gives full plus
   * three progressively-scaled frames.
   */
  val frames: Int = DEFAULT_TLC_SCALING_FRAMES,
  /**
   * The most-scaled position: the fraction of screen height at which the component's centre sits
   * for the final still (and the GIF's turning point). `0.5` is centred / full scale; smaller
   * values ride further off the top edge, so the component scales further down. Default `0.07` is
   * near the top edge, close to the list's minimum scale.
   */
  val minCenterFraction: Float = DEFAULT_TLC_SCALING_MIN_CENTER_FRACTION,
  /**
   * If true, also emit an animated GIF scaling the component down to [minCenterFraction] and back.
   */
  val gif: Boolean = true,
  /**
   * Per-frame delay for the [gif] output, in milliseconds. Default 80ms ≈ 12.5fps — smooth enough
   * for the scale animation, small enough to keep the file reasonable. Ignored when [gif] is false.
   */
  val frameIntervalMs: Int = DEFAULT_GIF_FRAME_INTERVAL_MS,
)

/**
 * Default still-frame count for [TlcScalingPreview] (unscaled + most-scaled + two between). Exposed
 * as a top-level const because Kotlin annotation classes can't carry a companion object.
 */
const val DEFAULT_TLC_SCALING_FRAMES: Int = 4

/** Default most-scaled centre fraction for [TlcScalingPreview] — near the top edge. */
const val DEFAULT_TLC_SCALING_MIN_CENTER_FRACTION: Float = 0.07f
