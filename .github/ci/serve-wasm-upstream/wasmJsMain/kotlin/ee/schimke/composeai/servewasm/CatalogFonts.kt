package ee.schimke.composeai.servewasm

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * The typefaces the native catalog lane composes with.
 *
 * The lane substitutes its own compiled composables for the published snapshots, so it has to draw
 * text with the same faces the snapshot renderer rasterized — otherwise metrics, wrapping and every
 * typography-themed variant differ in the *default* lane, which for a parity tool is the
 * measurement itself drifting rather than a cosmetic gap (#4821).
 *
 * Composing without them fell back to the CMP bundled font. The manifest and the files are the same
 * ones `:samples:cmp-wasm-catalog` loads upstream and the same ones the offline parity harness
 * registers — here they are the repository's own `assets/rc-fonts`, packaged into the frontend
 * bundle as `fonts/` by `wasmFrontendDist`, so there is one vendored copy rather than two.
 */
internal sealed interface CatalogFonts {
  /**
   * Nothing has been decided yet: the caller must not compose text against a font it will replace.
   */
  data object Loading : CatalogFonts

  /**
   * Resolved. A null [family] is the honest "the fetch failed or timed out" answer, and composes
   * with the CMP bundled font exactly as this lane did before — degraded, but never blocked.
   */
  data class Ready(
    val family: FontFamily? = null,
    val generics: Map<String, FontFamily> = emptyMap(),
    val named: Map<String, FontFamily> = emptyMap(),
  ) : CatalogFonts
}

/** One font file declared by `fonts.json`, flattened out of its family entry. */
internal data class ManifestFont(
  val role: String,
  val family: String,
  val file: String,
  val weight: Int,
  val italic: Boolean,
)

/**
 * Parse a `fonts.json` manifest into its flattened faces.
 *
 * Top-level and `internal` so the rules can be tested without a browser: this is the half of the
 * loader that has rules, and the half where a dropped entry looks like a working render.
 *
 * Every field is defended rather than trusted — a malformed entry is skipped, not fatal, because
 * one bad face must not cost the catalog every other family it could have loaded.
 */
internal fun parseFontsManifest(raw: String): List<ManifestFont> {
  val root =
    runCatching { Json { ignoreUnknownKeys = true }.parseToJsonElement(raw) as? JsonObject }
      .getOrNull() ?: return emptyList()
  // `as?` at every field, never `.jsonPrimitive` / `.jsonArray`. Those accessors THROW on a
  // wrong-typed element, and the only `runCatching` in this path is `loadCatalogFonts`'s, which
  // wraps the whole call: one family whose `name` was an object, or whose `fonts` was not an array,
  // therefore collapsed the entire manifest to `emptyList()` and cost the catalog every other
  // family. That is precisely the "skipped, not fatal" this function's contract promises, so it has
  // to hold per entry rather than per parse.
  val families = root["families"] as? JsonArray ?: return emptyList()
  return families.flatMap { entry ->
    val family = entry as? JsonObject ?: return@flatMap emptyList()
    val name = (family["name"] as? JsonPrimitive)?.contentOrNull.orEmpty()
    val role = (family["role"] as? JsonPrimitive)?.contentOrNull.orEmpty()
    if (role.isEmpty()) return@flatMap emptyList()
    val fonts = family["fonts"] as? JsonArray ?: return@flatMap emptyList()
    fonts.mapNotNull { value ->
      val font = value as? JsonObject ?: return@mapNotNull null
      val file =
        (font["file"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
          ?: return@mapNotNull null
      ManifestFont(
        role = role,
        family = name,
        file = file,
        // Absent weight is Regular, which is what a single-face family means by omitting it.
        weight = (font["weight"] as? JsonPrimitive)?.intOrNull ?: 400,
        italic = (font["italic"] as? JsonPrimitive)?.booleanOrNull ?: false,
      )
    }
  }
}

/**
 * Fetch every family the manifest declares, from [base] on this same origin.
 *
 * Fail-soft in three independent layers, because the alternative to a partial load is no text
 * parity at all:
 * * a manifest that will not fetch or parse yields [CatalogFonts.Ready] with nothing, i.e. today's
 *   bundled-font behaviour;
 * * each *family* loads in isolation, so a 404'd generic cannot take the already-loadable default
 *   down with it and cost every component its metrics;
 * * the caller applies its own timeout, so a hung fetch degrades rather than blocking the reveal.
 */
@OptIn(ExperimentalEncodingApi::class)
internal suspend fun loadCatalogFonts(
  base: String = FONTS_BASE,
  fetchText: suspend (String) -> String,
  fetchBase64: suspend (String) -> String,
): CatalogFonts.Ready {
  val entries = runCatching {
    parseFontsManifest(fetchText(base + "fonts.json"))
  }
    .getOrDefault(emptyList())
  if (entries.isEmpty()) return CatalogFonts.Ready()

  suspend fun load(faces: List<ManifestFont>): FontFamily =
    FontFamily(
      faces.map { face ->
        Font(
          identity = face.file,
          data = Base64.decode(fetchBase64(base + face.file)),
          weight = FontWeight(face.weight),
          style = if (face.italic) FontStyle.Italic else FontStyle.Normal,
        )
      }
    )

  suspend fun familyOrNull(faces: List<ManifestFont>): FontFamily? = runCatching {
    load(faces)
  }
    .getOrNull()

  // `role: "default"` is the whole M3 type scale — the one that decides whether a button's label
  // wraps where the snapshot's does.
  val default =
    entries.filter { it.role == "default" }.takeIf { it.isNotEmpty() }?.let { familyOrNull(it) }

  val generics = mutableMapOf<String, FontFamily>()
  entries
    .filter { it.role == "generic" && it.family.isNotEmpty() }
    .groupBy { it.family }
    .forEach { (name, faces) -> familyOrNull(faces)?.let { generics[name] = it } }

  // Named downloadable-GoogleFont substitutes, keyed by the display name `namedFontFamily` looks
  // up (`Orbitron`, `Space Grotesk`, …).
  val named = mutableMapOf<String, FontFamily>()
  entries
    .filter { it.role == "named" && it.family.isNotEmpty() }
    .groupBy { it.family }
    .forEach { (name, faces) -> familyOrNull(faces)?.let { named[name] = it } }

  return CatalogFonts.Ready(default, generics, named)
}

/**
 * Where the faces are served from, relative to the frontend bundle.
 *
 * Same origin as the page, so no CORS and no `?fontsBase=` escape hatch: this frontend is served by
 * the server that hosts the fonts, which is the difference between it and the standalone catalog
 * that runs in a sandboxed iframe with an opaque origin.
 */
internal const val FONTS_BASE: String = "fonts/"

/** How long the catalog waits before composing with the bundled font instead. */
internal const val FONT_LOAD_TIMEOUT_MS: Long = 8_000L
