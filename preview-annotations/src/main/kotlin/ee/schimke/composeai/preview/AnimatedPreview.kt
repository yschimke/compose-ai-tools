package ee.schimke.composeai.preview

/**
 * Opts a `@Preview` composable into animation capture — drives Compose's
 * test `mainClock` across a fixed window and emits an animated GIF, with
 * an optional curve plot of the discovered animations alongside.
 *
 * Discovery picks this up by FQN, mirroring the [ScrollingPreview] split:
 * consumers that want to use the annotation in their own code depend on
 * `ee.schimke.composeai:preview-annotations`.
 *
 * The renderer drives a paused `mainClock.advanceTimeBy([frameIntervalMs])`
 * for `[durationMs] / [frameIntervalMs]` frames, captures each as a PNG,
 * and encodes the sequence with `ScrollGifEncoder`. Output lands at
 * `renders/<id>.gif`.
 *
 * When [showCurves] is true, the renderer additionally attaches Compose
 * UI Tooling's `PreviewAnimationClock` to the composition, samples each
 * discovered animation's properties at every frame, and writes a sidecar
 * curve plot at `renders/<id>_curves.png`. This path requires Compose UI
 * Tooling 1.11.0+ on the test classpath — earlier versions fail at render
 * time with a clear message.
 */
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION)
@MustBeDocumented
annotation class AnimatedPreview(
    /**
     * Total animation window to capture, in milliseconds. The renderer
     * advances the paused `mainClock` by [frameIntervalMs] per step until
     * the cumulative virtual time reaches this value.
     *
     * Defaults to 1500ms — comfortable cover for a typical UI tween or
     * `AnimatedVisibility` reveal without bloating GIF size.
     */
    val durationMs: Int = DEFAULT_ANIMATION_DURATION_MS,
    /**
     * Per-frame delay, in milliseconds. Drives both the virtual-time
     * stepping and the GIF's per-frame `delayTime`. Snaps to GIF's 10ms
     * timing resolution at encode time. Default 33ms ≈ 30fps.
     */
    val frameIntervalMs: Int = DEFAULT_ANIMATION_FRAME_INTERVAL_MS,
    /**
     * When `true`, emit a `<id>_curves.png` sidecar plotting each
     * discovered animation's value-vs-time samples across [durationMs].
     * Requires Compose UI Tooling 1.11.0+ on the test classpath; older
     * versions fail at render time with a clear message rather than
     * silently producing an empty plot.
     */
    val showCurves: Boolean = false,
)

/** Default total animation window for `@AnimatedPreview`. */
const val DEFAULT_ANIMATION_DURATION_MS: Int = 1500

/** Default per-frame delay for `@AnimatedPreview`. */
const val DEFAULT_ANIMATION_FRAME_INTERVAL_MS: Int = 33
