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
)

@Serializable
data class PreviewInfo(
  val id: String,
  val functionName: String,
  val className: String,
  val sourceFile: String? = null,
  val params: PreviewParams = PreviewParams(),
  val captures: List<Capture> = listOf(Capture()),
  val dataProducts: List<PreviewDataProduct> = emptyList(),
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
)
