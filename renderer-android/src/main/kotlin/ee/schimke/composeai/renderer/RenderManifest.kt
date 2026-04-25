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
    TOP,
    END,
    LONG,
    GIF,
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
    /**
     * Per-frame delay for [ScrollMode.GIF] captures, in milliseconds. `0`
     * means "renderer default" ([ScrollGifEncoder.DEFAULT_FRAME_DELAY_MS]).
     */
    val frameIntervalMs: Int = 0,
    val atEnd: Boolean = false,
    val reachedPx: Int? = null,
)

/** Renderer-side mirror of the plugin's `AnimationCapture`. */
@Serializable
data class AnimationCapture(
    val durationMs: Int,
    val frameIntervalMs: Int,
    val showCurves: Boolean = false,
)

/**
 * Heavy/fast threshold for [RenderPreviewCapture.cost]. Mirrors the plugin's
 * `HEAVY_COST_THRESHOLD` — anything strictly greater is considered "heavy"
 * and gets dropped when `composeai.render.tier=fast`. Single source of truth
 * for the renderer; the plugin enforces the same threshold over the same
 * cost numbers it stamped at discovery.
 */
const val HEAVY_COST_THRESHOLD: Float = 5.0f

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
     * Every accessibility-relevant node ATF saw on the rendered tree.
     * Populated whether or not [findings] is empty so consumers can render
     * a Paparazzi-style "what TalkBack sees" overlay even when there's
     * nothing to fix. Empty list ≈ a11y disabled or the View has no
     * labelled / actionable content.
     */
    val nodes: List<AccessibilityNode> = emptyList(),
    /**
     * Relative path (from the aggregated `accessibility.json`) to an
     * annotated screenshot showing each finding as a numbered badge + legend.
     * `null` when there were no findings, or when overlay generation was
     * skipped. Consumers should treat a missing file the same as a missing
     * pointer — fall back to the clean render.
     */
    val annotatedPath: String? = null,
)

/**
 * One accessibility-relevant node from the rendered View tree, captured for
 * the Paparazzi-style overlay (translucent colour fill on the screenshot
 * matched against a swatched legend). The shape is deliberately small — we
 * keep only what TalkBack would announce and what the overlay needs to
 * draw, not the full ATF [ViewHierarchyElement][com.google.android.apps.common.testing.accessibility.framework.uielement.ViewHierarchyElement]
 * graph.
 */
@Serializable
data class AccessibilityNode(
    /** Visible text or contentDescription. Always non-empty for emitted nodes. */
    val label: String,
    /**
     * TalkBack's class announcement (`Button`, `Image`, `TextView`, …).
     * `null` for plain Views that only carry a label, so the legend can
     * skip the role chip and avoid the noisy `View` everyone gets.
     */
    val role: String? = null,
    /**
     * Extra semantic state (`selected`, `checked`, `unchecked`). Empty for
     * stateless nodes. Heading isn't here — ATF's hierarchy doesn't expose
     * it cleanly enough to detect Compose-side `Modifier.semantics { heading() }`.
     */
    val states: List<String> = emptyList(),
    /** `left,top,right,bottom` in source-bitmap pixels — same shape as [AccessibilityFinding.boundsInScreen]. */
    val boundsInScreen: String,
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
    val animation: AnimationCapture? = null,
    val renderOutput: String = "",
    /**
     * Estimated render cost normalised so a static `@Preview` is `1.0`. See
     * the plugin's `Capture.cost` for the full catalogue. Defaults to `1.0`
     * so older manifests parse as cheap-everywhere.
     */
    val cost: Float = 1.0f,
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
    /**
     * FQN of a `PreviewParameterProvider` harvested from `@PreviewParameter`
     * on one of the preview function's parameters, if any. When non-null the
     * renderer enumerates the provider's `values` (capped by
     * [previewParameterLimit]) and emits one file per value with a
     * `_PARAM_<idx>` suffix. `null` means the preview has no parameter
     * provider — the default single-capture path applies.
     */
    val previewParameterProviderClassName: String? = null,
    /** Mirrors `@PreviewParameter.limit`. `Int.MAX_VALUE` = take every value. */
    val previewParameterLimit: Int = Int.MAX_VALUE,
    val kind: PreviewKind = PreviewKind.COMPOSE,
)
