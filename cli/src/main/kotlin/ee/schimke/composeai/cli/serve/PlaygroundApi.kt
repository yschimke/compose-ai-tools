package ee.schimke.composeai.cli.serve

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire types for the **playground** REST surface — the Stage-1 "compile a snippet, get a result +
 * an expiring preview token" contract described in
 * [docs/design/PLAYGROUND.md](../../../../../../../../docs/design/PLAYGROUND.md).
 *
 * The request/response shapes are deliberately a **superset** of the `kotlin-compiler-server`
 * `/api/{version}/compiler/run` contract that a stock `kotlin-playground` frontend speaks, so an
 * unmodified editor can POST to us: it sends `{ args, files, confType }` and reads `{ text,
 * exception, errors }`. The two fields it does **not** know about — [PlaygroundRunResponse.image]
 * and [PlaygroundRunResponse.previewToken] — are additive; a stock frontend ignores them and ours
 * surfaces them as the still frame + the "Open live preview →" handoff.
 *
 * These are pure data types with no server behaviour; the route handler and the compile/render
 * plumbing live elsewhere.
 */

/** Which renderer + permalink target a snippet compiles for. See PLAYGROUND.md §3. */
@Serializable
enum class PlaygroundMode {
  /** Compose Multiplatform, desktop (Skiko) daemon → live streaming session. */
  @SerialName("compose-cmp") CMP,
  /** Jetpack Compose, Robolectric daemon → live streaming session. */
  @SerialName("compose-android") ANDROID,
  /** Snippet emits a RemoteDocument → `/d/<id>` document permalink, played client-side. */
  @SerialName("remote-compose") REMOTE_COMPOSE;

  companion object {
    /**
     * Resolve the request's `confType` to a mode. Accepts our own ids (the [SerialName]s above) and
     * tolerates the stock `kotlin-playground` target ids so an unmodified frontend still lands on a
     * sensible mode: `canvas`/`js`/`wasm`/`compose-wasm` (its in-browser Compose targets) map to
     * [CMP], everything else defaults to [CMP] as well — the one mode a from-source box can always
     * serve. An empty/absent `confType` is [CMP].
     */
    fun fromConfType(confType: String?): PlaygroundMode =
      when (confType?.trim()?.lowercase()) {
        "compose-android",
        "android" -> ANDROID
        "remote-compose",
        "remotecompose",
        "rc" -> REMOTE_COMPOSE
        else -> CMP
      }
  }
}

/**
 * One editor file in a run request. `publicId` is carried through but unused server-side.
 *
 * A request may carry **several**: every file is staged into the snippet's source dir and passed to
 * one compile, so files see each other's declarations (they are one module, not N compiles). Names
 * are sanitised and de-duplicated by [PlaygroundCompileService]; only the text matters to the
 * compiler, since Kotlin does not require a file name to match its declarations.
 */
@Serializable
data class PlaygroundFile(val name: String, val text: String, val publicId: String = "")

/**
 * A Stage-1 run request. Mirrors the `kotlin-compiler-server` body so a stock frontend fits; [args]
 * is accepted and ignored (a preview has no argv), and the mode comes from [confType] via
 * [PlaygroundMode.fromConfType].
 *
 * [catalog] is ours, and additive: a stock frontend omits it and lands on the host's pinned
 * `--playground-bundle` default exactly as before.
 */
@Serializable
data class PlaygroundRunRequest(
  val args: String = "",
  val files: List<PlaygroundFile> = emptyList(),
  val confType: String = "",
  /**
   * Which served catalog to compile against (`compose-m3`), chosen per request by the editor's
   * catalog selector. Empty ⇒ the host's pinned default for [confType]'s mode. An unknown or
   * unloaded id is a clean "not available" response, never a fallback to the default — silently
   * compiling against a *different* design system than the one asked for would report success for
   * the wrong thing.
   */
  val catalog: String = "",
)

/**
 * One entry in the editor's catalog selector (`GET /api/{version}/compiler/catalogs`).
 *
 * [modes] is what makes this worth a round trip rather than a static page: a catalog's bundle
 * backend decides its renderer, so selecting `compose-m3` (desktop) and selecting an Android
 * catalog offer different mode sets. The client repopulates its mode control from the selected
 * entry instead of offering modes the host would then refuse.
 */
@Serializable
data class PlaygroundCatalogInfo(
  /** The `catalog` value to send back on a run. Empty for the host's pinned default entry. */
  val id: String,
  /** What the selector shows. */
  val label: String,
  /** `desktop` | `android`, or empty for the pinned default (which spans whatever was pinned). */
  val backend: String = "",
  val modes: List<PlaygroundMode> = emptyList(),
  /**
   * True once this catalog's classpath is resolved — the first run against it pays for the unpack.
   */
  val resolved: Boolean = false,
)

/** `GET /api/{version}/compiler/catalogs`: what the editor's catalog selector may offer. */
@Serializable
data class PlaygroundCatalogsResponse(val catalogs: List<PlaygroundCatalogInfo> = emptyList())

/** Severity of a compiler diagnostic, spelled the way the editor's highlight lane expects. */
@Serializable
enum class PlaygroundSeverity {
  @SerialName("error") ERROR,
  @SerialName("warning") WARNING,
  @SerialName("info") INFO,
}

/**
 * One compiler diagnostic. Line/char positions are **0-based** to match CodeMirror (what
 * `kotlin-playground` renders against); a null position is a file-level diagnostic with no anchor.
 */
@Serializable
data class PlaygroundDiagnostic(
  val severity: PlaygroundSeverity,
  val message: String,
  val file: String? = null,
  val line: Int? = null,
  val ch: Int? = null,
  val endLine: Int? = null,
  val endCh: Int? = null,
)

/** A CodeMirror position in the stock `errors`-map shape (0-based line + char). */
@Serializable data class PlaygroundPosition(val line: Int, val ch: Int)

/** A `[start, end)` span in the stock `errors`-map shape. */
@Serializable
data class PlaygroundInterval(val start: PlaygroundPosition, val end: PlaygroundPosition)

/**
 * One diagnostic in the **stock `kotlin-compiler-server`** `errors`-map shape — the wire form a
 * stock `kotlin-playground` frontend reads to draw inline squiggles. Distinct from
 * [PlaygroundDiagnostic] (our flat internal shape): the stock frontend iterates the `errors` map
 * per file and reads `error.interval.start`, so the position must be **nested** under `interval`,
 * and `severity` is the upstream uppercase spelling (`ERROR`/`WARNING`). [PlaygroundErrorsWire]
 * projects our diagnostics into this shape.
 */
@Serializable
data class PlaygroundStockError(
  val interval: PlaygroundInterval,
  val message: String,
  val severity: String,
  val className: String,
)

/**
 * The Stage-1 result.
 *
 * On a clean compile: [diagnostics] carries any warnings, [image] is the first-frame render as a
 * `data:image/png;base64,…` URI, and [previewToken] / [previewUrl] are the handoff to the live
 * Stage-2 session. On a compile error: [diagnostics] carries the errors and **no** token is minted
 * ([previewToken] stays null). [exception] is reserved for a server-side failure that isn't a user
 * compile error (e.g. the render subprocess died).
 *
 * [documentUrl] is the [PlaygroundMode.REMOTE_COMPOSE] terminal instead of a token: the snippet's
 * captured `.rc` document is published as an expiring `/d/<id>` permalink the browser plays
 * client-side (PLAYGROUND.md §3). A run yields **either** a [previewToken] (the live CMP/Android
 * modes) **or** a [documentUrl] (RC) — never both — and [previewToken] stays null on the RC path.
 *
 * [previewId] is the `@Preview` this run actually rendered and tokenized, and [previews] is every
 * `@Preview` the snippet declared — a multi-file snippet can hold several, and only one drives the
 * first frame and the Stage-2 session. Both are additive fields a stock frontend ignores; ours uses
 * them to say *which* preview it drew when a snippet declares more than one.
 *
 * [errors] is the **same** diagnostics projected into the stock `kotlin-compiler-server` wire shape
 * (a map keyed by file name → [PlaygroundStockError]s with nested `interval` positions), so an
 * unmodified `kotlin-playground` frontend can render inline squiggles. Our own frontend reads the
 * richer [diagnostics] instead; both are populated, so neither client is second-class.
 */
@Serializable
data class PlaygroundRunResponse(
  val diagnostics: List<PlaygroundDiagnostic> = emptyList(),
  val errors: Map<String, List<PlaygroundStockError>> = emptyMap(),
  val text: String = "",
  val exception: String? = null,
  val image: String? = null,
  val previewToken: String? = null,
  val previewUrl: String? = null,
  val documentUrl: String? = null,
  val previewId: String? = null,
  val previews: List<String> = emptyList(),
)
