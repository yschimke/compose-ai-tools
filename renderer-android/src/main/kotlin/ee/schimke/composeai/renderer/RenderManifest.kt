package ee.schimke.composeai.renderer

import kotlinx.serialization.Serializable

enum class PreviewKind {
    COMPOSE,
    TILE,
}

@Serializable
data class RenderManifest(
    val module: String,
    val variant: String,
    val previews: List<RenderPreviewEntry>,
    /**
     * Relative path (from this manifest's parent directory) to a sidecar
     * [AccessibilityReport] JSON file, when accessibility checks are enabled.
     * `null` means the feature is off for this module — tools should treat the
     * absence of this pointer as "no a11y data" rather than probing for the
     * file on disk.
     */
    val accessibilityReport: String? = null,
)

/**
 * ATF findings per preview. Written by [RobolectricRenderTestBase] when
 * accessibility checks are enabled, read by the plugin's post-render verify
 * task and by downstream tools (CLI, VSCode).
 */
@Serializable
data class AccessibilityReport(
    val module: String,
    val entries: List<AccessibilityEntry>,
)

@Serializable
data class AccessibilityEntry(
    val previewId: String,
    val findings: List<AccessibilityFinding>,
    /**
     * Relative path (from the aggregated `accessibility.json`) to an
     * annotated screenshot showing each finding as a numbered badge + legend.
     * `null` when there were no findings, or when overlay generation was
     * skipped. Consumers should treat a missing file the same as a missing
     * pointer — fall back to the clean render.
     */
    val annotatedPath: String? = null,
)

@Serializable
data class AccessibilityFinding(
    /** `ERROR`, `WARNING`, `INFO`, or `NOT_RUN` — upper-cased ATF `AccessibilityCheckResultType`. */
    val level: String,
    /** Short rule identifier — ATF check class simple name (e.g. `TouchTargetSizeCheck`). */
    val type: String,
    val message: String,
    /** Human-readable description of the offending element, if ATF could resolve one. */
    val viewDescription: String? = null,
    /** `left,top,right,bottom` in the preview's pixel space — agents can highlight on the PNG. */
    val boundsInScreen: String? = null,
)

@Serializable
data class RenderPreviewEntry(
    val id: String,
    val functionName: String,
    val className: String,
    val sourceFile: String? = null,
    val params: RenderPreviewParams = RenderPreviewParams(),
    val renderOutput: String? = null,
)

@Serializable
data class RenderPreviewParams(
    val name: String? = null,
    val device: String? = null,
    val widthDp: Int? = null,
    val heightDp: Int? = null,
    val fontScale: Float = 1.0f,
    val showSystemUi: Boolean = false,
    val showBackground: Boolean = false,
    val backgroundColor: Long = 0,
    val uiMode: Int = 0,
    val locale: String? = null,
    val group: String? = null,
    /** FQN of the `PreviewWrapperProvider` from `@PreviewWrapper`, if any. */
    val wrapperClassName: String? = null,
    val kind: PreviewKind = PreviewKind.COMPOSE,
    /**
     * Virtual-time offset to advance `mainClock` by before capture, sourced from
     * Roborazzi's `@RoboComposePreviewOptions(manualClockOptions = [...])`. `null`
     * means use the renderer's default. One entry per `ManualClockOptions` value
     * is emitted at discovery time.
     */
    val advanceTimeMillis: Long? = null,
)
