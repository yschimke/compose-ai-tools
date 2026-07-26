package ee.schimke.composeai.renderer

import ee.schimke.composeai.scroll.ScrollGifEncoder
import kotlinx.serialization.Serializable

enum class PreviewKind {
  COMPOSE,
  TILE,
  NOTIFICATION,
  GLANCE_APPWIDGET,
  XR_SUBSPACE,
  // A Lottie asset discovered in this module. Carried so `previews.json` deserialises, but never
  // rendered here — the JVM desktop Compottie path (`composePreviewRenderLottie`) owns it, and
  // `RobolectricRenderTest` filters it out. There is no Android Lottie player.
  LOTTIE,
  // An SVG asset discovered in this module. Carried so `previews.json` deserialises, but never
  // rendered here — the JVM desktop path (`composePreviewRenderSvg`) inflates it via Skia's
  // `loadSvgPainter`, and `RobolectricRenderTest` filters it out. There is no Android SVG decoder.
  SVG,
  // A synthetic design-token catalog sheet aggregated from `@ColorCatalog` properties. Rendered
  // here by `CatalogPreviewStrategy`, which reflects each token's value off the loaded consumer
  // class and lays them out as a labelled swatch sheet. Payload travels on
  // `RenderPreviewParams.catalogTokens`.
  CATALOG,
  // A synthetic theme catalog sheet for one `@ThemeCatalog` provider. Rendered here by
  // `ThemeCatalogStrategy`, which resolves the `PreviewWrapperProvider` named on
  // `RenderPreviewParams.wrapperClassName` and composes its `Wrap(content)` around a canned M3
  // role + type specimen, capturing the theme's live `MaterialTheme.colorScheme` / `typography`.
  THEME_CATALOG,
  // The Wear sibling of THEME_CATALOG, for one `@WearThemeCatalog` provider. Rendered here by
  // `WearThemeCatalogStrategy`, which composes the same kind of canned specimen but reads
  // `androidx.wear.compose.material3.MaterialTheme` (reflectively, via `WearMaterialTheme`) —
  // the theme a Wear provider actually installs, and which the mobile specimen can't see.
  WEAR_THEME_CATALOG,
  // A real Activity from the module's merged manifest. Rendered here by `AppTourRenderer`, which
  // launches the activity for real (full lifecycle, its own `setContent`) via Robolectric's
  // `ActivityController` and captures its window — the launcher activity's capture is the app's
  // hero image. Not a `PreviewRenderStrategy`: there is no composition to produce, the activity
  // owns its own content.
  ACTIVITY,
  // A scripted multi-step tour of the app (committed `compose-previews/tours/` spec). Rendered
  // here by `AppTourRenderer`: launch the start activity, then per step click / fire an intent /
  // press back — following `startActivity` calls across real activities — and capture one PNG per
  // step. Step payloads ride on `RenderPreviewCapture.tourStep`.
  APP_TOUR,
}

/** Renderer-side mirror of the plugin's `CatalogTokenKind`. */
@Serializable
enum class CatalogTokenKind {
  COLOR,
  TEXT_STYLE,
  SHAPE,
  COLOR_SCHEME,
  TYPOGRAPHY,
  SHAPES,
}

/** Renderer-side mirror of the plugin's `CatalogToken`. */
@Serializable
data class CatalogToken(
  val className: String,
  val member: String,
  val label: String,
  val tokenKind: CatalogTokenKind = CatalogTokenKind.COLOR,
)

/**
 * Mirrors `ee.schimke.composeai.preview.ScrollMode` from the `preview-annotations` artifact.
 * Duplicated on the renderer side (same split as [PreviewKind]) so the renderer can read
 * `previews.json` without depending on the annotation artifact.
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
   * Per-frame delay for [ScrollMode.GIF] captures, in milliseconds. `0` means "renderer default"
   * ([ScrollGifEncoder.DEFAULT_FRAME_DELAY_MS]).
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

/** Renderer-side mirror of the plugin's `FocusDirection`. */
@Serializable
enum class FocusDirection {
  Next,
  Previous,
  Up,
  Down,
  Left,
  Right,
}

/** Renderer-side mirror of the plugin's `FocusCapture`. */
@Serializable
data class FocusCapture(
  val tabIndex: Int? = null,
  val direction: FocusDirection? = null,
  val step: Int? = null,
  val overlay: Boolean = false,
  val enterPlacesFocus: Boolean = false,
  val pressed: Boolean = false,
)

/** Renderer-side mirror of the plugin's `FocusGifCapture`. */
@Serializable
data class FocusGifCapture(
  val steps: List<FocusCapture>,
  val frameDelayMs: Int = DEFAULT_FOCUS_GIF_FRAME_DELAY_MS,
)

/** Mirrors the plugin's `DEFAULT_FOCUS_GIF_FRAME_DELAY_MS`. */
const val DEFAULT_FOCUS_GIF_FRAME_DELAY_MS: Int = 800

/** Renderer-side mirror of the plugin's `AmbientCaptureState`. */
@Serializable
enum class AmbientCaptureState {
  Interactive,
  Ambient,
}

/** Renderer-side mirror of the plugin's `AmbientCapture`. */
@Serializable
data class AmbientCapture(
  val state: AmbientCaptureState = AmbientCaptureState.Ambient,
  val burnInProtectionRequired: Boolean = false,
  val deviceHasLowBitAmbient: Boolean = false,
)

/** Renderer-side mirror of the plugin's `GestureHintCapture`. */
@Serializable data class GestureHintCapture(val showHints: Boolean = true)

/** Renderer-side mirror of the plugin's `LauncherWidgetCaptureResizeOrder`. */
@Serializable
enum class LauncherWidgetCaptureResizeOrder {
  Diagonal,
  WidthFirst,
  HeightFirst,
}

/** Renderer-side mirror of the plugin's `LauncherWidgetCapture`. */
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
   * Per-frame delay carried through from `@LauncherWidgetResize.frameDelayMs` for the future
   * GIF-stitch pass. `null` when the capture didn't originate from a resize walk.
   */
  val frameDelayMs: Int? = null,
  /**
   * `true` renders the widget inside the simulated launcher home screen rather than as a bare
   * cell-sized box. Maps onto `LauncherWidgetOverride.launcherMode`.
   */
  val launcherMode: Boolean = false,
)

/** Renderer-side mirror of the plugin's `TourIntentSpec`. */
@Serializable
data class TourIntentSpec(
  val activityClassName: String? = null,
  val action: String? = null,
  val data: String? = null,
  val categories: List<String> = emptyList(),
  val extras: Map<String, String> = emptyMap(),
)

/** Renderer-side mirror of the plugin's `TourClickSpec`. */
@Serializable
data class TourClickSpec(
  val text: String? = null,
  val contentDescription: String? = null,
  val tag: String? = null,
  val viewId: String? = null,
)

/** Renderer-side mirror of the plugin's `TourStepCapture`. */
@Serializable
data class TourStepCapture(
  val index: Int,
  val label: String,
  val click: TourClickSpec? = null,
  val intent: TourIntentSpec? = null,
  val back: Boolean = false,
)

/**
 * Heavy/fast threshold for [RenderPreviewCapture.cost]. Mirrors the plugin's `HEAVY_COST_THRESHOLD`
 * — anything strictly greater is considered "heavy" and gets dropped when
 * `composeai.render.tier=fast`. Single source of truth for the renderer; the plugin enforces the
 * same threshold over the same cost numbers it stamped at discovery.
 */
const val HEAVY_COST_THRESHOLD: Float = 5.0f

@Serializable
data class RenderManifest(
  val module: String,
  val variant: String,
  val previews: List<RenderPreviewEntry>,
  /**
   * Generic per-extension report pointer map. Keys are extension ids (e.g. `"a11y"`), values are
   * module-relative paths from this manifest to that extension's aggregated sidecar JSON. Mirror of
   * the plugin-side `PreviewManifest.dataExtensionReports`. The renderer doesn't consume this
   * pointer today — it's parsed for wire-format completeness so a future renderer-side strategy
   * lookup has the data on hand. See `PreviewManifest.reportsView` (CLI) for the unified read path.
   */
  val dataExtensionReports: Map<String, String> = emptyMap(),
)

@Serializable
data class RenderPreviewEntry(
  val id: String,
  val functionName: String,
  val className: String,
  val sourceFile: String? = null,
  val params: RenderPreviewParams = RenderPreviewParams(),
  /**
   * Rendered snapshots produced by this preview. See the plugin-side `Capture` docs — each entry
   * carries the dimensional values (`advanceTimeMillis`, `scroll`) that distinguish it from its
   * siblings and the PNG path it lands at. Always at least one element.
   */
  val captures: List<RenderPreviewCapture> = listOf(RenderPreviewCapture()),
  /**
   * Non-null on a synthetic `@OverrideVariant` preview: the `previewOverride*` values the renderer
   * seeds via `PreviewOverrideController.set(...)` before composing this entry, so the same function
   * renders once more with the knob(s) flipped. `null` on an ordinary preview (defaults resolve).
   * Uses the canonical [ee.schimke.composeai.data.overrides.OverrideVariantSpec] so every backend
   * shares one seed→value mapping.
   */
  val overrides: ee.schimke.composeai.data.overrides.OverrideVariantSpec? = null,
  /**
   * Annotation-sourced *rendered artefacts* for this preview — secondary images (each with a
   * `kind`, an output PNG, and a render cost), as opposed to the primary screenshots in [captures].
   * NOT the daemon's structured "data products" (a11y findings, semantics trees); those are JSON
   * produced by the daemon's `DataProductRegistry`, never by this render manifest. See
   * [RenderPreviewArtifact]. The wire field name stays `dataProducts` for back-compat.
   */
  val dataProducts: List<RenderPreviewArtifact> = emptyList(),
) {
  /**
   * Concise, stable label — the (already-unique, fan-out-suffixed) preview [id]. This is
   * load-bearing, not cosmetic: [RobolectricRenderTest]'s `@Parameters(name = "{0}")` names each
   * parameterized case by this `toString()`, and Gradle's JUnit XML writer turns that name into a
   * `TEST-<name>.xml` filename. The data-class default dumped every field (params, captures,
   * dataProducts), producing ~600-char names that overflow the filesystem's 255-byte filename limit
   * (`FileNotFoundException: … (File name too long)`) on the per-case-file reporting path. The `id`
   * keeps test reports readable while staying well under the limit.
   */
  override fun toString(): String = id
}

@Serializable
data class RenderPreviewCapture(
  val advanceTimeMillis: Long? = null,
  val scroll: ScrollCapture? = null,
  val animation: AnimationCapture? = null,
  val focus: FocusCapture? = null,
  val focusGif: FocusGifCapture? = null,
  val ambient: AmbientCapture? = null,
  val gestureHint: GestureHintCapture? = null,
  val launcherWidget: LauncherWidgetCapture? = null,
  /**
   * `null` → not an app-tour step. Set on every capture of a `kind=APP_TOUR` preview: the
   * navigation action `AppTourRenderer` performs before capturing this step.
   */
  val tourStep: TourStepCapture? = null,
  val renderOutput: String = "",
  /**
   * Estimated render cost normalised so a static `@Preview` is `1.0`. See the plugin's
   * `Capture.cost` for the full catalogue. Defaults to `1.0` so older manifests parse as
   * cheap-everywhere.
   */
  val cost: Float = 1.0f,
)

/**
 * A secondary *rendered artefact* of a preview produced by the standalone render path: a `kind`-
 * tagged image with its own output PNG and render [cost], distinct from the primary [captures].
 *
 * Renamed from `RenderPreviewDataProduct` to stop colliding with the daemon's structured "data
 * products" (a11y / semantics / theme JSON over the daemon protocol) — different concept, same old
 * name. The Kotlin class name isn't serialized, so this rename is wire-neutral; the owning field is
 * still [RenderPreviewEntry.dataProducts] to keep the manifest JSON unchanged.
 */
@Serializable
data class RenderPreviewArtifact(
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
   * Compose density factor (= densityDpi / 160) sourced from the `@Preview` device. The Android
   * renderer maps this to a Robolectric `<n>dpi` qualifier; the desktop renderer hands it to
   * `Density(...)`. `null` means "use the renderer's default" (matches the historical 2.0x
   * behaviour).
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
   * FQN of a `PreviewParameterProvider` harvested from `@PreviewParameter` on one of the preview
   * function's parameters, if any. When non-null the renderer enumerates the provider's `values`
   * (capped by [previewParameterLimit]) and emits one file per value with a `_PARAM_<idx>` suffix.
   * `null` means the preview has no parameter provider — the default single-capture path applies.
   */
  val previewParameterProviderClassName: String? = null,
  /** Mirrors `@PreviewParameter.limit`. `Int.MAX_VALUE` = take every value. */
  val previewParameterLimit: Int = Int.MAX_VALUE,
  val kind: PreviewKind = PreviewKind.COMPOSE,
  /** For [PreviewKind.CATALOG] only: the design tokens this synthetic sheet renders, in order. */
  val catalogTokens: List<CatalogToken> = emptyList(),
  /**
   * For [PreviewKind.ACTIVITY] / [PreviewKind.APP_TOUR] only: the Intent the (start) activity is
   * launched with. `null` on an ACTIVITY preview means its default launch intent.
   */
  val launchIntent: TourIntentSpec? = null,
)
