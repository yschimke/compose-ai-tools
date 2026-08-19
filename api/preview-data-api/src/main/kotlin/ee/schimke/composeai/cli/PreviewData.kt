package ee.schimke.composeai.cli

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// ---------------------------------------------------------------------------
// Wire-format DTOs for the compose-preview render pipeline. Lives in
// `:preview-data-api` (step A of the clean-API carve-out, issue #1084), kept
// in the `ee.schimke.composeai.cli` package for source-compatibility — these
// types used to live in `:cli/Commands.kt` before the extraction. Same pattern
// as `:data-a11y-core`'s `AccessibilityModels.kt` keeping its pre-extraction
// package.
//
// Consumers: the `:cli` formats them for `compose-preview show --json`,
// contrib scripting decodes extension payloads off them, future MCP
// integrations stream them over the wire. Anything that needs to call
// "render and give me back result objects" goes through the soon-to-be-built
// `:gradle-preview-driver` (step B).
// ---------------------------------------------------------------------------

/** On-disk shape mirrors gradle-plugin/PreviewData.kt (parsed with ignoreUnknownKeys). */
@Serializable
data class PreviewParams(
  val name: String? = null,
  val device: String? = null,
  val widthDp: Int? = null,
  val heightDp: Int? = null,
  /**
   * Wrapped-axis content-size bounds (the Max / Min / Within size modes), in **pixels**. Null means
   * "no bound" — the AS-parity wrap (min = 0, max = sandbox). They only bite on a wrapped axis (one
   * with no fixed [widthDp]/[heightDp]), letting a bundle carry a "render this component as it
   * would appear constrained to a list column" request that the desktop renderer honours — the same
   * `PreviewOverrides.{min,max}{Width,Height}Px` the daemon applies for `compose-preview serve`.
   * Absent from discovery today (no `@Preview` equivalent), so they round-trip only when a bundle /
   * catalog author sets them; the render pipeline defaults them to the unbounded wrap.
   */
  val minWidthPx: Int? = null,
  val minHeightPx: Int? = null,
  val maxWidthPx: Int? = null,
  val maxHeightPx: Int? = null,
  /**
   * Bound a **wrapped** axis is measured against, in dp, replacing the renderer's generic 400×800
   * dp sandbox. Unlike [widthDp]/[heightDp] it does NOT fix the axis: the capture still crops to
   * the measured size, only the bound `Modifier.fillMaxWidth` and friends resolve against changes.
   * Discovery sets it for a Wear module's device-less previews, which measure against the 227 dp
   * watch screen and still export as tight stickers — see
   * `discovery.PreviewDiscovery.retargetWearStickers`.
   */
  val wrapSandboxWidthDp: Int? = null,
  /** See [wrapSandboxWidthDp]. */
  val wrapSandboxHeightDp: Int? = null,
  /**
   * Compose density factor (= densityDpi / 160) resolved at discovery from the @Preview device;
   * null means "use the renderer default". Carried through so agents can spot per-device fan-outs
   * without re-reading the Gradle manifest.
   */
  val density: Float? = null,
  val fontScale: Float = 1.0f,
  val showSystemUi: Boolean = false,
  val showBackground: Boolean = false,
  val backgroundColor: Long = 0,
  val uiMode: Int = 0,
  val locale: String? = null,
  val group: String? = null,
  val wrapperClassName: String? = null,
  /**
   * FQN of the `PreviewParameterProvider` harvested from `@PreviewParameter` on one of the preview
   * function's parameters, if any. Discovery records this but does NOT expand captures — the
   * renderer fans out on disk as `<id>_PARAM_<idx>.<ext>` and the CLI globs those files in
   * `buildResults`.
   */
  val previewParameterProviderClassName: String? = null,
  /** Mirrors `@PreviewParameter.limit`. `Int.MAX_VALUE` = take every value. */
  val previewParameterLimit: Int = Int.MAX_VALUE,
  /** "COMPOSE" or "TILE". Free-form string so unknown future kinds round-trip. */
  val kind: String = "COMPOSE",
)

/**
 * Scroll state of a capture. Mirrors `ScrollCapture` in gradle-plugin/PreviewData.kt — kept as a
 * string-typed mirror so unknown future modes/axes round-trip cleanly through the CLI.
 */
@Serializable
data class ScrollCapture(
  val mode: String,
  val axis: String = "VERTICAL",
  val maxScrollPx: Int = 0,
  val reduceMotion: Boolean = true,
  val atEnd: Boolean = false,
  val reachedPx: Int? = null,
)

@Serializable
data class Capture(
  val advanceTimeMillis: Long? = null,
  val scroll: ScrollCapture? = null,
  val renderOutput: String = "",
  /**
   * `true` → best-effort capture: displayed if present, but not required by the missing-render gate
   * (e.g. the XR subspace composite still baked by the native `xr-composite` tool, which may be
   * absent when the binary / display / software GL isn't available). Defaults to `false`.
   */
  val optional: Boolean = false,
  /**
   * Presence markers for per-capture **detected-feature** annotations discovery emits: a
   * `@FocusedPreview` capture carries a `focus` (or `focusGif`) block, and a `@GestureHintPreview`
   * one carries `gestureHint`. Modelled here only as opaque [JsonElement]s — the serve layer needs
   * to know *whether* a preview supports keyboard focus / one-handed gestures (to gate the viewer's
   * feature controls), not the block's contents, and modelling the full nested shape would drag the
   * gradle-plugin's discovery types into `:preview-data-api`. Absent (null) when the preview
   * carries neither annotation.
   */
  val focus: JsonElement? = null,
  val focusGif: JsonElement? = null,
  val hover: JsonElement? = null,
  val gestureHint: JsonElement? = null,
  /**
   * `@SettledPreview`'s pre-capture settle window. Same opaque-marker treatment as [focus] above:
   * agent readers of `previews.json` need to see *that* a still was settled (and by how much, which
   * is the whole content of the block) without `:preview-data-api` re-declaring discovery's type.
   * Absent (null) on a capture rendered at the default advance.
   */
  val settle: JsonElement? = null,
) {
  /**
   * Binary-compatible constructor retained for consumers compiled before [settle] was added. Same
   * policy — and same reasoning — as [CatalogEntry]'s pre-`kitValue` constructor: a default on the
   * new primary parameter preserves *source* compatibility only, while appending it still changes
   * the JVM constructor descriptor and its default-argument bridge.
   */
  constructor(
    advanceTimeMillis: Long? = null,
    scroll: ScrollCapture? = null,
    renderOutput: String = "",
    optional: Boolean = false,
    focus: JsonElement? = null,
    focusGif: JsonElement? = null,
    hover: JsonElement? = null,
    gestureHint: JsonElement? = null,
  ) : this(
    advanceTimeMillis = advanceTimeMillis,
    scroll = scroll,
    renderOutput = renderOutput,
    optional = optional,
    focus = focus,
    focusGif = focusGif,
    hover = hover,
    gestureHint = gestureHint,
    settle = null,
  )
}

/**
 * Design-catalog role. Mirrors `CatalogRole` in gradle-plugin/PreviewData.kt — string-typed decode
 * would lose the closed set, so it's modelled as an enum; unknown future roles are handled by the
 * caller (the field itself is nullable on [PreviewInfo]).
 */
@Serializable
enum class CatalogRole {
  COMPONENT,
  VARIANT,
}

/**
 * One `key=value` content/i18n/a11y axis of a [CatalogRole.VARIANT]. Mirrors `CatalogVariantProp`.
 */
@Serializable data class CatalogVariantProp(val key: String, val value: String)

/**
 * Design-catalog identity a preview carries via `@CatalogComponent` / `@CatalogVariant`. Mirrors
 * `CatalogEntry` in gradle-plugin/PreviewData.kt so this wire-format API exposes the same metadata
 * discovery writes to `previews.json` — otherwise `ignoreUnknownKeys` would silently drop it for
 * `compose-preview show --json`, contrib scripting, and MCP readers. `null` for non-catalog
 * previews.
 */
@Serializable
data class CatalogEntry(
  val role: CatalogRole,
  val componentId: String,
  val group: String? = null,
  val section: String? = null,
  val caption: String? = null,
  val reference: String? = null,
  /**
   * COMPONENT: the component **family** [reference] is one variant of. Kept apart from [reference]
   * because the two answer opposite questions — a parity diff needs the one concrete node this
   * sticker renders, while matching an instance found on a whole screen needs the family, since a
   * screen rarely uses the exact variant a catalog pictured.
   */
  val referenceSet: String? = null,
  /**
   * COMPONENT: why there is no [reference] — a stated finding about the kit (it retired the
   * pattern, never published it) as opposed to the silence of nobody having looked yet, which a
   * consumer cannot tell apart from a null [reference] alone.
   */
  val noReference: String? = null,
  /** COMPONENT: whether a Figma export contains only [reference]'s own content. */
  val referenceContentsOnly: Boolean = true,
  val parallel: String? = null,
  val state: String? = null,
  val props: List<CatalogVariantProp> = emptyList(),
  /**
   * COMPONENT: `@CatalogComponent.perBreakpoint` — the design-artifacts export splits this
   * component into one per breakpoint its function rendered at, so a reader that dropped the field
   * would see one component where the published catalog has several.
   */
  val perBreakpoint: Boolean = false,
  /**
   * The design-kit variant property this entry's knobs turn: on a COMPONENT the default for its
   * override-variant cells, on a VARIANT the axis its single [props] entry means.
   */
  val kitAxis: String? = null,
  /** VARIANT: the design-kit value this variant maps to, when the kit spells it differently. */
  val kitValue: String? = null,
) {
  /**
   * Binary-compatible constructor retained for consumers compiled before [kitValue] was added. A
   * default on the new primary parameter preserves source compatibility only — appending it still
   * changes the JVM constructor descriptor and its default-argument bridge — so a consumer built
   * against the previous artifact would fail with `NoSuchMethodError` without this overload. Same
   * policy as [PreviewResult]'s pre-`projectDirectory` constructor below.
   */
  constructor(
    role: CatalogRole,
    componentId: String,
    group: String? = null,
    section: String? = null,
    caption: String? = null,
    reference: String? = null,
    referenceSet: String? = null,
    noReference: String? = null,
    referenceContentsOnly: Boolean = true,
    parallel: String? = null,
    state: String? = null,
    props: List<CatalogVariantProp> = emptyList(),
    perBreakpoint: Boolean = false,
    kitAxis: String? = null,
  ) : this(
    role = role,
    componentId = componentId,
    group = group,
    section = section,
    caption = caption,
    reference = reference,
    referenceSet = referenceSet,
    noReference = noReference,
    referenceContentsOnly = referenceContentsOnly,
    parallel = parallel,
    state = state,
    props = props,
    perBreakpoint = perBreakpoint,
    kitAxis = kitAxis,
    kitValue = null,
  )
}

@Serializable
data class PreviewInfo(
  val id: String,
  val functionName: String,
  val className: String,
  val sourceFile: String? = null,
  /**
   * A 1-based line in [sourceFile] known to fall **inside** this preview's function body — its
   * first statement, from the classfile's `LineNumberTable`. Mirrors `PreviewInfo.bodyLine` in
   * gradle-plugin/PreviewData.kt.
   *
   * Lets a consumer address the declaration rather than the whole file: walk outwards from here to
   * the surrounding declaration. An **anchor, not a span** — Kotlin emits an inline function's body
   * into its caller with SMAP line numbers past the end of the caller's file, so the last line of a
   * method is not a number worth publishing. See the discovery-side KDoc for the measurements.
   *
   * Absent from manifests produced before this field existed, so treat it as a hint and keep a
   * whole-file fallback.
   */
  val bodyLine: Int? = null,
  val params: PreviewParams = PreviewParams(),
  val captures: List<Capture> = listOf(Capture()),
  val dataProducts: List<PreviewDataProduct> = emptyList(),
  val catalog: CatalogEntry? = null,
  /**
   * `@FixedTheme` (or a `@ThemeCatalog`-synthesised sheet): this preview's subject **is** a theme,
   * so a preview host must not re-render it under a `themeProvider` override. Mirrors
   * `PreviewInfo.fixedTheme` in gradle-plugin/PreviewData.kt so this wire-format API exposes what
   * discovery writes to `previews.json` — otherwise `ignoreUnknownKeys` would silently drop it for
   * `compose-preview show --json`, contrib scripting, and MCP readers. False for ordinary previews.
   */
  val fixedTheme: Boolean = false,
  /** Mirrors discovery's `@PreviewHelper(includeInA11y = false)` manifest flag. */
  val includeInA11y: Boolean = true,
)

@Serializable
data class PreviewDataProduct(
  val kind: String,
  val output: String = "",
  val mediaTypes: List<String> = emptyList(),
  val advanceTimeMillis: Long? = null,
  val scroll: ScrollCapture? = null,
)

@Serializable
data class PreviewManifest(
  val module: String,
  val variant: String,
  val previews: List<PreviewInfo>,
  /**
   * Generic per-extension report pointer map. Keys are extension ids (e.g. `"a11y"`), values are
   * module-relative paths to that extension's aggregated sidecar JSON. Empty when no extension
   * produced a canned report.
   *
   * Strategy layer (see `ExtensionReportRenderer` in `:cli`) iterates this map; callers prefer
   * [reportsView] to keep the access seam in case future wire-format evolutions need it.
   */
  val dataExtensionReports: Map<String, String> = emptyMap(),
) {
  /**
   * Thin alias over [dataExtensionReports] kept as an access seam — the v1 `accessibilityReport`
   * back-compat path used to be hidden behind it, and future wire-format changes are easier to
   * introduce here than at every callsite. Today it's a pass-through.
   */
  val reportsView: Map<String, String>
    get() = dataExtensionReports
}

/**
 * One rendered snapshot inside a [PreviewResult]. Carries the dimensional coordinates
 * ([advanceTimeMillis], [scroll]) that distinguish this capture from its siblings, plus runtime
 * data the agent needs to act on it ([pngPath], [sha256], [changed]).
 *
 * A static preview produces a single `CaptureResult` with both dimensions null; an animation/scroll
 * fan-out produces N entries — one row per capture filename on disk.
 */
@Serializable
data class CaptureResult(
  val advanceTimeMillis: Long? = null,
  val scroll: ScrollCapture? = null,
  val pngPath: String? = null,
  val sha256: String? = null,
  val changed: Boolean? = null,
  /**
   * Mirror of the manifest [Capture.optional] flag: `true` → best-effort capture whose missing PNG
   * is expected (e.g. a `@ColorCatalog` sheet on the desktop backend, which can't draw them yet
   * — #2135). Carried into the CLI envelope so `--missing-renders` gating and the CI diff bot treat
   * the skip as expected instead of reporting a render failure.
   */
  val optional: Boolean = false,
  /** `@PreviewParameter` coordinate that distinguishes this capture from its siblings. */
  val parameterLabel: String? = null,
  /**
   * The **addressable id** of this `@PreviewParameter` row — `<previewId>_<row>`, the id `--id` /
   * `--filter` / `--preview` select on and the daemon renders on (issue #3819). Null for any
   * capture that isn't a provider row (an ordinary preview, a time/scroll fan-out frame, a data
   * product artefact).
   *
   * Deliberately separate from [parameterLabel], which is a lossy human coordinate (`PARAM_3` reads
   * as `parameter 3`) and can never be turned back into a selector. Both come from one derivation —
   * `PreviewParameterFanout` — so what a consumer is shown is what it can ask for.
   */
  val parameterRowId: String? = null,
)

/**
 * One per-extension data product attached to a [PreviewResult]. Generic envelope so the data API
 * doesn't have to know about every extension's wire shape — consumers decode [payload] against the
 * typed DTOs published by the per-extension `data-<id>-core` module (e.g. `:data-a11y-core`'s
 * `AccessibilityReport`).
 *
 * The [schema] string identifies the payload's shape so consumers can detect a version bump (e.g.
 * `"compose-preview-data-a11y/v1"`); pinned strings, never regex-matched.
 */
@Serializable data class ExtensionPayload(val schema: String, val payload: JsonElement)

/**
 * CLI output DTO — enriches manifest entries with runtime data agents need.
 *
 * Extension data flows through [dataExtensions] — a generic id→payload map so the data API stays
 * agnostic of which extensions exist. Consumers decode each extension's payload against its typed
 * DTOs (a11y: this module's [AccessibilityEntry] mirror — the JVM-side typed-decode surface for the
 * `compose-preview-data-a11y/v1` payload body).
 */
@Serializable
data class PreviewResult(
  val id: String,
  val module: String,
  /** Gradle's resolved project directory; never reconstruct this from [module]. */
  val projectDirectory: String? = null,
  val functionName: String,
  val className: String,
  val sourceFile: String? = null,
  val params: PreviewParams = PreviewParams(),
  /**
   * All rendered snapshots for this preview. Always at least one element. `length > 1` ⇔ a
   * `@RoboComposePreviewOptions` time fan-out or a scroll-with-progress capture — agents that need
   * every PNG should iterate this list rather than reading [pngPath].
   */
  val captures: List<CaptureResult> = emptyList(),
  /** First capture's PNG path. Kept for back-compat with existing agents. */
  val pngPath: String? = null,
  /** First capture's PNG sha256. Kept for back-compat. */
  val sha256: String? = null,
  /** First capture's `changed` flag. Kept for back-compat. */
  val changed: Boolean? = null,
  /**
   * Generic per-extension data carrier — keyed by extension id, value is the typed payload
   * envelope. Consumers decode `dataExtensions["a11y"]?.payload` against this module's
   * [AccessibilityEntry], `dataExtensions["theme"]` against the theme extension's published DTOs,
   * and so on.
   *
   * The data API has no business knowing which extensions exist — see `docs/AGENTS.md` "Important
   * constraints" / "No hardcoded special-case logic for extensions."
   */
  val dataExtensions: Map<String, ExtensionPayload> = emptyMap(),
) {
  /**
   * Binary-compatible constructor retained for consumers compiled before [projectDirectory] was
   * added. A default on the new primary parameter preserves source compatibility only; this
   * overload preserves the old JVM constructor descriptor and its default-argument bridge.
   */
  constructor(
    id: String,
    module: String,
    functionName: String,
    className: String,
    sourceFile: String? = null,
    params: PreviewParams = PreviewParams(),
    captures: List<CaptureResult> = emptyList(),
    pngPath: String? = null,
    sha256: String? = null,
    changed: Boolean? = null,
    dataExtensions: Map<String, ExtensionPayload> = emptyMap(),
  ) : this(
    id = id,
    module = module,
    projectDirectory = null,
    functionName = functionName,
    className = className,
    sourceFile = sourceFile,
    params = params,
    captures = captures,
    pngPath = pngPath,
    sha256 = sha256,
    changed = changed,
    dataExtensions = dataExtensions,
  )
}
