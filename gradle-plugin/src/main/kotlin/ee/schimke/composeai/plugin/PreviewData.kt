package ee.schimke.composeai.plugin

import kotlinx.serialization.Serializable

/**
 * Which @Preview flavour the entry came from. Drives renderer selection —
 * [COMPOSE] previews are `@Composable` functions invoked through the normal
 * Compose machinery; [TILE] previews are plain functions returning
 * `androidx.wear.tiles.tooling.preview.TilePreviewData` that need to be
 * inflated via `androidx.wear.tiles.renderer.TileRenderer`.
 */
enum class PreviewKind {
    COMPOSE,
    TILE,
}

/**
 * Mirrors `ee.schimke.composeai.preview.ScrollMode` from the `preview-annotations`
 * artifact. Duplicated here so the Gradle plugin can serialize the value into
 * `previews.json` without pulling the annotation artifact onto the plugin's
 * compile classpath — same split we use for [PreviewKind] across plugin /
 * renderer modules.
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

/**
 * Scroll state of a capture. Combines the intent sourced from
 * `@ScrollingPreview` ([mode], [axis], [maxScrollPx], [reduceMotion]) with the
 * outcome recorded by the renderer ([atEnd], [reachedPx]). `null` on
 * [Capture.scroll] means the capture didn't drive any scrollable.
 *
 * Result fields default to "not populated" so the plugin-side initial build
 * can emit this type before the renderer has run; the renderer overwrites
 * them post-capture (today it doesn't, pending a manifest-rewrite step —
 * they're here so the JSON shape is stable in advance).
 */
@Serializable
data class ScrollCapture(
    // Intent
    val mode: ScrollMode,
    val axis: ScrollAxis = ScrollAxis.VERTICAL,
    val maxScrollPx: Int = 0,
    val reduceMotion: Boolean = true,
    /**
     * Per-frame delay for [ScrollMode.GIF] output, in milliseconds. Ignored
     * by other modes. `0` means "use the renderer's built-in default"
     * (matches the annotation default).
     */
    val frameIntervalMs: Int = 0,
    // Outcome
    /**
     * `true` when the scrollable reported it was already at the end of its
     * content before the renderer stopped. Distinct from `reachedPx ==
     * maxScrollPx`, which signals the user-set cap was hit without
     * necessarily exhausting the content.
     */
    val atEnd: Boolean = false,
    /** Pixels actually scrolled. `null` when not yet reported. */
    val reachedPx: Int? = null,
)

/**
 * Animation capture state sourced from `@AnimatedPreview`. Carried as its
 * own field on [Capture] (orthogonal to [Capture.scroll] / [Capture.advanceTimeMillis])
 * so the renderer can switch on its presence without overloading the scroll
 * machinery. Output is always a single `.gif` plus an optional `<stem>_curves.png`
 * sidecar when [showCurves] is true.
 */
@Serializable
data class AnimationCapture(
    val durationMs: Int,
    val frameIntervalMs: Int,
    val showCurves: Boolean = false,
)

@Serializable
data class PreviewParams(
    val name: String? = null,
    val device: String? = null,
    val widthDp: Int? = null,
    val heightDp: Int? = null,
    /**
     * Compose density factor (= densityDpi / 160), resolved from the `@Preview`
     * device or `spec:...,dpi=...` at discovery time. `null` means the renderer
     * should fall back to its built-in default.
     *
     * Renderers map this to a Robolectric `<n>dpi` qualifier so output bitmap
     * dimensions match what Android Studio renders for the same `@Preview` —
     * the `xxhdpi`-class phones it pictures by default come out at ~2.625x, not
     * the 2.0x `xhdpi` Robolectric otherwise picks.
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
     * FQN of a `PreviewParameterProvider` from `@PreviewParameter` on one of the
     * preview function's parameters, if any. Discovery only records the spec —
     * the renderer instantiates the provider, enumerates its `values` (capped
     * by [previewParameterLimit]), and fans out one rendered file per value
     * with a `_PARAM_<idx>` suffix inserted before the extension.
     *
     * We intentionally do not expand at discovery time: the plugin's own
     * classpath doesn't have the consumer's Compose dependencies, so loading
     * the provider would require rebuilding the consumer's classloader from
     * scratch. Leaving fan-out to the renderer keeps discovery classpath-cheap.
     */
    val previewParameterProviderClassName: String? = null,
    /**
     * Mirrors `@PreviewParameter.limit`. `Int.MAX_VALUE` is the annotation
     * default — the renderer takes every value the provider yields. Applied
     * via `values.take(limit)` so providers backed by infinite sequences stay
     * bounded.
     */
    val previewParameterLimit: Int = Int.MAX_VALUE,
    val kind: PreviewKind = PreviewKind.COMPOSE,
)

/**
 * One rendered snapshot of a preview at a specific point in some dimensional
 * space. The non-null fields on a [Capture] *are* its dimensions: a static
 * preview has a single capture with everything null; a
 * `@RoboComposePreviewOptions`-annotated preview produces N captures differing
 * only in [advanceTimeMillis]; a `@ScrollingPreview` produces a capture with
 * [scroll] set; a preview annotated with both produces the cross-product.
 *
 * The JSON carries each dimension as a typed field rather than a generic
 * `dimensions: map` so agent consumers of `previews.json` can read specific
 * knobs without traversing an untyped structure.
 */
@Serializable
data class Capture(
    /** `null` → no explicit `mainClock.advanceTimeBy` before capture (renderer applies its default step). */
    val advanceTimeMillis: Long? = null,
    /** `null` → no scroll drive. */
    val scroll: ScrollCapture? = null,
    /** `null` → not an animation capture. Mutually exclusive with [scroll] in practice. */
    val animation: AnimationCapture? = null,
    /** Module-relative PNG path, e.g. `renders/<preview id>_TIME_500ms.png`. */
    val renderOutput: String = "",
)

@Serializable
data class PreviewInfo(
    val id: String,
    val functionName: String,
    val className: String,
    val sourceFile: String? = null,
    val params: PreviewParams = PreviewParams(),
    /**
     * All snapshots this preview produces. Always at least one element:
     * a static preview has a single capture with null dimensions; an
     * animated / scrolled preview can have many.
     */
    val captures: List<Capture> = listOf(Capture()),
)

@Serializable
data class PreviewManifest(
    val module: String,
    val variant: String,
    val previews: List<PreviewInfo>,
    /**
     * Relative path (from this manifest's parent directory) to a sidecar
     * accessibility report JSON, when `composePreview.accessibilityChecks.enabled`
     * is `true`. `null` signals the feature is off — downstream tools should
     * treat the absence of this pointer as "no a11y data" rather than probing
     * for the file on disk.
     */
    val accessibilityReport: String? = null,
)
