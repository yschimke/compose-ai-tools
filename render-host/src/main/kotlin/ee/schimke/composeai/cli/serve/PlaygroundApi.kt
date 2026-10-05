package ee.schimke.composeai.cli.serve

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Wire types for the playground REST surface
// ([docs/design/PLAYGROUND.md](../../../../../../../../docs/design/PLAYGROUND.md)). A superset of
// `kotlin-compiler-server`'s `/api/{version}/compiler/run` contract, so a stock `kotlin-playground`
// frontend works unmodified and ignores the additive fields.

/** Which renderer + permalink target a snippet compiles for. See PLAYGROUND.md §3. */
@Serializable
public enum class PlaygroundMode {
  /** Compose Multiplatform, desktop (Skiko) daemon → live streaming session. */
  @SerialName("compose-cmp") CMP,
  /** Jetpack Compose, Robolectric daemon → live streaming session. */
  @SerialName("compose-android") ANDROID,
  /** Snippet emits a RemoteDocument → `/d/<id>` document permalink, played client-side. */
  @SerialName("remote-compose") REMOTE_COMPOSE;

  public companion object {
    /**
     * Resolve the request's `confType`. Unknown values, including the stock playground's in-browser
     * targets, map to [CMP]: the one mode a from-source box can always serve.
     */
    public fun fromConfType(confType: String?): PlaygroundMode =
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
 * One editor file in a run request. All files compile together as one module; names are sanitised
 * by [PlaygroundCompileService]. `publicId` is carried through but unused server-side.
 */
@Serializable
public data class PlaygroundFile(val name: String, val text: String, val publicId: String = "")

/**
 * A Stage-1 run request, mirroring the `kotlin-compiler-server` body. [args] is ignored; the mode
 * comes from [confType] via [PlaygroundMode.fromConfType].
 */
@Serializable
public data class PlaygroundRunRequest(
  val args: String = "",
  val files: List<PlaygroundFile> = emptyList(),
  val confType: String = "",
  /**
   * Which served catalog to compile against; empty means the host's pinned default. An unknown id
   * is refused rather than silently compiled against a different design system.
   */
  val catalog: String = "",
  /**
   * Optional single-user editing lease. Empty keeps the original stateless, one-shot compile.
   * Non-empty values are accepted only from the authenticated owner that acquired the lease.
   */
  val editLease: String = "",
  /** Monotonic client revision within [editLease]. Required to increase on every leased compile. */
  val revision: Long = 0,
)

/** Result of explicitly acquiring the host's one stateful Playground editing lease. */
@Serializable
public data class PlaygroundEditLeaseResponse(
  val acquired: Boolean,
  val lease: String? = null,
  val expiresAtEpochMs: Long? = null,
  /** Last revision accepted by this lease, so a reattached editor can continue monotonically. */
  val revision: Long = 0,
  val message: String,
)

/** Body for `POST /api/{version}/compiler/edit-lease`. [client] is unique to one browser tab. */
@Serializable public data class PlaygroundEditLeaseAcquireRequest(val client: String = "")

/** Body for `POST /api/{version}/compiler/edit-lease/release`. */
@Serializable
public data class PlaygroundEditLeaseReleaseRequest(
  val lease: String = "",
  /** Empty preserves the original whole-lease release contract for non-browser API callers. */
  val client: String = "",
)

/**
 * One entry in the editor's catalog selector (`GET /api/{version}/compiler/catalogs`). [modes]
 * depends on the catalog's backend, so the client only offers modes the host will accept.
 */
@Serializable
public data class PlaygroundCatalogInfo(
  /** The `catalog` value to send back on a run. Empty for the host's pinned default entry. */
  val id: String,
  /** What the selector shows. */
  val label: String,
  /** `desktop` | `android`, or empty for the pinned default (which spans whatever was pinned). */
  val backend: String = "",
  val modes: List<PlaygroundMode> = emptyList(),
  /** True once this catalog's classpath is resolved; the first run against it pays the unpack. */
  val resolved: Boolean = false,
  /** Served catalog system, distinct from a module-qualified [id]. */
  val system: String = id,
  /** Owning Gradle module when this entry is one target in a repository-wide catalog. */
  val module: String = "",
)

/** `GET /api/{version}/compiler/catalogs`: what the editor's catalog selector may offer. */
@Serializable
public data class PlaygroundCatalogsResponse(
  val catalogs: List<PlaygroundCatalogInfo> = emptyList()
)

/** Severity of a compiler diagnostic, spelled the way the editor's highlight lane expects. */
@Serializable
public enum class PlaygroundSeverity {
  @SerialName("error") ERROR,
  @SerialName("warning") WARNING,
  @SerialName("info") INFO,
}

/**
 * One compiler diagnostic. Line/char positions are **0-based** to match CodeMirror (what
 * `kotlin-playground` renders against); a null position is a file-level diagnostic with no anchor.
 */
@Serializable
public data class PlaygroundDiagnostic(
  val severity: PlaygroundSeverity,
  val message: String,
  val file: String? = null,
  val line: Int? = null,
  val ch: Int? = null,
  val endLine: Int? = null,
  val endCh: Int? = null,
)

/** A CodeMirror position in the stock `errors`-map shape (0-based line + char). */
@Serializable public data class PlaygroundPosition(val line: Int, val ch: Int)

/** A `[start, end)` span in the stock `errors`-map shape. */
@Serializable
public data class PlaygroundInterval(val start: PlaygroundPosition, val end: PlaygroundPosition)

/**
 * One diagnostic in the stock `kotlin-compiler-server` `errors`-map shape, which needs the position
 * nested under `interval` and an uppercase `severity`. Projected by [PlaygroundErrorsWire].
 */
@Serializable
public data class PlaygroundStockError(
  val interval: PlaygroundInterval,
  val message: String,
  val severity: String,
  val className: String,
)

/**
 * The Stage-1 result. A clean compile carries warnings in [diagnostics], the first frame in [image]
 * (a `data:` URI) and the live handoff in [previewToken] / [previewUrl]; Remote Compose mode
 * returns a [documentUrl] permalink instead of a token. A compile error mints no token, and
 * [exception] is only for server-side failures.
 *
 * [previewId] is the `@Preview` that was rendered, out of every one in [previews]. [errors] holds
 * the same diagnostics in the stock wire shape, for unmodified frontends.
 */
@Serializable
public data class PlaygroundRunResponse(
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
  /** Echoed only for stateful editing compiles. */
  val editLease: String? = null,
  /** The accepted stateful revision. Null on the original one-shot path. */
  val revision: Long? = null,
  /** True when this revision used BTA incremental compilation rather than the full fallback. */
  val incremental: Boolean = false,
)

/**
 * `GET /usage/{previewId}`: the plain-Compose usage code behind one catalog card (the viewer's
 * Source panel). Fetched lazily because it can cost a GitHub read ([PlaygroundSeedResolver]).
 */
@Serializable
public data class UsageSnippetResponse(
  /** The cleaned Kotlin. */
  val text: String,
  /** The declaration the card's render comes from, for the panel's caption. */
  val entryFunction: String? = null,
  /**
   * True when the catalog declared what its own helpers mean. False ⇒ only the shared annotations
   * came off and the catalog's own helpers are still in [text]; the panel says so rather than
   * presenting machinery as usage.
   */
  val scaffoldsDeclared: Boolean = false,
  /** Declared scaffolding that survived, so the panel can name what will not resolve. */
  val residue: List<String> = emptyList(),
  /** The preview's source on GitHub — "the whole sticker" for anyone who wants it. */
  val blobUrl: String? = null,
  /** Where "open in playground" goes, so the panel and the provenance row cannot disagree. */
  val playgroundHref: String? = null,
  /**
   * Reference pages for the platform APIs [text] imports ([ApiDocLinks]), composables first in the
   * order the code names them.
   */
  val apiDocs: List<ApiDocLink> = emptyList(),
)

/** One entry of [UsageSnippetResponse.apiDocs]: the wire shape of [ApiDocLinks.Link]. */
@Serializable
public data class ApiDocLink(
  /** The name as the snippet writes it — an `as` alias where the code renamed one. */
  val name: String,
  /** The imported fully-qualified name, shown as the link's tooltip. */
  val fqn: String,
  /** True when this resolved to a top-level `@Composable`, which the panel groups separately. */
  val composable: Boolean = false,
  /** The `developer.android.com` reference page. */
  val url: String,
)
