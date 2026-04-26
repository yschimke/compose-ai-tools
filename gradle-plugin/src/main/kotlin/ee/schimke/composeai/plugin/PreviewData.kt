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
)

@Serializable
data class PreviewManifest(
  val module: String,
  val variant: String,
  val previews: List<PreviewInfo>,
  /**
   * Relative path (from this manifest's parent directory) to a sidecar accessibility report JSON,
   * when `composePreview.accessibilityChecks.enabled` is `true`. `null` signals the feature is off
   * — downstream tools should treat the absence of this pointer as "no a11y data" rather than
   * probing for the file on disk.
   */
  val accessibilityReport: String? = null,
)

// ---------------------------------------------------------------------------
// Android XML resource previews — see docs/ANDROID_RESOURCE_PREVIEWS.md
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
 * qualifier. `LEGACY` falls back to the `<adaptive-icon android:icon=…>` slot when present, or to
 * the foreground rendered against a transparent background otherwise.
 */
@Serializable
enum class AdaptiveShape {
  CIRCLE,
  ROUNDED_SQUARE,
  SQUARE,
  LEGACY,
}

/**
 * Coordinates of a single resource capture. [qualifiers] is the runtime configuration the capture
 * was rendered under (see [ResourceQualifierParser]) — *not* the qualifier of any particular source
 * file: when a resource has both a default-qualifier file and qualified variants, AAPT picks
 * whichever matches the active configuration, and we record what we asked for.
 */
@Serializable
data class ResourceVariant(val qualifiers: String? = null, val shape: AdaptiveShape? = null)

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
