package ee.schimke.composeai.renderer

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.isSpecified
import ee.schimke.composeai.io.SystemFileSystem
import java.io.File
import java.util.Locale
import okio.FileSystem
import okio.Path.Companion.toPath

/**
 * Per-sheet structured-token sidecar for `@ColorCatalog` / `@TypographyCatalog` / `@ThemeCatalog`
 * renders. Sibling of the specimen PNG, placed under
 * `<renders-parent>/data/catalog-tokens/<sanitized-id>.catalog.json` — the same
 * `<outputDir>/data/<kind>/` convention [NotificationSidecar] uses.
 *
 * Where the PNG shows the tokens as *pixels*, this carries their **resolved values** — the exact
 * `#AARRGGBB` a `@ColorCatalog` colour reflects to, and the resolved `fontSize` / `fontWeight` /
 * metrics a `@TypographyCatalog` `TextStyle` reflects to. That turns an annotation-declared palette
 * or type scale into importable data (design-parity's `catalog-export`, the `design-artifacts`
 * kits), not just a viewable sheet — see issue #2167. For the static token catalogs ([write]) the
 * values come from the very reflection the specimen sheet runs ([CatalogValueReflection]); for a
 * `@ThemeCatalog` theme ([writeResolved], issue #2179) they come from the live
 * `MaterialTheme.colorScheme` / `.typography` the wrapper resolved to *inside* composition, keyed
 * by theme so each becomes a Figma variable mode downstream.
 *
 * Hand-rolled JSON, best-effort — same rationale as [NotificationSidecar]: the renderer-android
 * runtime classpath deliberately omits `kotlinx-serialization`, and a per-token failure must not
 * derail the PNG render path. The group is implicit in [previewId] (`typographycatalog__Display`),
 * so it isn't repeated per token.
 */
internal object CatalogTokenSidecar {

  const val SCHEMA = "compose-preview-catalog-tokens/v1"

  /**
   * `<outputDir>/data/catalog-tokens/<id>.catalog.json`, mirroring [NotificationSidecar.pathFor].
   */
  fun pathFor(rendersDir: File, previewId: String): File =
    File(
      File(rendersDir.parentFile ?: rendersDir, "data/catalog-tokens"),
      sanitize(previewId) + ".catalog.json",
    )

  /**
   * Write the resolved-token sidecar for [previewId] from its [tokens]. Resolves the output dir
   * from the `composeai.render.outputDir` system property the PNG path uses; silently no-ops when
   * it's unset (unit-test invocations that don't run through the plugin's render task) or when
   * [tokens] is empty. A token whose value can't be reflected is skipped, not fatal.
   */
  fun write(
    previewId: String,
    tokens: List<CatalogToken>,
    fileSystem: FileSystem = SystemFileSystem,
  ) {
    if (tokens.isEmpty()) return
    try {
      val rendersDirPath = System.getProperty("composeai.render.outputDir") ?: return
      val json = buildJson(previewId, tokens) ?: return
      val sidecar = pathFor(File(rendersDirPath), previewId)
      sidecar.parentFile?.mkdirs()
      fileSystem.write(sidecar.path.toPath()) { writeUtf8(json) }
    } catch (e: Throwable) {
      System.err.println("Failed to write catalog-token sidecar for $previewId: ${e.message}")
    }
  }

  /**
   * A single composition-resolved catalog token — a Material 3 role/style [label] paired with the
   * value the live theme resolved it to. Sibling of [CatalogToken], but carrying the *value*
   * directly instead of a `className`/`member` to reflect, because a `@ThemeCatalog` theme's tokens
   * only exist inside composition (`MaterialTheme.colorScheme` / `.typography`), not as static
   * vals.
   */
  sealed interface ResolvedToken {
    val label: String

    data class Colour(override val label: String, val color: Color) : ResolvedToken

    data class Type(override val label: String, val style: TextStyle) : ResolvedToken
  }

  /**
   * Theme-catalog variant of [write] (issue #2179). The [tokens] are already resolved from the live
   * composition — the `MaterialTheme.colorScheme` roles and `MaterialTheme.typography` styles the
   * `@ThemeCatalog` provider produced — so no reflection is involved; the specimen sheet read them
   * to paint the swatches, this serialises the same values. Carries the theme's display [themeName]
   * at the top level so a detached reader keys each token set by theme — the per-theme axis
   * design-parity's `catalog-export` maps onto a Figma variable mode (#2179 step 5). Same location,
   * schema, and best-effort discipline as [write]; silently no-ops when the output dir is unset or
   * [tokens] is empty.
   */
  fun writeResolved(
    previewId: String,
    themeName: String,
    tokens: List<ResolvedToken>,
    fileSystem: FileSystem = SystemFileSystem,
  ) {
    if (tokens.isEmpty()) return
    try {
      val rendersDirPath = System.getProperty("composeai.render.outputDir") ?: return
      val json = buildResolvedJson(previewId, themeName, tokens) ?: return
      val sidecar = pathFor(File(rendersDirPath), previewId)
      sidecar.parentFile?.mkdirs()
      fileSystem.write(sidecar.path.toPath()) { writeUtf8(json) }
    } catch (e: Throwable) {
      System.err.println("Failed to write theme-catalog token sidecar for $previewId: ${e.message}")
    }
  }

  /** Returns the theme sidecar JSON, or null when nothing serialised (nothing worth writing). */
  private fun buildResolvedJson(
    previewId: String,
    themeName: String,
    tokens: List<ResolvedToken>,
  ): String? {
    val entries = tokens.map { resolvedTokenJson(it) }
    if (entries.isEmpty()) return null
    val sb = StringBuilder()
    sb.append('{')
    sb.append("\"schema\":").append(jsonString(SCHEMA)).append(',')
    sb.append("\"previewId\":").append(jsonString(previewId)).append(',')
    sb.append("\"theme\":").append(jsonString(themeName)).append(',')
    sb.append("\"tokens\":[")
    entries.forEachIndexed { i, e ->
      if (i > 0) sb.append(',')
      sb.append(e)
    }
    sb.append("]}")
    return sb.toString()
  }

  /**
   * One resolved token object. Mirrors [tokenJson]'s shape minus the `className`/`member`
   * reflection coordinates (a composition-resolved token has none), so a reader handles both
   * sidecar flavours uniformly: `{ "label", "kind", "color"|"textStyle" }`.
   */
  private fun resolvedTokenJson(token: ResolvedToken): String {
    val value =
      when (token) {
        is ResolvedToken.Colour -> "\"kind\":\"COLOR\"," + colorJson(token.color)
        is ResolvedToken.Type -> "\"kind\":\"TEXT_STYLE\"," + textStyleJson(token.style)
      }
    return "{\"label\":${jsonString(token.label)},$value}"
  }

  /** Returns the sidecar JSON, or null when no token resolved (nothing worth writing). */
  private fun buildJson(previewId: String, tokens: List<CatalogToken>): String? {
    val entries = tokens.mapNotNull { tokenJson(it) }
    if (entries.isEmpty()) return null
    val sb = StringBuilder()
    sb.append('{')
    sb.append("\"schema\":").append(jsonString(SCHEMA)).append(',')
    sb.append("\"previewId\":").append(jsonString(previewId)).append(',')
    sb.append("\"tokens\":[")
    entries.forEachIndexed { i, e ->
      if (i > 0) sb.append(',')
      sb.append(e)
    }
    sb.append("]}")
    return sb.toString()
  }

  /** One token object, or null if its value couldn't be reflected. */
  private fun tokenJson(token: CatalogToken): String? {
    val value =
      when (token.tokenKind) {
        CatalogTokenKind.COLOR ->
          runCatching {
              colorJson(CatalogValueReflection.reflectColor(token.className, token.member))
            }
            .getOrNull()
        CatalogTokenKind.TEXT_STYLE ->
          runCatching {
              textStyleJson(CatalogValueReflection.reflectTextStyle(token.className, token.member))
            }
            .getOrNull()
      } ?: return null
    val sb = StringBuilder()
    sb.append('{')
    sb.append("\"label\":").append(jsonString(token.label)).append(',')
    sb.append("\"className\":").append(jsonString(token.className)).append(',')
    sb.append("\"member\":").append(jsonString(token.member)).append(',')
    sb.append("\"kind\":").append(jsonString(token.tokenKind.name)).append(',')
    sb.append(value)
    sb.append('}')
    return sb.toString()
  }

  /** `"color":{"hex":"#AARRGGBB","argb":<int>}` — hex matches the sheet's swatch label. */
  private fun colorJson(color: Color): String {
    val argb = color.toArgb()
    val hex = String.format(Locale.ROOT, "#%08X", argb)
    return "\"color\":{\"hex\":${jsonString(hex)},\"argb\":$argb}"
  }

  /** `"textStyle":{...}` — only the metrics that are actually specified are emitted. */
  private fun textStyleJson(style: TextStyle): String {
    val fields = mutableListOf<String>()
    spField(style.fontSize)?.let { fields += "\"fontSizeSp\":$it" }
    style.fontWeight?.weight?.let { fields += "\"fontWeight\":$it" }
    style.fontStyle?.let { fields += "\"fontStyle\":${jsonString(it.toString())}" }
    spField(style.letterSpacing)?.let { fields += "\"letterSpacingSp\":$it" }
    spField(style.lineHeight)?.let { fields += "\"lineHeightSp\":$it" }
    style.fontFamily?.let { fields += "\"fontFamily\":${jsonString(it.toString())}" }
    return "\"textStyle\":{${fields.joinToString(",")}}"
  }

  /** The `.sp` magnitude of a [TextUnit], or null when unspecified or not in sp. */
  private fun spField(unit: TextUnit): Float? =
    if (unit.isSpecified && unit.isSp) unit.value else null

  private fun sanitize(s: String): String = s.replace(Regex("""[/\\:*?"<>|\s]"""), "_")

  private fun jsonString(s: String): String {
    val sb = StringBuilder(s.length + 2)
    sb.append('"')
    for (c in s) {
      when (c) {
        '"' -> sb.append("\\\"")
        '\\' -> sb.append("\\\\")
        '\b' -> sb.append("\\b")
        '\n' -> sb.append("\\n")
        '\r' -> sb.append("\\r")
        '\t' -> sb.append("\\t")
        else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
      }
    }
    sb.append('"')
    return sb.toString()
  }
}
