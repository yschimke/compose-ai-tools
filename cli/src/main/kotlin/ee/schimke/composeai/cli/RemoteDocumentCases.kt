package ee.schimke.composeai.cli

import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.RemoteComposeOverride
import ee.schimke.composeai.daemon.protocol.RemoteComposePlayerKind
import ee.schimke.composeai.daemon.protocol.RemoteComposeProfile
import ee.schimke.composeai.daemon.protocol.RemoteNamedValue
import ee.schimke.composeai.daemon.protocol.UiMode
import java.io.File
import java.util.Base64
import kotlin.math.roundToInt
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * One Remote Compose document to render with its own data: the input of `render-cases`. A producer
 * that knows what to draw (Livery's `cases.json`, a design tool's export) writes a list of these;
 * `render-cases` plays each on the daemon's Android lane as a carried document
 * (`overrides.remoteCompose.documentBase64`), so it draws on the platform's own players.
 *
 * @property id names the case's PNG (`<id>.png`, `/` making directories); `case-<n>` when absent.
 * @property namedValues bound before the first frame, by their names in the document.
 * @property skipped values that have no daemon type (an image reference), named for the report.
 */
internal data class DocumentCase(
  val id: String,
  val document: ByteArray,
  val widthDp: Int,
  val heightDp: Int,
  val dark: Boolean?,
  val profile: RemoteComposeProfile?,
  val namedValues: Map<String, RemoteNamedValue>,
  val skipped: List<String>,
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
      namedValues == other.namedValues &&
      skipped == other.skipped

  override fun hashCode(): Int = id.hashCode() * 31 + document.contentHashCode()
}

/**
 * Reads a cases file:
 * ```
 * { "cases": [ {
 *     "id": "banner/in-app/default/light",          // optional
 *     "document": "documents/banner/in-app.rc",     // relative to the file
 *     "widthDp": 360, "heightDp": 160,
 *     "dark": false,                                 // optional
 *     "profile": "androidx7",                        // optional; else from "target"
 *     "target": { "profiles": ["androidx"], "rcApiLevel": 7 },
 *     "values": { "headline": { "type": "string", "value": "Hello" },
 *                 "theme.primary": { "type": "color", "argb": 4284960932 } }
 * } ] }
 * ```
 *
 * A value's type is `type` or `kind`: `string`, `int`, `long`, `float`, `dp`, `bool`, `color`
 * (`argb` as a number or `#AARRGGBB`). Anything else, such as an `image` reference, is skipped and
 * reported. Unknown fields are ignored, so a producer can carry its own (Livery's `design`,
 * `background`).
 */
internal object RemoteDocumentCases {
  private val json = Json { ignoreUnknownKeys = true }

  class Invalid(message: String) : Exception(message)

  fun read(file: File): List<DocumentCase> {
    val root = runCatching {
      json.parseToJsonElement(file.readText()).jsonObject
    }
      .getOrElse { throw Invalid("${file.path} is not a JSON object: ${it.message}") }
    val cases = root["cases"] as? JsonArray ?: throw Invalid("${file.path} has no \"cases\" array")
    val base = file.absoluteFile.parentFile
    return cases.mapIndexed { i, element ->
      val case = element as? JsonObject ?: throw Invalid("case $i is not an object")
      parse(case, i, base)
    }
  }

  fun parse(case: JsonObject, index: Int, base: File): DocumentCase {
    fun str(key: String) = (case[key] as? JsonPrimitive)?.contentOrNull
    fun int(key: String) = (case[key] as? JsonPrimitive)?.intOrNull
    val id = str("id")?.takeIf { it.isNotBlank() } ?: "case-$index"
    val path = str("document") ?: throw Invalid("case $id has no \"document\"")
    val document = File(path).let { if (it.isAbsolute) it else File(base, path) }
    if (!document.isFile) throw Invalid("case $id: no document at ${document.path}")
    val width =
      int("widthDp")?.takeIf { it > 0 } ?: throw Invalid("case $id needs a positive widthDp")
    val height =
      int("heightDp")?.takeIf { it > 0 } ?: throw Invalid("case $id needs a positive heightDp")
    val skipped = mutableListOf<String>()
    val values =
      (case["values"] as? JsonObject).orEmpty().mapNotNull { (name, v) ->
        namedValue(v as? JsonObject)?.let { name to it }
          ?: run {
            skipped += name
            null
          }
      }
    return DocumentCase(
      id = id,
      document = document.readBytes(),
      widthDp = width,
      heightDp = height,
      dark = (case["dark"] as? JsonPrimitive)?.booleanOrNull,
      profile =
        str("profile")?.let(::profileOf) ?: (case["target"] as? JsonObject)?.let(::profileOf),
      namedValues = values.toMap(),
      skipped = skipped,
    )
  }

  /** A typed value, or null when the daemon has no type for it. */
  internal fun namedValue(v: JsonObject?): RemoteNamedValue? {
    if (v == null) return null
    val type = (v["type"] ?: v["kind"])?.jsonPrimitive?.contentOrNull ?: return null
    val value = v["value"] as? JsonPrimitive
    return when (type) {
      "string" -> value?.contentOrNull?.let { RemoteNamedValue.StringValue(it) }
      "int" -> value?.intOrNull?.let { RemoteNamedValue.IntValue(it) }
      "long" ->
        value
          ?.longOrNull
          ?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }
          ?.let { RemoteNamedValue.IntValue(it.toInt()) }
      "float" -> value?.floatOrNull?.let { RemoteNamedValue.FloatValue(it) }
      "dp" -> value?.floatOrNull?.let { RemoteNamedValue.DpValue(it) }
      "bool" -> value?.booleanOrNull?.let { RemoteNamedValue.BooleanValue(it) }
      "color" -> argb(v["argb"] as? JsonPrimitive ?: value)?.let { RemoteNamedValue.ColorValue(it) }
      else -> null
    }
  }

  /**
   * `#AARRGGBB` from a number (ARGB as an unsigned 32-bit value) or a `#AARRGGBB` / `#RRGGBB`
   * string.
   */
  internal fun argb(p: JsonPrimitive?): String? {
    if (p == null) return null
    p.longOrNull?.let {
      return "#%08X".format(it and 0xFFFFFFFFL)
    }
    val s = p.contentOrNull?.trim() ?: return null
    return when {
      Regex("#[0-9A-Fa-f]{8}").matches(s) -> s.uppercase()
      Regex("#[0-9A-Fa-f]{6}").matches(s) -> "#FF" + s.substring(1).uppercase()
      else -> null
    }
  }

  /** A daemon profile from its wire name, or null. */
  internal fun profileOf(wire: String): RemoteComposeProfile? =
    RemoteComposeProfile.entries.firstOrNull { it.wireName().equals(wire, ignoreCase = true) }

  /**
   * A daemon profile from a target's operation profiles (`androidx`, `widgets`, `glance-wear`) and
   * its `rcApiLevel`: the platform player that target plays on.
   */
  internal fun profileOf(target: JsonObject): RemoteComposeProfile? {
    val profiles =
      (target["profiles"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
    val level = (target["rcApiLevel"] as? JsonPrimitive)?.intOrNull
    return when {
      "glance-wear" in profiles || "wear-widgets" in profiles -> RemoteComposeProfile.WEAR_WIDGETS
      "widgets" in profiles ->
        if (level != null && level <= 6) RemoteComposeProfile.WIDGETS_V6
        else RemoteComposeProfile.WIDGETS_V7
      "androidx" in profiles ->
        when (level) {
          7 -> RemoteComposeProfile.ANDROIDX7
          8 -> RemoteComposeProfile.ANDROIDX8
          9 -> RemoteComposeProfile.ANDROIDX9
          else -> RemoteComposeProfile.ANDROIDX
        }
      else -> null
    }
  }

  private fun RemoteComposeProfile.wireName(): String =
    json.encodeToJsonElement(RemoteComposeProfile.serializer(), this).jsonPrimitive.content

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
