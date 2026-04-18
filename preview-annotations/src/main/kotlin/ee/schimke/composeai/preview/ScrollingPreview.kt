package ee.schimke.composeai.preview

/**
 * Opts a `@Preview` composable into scrolling-screenshot capture.
 *
 * The compose-preview Gradle plugin's discovery task picks this up by FQN,
 * independently of whether the annotation artifact is on the consumer's
 * compile classpath at plugin-apply time. Consumers that want to use the
 * annotation in their own code depend on
 * `ee.schimke.composeai:preview-annotations`.
 *
 * The annotation only records *intent* in `previews.json` — renderer support
 * for [ScrollMode.END] and [ScrollMode.LONG] lands in subsequent changes.
 */
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION)
@MustBeDocumented
annotation class ScrollingPreview(
    /** Capture strategy for the scrollable content. */
    val mode: ScrollMode,
    /**
     * Upper bound on how far we'll scroll, in pixels. `0` means unbounded —
     * drive to the content's natural end. Use a positive value on infinite
     * or very tall scrollers to cap capture size.
     */
    val maxScrollPx: Int = 0,
    /**
     * If true, scrolling captures wrap the preview body in a
     * `LocalReduceMotion provides ReduceMotion(true)` to flatten Wear
     * `TransformingLazyColumn` item transforms so slices / end-state
     * captures look consistent.
     */
    val reduceMotion: Boolean = true,
    /** Which axis to drive. Only [ScrollAxis.VERTICAL] is rendered today. */
    val axis: ScrollAxis = ScrollAxis.VERTICAL,
)

enum class ScrollMode {
    /** Scroll to the end of the content, then capture a single frame. */
    END,

    /**
     * Capture the full scrollable content, stitching multiple frames into
     * one tall (or wide) PNG.
     */
    LONG,
}

enum class ScrollAxis {
    VERTICAL,
    HORIZONTAL,
}
