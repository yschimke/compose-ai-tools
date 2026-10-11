package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.bundle.MAX_FIGMA_RASTER_EDGE_PX
import ee.schimke.composeai.bundle.downscaleRaster
import java.util.Base64
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

/**
 * Shared helpers for serving a catalog's baked `compose/figma-svg` exports. Hybrid exports
 * reference per-node raster crops as external hrefs (`figma-raster/<node>.png`, or slug-prefixed on
 * a delivery branch), which fetching and serving (inlining, since Figma's importer can't resolve
 * external refs) both walk. Used by [ServeRenderHost], [ServeCatalogStore] and [ServeBundleHost].
 */

/** `<image href="…figma-raster/<node>.png">` refs (bare or slug-prefixed). */
private val FIGMA_RASTER_HREF = Regex("href=\"([^\"]*figma-raster/[^\"]+)\"")

/** The figma-raster hrefs a hybrid SVG references, relative to the SVG's dir. */
// Public because `:server` call sites live in another module; not a widened API by intent.
public fun figmaRasterHrefs(svg: String): List<String> =
  FIGMA_RASTER_HREF.findAll(svg).map { it.groupValues[1] }.toList()

/**
 * Longest-edge cap for crops inlined into a self-contained figma-svg; larger device-resolution
 * crops are downscaled (aspect kept, `<image>` box unchanged) so base64 doesn't balloon the SVG.
 * Must equal [MAX_FIGMA_RASTER_EDGE_PX], which `bundle pack` applies when writing crops.
 */
internal const val MAX_INLINE_RASTER_EDGE_PX: Int = MAX_FIGMA_RASTER_EDGE_PX

/**
 * Inline an SVG's `figma-raster/<node>.png` crops (relative to [dir]) as base64 `data:` URIs so it
 * is self-contained. Crops over [maxEdgePx] are downscaled ([MAX_INLINE_RASTER_EDGE_PX]; pass
 * [Int.MAX_VALUE] for full resolution). Vector-only SVGs pass through; missing crops stay plain
 * refs.
 */
public fun inlineFigmaRasters(
  fileSystem: FileSystem,
  dir: Path,
  svg: String,
  maxEdgePx: Int = MAX_INLINE_RASTER_EDGE_PX,
): String {
  if (!svg.contains("figma-raster/")) return svg
  val root = dir.normalized()
  return FIGMA_RASTER_HREF.replace(svg) { match ->
    val href = match.groupValues[1]
    // Untrusted SVG: a `..` or absolute href that escapes `dir` is left as a plain ref.
    val cropPath = "$dir/$href".toPath().normalized()
    if (!cropPath.isUnder(root) || !fileSystem.exists(cropPath)) return@replace match.value
    val crop = fileSystem.read(cropPath) { readByteArray() }
    val bounded = downscaleRaster(crop, maxEdgePx)
    "href=\"data:image/png;base64,${Base64.getEncoder().encodeToString(bounded)}\""
  }
}

/**
 * Rewrite `figma-raster/<node>.png` hrefs to absolute URLs under [baseUrl] (e.g. the delivery
 * branch on `raw.githubusercontent.com`), so web-served SVGs link rather than embed rasters.
 * Traversing hrefs are left untouched, as in [inlineFigmaRasters].
 */
public fun linkFigmaRasters(svg: String, baseUrl: String): String {
  if (!svg.contains("figma-raster/")) return svg
  val base = baseUrl.trimEnd('/')
  return FIGMA_RASTER_HREF.replace(svg) { match ->
    val href = match.groupValues[1]
    if (href.startsWith("/") || href.contains("..") || href.contains(":"))
      return@replace match.value
    "href=\"$base/$href\""
  }
}

// Web mode (`?mode=web`): the default figma-svg embeds fonts and rasters for pasting into Figma. A
// browser opening the `.svg` directly can load fonts from Google Fonts instead, so [webModeSvg]
// strips the base64 `@font-face` blocks and adds one `@import` for the families/weights used.
// Rasters stay inlined for now.

/** One `@font-face` the SVG embeds, reduced to what a Google Fonts `css2` request needs. */
internal data class WebFontFace(val family: String, val weight: Int, val italic: Boolean)

private val FONT_FACE_BLOCK = Regex("@font-face\\{[^}]*\\}")

/**
 * Rewrite an embedded figma-svg for web viewing: replace base64 `@font-face` blocks with a Google
 * Fonts `@import`. Passes through when there are no parseable faces. Pure.
 */
public fun webModeSvg(svg: String): String {
  val faces = FONT_FACE_BLOCK.findAll(svg).mapNotNull { parseWebFontFace(it.value) }.toList()
  if (faces.isEmpty()) return svg
  val importUrl = googleFontsImportUrl(faces) ?: return svg
  // Escape `&` for the XML `<style>` text; the XML parser decodes it before CSS sees the `@import`.
  val importUrlXml = importUrl.replace("&", "&amp;")
  // Drop embedded faces, then put the `@import` at the head of the first `<style>` (CSS requires it
  // first).
  val stripped = FONT_FACE_BLOCK.replace(svg, "")
  return stripped.replaceFirst("<style>", "<style>@import url('$importUrlXml');")
}

/**
 * Parse `@font-face{font-family:'X';font-style:normal;font-weight:N;src:…}` into a [WebFontFace].
 */
private fun parseWebFontFace(block: String): WebFontFace? {
  val family =
    Regex("font-family:'([^']*)'").find(block)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
      ?: return null
  val weight = Regex("font-weight:(\\d+)").find(block)?.groupValues?.get(1)?.toIntOrNull() ?: 400
  val italic = block.contains("font-style:italic")
  return WebFontFace(family, weight, italic)
}

/**
 * One Google Fonts `css2` URL for [faces], grouped by family with sorted, deduplicated weights (and
 * `ital,wght` when italic is used). Generic families are skipped; null when none remain.
 */
internal fun googleFontsImportUrl(faces: List<WebFontFace>): String? {
  val generics = setOf("sans-serif", "serif", "monospace", "cursive", "fantasy", "system-ui")
  val byFamily =
    faces.filter { it.family.lowercase() !in generics }.groupBy { it.family }.toSortedMap()
  if (byFamily.isEmpty()) return null
  val families = byFamily.map { (family, fs) ->
    val enc = family.trim().replace(" ", "+")
    if (fs.any { it.italic }) {
      val tuples =
        fs
          .map { (if (it.italic) 1 else 0) to googleFontsWeight(it.weight) }
          .distinct()
          .sortedWith(compareBy({ it.first }, { it.second }))
      "family=$enc:ital,wght@" + tuples.joinToString(";") { "${it.first},${it.second}" }
    } else {
      "family=$enc:wght@" +
        fs.map { googleFontsWeight(it.weight) }.distinct().sorted().joinToString(";")
    }
  }
  return "https://fonts.googleapis.com/css2?" + families.joinToString("&") + "&display=swap"
}

/** CSS2 static-family instances use conventional 100-step weights, unlike Compose's 1..1000. */
private fun googleFontsWeight(weight: Int): Int =
  (((weight.coerceIn(1, 1000) + 50) / 100) * 100).coerceIn(100, 900)

/** True when this path is [root] or under it (both normalized). */
private fun Path.isUnder(root: Path): Boolean {
  var p: Path? = this
  while (p != null) {
    if (p == root) return true
    p = p.parent
  }
  return false
}
