package ee.schimke.composeai.preview

/**
 * Opts a Wear `@Preview` **component** into TLC-scaling previews: extra captures that show the
 * component scaled and faded as a `TransformingLazyColumn` row would be at increasing distance from
 * the screen centre — with the component authored in exactly the normal TLC-item code (real
 * `Modifier.transformedHeight(this, spec)` + `SurfaceTransformation(spec)`), hosted in a real
 * single-item `TransformingLazyColumn` so the scaling is genuine, not faked.
 *
 * It declares, from one annotated preview:
 * - **[frames] stills**, evenly stepped from **unscaled** (centred, full scale) to **most scaled**
 *   (riding the top edge). Five by default. The still sweep is realised with a `@PreviewParameter`
 *   over the scaling levels, so the plugin renders one frame per level from the single preview.
 * - an **animated GIF** (when [gif]) that scales the component **down and back up** — full → most
 *   scaled → full — looping, at [frameIntervalMs] per frame.
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
   * How many still frames the sweep steps through, from unscaled (centred) to most scaled. Must be
   * `>= 2` (the two endpoints); the default of 5 gives full plus four progressively-scaled frames.
   */
  val frames: Int = DEFAULT_TLC_SCALING_FRAMES,
  /**
   * If true, also emit an animated GIF scaling the component down to the most-scaled frame and
   * back.
   */
  val gif: Boolean = true,
  /**
   * Per-frame delay for the [gif] output, in milliseconds. Default 80ms ≈ 12.5fps — smooth enough
   * for the scale animation, small enough to keep the file reasonable. Ignored when [gif] is false.
   */
  val frameIntervalMs: Int = DEFAULT_GIF_FRAME_INTERVAL_MS,
)

/**
 * Default still-frame count for [TlcScalingPreview] (unscaled + most-scaled + three between).
 * Exposed as a top-level const because Kotlin annotation classes can't carry a companion object.
 */
const val DEFAULT_TLC_SCALING_FRAMES: Int = 5
