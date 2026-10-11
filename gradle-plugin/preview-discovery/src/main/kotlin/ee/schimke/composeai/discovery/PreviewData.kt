package ee.schimke.composeai.discovery

import kotlinx.serialization.Serializable

/**
 * Which @Preview flavour the entry came from; drives renderer selection. [COMPOSE] previews are
 * invoked through Compose; [TILE] functions return `TilePreviewData` inflated by `TileRenderer`;
 * [NOTIFICATION] functions return a `Notification` inflated via `recoverBuilder` +
 * `RemoteViews.apply`; [GLANCE_APPWIDGET] previews are composed to `RemoteViews` through a
 * synthetic `GlanceAppWidget` and inflated the same way.
 */
enum class PreviewKind {
  COMPOSE,
  TILE,
  NOTIFICATION,
  GLANCE_APPWIDGET,
  XR_SUBSPACE,
  /**
   * A Lottie asset (`.json` detected by structure, or `.lottie`) under module resources, with no
   * composable. The asset bytes are the IR: rendered via Compottie and packed as `ir/<id>.lottie`.
   * Path on [PreviewParams.assetPath].
   */
  LOTTIE,
  /**
   * An `.svg` asset under module resources, with no composable. Like [LOTTIE] the bytes are the IR;
   * rendered as a single still via Skia's `loadSvgPainter`, so desktop-only. The `viewBox` /
   * `width` / `height` seed [PreviewParams.widthDp] / [PreviewParams.heightDp].
   */
  SVG,
  /**
   * A synthetic token sheet aggregated from `@ColorCatalog` / `@TypographyCatalog` properties.
   * Tokens travel on [PreviewParams.catalogTokens]; the renderer reflects their values at render
   * time.
   */
  CATALOG,
  /**
   * A synthetic theme sheet for one `@ThemeCatalog` provider. Unlike [CATALOG] it composes: the
   * provider named on [PreviewParams.wrapperClassName] wraps a canned M3 specimen, showing the
   * theme's live resolved colours and typography.
   */
  THEME_CATALOG,
  /**
   * The Wear counterpart of [THEME_CATALOG], for `@WearThemeCatalog` providers. A separate kind
   * because a Wear provider never installs the mobile `MaterialTheme`, so the mobile specimen would
   * silently show baseline defaults.
   */
  WEAR_THEME_CATALOG,
  /**
   * A real Activity from the merged manifest, launched with its full lifecycle under Robolectric
   * and captured. The launcher activity is the app's hero image; others are `optional` since they
   * may need extras discovery can't guess. FQCN on [PreviewInfo.className]; optional intent on
   * [PreviewParams.launchIntent].
   */
  ACTIVITY,
  /**
   * A scripted multi-step tour from `compose-previews/tours/<name>.json`: launch, then click /
   * intent / back per step, following real `startActivity` calls, one PNG per step. Steps ride on
   * [Capture.tourStep]; start intent on [PreviewParams.launchIntent].
   */
  APP_TOUR,
}

/**
 * Which kind of design token a [CatalogToken] points at — drives which specimen layout renders it.
 */
enum class CatalogTokenKind {
  /** An `androidx.compose.ui.graphics.Color` property → a labelled swatch row. */
  COLOR,
  /**
   * An `androidx.compose.ui.text.TextStyle` property → a labelled sample-text row in that style.
   */
  TEXT_STYLE,
  /** An `androidx.compose.ui.graphics.Shape` property → a labelled box clipped to that shape. */
  SHAPE,
  /** A whole M3 `ColorScheme` property, expanded into one swatch row per role. */
  COLOR_SCHEME,
  /** A whole M3 `Typography` property, expanded into one row per style. */
  TYPOGRAPHY,
  /** A whole M3 `Shapes` property, expanded into one row per shape role. */
  SHAPES,
}

/**
 * One design-token property in a [PreviewKind.CATALOG] sheet. Only the coordinates and [label] are
 * recorded — the plugin's scan classpath has no Compose runtime, so the renderer reflects the value
 * at render time.
 */
@Serializable
data class CatalogToken(
  /** FQN of the class carrying the property (e.g. the file's synthetic `TokensKt`). */
  val className: String,
  /** The property/backing-field name to reflect. */
  val member: String,
  /** Display label for the swatch — the `@ColorCatalog.name` or, by default, the property name. */
  val label: String,
  val tokenKind: CatalogTokenKind = CatalogTokenKind.COLOR,
)

/**
 * Mirrors `ee.schimke.composeai.preview.ScrollMode`, duplicated so the plugin can serialize it
 * without the annotation artifact on its classpath. Same reason applies to the other mirrored enums
 * in this file.
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
 * Scroll state of a capture: intent from `@ScrollingPreview` ([mode], [axis], [maxScrollPx],
 * [reduceMotion]) plus renderer-recorded outcome ([atEnd], [reachedPx]). Outcome fields default to
 * "not populated" so the JSON shape is stable before the renderer writes them.
 */
@Serializable
data class ScrollCapture(
  // Intent
  val mode: ScrollMode,
  val axis: ScrollAxis = ScrollAxis.VERTICAL,
  val maxScrollPx: Int = 0,
  val reduceMotion: Boolean = true,
  /** Per-frame delay for [ScrollMode.GIF], in ms; `0` uses the renderer default. */
  val frameIntervalMs: Int = 0,
  // Outcome
  /**
   * Content was exhausted before the renderer stopped — distinct from `reachedPx == maxScrollPx`,
   * which only means the cap was hit.
   */
  val atEnd: Boolean = false,
  /** Pixels actually scrolled. `null` when not yet reported. */
  val reachedPx: Int? = null,
)

/**
 * `@AnimatedPreview` state, a separate [Capture] field so the renderer switches on its presence.
 * Output is one `.gif` / `.apng`, plus `<stem>_curves.png` when [showCurves].
 */
@Serializable
data class AnimationCapture(
  val durationMs: Int,
  val frameIntervalMs: Int,
  val showCurves: Boolean = false,
  /**
   * Container format as the backend will actually write it — Android records GIF for an APNG
   * request until it can encode APNG.
   */
  val format: MotionFormat = MotionFormat.GIF,
  /** `@AnimatedPreview(caption = …)` — the Motion-section line. Empty when the author gave none. */
  val caption: String = "",
)

/** Motion container format; mirrors `ee.schimke.composeai.preview.MotionFormat`. */
@Serializable
enum class MotionFormat {
  APNG,
  GIF;

  /** The output-file extension this format writes, without the dot. */
  val extension: String
    get() = name.lowercase()
}

/**
 * The gesture an [InteractionCapture] dispatches; mirrors
 * `ee.schimke.composeai.preview.InteractionGesture`.
 */
@Serializable
enum class InteractionGesture {
  TAP,
  PRESS_AND_HOLD,
}

/**
 * `@InteractionPreview` state, separate from [Capture.animation]. Unlike an animation, its duration
 * follows from the script (lead-in plus one gesture and settle window per target), so the renderer
 * derives the window and script and recording can't disagree.
 */
@Serializable
data class InteractionCapture(
  /** The gesture dispatched at each entry of [targets]. */
  val gesture: InteractionGesture,
  /**
   * Zero-based indices into the preview's clickable nodes in layout order, one gesture each, in
   * order. Repeats are meaningful.
   */
  val targets: List<Int>,
  /** `@InteractionPreview(caption = …)` — the Motion-section line. */
  val caption: String = "",
  /** Pointer-down dwell for [InteractionGesture.PRESS_AND_HOLD], in ms. */
  val holdMs: Int,
  /** Settle window after each gesture, in ms — where the animation being documented plays. */
  val gapMs: Int,
  /** Resting frames captured before the first gesture, in ms. */
  val leadInMs: Int,
  /** Per-frame interval in ms; 16 ≈ 60fps by default. */
  val frameIntervalMs: Int,
  /** Container format. Defaults to APNG — see the annotation's KDoc for why. */
  val format: MotionFormat = MotionFormat.APNG,
)

/** Mirrors `ee.schimke.composeai.preview.FocusDirection`. */
enum class FocusDirection {
  Next,
  Previous,
  Up,
  Down,
  Left,
  Right,
}

/**
 * `@FocusedPreview` state. The renderer provides keyboard `InputMode` (Robolectric is permanently
 * in touch mode, where `clickable` refuses focus) and walks focus to the target before capture.
 */
@Serializable
data class FocusCapture(
  /**
   * Zero-based tab-order index for indexed-mode captures; `null` in traversal mode (see
   * [direction]).
   */
  val tabIndex: Int? = null,
  /**
   * Direction to move before this capture in traversal mode; `null` in indexed mode (see
   * [tabIndex]).
   */
  val direction: FocusDirection? = null,
  /**
   * 1-based step number within `traverse = [...]`, so overlays can label steps that share a
   * direction.
   */
  val step: Int? = null,
  /** Post-apply a stroke + label overlay; the raw capture is kept as `<basename>.raw.png`. */
  val overlay: Boolean = false,
  /**
   * Skip the `+1 Next` compensation after `moveFocus(Enter)`, for roots whose `onEnter` already
   * places focus. See `@FocusedPreview.enterPlacesFocus`.
   */
  val enterPlacesFocus: Boolean = false,
  /**
   * Dispatch an indirect-pointer Press onto the focused node before capture, to show the pressed
   * state. Indexed mode only. See `@FocusedPreview.pressed`.
   */
  val pressed: Boolean = false,
)

/** Hover state driven by a real pointer move without first focusing the target. */
@Serializable data class HoverCapture(val targetIndex: Int = 0)

/** Pointer drag held at a deterministic horizontal displacement. */
@Serializable data class DragCapture(val targetIndex: Int = 0)

/**
 * `@FocusedPreview(gif = true)` state: the per-step focus instructions, driven through the same
 * focus walk and stitched into one GIF. Separate from [FocusCapture] so the renderer routes PNG vs
 * GIF mode up front.
 */
@Serializable
data class FocusGifCapture(
  val steps: List<FocusCapture>,
  /** Per-frame delay in ms; both the settle window between steps and the GIF frame delay. */
  val frameDelayMs: Int = DEFAULT_FOCUS_GIF_FRAME_DELAY_MS,
)

/** ~800ms leaves the focus-in transition visible before the next step moves focus. */
const val DEFAULT_FOCUS_GIF_FRAME_DELAY_MS: Int = 800

/**
 * `@AmbientPreview` state. The renderer wraps the composition with `AmbientOverrideExtension`,
 * which installs `LocalAmbientModeManager` — the same seam as `renderNow.overrides.ambient`.
 */
@Serializable
data class AmbientCapture(
  /**
   * Mirrors `androidx.wear.compose.foundation.AmbientMode`; only `Interactive` / `Ambient` exist
   * here.
   */
  val state: AmbientCaptureState = AmbientCaptureState.Ambient,
  /**
   * Mirrors `isBurnInProtectionRequired`; only meaningful when [state] is
   * [AmbientCaptureState.Ambient].
   */
  val burnInProtectionRequired: Boolean = false,
  /**
   * Mirrors `isLowBitAmbientSupported`; only meaningful when [state] is
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
 * `@SettledPreview` window: the renderer advances the paused clock before capturing a still, so
 * `LaunchedEffect { delay(…) }` reveals are captured settled. Still captures only; motion captures
 * drive the clock themselves.
 */
@Serializable
data class SettleCapture(
  /** Exact advance in ms, or `0` for auto (until the composition quiesces, bounded by [maxMs]). */
  val afterMs: Int = 0,
  /** Bound on the auto walk, in milliseconds. Mirrors `SettledPreview.maxMs`. */
  val maxMs: Int = DEFAULT_SETTLE_MAX_MS,
) {
  /** The longest window this settle can consume in either mode. */
  val windowMs: Int
    get() = if (afterMs > 0) afterMs else maxMs
}

/** Default bound on `@SettledPreview`'s auto walk. Mirrors `preview-annotations`' constant. */
const val DEFAULT_SETTLE_MAX_MS: Int = 1000

/** Hard ceiling on any settle window. Mirrors `preview-annotations`' constant. */
const val MAX_SETTLE_MS: Int = 5000

/** One 60Hz frame: the settle step size and the minimum window. */
const val SETTLE_FRAME_MS: Int = 16

/**
 * Virtual time a focused capture spends before any drive — two frames, matching both backends. The
 * focus walk needs a laid-out tree, so discovery raises a smaller `@SettledPreview(afterMs)` to
 * this floor to keep backends consistent.
 */
const val FOCUS_SETUP_FRAMES_MS: Int = 32

/**
 * `@GlimmerEnvironmentPreview` environment: the renderer captures opaque RGB-on-black Glimmer UI,
 * then delegates ADD compositing to the environment connector.
 */
@Serializable
enum class GlimmerEnvironmentCapture {
  Light,
  Dark,
  Busy,
  VeniceCanalCats,
}

/**
 * `@GestureHintPreview` override: the renderer wraps the composition with
 * `GestureOverrideExtension` so `GestureHint` force-shows its indicator.
 */
@Serializable
data class GestureHintCapture(
  /**
   * Force-show the one-handed-gesture hints for the render. Mirrors `GestureOverride.showHints`.
   */
  val showHints: Boolean = true
)

/**
 * `@PermissionPreview` grant state. The renderer builds `PermissionsOverrideExtension`, which seeds
 * Robolectric's grant set. Unlike ambient/gesture overrides this must happen **before**
 * `setContent`, because permissions are read through the platform API on the first composition.
 */
@Serializable
data class PermissionsCapture(
  /**
   * Grant state keyed by full permission name. **Exhaustive**: anything not named is denied, so
   * grants can't leak between previews.
   */
  val grants: Map<String, PermissionGrantCaptureState> = emptyMap()
)

/**
 * Grant state; mirrors the daemon's `PermissionGrantStateOverride` (the plugin can't depend on
 * `:daemon:core`). `DENIED` is explicit so a denial survives future default changes.
 */
@Serializable
enum class PermissionGrantCaptureState {
  GRANTED,
  DENIED,
}

/**
 * `@LauncherWidgetPreview` container-size override, stamped on every capture. The renderer wraps
 * the composition in `LauncherWidgetExtension` at the resolved dp footprint. Fields mirror
 * `LauncherWidgetOverride`; `null` bounds mean the annotation's `-1` sentinel (connector defaults).
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
   * From `@LauncherWidgetResize.frameDelayMs`; `null` outside a resize walk. Identical across every
   * stop of a walk.
   */
  val frameDelayMs: Int? = null,
  /** Render inside the simulated launcher home screen rather than a bare cell-sized box. */
  val launcherMode: Boolean = false,
)

/** Mirror of `LauncherResizeOrder` in `:daemon:core`. */
@Serializable
enum class LauncherWidgetCaptureResizeOrder {
  Diagonal,
  WidthFirst,
  HeightFirst,
}

/**
 * One `<activity>` / `<activity-alias>` from the merged manifest, indexed on
 * [PreviewManifest.activities] so tooling can see the app's entry points and intent filters.
 * Enabled activities also fan out into [PreviewKind.ACTIVITY] previews.
 */
@Serializable
data class ManifestActivity(
  /** Fully qualified activity class name, short `.Name` forms resolved against the package. */
  val className: String,
  /** `android:exported` — launchable by other apps. Defaults to `false` when unspecified. */
  val exported: Boolean = false,
  /** `true` for the `MAIN`/`LAUNCHER` entry point — the activity whose capture is the hero. */
  val launcher: Boolean = false,
  /** Verbatim `android:label`, when the manifest declares one (may be a `@string/…` reference). */
  val label: String? = null,
  /** The activity's `<intent-filter>` declarations — the advertised ways to launch it. */
  val intentFilters: List<ActivityIntentFilter> = emptyList(),
)

/** One `<intent-filter>` on a [ManifestActivity]. */
@Serializable
data class ActivityIntentFilter(
  val actions: List<String> = emptyList(),
  val categories: List<String> = emptyList(),
  /** `android:scheme` values from `<data>` children — the deep-link schemes this filter matches. */
  val dataSchemes: List<String> = emptyList(),
  /** `android:host` values from `<data>` children. */
  val dataHosts: List<String> = emptyList(),
)

/**
 * An Intent built at render time for an ACTIVITY preview, a tour's start, or a tour step.
 * [activityClassName] targets a component; [action]/[data] form an implicit intent resolved through
 * the real manifest-backed `PackageManager`.
 */
@Serializable
data class TourIntentSpec(
  /** FQCN of the target activity for an explicit intent; `null` for implicit resolution. */
  val activityClassName: String? = null,
  /** Intent action, e.g. `android.intent.action.VIEW`. */
  val action: String? = null,
  /** Data URI string, e.g. a deep link like `myapp://detail/42`. */
  val data: String? = null,
  val categories: List<String> = emptyList(),
  /** String extras placed on the intent (`putExtra(key, value)`). */
  val extras: Map<String, String> = emptyMap(),
)

/**
 * A click target in a tour step; set exactly one selector. Compose via semantics ([text],
 * [contentDescription], [tag]), Views via [viewId] or [text]. The click runs the node's real
 * action.
 */
@Serializable
data class TourClickSpec(
  /** Match a node by its visible text (exact match, merged semantics). */
  val text: String? = null,
  /** Match a node by content description. */
  val contentDescription: String? = null,
  /** Match a Compose node by `Modifier.testTag(...)`. */
  val tag: String? = null,
  /** Match a classic View by its resource entry name (`R.id.<viewId>`). */
  val viewId: String? = null,
)

/**
 * One step of a [PreviewKind.APP_TOUR], on [Capture.tourStep]. The renderer performs at most one of
 * [click] / [intent] / [back] (none for the launch step), settles, and captures the resumed
 * activity's window.
 */
@Serializable
data class TourStepCapture(
  /** Zero-based step position; step 0 is always the synthesized "launch" capture. */
  val index: Int,
  /** Human label from the tour spec, embedded in the render filename and shown by tooling. */
  val label: String,
  val click: TourClickSpec? = null,
  val intent: TourIntentSpec? = null,
  val back: Boolean = false,
)

/**
 * Cost catalogue, normalised so a static `@Preview` is `1.0`; stamped onto each [Capture] and used
 * by tooling to throttle interactive renders. Relative wall-time approximations:
 * - [STATIC_COST] / [SCROLL_TOP_COST] = 1
 * - [SCROLL_END_COST] ≈ 3 — capture plus scroll prelude
 * - [SCROLL_LONG_COST] ≈ 20 — stitched slices
 * - [SCROLL_GIF_COST] ≈ 40 — many frames + GIF encode
 * - [ANIMATION_COST] ≈ 50 — dominated by GIF encode
 * - [INTERACTION_COST] ≈ 60 — animation loop plus gestures at 60fps
 * - [ACCESSIBILITY_COST_PER_CAPTURE] = 4 — added by tooling when ATF runs (global toggle, not
 *   stored)
 *
 * [HEAVY_COST_THRESHOLD] sits between END and LONG, so static/TOP/END run on every save and the
 * rest are on-demand.
 */
const val STATIC_COST: Float = 1.0f
const val SCROLL_TOP_COST: Float = 1.0f
const val SCROLL_END_COST: Float = 3.0f
const val SCROLL_LONG_COST: Float = 20.0f
const val SCROLL_GIF_COST: Float = 40.0f
const val FOCUS_GIF_COST: Float = 40.0f
const val ANIMATION_COST: Float = 50.0f
const val INTERACTION_COST: Float = 60.0f

/**
 * Extra cost per second of `@SettledPreview` window. Both backends walk it frame by frame, so the
 * default 1000ms lands at 6.0 — just above [HEAVY_COST_THRESHOLD] — while short windows stay cheap.
 */
const val SETTLE_COST_PER_SECOND: Float = 5.0f

/** Per-capture cost of a still carrying a settle of [windowMs]. See [SETTLE_COST_PER_SECOND]. */
fun settleCaptureCost(windowMs: Int): Float =
  STATIC_COST + (windowMs.coerceAtLeast(0) / 1000f) * SETTLE_COST_PER_SECOND

const val ACCESSIBILITY_COST_PER_CAPTURE: Float = 4.0f
const val HEAVY_COST_THRESHOLD: Float = 5.0f

/**
 * A full activity launch plus capture; kept under [HEAVY_COST_THRESHOLD] so the hero image
 * refreshes in the fast tier.
 */
const val ACTIVITY_LAUNCH_COST: Float = 4.0f

/**
 * Per tour step: each step replays the prefix's lifecycle work, so tours land in the heavy bucket.
 */
const val TOUR_STEP_COST: Float = 8.0f

/** Single seam so plugin, renderer and VS Code agree on which captures the save loop skips. */
fun isHeavyCost(cost: Float): Boolean = cost > HEAVY_COST_THRESHOLD

@Serializable
data class PreviewParams(
  val name: String? = null,
  val device: String? = null,
  val widthDp: Int? = null,
  val heightDp: Int? = null,
  /**
   * Compose density (densityDpi / 160) from the `@Preview` device or `spec:…,dpi=`; `null` uses the
   * renderer default. Mapped to a Robolectric `<n>dpi` qualifier so bitmaps match Studio (~2.625x,
   * not Robolectric's default 2.0x).
   */
  val density: Float? = null,
  /**
   * Bound a **wrapped** width is measured against, replacing the 400×800 dp sandbox
   * ([DeviceDimensions.SANDBOX_WIDTH_DP]). Not [widthDp], which fixes the axis and disables the
   * intrinsic crop. Set by [PreviewDiscovery.retargetWearStickers].
   */
  val wrapSandboxWidthDp: Int? = null,
  /** Bound a **wrapped** height axis is measured against. See [wrapSandboxWidthDp]. */
  val wrapSandboxHeightDp: Int? = null,
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
   * FQN of the `@PreviewParameter` provider, if any. The renderer instantiates it and fans out one
   * file per value (`_PARAM_<idx>`); discovery doesn't, because its classpath lacks the consumer's
   * Compose dependencies.
   */
  val previewParameterProviderClassName: String? = null,
  /**
   * Mirrors `@PreviewParameter.limit`; applied via `values.take(limit)` so infinite providers stay
   * bounded.
   */
  val previewParameterLimit: Int = Int.MAX_VALUE,
  val kind: PreviewKind = PreviewKind.COMPOSE,
  /** [PreviewKind.CATALOG] only: the tokens this sheet aggregates, in render order. */
  val catalogTokens: List<CatalogToken> = emptyList(),
  /**
   * [PreviewKind.LOTTIE] / SVG only: module-resource-relative asset path (e.g.
   * `lottie/loading.json`), loaded via the classloader and packed by the bundle.
   */
  val assetPath: String? = null,
  /**
   * [PreviewKind.ACTIVITY] / [PreviewKind.APP_TOUR] only: the launch intent. `null` on an ACTIVITY
   * means the default `ACTION_MAIN` launch.
   */
  val launchIntent: TourIntentSpec? = null,
  /**
   * `@CaptureGutter`: transparent dp added on each edge so shadows/badges outside the component's
   * bounds aren't cropped. `null` means none.
   *
   * Applied by the renderer outside the composable — the component measures exactly as without it
   * and is placed inset — so consumers know canvas minus gutter is the component (unlike padding
   * inside the preview body).
   */
  val captureGutter: CaptureGutterDp? = null,
)

/**
 * Per-edge capture gutter in **dp**, applied by each backend at its own density. Start/end are
 * layout-direction-resolved, so a gutter follows its badge under RTL.
 */
@Serializable
data class CaptureGutterDp(
  val start: Int = 0,
  val top: Int = 0,
  val end: Int = 0,
  val bottom: Int = 0,
) {
  /** True when every edge is zero — nothing to apply, and nothing worth recording. */
  fun isEmpty(): Boolean = start == 0 && top == 0 && end == 0 && bottom == 0
}

/**
 * One rendered snapshot of a preview. The non-null fields are its dimensions: a static preview has
 * one capture with all null; multiple annotations produce the cross-product. Typed fields rather
 * than a generic map, so consumers can read specific knobs.
 */
@Serializable
data class Capture(
  /** `null` → renderer's default clock step before capture. */
  val advanceTimeMillis: Long? = null,
  /** `null` → no scroll drive. */
  val scroll: ScrollCapture? = null,
  /** `null` → not an animation capture. Mutually exclusive with [scroll] in practice. */
  val animation: AnimationCapture? = null,
  /**
   * `null` → not an interaction capture. Owns its own capture rather than crossing with the scroll
   * / time fan-out.
   */
  val interaction: InteractionCapture? = null,
  /** `null` → no focus drive. Set when the preview carries a `@FocusedPreview` annotation. */
  val focus: FocusCapture? = null,
  /** `null` → no pointer-hover drive. */
  val hover: HoverCapture? = null,
  /** `null` → no held pointer-drag drive. */
  val drag: DragCapture? = null,
  /** `null` → no focus GIF. Mutually exclusive with [focus]. */
  val focusGif: FocusGifCapture? = null,
  /** `null` → no ambient override. */
  val ambient: AmbientCapture? = null,
  /** `null` → default advance; otherwise settle first. Still captures only. */
  val settle: SettleCapture? = null,
  /**
   * `null` → keep the raw additive Glimmer capture; otherwise also ADD-composite the environment.
   */
  val glimmerEnvironment: GlimmerEnvironmentCapture? = null,
  /** `null` → no gesture-hint override. */
  val gestureHint: GestureHintCapture? = null,
  /** `null` → no permission override; otherwise applied before `setContent`. */
  val permissions: PermissionsCapture? = null,
  /** `null` → no launcher-widget size override. */
  val launcherWidget: LauncherWidgetCapture? = null,
  /** `null` → not an app-tour step. See [TourStepCapture]. */
  val tourStep: TourStepCapture? = null,
  /** Module-relative PNG path, e.g. `renders/<preview id>_TIME_500ms.png`. */
  val renderOutput: String = "",
  /**
   * Best-effort capture: shown if present but not required by `composePreviewRenderAll`'s
   * missing-render gate. For outputs of optional out-of-band tools (e.g. the native `xr-composite`
   * renderer).
   */
  val optional: Boolean = false,
  /**
   * Estimated render cost (static = `1.0`; see [STATIC_COST] and siblings). Defaults to `1.0` so
   * older manifests parse as cheap.
   */
  val cost: Float = STATIC_COST,
)

/**
 * Annotation-sourced data product request (e.g. `@ScrollingPreview(modes = [LONG, GIF])`), moving
 * heavyweight artefacts out of the primary capture carousel.
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
   * Optional clock coordinate shared with [Capture.advanceTimeMillis]; `null` uses the default
   * advance.
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

/**
 * Whether a [CatalogEntry] is a top-level component (`@CatalogComponent`) or a variant folded onto
 * a parent (`@CatalogVariant`).
 */
enum class CatalogRole {
  COMPONENT,
  VARIANT,
}

/**
 * One axis distinguishing a variant from its parent's default (e.g. `locale = ar-XB`), from
 * `@CatalogVariant.props` `"key=value"` strings (annotations can't hold a `Map`).
 */
@Serializable data class CatalogVariantProp(val key: String, val value: String)

/**
 * Design-catalog identity from `@CatalogComponent` / `@CatalogVariant` / `@CatalogGroup`, attached
 * to [PreviewInfo.catalog]. The design-artifacts export builds its catalog inventory from these,
 * with `catalog.spec.json` entries layered on as overrides.
 *
 * [componentId] is the component's own id for a COMPONENT and the parent's id for a VARIANT.
 * [group] / [section] are component-only; [state] / [props] variant-only. The kit correspondence
 * fields ([reference], [referenceSet], [noReference], [referenceContentsOnly], [parallel]) apply to
 * both, since a variant is compared in its own right.
 */
@Serializable
data class CatalogEntry(
  val role: CatalogRole,
  /** COMPONENT: this component's id. VARIANT: the parent component id (`@CatalogVariant.of`). */
  val componentId: String,
  /** Resolved group (per-component override, else file `@CatalogGroup`, else `Components`). */
  val group: String? = null,
  /** Optional top-level tab from the file `@CatalogGroup.section`. */
  val section: String? = null,
  /** One-line description shown under the sticker; `null` when the annotation left it blank. */
  val caption: String? = null,
  /** Seed-kit handle for the one-off import. */
  val reference: String? = null,
  /**
   * The Figma component set [reference] belongs to. [reference] stays one concrete node for parity
   * diffs; the set is for matching instances found on whole screens, which rarely use the exact
   * pictured variant.
   */
  val referenceSet: String? = null,
  /**
   * Why there is no [reference] when that's a finding (e.g. the kit retired it), as opposed to
   * "nobody has looked yet".
   */
  val noReference: String? = null,
  /**
   * Whether a Figma export contains only [reference]'s own content; `false` opts into overlapping
   * sheet layers.
   */
  val referenceContentsOnly: Boolean = true,
  /** Component id of the counterpart in the `compareWith` sibling system. */
  val parallel: String? = null,
  /** VARIANT only: the interaction/state this render shows (`pressed`, `disabled`, …). */
  val state: String? = null,
  /** VARIANT only: named content/i18n/a11y axes distinguishing this render from the default. */
  val props: List<CatalogVariantProp> = emptyList(),
  /**
   * COMPONENT only: `@CatalogComponent.perBreakpoint` — split the multipreview fan-out into one
   * component per breakpoint. The breakpoints themselves come from the renders, so they can't
   * contradict them.
   */
  val perBreakpoint: Boolean = false,
  /**
   * COMPONENT only: `@CatalogComponent.breakpointKit`, `"<widthDp>=<kitAxis>=<kitValue>"` entries.
   * Carried verbatim: `design-map.mjs` is the only consumer and owns parsing. Empty ⇒ seeded with
   * the bare width.
   */
  val breakpointKit: List<String> = emptyList(),
  /**
   * COMPONENT only: `@CatalogComponent.related` — counterparts in other catalogs, as `"<system>"` /
   * `"<system>=<componentId>"` / `"<system>=<componentId>=<label>"`. Unlike [parallel] (the one
   * counterpart this render is diffed against), nothing scores or gates on these. Carried verbatim;
   * the export's catalog inventory is the only reader.
   */
  val related: List<String> = emptyList(),
  /**
   * COMPONENT only: `@CatalogComponent.motionPreview` — the `@Preview` function whose motion
   * captures publish on this component, when the recording lives on its own function. `null` reads
   * motion off the component's own preview.
   */
  val motionPreview: String? = null,
  /**
   * The kit's name for the variant property this entry's knobs turn, when it differs from the
   * code's. On a COMPONENT it's the default for its `@OverrideVariant` cells; on a VARIANT it names
   * the axis of its single [props] entry.
   */
  val kitAxis: String? = null,
  /**
   * VARIANT only: the kit's spelling of this variant's value (e.g. `Full-screen (range)` for
   * `type=range`).
   */
  val kitValue: String? = null,
)

/**
 * One editable knob a preview declares as **its own value parameter**, recovered from
 * `@kotlin.Metadata` — the secondary override format ([docs/design/COMPONENT_RECORD.md]). Unlike
 * `previewOverride*` lookups, parameters are statically enumerable, typed, and carry their
 * defaults:
 * ```kotlin
 * @Preview @Composable
 * fun FilledButton(label: String = "Filled", enabled: Boolean = true) {
 *   Button(onClick = {}, enabled = enabled) { Text(label) }
 * }
 * ```
 *
 * Rendering needs nothing new: all-defaulted previews already render via the `$default` bridge. To
 * seed a subset, a renderer passes `null` for unseeded positions ([index]), which sets their
 * default-mask bit. Only parameter types the harness can construct from a seed string become knobs.
 * [OverrideSeed.key] resolves against [name], so `@OverrideVariant` seeds both formats.
 *
 * @property name the Kotlin parameter name, and the seed key that addresses it.
 * @property index the parameter's zero-based position in the function's value-parameter list.
 * @property type which seed kind can be bound to it.
 */
@Serializable
data class PreviewKnob(
  val name: String,
  val index: Int,
  val type: PreviewKnobType,
  /**
   * The parameter's **literal** default as seed text, or null when it isn't a recoverable lone
   * constant (see `PreviewKnobDefaults`). Lets an editor show the current value and offer reset; a
   * viewer should show nothing rather than invent a default.
   */
  val default: String? = null,
  /**
   * The accepted values when they're a closed set — enum constant names in declaration order; empty
   * otherwise. Lets a viewer draw a picker, matching `previewOverrideChoice`.
   */
  val options: List<String> = emptyList(),
)

/** The value kinds a [PreviewKnob] can carry — the types the harness can build from a seed. */
@Serializable
enum class PreviewKnobType {
  STRING,
  BOOLEAN,
  INT,
  LONG,
  FLOAT,
  DOUBLE,

  /**
   * A Kotlin `enum class` parameter. Seeded by constant name, and the one kind whose accepted
   * values are a closed set — see [PreviewKnob.options].
   */
  ENUM,
}

/** Kind of a seeded `previewOverride*` value — mirrors `PreviewOverrideValue`'s subtypes. */
@Serializable
enum class OverrideSeedKind {
  STRING,
  BOOLEAN,
  INT,
  FLOAT,
  COLOR,
}

/**
 * One seeded `previewOverride*` value from an `@OverrideVariant` entry (`"key=value"` /
 * `"key#index=value"`). [raw] is verbatim; the renderer parses it into a typed value of [kind].
 * Stringly-typed so the plugin needn't carry the overrides runtime.
 */
@Serializable
data class OverrideSeed(
  val key: String,
  val index: Int? = null,
  val kind: OverrideSeedKind,
  val raw: String,
)

/**
 * A named override variant from `@OverrideVariant`. Discovery emits one synthetic [PreviewInfo] per
 * variant carrying these [seeds] (and an optional [interaction]); renderers seed via
 * `PreviewOverrideController.set(...)` before composing. [name] is the `_VARIANT_<name>` output tag
 * and catalog `state`.
 */
@Serializable
data class OverrideVariantSpec(
  val name: String,
  val seeds: List<OverrideSeed>,
  /** Real harness state, when this variant is more than a named knob seed. */
  val interaction: OverrideVariantInteraction? = null,
  /** Zero-based target among the preview's interactive nodes. */
  val interactionIndex: Int = 0,
  /**
   * The full axis assignment for a `@PreviewAxis` cross-product cell (e.g. `[size=xs,
   * shape=square]`), defaults included; empty for a hand-written variant. Lets the export publish
   * real `props` that pair with a kit's component set by construction rather than by name. [seeds]
   * holds only the non-default values.
   */
  val props: List<CatalogVariantProp> = emptyList(),
  /** Explicit design-kit variant property for this cell; null keeps downstream name matching. */
  val kitAxis: String? = null,
  /** Explicit design-kit value for this cell; null keeps downstream value matching. */
  val kitValue: String? = null,
  /**
   * `@OverrideVariant.kitProps`: the kit's whole assignment for a cell turning several knobs.
   * Non-empty **replaces** the seed vector downstream.
   */
  val kitProps: List<CatalogVariantProp> = emptyList(),
  /**
   * `@OverrideVariant.noReference`: why the kit has no cell for this render. Kept per cell because
   * one folded cell can be nodeless while its siblings resolve.
   */
  val noReference: String? = null,
  /**
   * `@OverrideVariant.secondary`: hide this cell from browse menus without changing rendering,
   * addressing or comparison.
   */
  val secondary: Boolean = false,
)

@Serializable
enum class OverrideVariantInteraction {
  Hovered,
  Focused,
  Pressed,
  Dragged,
}

@Serializable
data class PreviewInfo(
  val id: String,
  val functionName: String,
  val className: String,
  val sourceFile: String? = null,
  /**
   * A 1-based line in [sourceFile] inside this preview's body (its first statement, from
   * `LineNumberTable`), letting consumers jump to the declaration rather than the whole file.
   *
   * An anchor, not a span: inlined code carries SMAP line numbers past the end of the file, so the
   * max line can't be trusted on Kotlin.
   */
  val bodyLine: Int? = null,
  val params: PreviewParams = PreviewParams(),
  /**
   * Set on a synthetic `@OverrideVariant` preview: the values seeded before composing. See
   * [OverrideVariantSpec].
   */
  val overrides: OverrideVariantSpec? = null,
  /** All snapshots this preview produces; always at least one. */
  val captures: List<Capture> = listOf(Capture()),
  /**
   * Extra annotation-sourced products, surfaced through the data-product path rather than as
   * screenshots.
   */
  val dataProducts: List<PreviewDataProduct> = emptyList(),
  /**
   * Project-local composables this preview is presumed to render, inferred from its bytecode
   * (wrappers filtered, candidates scored against name and source set), most confident first.
   * Currently at most one entry.
   */
  val targets: List<PreviewTarget> = emptyList(),
  /** Design-catalog identity from `@CatalogComponent` / `@CatalogVariant`, or `null`. */
  val catalog: CatalogEntry? = null,
  /**
   * UI-builder policy from `@BuilderComponent`, or `null` (nearly always). The same [BuilderPolicy]
   * type `components.json` carries, so the two files can't drift.
   */
  val builder: BuilderPolicy? = null,
  /**
   * `@FixedTheme`: the subject is a theme, so hosts must not re-render it under a `themeProvider`
   * override.
   */
  val fixedTheme: Boolean = false,
  /**
   * `@PreviewHelper(includeInA11y = false)`: tooling-only content the a11y pipeline must not audit.
   */
  val includeInA11y: Boolean = true,
  /** Knobs declared as defaulted value parameters; see [PreviewKnob]. */
  val knobs: List<PreviewKnob> = emptyList(),
  /**
   * The design-system **library** components this preview renders, with signatures from
   * `@kotlin.Metadata`. Separate from [targets], which covers only the project's own composables
   * and drops `material3.*` as scaffolding, so a catalog sticker is described here instead.
   */
  val componentTargets: List<PreviewTarget> = emptyList(),
  /** The widget this preview draws, if any; see [PreviewWidget]. */
  val widget: PreviewWidget? = null,
)

/**
 * A preview that draws a widget, for design-guidelines widget rules (a Glance Wear widget sticker
 * otherwise looks like a component sticker).
 *
 * [host] is [HOST_WEAR] (Glance Wear widget) or [HOST_LAUNCHER] (Glance app widget or simulated
 * launcher). [profile] is the Remote Compose profile when discovery can tell: always
 * [PROFILE_WEAR_WIDGETS] for Wear, null for launcher (chosen at runtime).
 */
@Serializable
data class PreviewWidget(val host: String, val profile: String? = null) {
  companion object {
    const val HOST_WEAR: String = "wear"
    const val HOST_LAUNCHER: String = "launcher"
    const val PROFILE_WEAR_WIDGETS: String = "wear-widgets"
  }
}

/**
 * A composable a `@Preview` is presumed to render, keyed by FQN + name so a PNG can be correlated
 * back to production source. [signals] makes the inference auditable; [confidence] is the simple
 * tier.
 */
@Serializable
data class PreviewTarget(
  /** Owner class FQN (synthetic `…Kt` for top-level functions). */
  val className: String,
  /**
   * The **source-level** name, read from `@kotlin.Metadata`. Not the JVM name ([jvmName]), which is
   * mangled for value-class signatures (`AppTile-a1b2c3d`) and can't be called. Falls back to the
   * JVM name when metadata is unreadable — see [signatureKnown].
   */
  val functionName: String,
  /**
   * The JVM method name, for reflective lookup. Recorded unconditionally (not only when it differs)
   * so consumers never need `jvmName ?: functionName`. Null means "not recorded" (older manifest).
   */
  val jvmName: String? = null,
  /**
   * The JVM descriptor, the only thing that distinguishes overloads sharing a name. Null means "not
   * recorded".
   */
  val descriptor: String? = null,
  /** Module-relative source path of the target's owning file, when resolvable. */
  val sourceFile: String? = null,
  val confidence: TargetConfidence,
  val signals: List<TargetSignal> = emptyList(),
  /**
   * Real Kotlin value parameters in declared order, from `@kotlin.Metadata` (see
   * `ComposableSignature`), so consumers can render a true call site. Empty when unreadable or
   * parameterless.
   */
  val parameters: List<TargetParameter> = emptyList(),
  /**
   * Fully-qualified extension receiver (e.g. `ColumnScope`), or null. Only meaningful when
   * [signatureKnown].
   */
  val receiver: String? = null,
  /**
   * Whether [parameters] and [receiver] were actually read from metadata — distinguishes an
   * unreadable signature from a genuinely parameterless one, which matters to generators that claim
   * their output compiles.
   */
  val signatureKnown: Boolean = false,
  /**
   * Whether the composable is `public`/`internal`, i.e. callable from a generated file. Defaults to
   * true so older records don't silently lose call sites. Only meaningful when [signatureKnown].
   */
  val callableFromAnotherFile: Boolean = true,
  /**
   * Declares type parameters, which a call omitting all defaulted arguments can't infer. Only
   * meaningful when [signatureKnown].
   */
  val hasTypeParameters: Boolean = false,
  /**
   * Whether the composable declares a context receiver or context parameter, which a generated
   * wrapper cannot supply — see `ComposableSignatureInfo.hasContextReceivers`.
   */
  val hasContextReceivers: Boolean = false,
  /**
   * Fully-qualified `@RequiresOptIn` markers the declaration carries, which a generated wrapper
   * must apply itself — see `ComposableSignatureInfo.requiredOptIns`.
   */
  val requiredOptIns: List<String> = emptyList(),
  /**
   * The subset of [requiredOptIns] declared with AndroidX `RequiresOptIn`. Those need
   * `@androidx.annotation.OptIn(markerClass = …)`; `kotlin.OptIn` rejects them.
   */
  val androidxOptIns: List<String> = emptyList(),
  /**
   * `@kotlin.Deprecated` (any level) or `@java.lang.Deprecated` on the bound method. Generators
   * never emit deprecated calls (see [overloads]).
   */
  val deprecated: Boolean = false,
  /**
   * Every `@Composable` overload of [functionName] on [className], in declaration order; empty when
   * there is only one, and for project targets. Lets the builder-catalog step pick the overload
   * whose parameters cover the policy and skip deprecated ones.
   */
  val overloads: List<TargetOverload> = emptyList(),
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
   * Discovery recursed through a project-local wrapper (single `@Composable () -> Unit` parameter)
   * to reach the candidate.
   */
  WRAPPER_UNWRAPPED,
  /** A design-system library component; only on [PreviewInfo.componentTargets]. */
  LIBRARY_COMPONENT,
}

@Serializable
data class PreviewManifest(
  val module: String,
  val variant: String,
  val previews: List<PreviewInfo>,
  /**
   * Per-extension report pointers: extension id (e.g. `"a11y"`) → path relative to this manifest's
   * directory. Consumers iterate this rather than probing filenames. The old `accessibilityReport`
   * alias was removed, so older CLI / VS Code builds miss a11y findings.
   */
  val dataExtensionReports: Map<String, String> = emptyMap(),
  /**
   * Activities from the merged manifest (entry points and intent filters), e.g. for authoring tour
   * specs. Enabled ones also appear as [PreviewKind.ACTIVITY] previews. Empty for library/desktop
   * modules.
   */
  val activities: List<ManifestActivity> = emptyList(),
)

/** Cost catalogue extension for resource previews; same scale as the composable cost figures. */
const val RESOURCE_STATIC_COST: Float = 1.0f

const val RESOURCE_ADAPTIVE_COST: Float = 4.0f

const val RESOURCE_ANIMATED_COST: Float = 35.0f

/** Filmstrip: 5 keyframes and no GIF encode, so a fraction of the GIF cost. */
const val RESOURCE_ANIMATED_FILMSTRIP_COST: Float = RESOURCE_ANIMATED_COST / 5f

/**
 * Default keyframe fractions for an AnimatedVectorDrawable filmstrip capture — 0%, 25%, 50%, 75%,
 * 100% of the animation's reported `totalDuration`. Five cells gives reviewers enough sampling to
 * see the start/mid/end shape of a typical UI animation without the GIF's scrubbing overhead.
 */
val DEFAULT_RESOURCE_FILMSTRIP_FRACTIONS: List<Float> = listOf(0.0f, 0.25f, 0.5f, 0.75f, 1.0f)

/** One draw + PNG encode per stretch variant; tiers with static captures. */
const val RESOURCE_NINE_PATCH_COST: Float = 1.5f

/**
 * Drawable / mipmap resources the renderer handles. [VECTOR], [ANIMATED_VECTOR] and [ADAPTIVE_ICON]
 * come from XML root tags ([ResourceXmlClassifier]); [NINE_PATCH] from the `.9.png` convention
 * (AAPT2 compiles the guides into an `npTc` chunk).
 */
@Serializable
enum class ResourceType {
  VECTOR,
  ANIMATED_VECTOR,
  ADAPTIVE_ICON,
  NINE_PATCH,
}

/**
 * Target size for a 9-patch render:
 * - [INTRINSIC] — natural size.
 * - [HORIZONTAL] — 2× width.
 * - [VERTICAL] — 2× height.
 * - [BOTH] — 2× both.
 */
@Serializable
enum class NinePatchStretch {
  INTRINSIC,
  HORIZONTAL,
  VERTICAL,
  BOTH,
}

/**
 * Adaptive-icon mask, applied as a canvas clip rather than a qualifier. The contents are a separate
 * axis ([AdaptiveStyle]).
 */
@Serializable
enum class AdaptiveShape {
  CIRCLE,
  /** Pixel default mask, approximated by a rounded rect (corner ≈ 50% of half-width). */
  SQUIRCLE,
  ROUNDED_SQUARE,
  SQUARE,
}

/**
 * What goes inside the [AdaptiveShape] mask:
 * - [FULL_COLOR] — foreground + background (App Search / drawer).
 * - [THEMED_LIGHT] / [THEMED_DARK] — the `<monochrome>` layer tinted with the M3 baseline neutral
 *   palette (reproducible without a wallpaper).
 * - [LEGACY] — pre-O fallback (`android:icon` slot, else foreground on transparent); one capture
 *   per qualifier, no shape fan-out.
 */
@Serializable
enum class AdaptiveStyle {
  FULL_COLOR,
  THEMED_LIGHT,
  THEMED_DARK,
  LEGACY,
}

/**
 * Coordinates of one resource capture. [qualifiers] is the configuration requested (see
 * [ResourceQualifierParser]), not any source file's qualifier — AAPT picks the matching file.
 *
 * For adaptive icons, [shape] and [style] are independent; `LEGACY` always has `shape = null`.
 * [stretch] is 9-patch only. [filmstrip] is animated-vector only: the keyframe strip rather than
 * the GIF.
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
   * Keyframe fractions for filmstrip captures (empty otherwise), from
   * `composePreview.resourcePreviews.filmstripFractions`.
   */
  val filmstripFractions: List<Float> = emptyList(),
)

/**
 * One previewable resource; [id] is `<base>/<name>` (e.g. `drawable/ic_logo`). [sourceFiles] is
 * keyed by qualifier suffix, with `""` for the default file because null map keys aren't portable
 * JSON.
 */
@Serializable
data class ResourcePreview(
  val id: String,
  val type: ResourceType,
  val sourceFiles: Map<String, String> = emptyMap(),
  val captures: List<ResourceCapture> = emptyList(),
)

/**
 * A drawable / mipmap reference from `AndroidManifest.xml`. Doesn't trigger captures; lets tooling
 * link manifest lines to resource previews.
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
 * Sibling of [PreviewManifest] for XML resources (`resources.json`), keyed on `(resourceType,
 * resourceName)` rather than FQN.
 */
@Serializable
data class ResourceManifest(
  val module: String,
  val variant: String,
  val resources: List<ResourcePreview> = emptyList(),
  val manifestReferences: List<ManifestReference> = emptyList(),
)

/**
 * One `@Composable` overload of a [PreviewTarget]'s function: enough of its signature to print a
 * call from it, and whether it is deprecated. See [PreviewTarget.overloads].
 */
@Serializable
data class TargetOverload(
  val jvmName: String,
  val descriptor: String,
  val parameters: List<TargetParameter> = emptyList(),
  val receiver: String? = null,
  val callableFromAnotherFile: Boolean = true,
  val hasTypeParameters: Boolean = false,
  val hasContextReceivers: Boolean = false,
  val requiredOptIns: List<String> = emptyList(),
  val androidxOptIns: List<String> = emptyList(),
  val deprecated: Boolean = false,
)
