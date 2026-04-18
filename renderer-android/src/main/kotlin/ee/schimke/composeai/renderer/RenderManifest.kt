package ee.schimke.composeai.renderer

import kotlinx.serialization.Serializable

enum class PreviewKind {
    COMPOSE,
    TILE,
}

/**
 * Mirrors `ee.schimke.composeai.preview.ScrollMode` from the `preview-annotations`
 * artifact. Duplicated on the renderer side (same split as [PreviewKind]) so the
 * renderer can read `previews.json` without depending on the annotation artifact.
 */
enum class ScrollMode {
    END,
    LONG,
}

/** Mirrors `ee.schimke.composeai.preview.ScrollAxis`. */
enum class ScrollAxis {
    VERTICAL,
    HORIZONTAL,
}

/** Renderer-side mirror of the plugin's `ScrollCapture`. */
@Serializable
data class ScrollCapture(
    val mode: ScrollMode,
    val axis: ScrollAxis = ScrollAxis.VERTICAL,
    val maxScrollPx: Int = 0,
    val reduceMotion: Boolean = true,
    val atEnd: Boolean = false,
    val reachedPx: Int? = null,
)

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
    /**
     * Rendered snapshots produced by this preview. See the plugin-side
     * `Capture` docs — each entry carries the dimensional values
     * (`advanceTimeMillis`, `scroll`) that distinguish it from its siblings
     * and the PNG path it lands at. Always at least one element.
     */
    val captures: List<RenderPreviewCapture> = listOf(RenderPreviewCapture()),
)

@Serializable
data class RenderPreviewCapture(
    val advanceTimeMillis: Long? = null,
    val scroll: ScrollCapture? = null,
    val renderOutput: String = "",
)

@Serializable
data class RenderPreviewParams(
    val name: String? = null,
    val device: String? = null,
    val widthDp: Int? = null,
    val heightDp: Int? = null,
    /**
     * Compose density factor (= densityDpi / 160) sourced from the `@Preview`
     * device. The Android renderer maps this to a Robolectric `<n>dpi`
     * qualifier; the desktop renderer hands it to `Density(...)`. `null` means
     * "use the renderer's default" (matches the historical 2.0x behaviour).
     */
    val density: Float? = null,
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
)
