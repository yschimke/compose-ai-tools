package ee.schimke.composeai.screen

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The client half of the preview server's playground contract — `POST /api/{version}/compiler/run`
 * and `GET /api/{version}/compiler/catalogs` (PLAYGROUND.md §4).
 *
 * ### Why this lives in the model and not in the browser app
 *
 * Everything here is a pure function of text: building a request, choosing a target from what a
 * host advertises, reading a response, deciding whether a response is still wanted. The browser app
 * owns exactly one thing this module cannot have — `fetch` — and that is the only part with no
 * test. Putting the decisions here means they are tested on the JVM instead of being verified by
 * clicking.
 *
 * ### What the call actually buys
 *
 * "Does it compile" is the floor. The same response carries [CompileRunResponse.image] — the first
 * frame as a `data:` PNG — and [CompileRunResponse.previewToken], which opens a live interactive
 * session. So compile-checking the generated screen and running it are one request, not two.
 */
public object CompileCheck {

  /**
   * Lenient by construction: the server's response has fields this client does not model (`errors`,
   * `text`, `editLease`, …) and will grow more. Failing to parse a successful compile because the
   * host added a field would be the worst possible reading of a contract that explicitly documents
   * itself as a superset.
   */
  private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
  }

  /** The API version segment the server mounts the shim at; the frontend emits `1`. */
  public const val API_VERSION: String = "1"

  /** The served catalog whose classpath carries `androidx.compose.material3.*`. */
  public const val M3_CATALOG_SYSTEM: String = "compose-m3"

  /** The file name the generated screen is posted under. */
  public const val SOURCE_FILE_NAME: String = "Screen.kt"

  public fun catalogsUrl(host: String): String =
    "${host.trimEnd('/')}/api/$API_VERSION/compiler/catalogs"

  public fun runUrl(host: String): String = "${host.trimEnd('/')}/api/$API_VERSION/compiler/run"

  /**
   * Which catalog and mode to compile the generated screen against, from what the host advertises —
   * or null when this host cannot compile M3 at all.
   *
   * **This is the whole answer to the classpath question, asked of the server instead of assumed.**
   * `confType` selects the *renderer* ([CompileRunRequest.confType] → desktop / Robolectric / RC);
   * what puts `androidx.compose.material3.*` on the compile classpath is the **catalog**. A client
   * that sent only a `confType` would land on the host's pinned default, which may be somebody
   * else's design system — and every reference in the generated file would be unresolved, reported
   * as if the screen were wrong.
   *
   * Returning null rather than falling back is the same choice the server makes for an unknown
   * catalog: "not available here" beats compiling the right source against the wrong classpath and
   * reporting the difference as errors.
   */
  public fun targetFor(
    catalogs: List<CompileCatalogInfo>,
    system: String = M3_CATALOG_SYSTEM,
  ): CompileTarget? {
    val match =
      catalogs.firstOrNull { it.servedSystem == system && it.modes.isNotEmpty() } ?: return null
    // Desktop CMP over the Android/Robolectric lane: it is the cheaper seat (PLAYGROUND.md §3) and
    // the generated screen is plain Material 3 with nothing Android-only in it.
    val mode = match.modes.firstOrNull { it == MODE_CMP } ?: match.modes.first()
    return CompileTarget(catalog = match.id, confType = mode, label = match.label)
  }

  /** The desktop CMP mode's wire spelling — `PlaygroundMode.CMP`'s `@SerialName`. */
  public const val MODE_CMP: String = "compose-cmp"

  public fun parseCatalogs(body: String): List<CompileCatalogInfo> =
    json.decodeFromString<CompileCatalogsResponse>(body).catalogs

  /**
   * The run request body for [source].
   *
   * Posts the source **exactly** as the pane shows it. Reformatting between generating and posting
   * would shift every diagnostic's line by an amount nothing tracks, so the error the compiler
   * reported against line 12 would be drawn against line 11 of a different file.
   */
  public fun requestBody(source: String, target: CompileTarget): String =
    json.encodeToString(
      CompileRunRequest(
        confType = target.confType,
        catalog = target.catalog,
        files = listOf(CompileFile(name = SOURCE_FILE_NAME, text = source)),
      )
    )

  /**
   * The `?compileHost=` value, or null when the feature is off.
   *
   * Only `http:` and `https:` origins are accepted. This value goes straight into a `fetch`, and a
   * crafted query string is attacker-controlled input to a page an operator may have embedded — a
   * `javascript:` or `data:` URL there is a script-injection surface, not a typo. **Absent means
   * the feature is off**, not "use a default host": the browser-only loop is what works today and
   * it must keep working with no server anywhere near it.
   */
  public fun hostFrom(params: Map<String, String>): String? {
    val raw = params["compileHost"]?.trim().orEmpty()
    if (raw.isEmpty()) return null
    if (!raw.startsWith("http://") && !raw.startsWith("https://")) return null
    return raw
  }

  /**
   * Resolves a response's [path] against [host] when it came back relative, as `previewUrl` does.
   */
  public fun absoluteUrl(host: String, path: String): String =
    if (path.startsWith("http://") || path.startsWith("https://")) path
    else host.trimEnd('/') + "/" + path.removePrefix("/")

  /** A response body as the pane's outcome, or [CompileOutcome.Failed] if it is not one. */
  public fun readResponse(body: String): CompileOutcome {
    val parsed =
      try {
        json.decodeFromString<CompileRunResponse>(body)
      } catch (e: Exception) {
        return CompileOutcome.Failed("the host's reply was not a compile result: ${e.message}")
      }
    // `exception` is the server saying *it* failed, not the snippet — a dead render subprocess, a
    // refused lease. Reporting it as a compile error would blame the user's screen for the host.
    parsed.exception
      ?.takeIf { it.isNotBlank() }
      ?.let {
        return CompileOutcome.Failed(it)
      }
    return CompileOutcome.Checked(
      diagnostics = parsed.diagnostics,
      image = parsed.image,
      previewUrl = parsed.previewUrl,
      previewToken = parsed.previewToken,
    )
  }
}

/**
 * A monotonic fence over in-flight checks: every response older than the newest request is dropped.
 *
 * Every keystroke regenerates the source, so several checks can be in flight at once and they do
 * **not** come back in order. Without this, a slow response to an older edit lands last and paints
 * its errors over source that no longer exists — the user deletes the character that broke it, and
 * the error stays.
 *
 * Structured concurrency alone very nearly covers this (cancelling the previous check's coroutine
 * means its continuation never resumes), but "very nearly" is doing real work in that sentence and
 * a reader should not have to derive it. This is one integer and it is checkable in a test.
 */
public class StaleGuard {
  private var issued: Long = 0

  /** Claims the next sequence number for a request about to go out. */
  public fun issue(): Long = ++issued

  /** Whether a response tagged [sequence] is still the one the pane wants. */
  public fun isCurrent(sequence: Long): Boolean = sequence == issued
}

/** Which catalog + renderer the generated screen is compiled against. */
public data class CompileTarget(val catalog: String, val confType: String, val label: String)

/** What one round of the check produced. */
public sealed interface CompileOutcome {
  /** The host answered. [diagnostics] may still contain errors — that is a *successful* check. */
  public data class Checked(
    val diagnostics: List<CompileDiagnostic>,
    val image: String? = null,
    val previewUrl: String? = null,
    val previewToken: String? = null,
  ) : CompileOutcome {
    public val errors: List<CompileDiagnostic>
      get() = diagnostics.filter { it.severity == CompileSeverity.ERROR }

    public val compiles: Boolean
      get() = errors.isEmpty()
  }

  /**
   * The check could not be made, or the host failed on its own account. Never the screen's fault.
   */
  public data class Failed(val message: String) : CompileOutcome
}

@Serializable
public data class CompileFile(
  val name: String,
  val text: String,
  val publicId: String = "",
)

@Serializable
public data class CompileRunRequest(
  val args: String = "",
  val files: List<CompileFile> = emptyList(),
  val confType: String = "",
  val catalog: String = "",
)

@Serializable
public enum class CompileSeverity {
  @SerialName("error") ERROR,
  @SerialName("warning") WARNING,
  @SerialName("info") INFO,
}

/**
 * One compiler diagnostic. Positions are **0-based** line/char, matching CodeMirror and the
 * server's own `PlaygroundDiagnostic`; a null [line] is a file-level diagnostic with no anchor.
 */
@Serializable
public data class CompileDiagnostic(
  val severity: CompileSeverity,
  val message: String,
  val file: String? = null,
  val line: Int? = null,
  val ch: Int? = null,
  val endLine: Int? = null,
  val endCh: Int? = null,
) {
  /** `Screen.kt:13:5` — how the pane names where an error is, 1-based as an editor counts. */
  public fun location(): String? {
    val atLine = line ?: return null
    val name = file ?: CompileCheck.SOURCE_FILE_NAME
    val atCh = ch
    return if (atCh == null) "$name:${atLine + 1}" else "$name:${atLine + 1}:${atCh + 1}"
  }
}

@Serializable
public data class CompileRunResponse(
  val diagnostics: List<CompileDiagnostic> = emptyList(),
  val exception: String? = null,
  /** The first frame as a `data:image/png;base64,…` URI. Null on a failed compile. */
  val image: String? = null,
  val previewToken: String? = null,
  val previewUrl: String? = null,
)

/**
 * One entry the host offers (`GET /api/{version}/compiler/catalogs`).
 *
 * [system] is the served design system and the field to match on; [id] is what goes back on a run
 * and can be module-qualified when one repository serves several targets.
 */
@Serializable
public data class CompileCatalogInfo(
  val id: String = "",
  val label: String = "",
  val backend: String = "",
  val modes: List<String> = emptyList(),
  val resolved: Boolean = false,
  val system: String = "",
  val module: String = "",
) {
  /**
   * The served system this entry belongs to.
   *
   * The server defaults `system` to `id` and a host that omits the field means exactly that, so
   * resolve it here rather than matching against an empty string and concluding the host offers no
   * M3.
   */
  public val servedSystem: String
    get() = system.ifEmpty { id }
}

@Serializable
public data class CompileCatalogsResponse(val catalogs: List<CompileCatalogInfo> = emptyList())
