package ee.schimke.composeai.renderer

import ee.schimke.composeai.scroll.ScrollGifEncoder
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

/** Renderer-side mirror of the plugin's `FocusCapture`. */
@Serializable
data class FocusCapture(
    val tabIndex: Int,
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

// ---------------------------------------------------------------------------
// Accessibility models (`AccessibilityReport`, `AccessibilityEntry`,
// `AccessibilityNode`, `AccessibilityFinding`) used to live here. They moved
// to `:data-a11y-core` (`AccessibilityModels.kt`) under the same package as
// part of the D2.2 split — see `data/a11y/core/.../AccessibilityModels.kt`.
// Renderer-android keeps them on its API surface via `api(project(":data-a11y-core"))`,
// so existing imports of `ee.schimke.composeai.renderer.AccessibilityFinding` etc.
// resolve unchanged.
// ---------------------------------------------------------------------------

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
    /**
     * Annotation-sourced products available for this preview. These are rendered as data-product
     * artefacts, not as primary screenshots in the preview capture list.
     */
    val dataProducts: List<RenderPreviewDataProduct> = emptyList(),
)

@Serializable
data class RenderPreviewCapture(
    val advanceTimeMillis: Long? = null,
    val scroll: ScrollCapture? = null,
    val animation: AnimationCapture? = null,
    val focus: FocusCapture? = null,
    val renderOutput: String = "",
    /**
     * Estimated render cost normalised so a static `@Preview` is `1.0`. See
     * the plugin's `Capture.cost` for the full catalogue. Defaults to `1.0`
     * so older manifests parse as cheap-everywhere.
     */
    val cost: Float = 1.0f,
)

@Serializable
data class RenderPreviewDataProduct(
    val kind: String,
    val advanceTimeMillis: Long? = null,
    val scroll: ScrollCapture? = null,
    val output: String = "",
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
