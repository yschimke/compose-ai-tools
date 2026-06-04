package ee.schimke.composeai.discovery

import kotlinx.serialization.Serializable

/**
 * Which @Preview flavour the entry came from. Drives renderer selection — [COMPOSE] previews are
 * `@Composable` functions invoked through the normal Compose machinery; [TILE] previews are plain
 * functions returning `androidx.wear.tiles.tooling.preview.TilePreviewData` that need to be
 * inflated via `androidx.wear.tiles.renderer.TileRenderer`; [NOTIFICATION] previews are plain
 * functions taking a `Context` and returning `android.app.Notification`, inflated via
 * `Notification.Builder.recoverBuilder` + `RemoteViews.apply` (see issue #1249); [GLANCE_APPWIDGET]
 * previews are `@Composable @GlanceComposable` functions annotated with
 * `androidx.glance.preview.Preview` — the renderer wraps the function in a synthetic
 * `GlanceAppWidget.providePreview(...)`, runs `composeForPreview(...)` to materialise the tree to
 * `RemoteViews`, and inflates the result the same way `NOTIFICATION` does.
 */
enum class PreviewKind {
  COMPOSE,
  TILE,
  NOTIFICATION,
  GLANCE_APPWIDGET,
  XR_SUBSPACE,
  /**
   * A Lottie animation asset discovered directly as a file (no `@Preview`, no consumer composable):
   * a `.json` Lottie document (detected by structure) or a `.lottie` dotLottie archive under the
   * module's resources. The asset bytes *are* the preview's intermediate representation — the
   * renderer inflates them via Compottie, and a bundle carries the bytes (`ir/<id>.lottie`) and
   * replays them with zero consumer bytecode. The asset's classpath-relative path travels on
   * [PreviewParams.assetPath].
   */
  LOTTIE,
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
  /**
   * When `true`, the connector's focus walk skips the historical `+1 Next` compensation after
   * `moveFocus(Enter)` — used by previews whose root carries `focusProperties { onEnter = {
   * initialFocus.requestFocus() } }.focusGroup()`, where Enter already lands focus on the chosen
   * child. See `@FocusedPreview.enterPlacesFocus` for the full rationale.
   */
  val enterPlacesFocus: Boolean = false,
  /**
   * When `true`, the renderer dispatches an indirect-pointer Press event onto the focused
   * composable after the focus walk settles, before capturing pixels — captures the *pressed*
   * visual state on top of the focused state. Drives Compose UI's
   * `AndroidComposeView.sendIndirectPointerEvent` (the same entry point real XR Glasses touchpads
   * route through); see `@FocusedPreview.pressed` for the rationale. Only meaningful for
   * indexed-mode captures (`tabIndex`); traversal-mode skips it.
   */
  val pressed: Boolean = false,
)

/**
 * `@FocusedPreview(gif = true)` capture state. Carries the per-step focus instructions discovery
 * built from `[indices]` / `[traverse]`; the renderer drives each step through the same
 * `FocusManager.moveFocus` walk the per-PNG `focus` path uses and stitches the captured frames into
 * a single animated GIF.
 *
 * Carried on its own field on [Capture] rather than overloading [FocusCapture] so the renderer
 * routes per-step PNG mode and GIF mode at the top of the capture loop (mirrors the `scroll` /
 * `animation` split).
 */
@Serializable
data class FocusGifCapture(
  val steps: List<FocusCapture>,
  /**
   * Per-frame delay in milliseconds. Drives both the virtual-clock settle window between steps and
   * the GIF's per-frame `delayTime`. Defaults to [DEFAULT_FOCUS_GIF_FRAME_DELAY_MS].
   */
  val frameDelayMs: Int = DEFAULT_FOCUS_GIF_FRAME_DELAY_MS,
)

/**
 * Default per-frame delay for a `@FocusedPreview(gif = true)` capture. ~800ms gives the focus-in
 * transition (Material's ~150ms ripple fade plus a reader beat) enough dwell time to be visible
 * before the next step starts moving focus.
 */
const val DEFAULT_FOCUS_GIF_FRAME_DELAY_MS: Int = 800

/**
 * Wear OS ambient-mode capture state sourced from `@AmbientPreview`. Carried as its own field on
 * [Capture] — orthogonal to [Capture.scroll] / [Capture.animation] / [Capture.focus] — so the
 * renderer can switch on its presence to wrap the preview composition with the
 * `AmbientOverrideExtension` from `:data-ambient-connector`. The extension installs
 * `LocalAmbientModeManager` so consumer code reading `currentAmbientMode` observes the requested
 * state — same seam the daemon-driven `renderNow.overrides.ambient` planner uses.
 */
@Serializable
data class AmbientCapture(
  /**
   * Active state. Mirrors `androidx.wear.compose.foundation.AmbientMode` — only `Interactive` /
   * `Ambient` round-trip through `@AmbientPreview` (no `Inactive` value in the new API surface).
   */
  val state: AmbientCaptureState = AmbientCaptureState.Ambient,
  /**
   * Mirrors `AmbientMode.Ambient.isBurnInProtectionRequired`. Only meaningful when [state] is
   * [AmbientCaptureState.Ambient].
   */
  val burnInProtectionRequired: Boolean = false,
  /**
   * Mirrors `AmbientMode.Ambient.isLowBitAmbientSupported`. Only meaningful when [state] is
   * [AmbientCaptureState.Ambient].
   */
  val deviceHasLowBitAmbient: Boolean = false,
)

/** Mirror of `androidx.wear.compose.foundation.AmbientMode`'s active states. */
@Serializable
enum class AmbientCaptureState {
  Interactive,
  Ambient,
}

/**
 * Per-preview launcher-widget container-size override discovered from a `@LauncherWidgetPreview`
 * annotation. Stamped onto every capture of the annotated function — renderer wraps the composition
 * with `:data-launcher-widget-connector`'s `LauncherWidgetExtension`, which mirrors `MyWidget`
 * inside a `Box(Modifier.size(widthDp, heightDp))` at the resolved dp footprint.
 *
 * Field shape matches `LauncherWidgetOverride` in `:daemon:core` so the renderer can translate
 * one-for-one. Optional bounds / cell size carry `null` here when the annotation left them at their
 * `-1` sentinel — the connector then applies its defaults.
 */
@Serializable
data class LauncherWidgetCapture(
  val width: Int,
  val height: Int,
  val cellSizeDp: Int? = null,
  val cellSpacingDp: Int? = null,
  val minWidth: Int? = null,
  val minHeight: Int? = null,
  val maxWidth: Int? = null,
  val maxHeight: Int? = null,
  val resizeOrder: LauncherWidgetCaptureResizeOrder = LauncherWidgetCaptureResizeOrder.WidthFirst,
  /**
   * Per-frame delay carried through from `@LauncherWidgetResize.frameDelayMs`. `null` when the
   * capture didn't originate from a resize walk (single `@LauncherWidgetPreview` doesn't author a
   * GIF, so the field is absent there). The future GIF-stitch pass reads this off the per-stop
   * captures; the value is identical across every stop in the same walk by construction.
   */
  val frameDelayMs: Int? = null,
)

/** Mirror of `LauncherResizeOrder` in `:daemon:core`. */
@Serializable
enum class LauncherWidgetCaptureResizeOrder {
  Diagonal,
  WidthFirst,
  HeightFirst,
}

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
const val FOCUS_GIF_COST: Float = 40.0f
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
  /**
   * For [PreviewKind.LOTTIE] only: the module-resource-relative path of the discovered Lottie asset
   * (e.g. `lottie/loading.json`). The renderer loads it via the classloader (the plugin links the
   * processed-resources dir onto the render classpath), and the bundle reads the same path off the
   * module resources to pack the IR. `null` for every other kind.
   */
  val assetPath: String? = null,
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
  /**
   * `null` → no focus-driven GIF. Set when the preview carries `@FocusedPreview(gif = true)`.
   * Mutually exclusive with [focus] on the same capture — the GIF capture owns its steps inline.
   */
  val focusGif: FocusGifCapture? = null,
  /**
   * `null` → no Wear OS ambient-mode override. Set when the preview carries an `@AmbientPreview`
   * annotation. Renderer wraps the composition with `:data-ambient-connector`'s
   * `AmbientOverrideExtension` when present.
   */
  val ambient: AmbientCapture? = null,
  /**
   * `null` → no launcher-widget container-size override. Set when the preview carries a
   * `@LauncherWidgetPreview` annotation. Renderer wraps the composition with
   * `:data-launcher-widget-connector`'s `LauncherWidgetExtension` when present so the rendered PNG
   * sizes to the resolved dp footprint of a launcher cell.
   */
  val launcherWidget: LauncherWidgetCapture? = null,
  /** Module-relative PNG path, e.g. `renders/<preview id>_TIME_500ms.png`. */
  val renderOutput: String = "",
  /**
   * `true` → best-effort capture: displayed if its file exists, but NOT required by
   * `composePreviewRenderAll`'s missing-render gate. Used for artefacts produced by an optional,
   * out-of-band tool that may be absent (no binary / display / software GL) — for example the XR
   * subspace composite still baked by the native `xr-composite` renderer. Defaults to `false` so
   * existing captures stay required and older manifests are unchanged.
   */
  val optional: Boolean = false,
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
   * Generic per-extension report pointer map. Keys are extension ids (e.g. `"a11y"`), values are
   * module-relative paths (from this manifest's parent directory) to that extension's aggregated
   * sidecar JSON. Empty when no extension produced a canned report. Consumers iterate this map
   * rather than probing for specific filenames.
   *
   * The v1 alias `accessibilityReport: String?` that mirrored `dataExtensionReports["a11y"]` was
   * removed after one transition release. CLIs / VS Code builds older than that release will
   * silently miss a11y findings when reading manifests written by this plugin — bump the companion
   * CLI / VS Code extension alongside the plugin bump.
   */
  val dataExtensionReports: Map<String, String> = emptyMap(),
)

// ---------------------------------------------------------------------------
// Android resource previews — vector, animated-vector, adaptive-icon, 9-patch
// ---------------------------------------------------------------------------

/** Cost catalogue extension for resource previews; same scale as the composable cost figures. */
const val RESOURCE_STATIC_COST: Float = 1.0f

const val RESOURCE_ADAPTIVE_COST: Float = 4.0f

const val RESOURCE_ANIMATED_COST: Float = 35.0f

/**
 * Per-capture cost for an [ResourceType.ANIMATED_VECTOR] filmstrip render. Fewer frames than the
 * GIF capture (5 keyframes vs ~30) and no GIF encode loop — a small constant factor of the GIF cost
 * is a fair approximation.
 */
const val RESOURCE_ANIMATED_FILMSTRIP_COST: Float = RESOURCE_ANIMATED_COST / 5f

/**
 * Default keyframe fractions for an AnimatedVectorDrawable filmstrip capture — 0%, 25%, 50%, 75%,
 * 100% of the animation's reported `totalDuration`. Five cells gives reviewers enough sampling to
 * see the start/mid/end shape of a typical UI animation without the GIF's scrubbing overhead.
 */
val DEFAULT_RESOURCE_FILMSTRIP_FRACTIONS: List<Float> = listOf(0.0f, 0.25f, 0.5f, 0.75f, 1.0f)

/**
 * Per-capture cost for a 9-patch stretch render. Each capture is one `NinePatchDrawable.draw` into
 * a target-sized bitmap plus a PNG encode — within a constant factor of [RESOURCE_STATIC_COST]. The
 * 4-stretch fan-out per qualifier means the aggregate cost for one 9-patch is ~6x a vector; tier
 * alongside other static captures.
 */
const val RESOURCE_NINE_PATCH_COST: Float = 1.5f

/**
 * Drawable / mipmap resources the renderer knows how to handle. [VECTOR], [ANIMATED_VECTOR], and
 * [ADAPTIVE_ICON] come from XML root tags (classified by [ResourceXmlClassifier] in the plugin
 * module). [NINE_PATCH] comes from the `.9.png` file-extension convention — AAPT2 strips the 1-px
 * guide border at build time and stamps an `npTc` chunk into the compiled PNG, and at render time
 * Android resolves the drawable to a `NinePatchDrawable` whose `draw()` interpolates the patches
 * against whatever bounds we set.
 */
@Serializable
enum class ResourceType {
  VECTOR,
  ANIMATED_VECTOR,
  ADAPTIVE_ICON,
  NINE_PATCH,
}

/**
 * Which target size to render a 9-patch at. Each value maps to a different `(width, height)`
 * `setBounds` call against the same `NinePatchDrawable`, so a reviewer can see both the natural
 * appearance and how the patches stretch into a larger container.
 *
 * - [INTRINSIC] — `(intrinsicWidth, intrinsicHeight)`, the natural appearance.
 * - [HORIZONTAL] — `(intrinsicWidth * 2, intrinsicHeight)`, exercises the horizontal stretch zone.
 * - [VERTICAL] — `(intrinsicWidth, intrinsicHeight * 2)`, exercises the vertical stretch zone.
 * - [BOTH] — `(intrinsicWidth * 2, intrinsicHeight * 2)`, exercises both axes.
 */
@Serializable
enum class NinePatchStretch {
  INTRINSIC,
  HORIZONTAL,
  VERTICAL,
  BOTH,
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
 *
 * [stretch] is the 9-patch-only axis. Non-null on [ResourceType.NINE_PATCH] captures, `null`
 * elsewhere. Carried alongside [shape] / [style] rather than overloading them so the renderer can
 * switch on the type-specific axis without inspecting the resource type up front.
 *
 * [filmstrip] is the [ResourceType.ANIMATED_VECTOR]-only axis. `true` means the capture is the
 * keyframe filmstrip PNG (one cell per fraction in [ResourceCapture.filmstripFractions], composited
 * side-by-side) rather than the per-frame GIF. Mutually exclusive with the GIF capture on the same
 * variant — both captures fan out together when [ResourcePreviewsExtension.filmstrip] is enabled.
 */
@Serializable
data class ResourceVariant(
  val qualifiers: String? = null,
  val shape: AdaptiveShape? = null,
  val style: AdaptiveStyle? = null,
  val stretch: NinePatchStretch? = null,
  val filmstrip: Boolean = false,
)

@Serializable
data class ResourceCapture(
  val variant: ResourceVariant? = null,
  val renderOutput: String = "",
  val cost: Float = RESOURCE_STATIC_COST,
  /**
   * Animation keyframe fractions for filmstrip captures (`variant.filmstrip == true`). Empty on
   * every other capture. Each value is a fraction of the resolved animation duration in `[0, 1]`;
   * the renderer samples one bitmap per fraction via `AnimatorSet.setCurrentPlayTime` and
   * composites them side-by-side into a single horizontal PNG. Default values are seeded from
   * [DEFAULT_RESOURCE_FILMSTRIP_FRACTIONS] but the consumer can override via
   * `composePreview.resourcePreviews.filmstripFractions`.
   */
  val filmstripFractions: List<Float> = emptyList(),
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
