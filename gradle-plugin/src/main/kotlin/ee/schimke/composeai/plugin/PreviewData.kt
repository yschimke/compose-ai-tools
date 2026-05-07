package ee.schimke.composeai.plugin

import kotlinx.serialization.Serializable

/**
 * Which @Preview flavour the entry came from. Drives renderer selection — [COMPOSE] previews are
 * `@Composable` functions invoked through the normal Compose machinery; [TILE] previews are plain
 * functions returning `androidx.wear.tiles.tooling.preview.TilePreviewData` that need to be
 * inflated via `androidx.wear.tiles.renderer.TileRenderer`.
 */
enum class PreviewKind {
  COMPOSE,
  TILE,
}

/**
 * Mirrors `ee.schimke.composeai.preview.ScrollMode` from the `preview-annotations` artifact.
 * Duplicated here so the Gradle plugin can serialize the value into `previews.json` without pulling
 * the annotation artifact onto the plugin's compile classpath — same split we use for [PreviewKind]
 * across plugin / renderer modules.
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
 * Scroll state of a capture. Combines the intent sourced from `@ScrollingPreview` ([mode], [axis],
 * [maxScrollPx], [reduceMotion]) with the outcome recorded by the renderer ([atEnd], [reachedPx]).
 * `null` on [Capture.scroll] means the capture didn't drive any scrollable.
 *
 * Result fields default to "not populated" so the plugin-side initial build can emit this type
 * before the renderer has run; the renderer overwrites them post-capture (today it doesn't, pending
 * a manifest-rewrite step — they're here so the JSON shape is stable in advance).
 */
@Serializable
data class ScrollCapture(
  // Intent
  val mode: ScrollMode,
  val axis: ScrollAxis = ScrollAxis.VERTICAL,
  val maxScrollPx: Int = 0,
  val reduceMotion: Boolean = true,
  /**
   * Per-frame delay for [ScrollMode.GIF] output, in milliseconds. Ignored by other modes. `0` means
   * "use the renderer's built-in default" (matches the annotation default).
   */
  val frameIntervalMs: Int = 0,
  // Outcome
  /**
   * `true` when the scrollable reported it was already at the end of its content before the
   * renderer stopped. Distinct from `reachedPx == maxScrollPx`, which signals the user-set cap was
   * hit without necessarily exhausting the content.
   */
  val atEnd: Boolean = false,
  /** Pixels actually scrolled. `null` when not yet reported. */
  val reachedPx: Int? = null,
)

/**
 * Animation capture state sourced from `@AnimatedPreview`. Carried as its own field on [Capture]
 * (orthogonal to [Capture.scroll] / [Capture.advanceTimeMillis]) so the renderer can switch on its
 * presence without overloading the scroll machinery. Output is always a single `.gif` plus an
 * optional `<stem>_curves.png` sidecar when [showCurves] is true.
 */
@Serializable
data class AnimationCapture(
  val durationMs: Int,
  val frameIntervalMs: Int,
  val showCurves: Boolean = false,
)

/**
 * Mirrors `ee.schimke.composeai.preview.FocusDirection` from the `preview-annotations` artifact.
 * Duplicated here so the Gradle plugin can serialize the value into `previews.json` without pulling
 * the annotation artifact (and Compose) onto the plugin's compile classpath.
 */
enum class FocusDirection {
  Next,
  Previous,
  Up,
  Down,
  Left,
  Right,
}

/**
 * Focus capture state sourced from `@FocusedPreview`. Carried as its own field on [Capture] —
 * orthogonal to [Capture.scroll] / [Capture.animation] — so the renderer can switch on its presence
 * to (a) provide an `InputMode.Keyboard` `LocalInputModeManager` (Compose's `Modifier.clickable`
 * focusable refuses focus under touch mode, which Robolectric is permanently in) and (b) walk the
 * focus owner to the requested target before capture.
 */
@Serializable
data class FocusCapture(
  /**
   * Zero-based focus index in tab order, in indexed-mode captures. `null` in traversal-mode
   * captures (use [direction] instead). Capture 0 issues `moveFocus(Enter)` + Next steps to land on
   * `tabIndex`; later captures issue `moveFocus(Next)` deltas.
   */
  val tabIndex: Int? = null,
  /**
   * Direction to apply before this capture, in traversal-mode captures. `null` in indexed-mode
   * captures (use [tabIndex] instead). The renderer issues `moveFocus(Enter)` once on the first
   * traversal step, then `moveFocus(direction)` per capture.
   */
  val direction: FocusDirection? = null,
  /**
   * 1-based step number within a `traverse = [...]` array. Carried separately from [direction] so
   * the renderer's overlay can label captures (`step 2`) even when several steps share a direction.
   * `null` in indexed-mode captures.
   */
  val step: Int? = null,
  /**
   * When `true`, the renderer post-applies a stroke + label overlay to the captured PNG. The
   * pre-overlay capture is kept alongside as `<basename>.raw.png`.
   */
  val overlay: Boolean = false,
)

/**
 * Cost catalogue, normalised so a static `@Preview` (single compose pass + one screenshot) is
 * `1.0`. The discovery task stamps the right value onto each [Capture]; tooling reads them back to
 * throttle interactive renders.
 *
 * Values are wall-time approximations (relative, not absolute):
 *
 * - [STATIC_COST] / [SCROLL_TOP_COST] = 1 — one compose pass, one PNG.
 * - [SCROLL_END_COST] ≈ 3 — single capture plus a scroll-drive prelude.
 * - [SCROLL_LONG_COST] ≈ 20 — multi-slice stitched into a tall PNG.
 * - [SCROLL_GIF_COST] ≈ 40 — many frames + GIF encode.
 * - [ANIMATION_COST] ≈ 50 — `@AnimatedPreview` window: frame loop + optional curve strip + GIF
 *   encode. Frame counts vary slightly with the auto-detected duration, but the wall-time is
 *   dominated by the GIF encode, so a flat figure approximates well enough for tiering.
 * - [ACCESSIBILITY_COST_PER_CAPTURE] = 4 — flat per-capture overhead when ATF runs (not stored on
 *   the manifest because it's a global runtime toggle; tooling adds it in when computing effective
 *   cost).
 *
 * [HEAVY_COST_THRESHOLD] sits above END (3) and below LONG (20), so the cheap-enough-for-every-save
 * bucket includes static + TOP + END, and LONG / GIF / animated captures fall into the on-demand
 * "heavy" bucket.
 */
const val STATIC_COST: Float = 1.0f
const val SCROLL_TOP_COST: Float = 1.0f
const val SCROLL_END_COST: Float = 3.0f
const val SCROLL_LONG_COST: Float = 20.0f
const val SCROLL_GIF_COST: Float = 40.0f
const val ANIMATION_COST: Float = 50.0f
const val ACCESSIBILITY_COST_PER_CAPTURE: Float = 4.0f
const val HEAVY_COST_THRESHOLD: Float = 5.0f

/**
 * Returns `true` when [cost] exceeds [HEAVY_COST_THRESHOLD]. Single seam so the plugin, renderer,
 * and VS Code extension all agree on which captures the interactive save loop should skip — there's
 * no separate enum field on the manifest, just the numeric cost.
 */
fun isHeavyCost(cost: Float): Boolean = cost > HEAVY_COST_THRESHOLD

@Serializable
data class PreviewParams(
  val name: String? = null,
  val device: String? = null,
  val widthDp: Int? = null,
  val heightDp: Int? = null,
  /**
   * Compose density factor (= densityDpi / 160), resolved from the `@Preview` device or
   * `spec:...,dpi=...` at discovery time. `null` means the renderer should fall back to its
   * built-in default.
   *
   * Renderers map this to a Robolectric `<n>dpi` qualifier so output bitmap dimensions match what
   * Android Studio renders for the same `@Preview` — the `xxhdpi`-class phones it pictures by
   * default come out at ~2.625x, not the 2.0x `xhdpi` Robolectric otherwise picks.
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
   * FQN of a `PreviewParameterProvider` from `@PreviewParameter` on one of the preview function's
   * parameters, if any. Discovery only records the spec — the renderer instantiates the provider,
   * enumerates its `values` (capped by [previewParameterLimit]), and fans out one rendered file per
   * value with a `_PARAM_<idx>` suffix inserted before the extension.
   *
   * We intentionally do not expand at discovery time: the plugin's own classpath doesn't have the
   * consumer's Compose dependencies, so loading the provider would require rebuilding the
   * consumer's classloader from scratch. Leaving fan-out to the renderer keeps discovery
   * classpath-cheap.
   */
  val previewParameterProviderClassName: String? = null,
  /**
   * Mirrors `@PreviewParameter.limit`. `Int.MAX_VALUE` is the annotation default — the renderer
   * takes every value the provider yields. Applied via `values.take(limit)` so providers backed by
   * infinite sequences stay bounded.
   */
  val previewParameterLimit: Int = Int.MAX_VALUE,
  val kind: PreviewKind = PreviewKind.COMPOSE,
)

/**
 * One rendered snapshot of a preview at a specific point in some dimensional space. The non-null
 * fields on a [Capture] *are* its dimensions: a static preview has a single capture with everything
 * null; a `@RoboComposePreviewOptions`-annotated preview produces N captures differing only in
 * [advanceTimeMillis]; a `@ScrollingPreview` produces a capture with [scroll] set; a preview
 * annotated with both produces the cross-product.
 *
 * The JSON carries each dimension as a typed field rather than a generic `dimensions: map` so agent
 * consumers of `previews.json` can read specific knobs without traversing an untyped structure.
 */
@Serializable
data class Capture(
  /**
   * `null` → no explicit `mainClock.advanceTimeBy` before capture (renderer applies its default
   * step).
   */
  val advanceTimeMillis: Long? = null,
  /** `null` → no scroll drive. */
  val scroll: ScrollCapture? = null,
  /** `null` → not an animation capture. Mutually exclusive with [scroll] in practice. */
  val animation: AnimationCapture? = null,
  /** `null` → no focus drive. Set when the preview carries a `@FocusedPreview` annotation. */
  val focus: FocusCapture? = null,
  /** Module-relative PNG path, e.g. `renders/<preview id>_TIME_500ms.png`. */
  val renderOutput: String = "",
  /**
   * Estimated render cost, normalised so a static `@Preview` is `1.0`. See the cost catalogue at
   * the top of this file ([STATIC_COST], [SCROLL_LONG_COST], [ANIMATION_COST], …) for the figures
   * the discovery task stamps in. Defaults to `1.0` so older manifests (pre-cost field) parse as
   * cheap-everywhere and older tooling keeps its historical "render everything on every save"
   * behaviour.
   */
  val cost: Float = STATIC_COST,
)

/**
 * Annotation-sourced data product request for a preview. This keeps feature-specific authoring APIs
 * (for example `@ScrollingPreview(modes = [LONG, GIF])`) type-safe while moving heavyweight,
 * non-primary artefacts out of the privileged capture carousel.
 */
@Serializable
data class PreviewDataProduct(
  /** Data-product kind, e.g. `render/scroll/long`. */
  val kind: String,
  /** Extension that owns this suggested extra preview effect, e.g. `scroll`. */
  val extensionId: String? = null,
  /** Extension-local effect id, e.g. `long` or `gif`. */
  val effectId: String? = null,
  /** How the extension request is meant to be applied. */
  val usageMode: PreviewExtensionUsageMode? = null,
  /** Where this extra preview suggestion came from, e.g. an annotation FQN. */
  val suggestedBy: String? = null,
  /** Human-readable label clients can use without hardcoding every kind. */
  val displayName: String? = null,
  /** Generic shape markers clients can use to group and present products. */
  val facets: List<PreviewDataProductFacet> = emptyList(),
  /** Expected media types for path-backed artifacts. */
  val mediaTypes: List<String> = emptyList(),
  /** When the product samples a scenario. */
  val sampling: PreviewDataProductSampling? = null,
  /**
   * Optional virtual clock coordinate shared with [Capture.advanceTimeMillis]. `null` means the
   * renderer's default capture advance.
   */
  val advanceTimeMillis: Long? = null,
  /** Scroll intent when this product is backed by `@ScrollingPreview`; null for other products. */
  val scroll: ScrollCapture? = null,
  /** Module-relative product file path under `build/compose-previews`, e.g. `data/.../Foo.png`. */
  val output: String = "",
  /** Estimated render cost on the same scale as [Capture.cost]. */
  val cost: Float = STATIC_COST,
)

@Serializable
enum class PreviewDataProductFacet {
  STRUCTURED,
  ARTIFACT,
  IMAGE,
  ANIMATION,
  OVERLAY,
  CHECK,
  DIAGNOSTIC,
  PROFILE,
  INTERACTIVE,
}

@Serializable
enum class PreviewDataProductSampling {
  START,
  END,
  EACH_FRAME,
  ON_DEMAND,
  AGGREGATE,
  FAILURE,
}

@Serializable
enum class PreviewExtensionUsageMode {
  EXPLICIT_EFFECT,
  SUGGESTED_EXTRA_PREVIEW,
}

@Serializable
data class PreviewInfo(
  val id: String,
  val functionName: String,
  val className: String,
  val sourceFile: String? = null,
  val params: PreviewParams = PreviewParams(),
  /**
   * All snapshots this preview produces. Always at least one element: a static preview has a single
   * capture with null dimensions; an animated / scrolled preview can have many.
   */
  val captures: List<Capture> = listOf(Capture()),
  /**
   * Additional annotation-sourced products available for this preview. These are not primary
   * screenshots; clients fetch or surface them through the data-product path.
   */
  val dataProducts: List<PreviewDataProduct> = emptyList(),
  /**
   * Composables this preview is presumed to render. Discovery infers this by walking the preview
   * function's bytecode for project-local `@Composable` calls, filtering theme/layout wrappers, and
   * scoring the remaining candidates against the preview's name and source-set context. Empty when
   * no candidate cleared the confidence threshold; ordered most-confident first.
   *
   * v1 emits at most one entry; the list shape is reserved for future multi-target inference (e.g.
   * `Row { Foo(); Bar() }` returning both `Foo` and `Bar`).
   */
  val targets: List<PreviewTarget> = emptyList(),
)

/**
 * A composable that a `@Preview` function is presumed to render. Attached to [PreviewInfo.targets]
 * when discovery finds a high-enough-confidence match.
 *
 * The target is keyed by FQN + simple method name so consumers can correlate the rendered PNG back
 * to the production composable's source — the canonical use case is "this UI PR changed
 * `HomeScreen` in `src/main`, did its preview in `src/debug` change too?". [signals] makes the
 * inference auditable: tooling that wants to be strict can require `CROSS_FILE` + `NAME_MATCH`,
 * while best-effort consumers can use the [confidence] tier directly.
 */
@Serializable
data class PreviewTarget(
  /** Owner class FQN (synthetic `…Kt` for top-level functions). */
  val className: String,
  /** Composable function name on [className]. */
  val functionName: String,
  /** Module-relative source path of the target's owning file, when resolvable. */
  val sourceFile: String? = null,
  val confidence: TargetConfidence,
  val signals: List<TargetSignal> = emptyList(),
)

@Serializable
enum class TargetConfidence {
  HIGH,
  MEDIUM,
  LOW,
}

@Serializable
enum class TargetSignal {
  /** Preview file lives in a non-shipping source set (debug, screenshotTest, test, …). */
  NON_SHIPPING_SOURCE_SET,
  /** Preview file's name and contents look dedicated to previews (e.g. `*Previews.kt`). */
  DEDICATED_PREVIEW_FILE,
  /** Exactly one project-local non-wrapper `@Composable` call survived filtering. */
  SINGLE_PROJECT_COMPOSABLE_CALL,
  /** Stripping `Preview`/`Preview_` from the preview function name yields the candidate. */
  NAME_MATCH,
  /** Candidate is declared in a different source file than the preview. */
  CROSS_FILE,
  /** `@PreviewParameter` value was forwarded into the candidate call. */
  PARAMETER_FORWARDED,
  /**
   * Discovery recursed through a project-local theming/wrapper composable (single `@Composable ()
   * -> Unit` parameter) before landing on the candidate.
   */
  WRAPPER_UNWRAPPED,
}

@Serializable
data class PreviewManifest(
  val module: String,
  val variant: String,
  val previews: List<PreviewInfo>,
  /**
   * Relative path (from this manifest's parent directory) to a sidecar accessibility report JSON,
   * when the built-in `a11y` data-product plugin is enabled. `null` signals the feature is off —
   * downstream tools should treat the absence of this pointer as "no a11y data" rather than probing
   * for the file on disk.
   */
  val accessibilityReport: String? = null,
)

// ---------------------------------------------------------------------------
// Android XML resource previews — vector, animated-vector, adaptive-icon
// ---------------------------------------------------------------------------

/** Cost catalogue extension for resource previews; same scale as the composable cost figures. */
const val RESOURCE_STATIC_COST: Float = 1.0f

const val RESOURCE_ADAPTIVE_COST: Float = 4.0f

const val RESOURCE_ANIMATED_COST: Float = 35.0f

/** Subset of XML drawable / mipmap resources the renderer knows how to handle. */
@Serializable
enum class ResourceType {
  VECTOR,
  ANIMATED_VECTOR,
  ADAPTIVE_ICON,
}

/**
 * Adaptive-icon shape mask. Applied at render time as a canvas clip path — not a resource
 * qualifier. The mask only describes *what shape* the launcher would clip the icon to; the
 * *contents* inside the mask (full-color foreground+background, or 2-tone themed monochrome) are a
 * separate axis — see [AdaptiveStyle].
 */
@Serializable
enum class AdaptiveShape {
  CIRCLE,
  /**
   * Pixel / Material You default mask. Approximated with a superellipse-ish path (rounded rectangle
   * with corner radius ≈ 50% of the half-width); good enough at preview densities without requiring
   * a `Path` per-render.
   */
  SQUIRCLE,
  ROUNDED_SQUARE,
  SQUARE,
}

/**
 * What goes inside the [AdaptiveShape] mask. Mirrors the two surfaces a launcher renders an
 * adaptive icon at:
 * - [FULL_COLOR] — composited foreground + background, the colour appearance you see in App Search
 *   / app drawer.
 * - [THEMED_LIGHT] / [THEMED_DARK] — the `<monochrome>` layer (Android 13+) tinted with a
 *   wallpaper-derived 2-tone palette, the appearance launchers use on the home screen when "Themed
 *   icons" is enabled. Tints come from the Material 3 baseline neutral scheme so the preview is
 *   reproducible without a live wallpaper.
 * - [LEGACY] — the pre-O fallback. Renders the `<adaptive-icon android:icon=…>` slot when the
 *   consumer supplied one; otherwise the foreground against a transparent background. Single
 *   capture per qualifier — [LEGACY] doesn't fan out across [AdaptiveShape].
 */
@Serializable
enum class AdaptiveStyle {
  FULL_COLOR,
  THEMED_LIGHT,
  THEMED_DARK,
  LEGACY,
}

/**
 * Coordinates of a single resource capture. [qualifiers] is the runtime configuration the capture
 * was rendered under (see [ResourceQualifierParser]) — *not* the qualifier of any particular source
 * file: when a resource has both a default-qualifier file and qualified variants, AAPT picks
 * whichever matches the active configuration, and we record what we asked for.
 *
 * [shape] and [style] are independent axes for adaptive-icon captures. [style] =
 * [AdaptiveStyle.LEGACY] always pairs with `shape = null` (legacy fallback ignores the mask); other
 * styles always carry a shape. Both fields are `null` for non-adaptive resources.
 */
@Serializable
data class ResourceVariant(
  val qualifiers: String? = null,
  val shape: AdaptiveShape? = null,
  val style: AdaptiveStyle? = null,
)

@Serializable
data class ResourceCapture(
  val variant: ResourceVariant? = null,
  val renderOutput: String = "",
  val cost: Float = RESOURCE_STATIC_COST,
)

/**
 * One previewable resource. [id] is `<base>/<name>` (e.g. `drawable/ic_compose_logo`,
 * `mipmap/ic_launcher`). [sourceFiles] enumerates every contributing source file keyed by its
 * qualifier suffix — empty string `""` for the default-qualifier file, the verbatim qualifier
 * suffix otherwise (`"night"`, `"xhdpi"`, `"night-xhdpi-v26"`, …). The empty-string convention
 * keeps the JSON portable: nullable map keys would serialise as bare `null` literals which standard
 * JSON parsers reject.
 */
@Serializable
data class ResourcePreview(
  val id: String,
  val type: ResourceType,
  val sourceFiles: Map<String, String> = emptyMap(),
  val captures: List<ResourceCapture> = emptyList(),
)

/**
 * One drawable / mipmap reference observed in `AndroidManifest.xml`. References don't trigger
 * captures — they're an index that lets tooling link manifest lines to the already-rendered
 * resource preview by `(resourceType, resourceName)`.
 */
@Serializable
data class ManifestReference(
  /** Module-relative path of the manifest file the reference came from. */
  val source: String,
  /** Tag name of the component the attribute lives on: `application`, `activity`, … */
  val componentKind: String,
  /**
   * Fully qualified class name for activity / service / receiver / provider; `null` for
   * `application`.
   */
  val componentName: String? = null,
  /** Attribute name including namespace prefix, e.g. `android:icon`. */
  val attributeName: String,
  /** `drawable` or `mipmap`. */
  val resourceType: String,
  /** Resource name without the `@type/` prefix, e.g. `ic_launcher`. */
  val resourceName: String,
)

/**
 * Sibling of [PreviewManifest] for XML-resource previews. Composable manifests key on FQN; resource
 * manifests key on `(resourceType, resourceName)` — different lookup shapes, different consumers,
 * separate JSON files (`previews.json` vs `resources.json`).
 */
@Serializable
data class ResourceManifest(
  val module: String,
  val variant: String,
  val resources: List<ResourcePreview> = emptyList(),
  val manifestReferences: List<ManifestReference> = emptyList(),
)
