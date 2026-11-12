package ee.schimke.composeai.cli

import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.RemoteComposeOverride
import ee.schimke.composeai.daemon.protocol.RemoteComposePlayerKind
import ee.schimke.composeai.daemon.protocol.RemoteComposeProfile
import ee.schimke.composeai.daemon.protocol.RemoteDocumentCasesV1
import ee.schimke.composeai.daemon.protocol.RemoteNamedValue
import ee.schimke.composeai.daemon.protocol.UiMode
import java.io.File
import java.util.Base64
import kotlin.math.roundToInt
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * One Remote Compose document to render with its own data: the input of `render-cases`. A producer
 * that knows what to draw (Livery's `cases.json`, a design tool's export) writes a list of these;
 * `render-cases` plays each on the daemon's Android lane as a carried document
 * (`overrides.remoteCompose.documentBase64`), so it draws on the platform's own players.
 *
 * @property id names the case's PNG (`<id>.png`, `/` making directories); `case-<n>` when absent.
 * @property namedValues bound before the first frame, by their names in the document.
 */
internal data class DocumentCase(
  val id: String,
  val document: ByteArray,
  val widthDp: Int,
  val heightDp: Int,
  val dark: Boolean?,
  val profile: RemoteComposeProfile?,
  val namedValues: Map<String, RemoteNamedValue>,
) {
  /** The render's overrides: the carried document, its values and profile, at its size and mode. */
  fun toOverrides(
    density: Float,
    player: RemoteComposePlayerKind? = null,
    playerId: String? = null,
  ): PreviewOverrides =
    PreviewOverrides(
      widthPx = (widthDp * density).roundToInt(),
      heightPx = (heightDp * density).roundToInt(),
      density = density,
      uiMode = dark?.let { if (it) UiMode.DARK else UiMode.LIGHT },
      remoteCompose =
        RemoteComposeOverride.Builder()
          .also {
            it.documentBase64 = Base64.getEncoder().encodeToString(document)
            it.namedValues = namedValues
            it.profile = profile
            it.player = player
            it.playerId = playerId
          }
          .build(),
    )

  override fun equals(other: Any?): Boolean =
    other is DocumentCase &&
      id == other.id &&
      document.contentEquals(other.document) &&
      widthDp == other.widthDp &&
      heightDp == other.heightDp &&
      dark == other.dark &&
      profile == other.profile &&
      namedValues == other.namedValues

  override fun hashCode(): Int = id.hashCode() * 31 + document.contentHashCode()
}

/**
 * Reads a cases file: compose-preview-contracts' `RemoteDocumentCasesV1`
 * (`compose-preview/remote-document-cases/v1`), in the daemon's own vocabulary:
 * ```
 * { "schema": "compose-preview/remote-document-cases/v1",
 *   "cases": [ { "id": "banner/in-app/default/dark",
 *                "document": "documents/banner/in-app.rc",   // relative to the file
 *                "widthDp": 360, "heightDp": 160, "dark": true, "profile": "androidx7",
 *                "namedValues": { "headline": { "kind": "string", "value": "Hello" },
 *                                 "theme.primary": { "kind": "color", "argb": "#FF6750A4" } } } ] }
 * ```
 *
 * Values are [RemoteNamedValue]s and the profile a [RemoteComposeProfile], decoded with their own
 * serializers. Unknown fields are ignored, so a producer may carry its own.
 */
internal object RemoteDocumentCases {
  const val SCHEMA = RemoteDocumentCasesV1.SCHEMA

  private val json = Json { ignoreUnknownKeys = true }

  class Invalid(message: String) : Exception(message)

  fun read(file: File): List<DocumentCase> {
    val element = runCatching {
      json.parseToJsonElement(file.readText())
    }
      .getOrElse { throw Invalid("${file.path} is not JSON: ${it.message}") }
    // The contract defaults `schema`, so check that the file itself names it.
    val schema =
      (element as? JsonObject)?.get("schema")?.let { (it as? JsonPrimitive)?.contentOrNull }
    if (schema != SCHEMA) throw Invalid("${file.path} is \"$schema\", not $SCHEMA")
    val parsed = runCatching {
      json.decodeFromJsonElement(RemoteDocumentCasesV1.serializer(), element)
    }
      .getOrElse { throw Invalid("${file.path} is not a $SCHEMA file: ${it.message}") }
    val base = file.absoluteFile.parentFile
    return parsed.cases.map { c ->
      if (c.id.isBlank()) throw Invalid("a case has a blank id")
      if (c.widthDp <= 0 || c.heightDp <= 0) throw Invalid("case ${c.id} needs a positive size")
      val document = File(c.document).let { if (it.isAbsolute) it else File(base, c.document) }
      if (!document.isFile) throw Invalid("case ${c.id}: no document at ${document.path}")
      DocumentCase(
        id = c.id,
        document = document.readBytes(),
        widthDp = c.widthDp,
        heightDp = c.heightDp,
        dark = c.dark,
        profile = c.profile,
        namedValues = c.namedValues,
      )
    }
  }

  /**
   * Why some cases can't each have a PNG of their own: two ids that sanitise to one [pngPath]
   * (`case#1` and `case_1`), or one that would be written over by an output in [reserved] (the
   * contact sheet). Empty when every case's image is its own.
   */
  internal fun pathCollisions(cases: List<DocumentCase>, reserved: Set<String>): List<String> {
    val byPath = cases.groupBy { pngPath(it.id) }
    return byPath
      .filterValues { it.size > 1 }
      .map { (path, same) ->
        "cases ${same.joinToString { "'${it.id}'" }} would all be written to $path; give them distinct ids"
      } +
      byPath.keys
        .filter { it in reserved }
        .map { path ->
          "case '${byPath.getValue(path).first().id}' would be written to $path, which render-cases writes itself"
        } +
      nested(byPath.keys, reserved).map { (file, under) ->
        val owner = byPath[file]?.first()?.id?.let { "case '$it'" } ?: "render-cases' own $file"
        "case '${byPath.getValue(under).first().id}' would need $file as a directory, but $owner writes it as a file"
      }
  }

  /**
   * Pairs (file, path) where a case's [path] lies under a directory that is another output's file:
   * ids `a` and `a.png/b` both need `a.png`, once as a file and once as a directory, and an id
   * under `render-cases.json/` would shadow the summary.
   */
  private fun nested(casePaths: Set<String>, reserved: Set<String>): List<Pair<String, String>> {
    val files = casePaths + reserved
    return casePaths.flatMap { path ->
      val segments = path.split('/')
      (1 until segments.size)
        .map { segments.take(it).joinToString("/") }
        .filter { it in files }
        .map { it to path }
    }
  }

  /**
   * [id] as a relative PNG path: each `/`-separated segment kept to `[A-Za-z0-9._-]`, never `..`.
   */
  internal fun pngPath(id: String): String =
    id
      .split('/')
      .filter { it.isNotEmpty() }
      .joinToString("/") { seg ->
        seg.replace(Regex("[^A-Za-z0-9._-]"), "_").let { if (it == "." || it == "..") "_" else it }
      }
      .ifEmpty { "case" } + ".png"
}
