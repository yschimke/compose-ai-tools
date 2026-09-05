package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.bundle.MAX_FIGMA_RASTER_EDGE_PX
import ee.schimke.composeai.bundle.downscaleRaster
import java.util.Base64
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

/**
 * Shared helpers for serving a catalog's baked `compose/figma-svg` exports. A hybrid export
 * references its per-node raster crops as **external** hrefs (`figma-raster/<node>.png`, or the
 * delivery branch's slug-prefixed `<slug>.figma-raster/<node>.png`), so both fetching (enumerate
 * the crops to download) and serving (inline them so the SVG is self-contained, since Figma's
 * importer can't resolve external hrefs) walk those hrefs. Used by both the daemon path
 * ([ServeRenderHost]) and the static catalog path ([ServeCatalogStore] / [ServeBundleHost]).
 */

/**
 * `<image href="…figma-raster/<node>.png">` refs a hybrid figma-svg carries (bare or
 * slug-prefixed).
 */
private val FIGMA_RASTER_HREF = Regex("href=\"([^\"]*figma-raster/[^\"]+)\"")

/**
 * The figma-raster hrefs a hybrid SVG references (external crop paths, relative to the SVG's dir).
 */
// Public rather than `internal` since the move to `:render-host`: `internal` is module-scoped,
// and the `:server` call sites are in a different module now. Not a widened API by intent.
fun figmaRasterHrefs(svg: String): List<String> =
  FIGMA_RASTER_HREF.findAll(svg).map { it.groupValues[1] }.toList()

/**
 * Longest-edge cap (px) for a raster crop inlined into a self-contained figma-svg. A hybrid
 * sticker's crop is captured at device resolution, so a full-screen photo/`TextField` region can
 * run to megabytes — and the base64 embedding adds a third on top, ballooning the "paste into
 * Figma" SVG. A crop whose longest edge exceeds this is downscaled (aspect preserved) before
 * embedding; the SVG's `<image x y width height>` box is unchanged, so the bitmap still fills the
 * layer exactly, just at a bounded density.
 *
 * Aliases the shared [MAX_FIGMA_RASTER_EDGE_PX], which `bundle pack` now applies when it *writes* a
 * crop — the two must stay equal or the pack-time bound would either discard pixels this path still
 * wanted, or leave bytes it is about to throw away.
 */
internal const val MAX_INLINE_RASTER_EDGE_PX: Int = MAX_FIGMA_RASTER_EDGE_PX

/**
 * Inline an SVG's `figma-raster/<node>.png` crops as `data:image/png;base64` URIs, reading each
 * crop (relative to [dir], where its href resolves) via [fileSystem], so the served SVG is
 * self-contained. A crop whose longest edge exceeds [maxEdgePx] is downscaled before embedding (see
 * [MAX_INLINE_RASTER_EDGE_PX]); pass [Int.MAX_VALUE] to embed full-resolution bytes. A vector-only
 * SVG passes through; a crop missing on disk is left as a plain ref.
 */
// Public rather than `internal` since the move to `:render-host`: `internal` is module-scoped,
// and the `:server` call sites are in a different module now. Not a widened API by intent.
fun inlineFigmaRasters(
  fileSystem: FileSystem,
  dir: Path,
  svg: String,
  maxEdgePx: Int = MAX_INLINE_RASTER_EDGE_PX,
): String {
  if (!svg.contains("figma-raster/")) return svg
  val root = dir.normalized()
  return FIGMA_RASTER_HREF.replace(svg) { match ->
    val href = match.groupValues[1]
    // Resolve + contain: an untrusted catalog SVG must not read outside `dir` via `..`/absolute
    // hrefs — a crop that would escape is left as a plain ref, never followed.
    val cropPath = "$dir/$href".toPath().normalized()
    if (!cropPath.isUnder(root) || !fileSystem.exists(cropPath)) return@replace match.value
    val crop = fileSystem.read(cropPath) { readByteArray() }
    val bounded = downscaleRaster(crop, maxEdgePx)
    "href=\"data:image/png;base64,${Base64.getEncoder().encodeToString(bounded)}\""
  }
}

/**
 * Rewrite an SVG's `figma-raster/<node>.png` hrefs to absolute URLs under [baseUrl] (the crops'
 * public home — e.g. the catalog's delivery branch on `raw.githubusercontent.com`), so a
 * web/document-served SVG *links* its rasters instead of carrying their bytes. The href's own
 * relative path is preserved under the base, mirroring how it resolves next to the SVG on disk. A
 * traversing href (`..` / absolute) is left untouched, exactly like [inlineFigmaRasters]'s
 * containment. A vector-only SVG passes through.
 */
// Public rather than `internal` since the move to `:render-host`: `internal` is module-scoped,
// and the `:server` call sites are in a different module now. Not a widened API by intent.
fun linkFigmaRasters(svg: String, baseUrl: String): String {
  if (!svg.contains("figma-raster/")) return svg
  val base = baseUrl.trimEnd('/')
  return FIGMA_RASTER_HREF.replace(svg) { match ->
    val href = match.groupValues[1]
    if (href.startsWith("/") || href.contains("..") || href.contains(":"))
      return@replace match.value
    "href=\"$base/$href\""
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// Web mode (`?mode=web`)
//
// The default served figma-svg is self-contained: fonts base64-embedded as `@font-face`, rasters
// inlined as `data:` URIs — right for pasting into Figma (its importer resolves fonts by family
// name and can't fetch external hrefs) but heavy, and it duplicates the font bytes into every
// sticker. A **web/document** viewer that opens the `.svg` URL directly (not as an `<img>`, where
// browsers block external refs in secure-static mode) can instead pull the faces from Google Fonts.
//
// [webModeSvg] rewrites an embedded SVG for that context: it strips the base64 `@font-face` blocks
// and injects a single `@import url('https://fonts.googleapis.com/css2?family=…')` covering exactly
// the families/weights/italics the SVG uses (the `<text>` still carry those family names, so the
// browser resolves them from the imported sheet). Rasters are left as-is (still inlined) for now —
// referencing the per-node crops needs an HTTP route to serve them, a separate step.
// ─────────────────────────────────────────────────────────────────────────────

/** One `@font-face` the SVG embeds, reduced to what a Google Fonts `css2` request needs. */
internal data class WebFontFace(val family: String, val weight: Int, val italic: Boolean)

private val FONT_FACE_BLOCK = Regex("@font-face\\{[^}]*\\}")

/**
 * Rewrite an embedded figma-svg for web/document viewing: replace the base64 `@font-face` blocks
 * with an external Google Fonts `@import`, so the browser fetches the faces instead of the SVG
 * carrying their bytes. A vector-only SVG, or one with no parseable `@font-face`, passes through
 * unchanged. Rasters are untouched (still inlined). Pure — unit-testable without a served host.
 */
// Public rather than `internal` since the move to `:render-host`: `internal` is module-scoped,
// and the `:server` call sites are in a different module now. Not a widened API by intent.
fun webModeSvg(svg: String): String {
  val faces = FONT_FACE_BLOCK.findAll(svg).mapNotNull { parseWebFontFace(it.value) }.toList()
  if (faces.isEmpty()) return svg
  val importUrl = googleFontsImportUrl(faces) ?: return svg
  // The URL's `&` separators (`&family=`, `&display=swap`) are XML entity starts inside the
  // `<style>` text of an `image/svg+xml` document, so escape them — the XML parser decodes `&amp;`
  // back to `&` before the CSS parser sees the `@import`, keeping the served SVG well-formed.
  val importUrlXml = importUrl.replace("&", "&amp;")
  // Drop every embedded face, then put the @import at the head of the first <style> (CSS requires
  // `@import` before other rules; the base64 bytes are what bloated the sticker, so this is the
  // win).
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
 * Build a single Google Fonts `css2` URL for [faces], grouped by family with sorted, de-duplicated
 * weights (and the `ital,wght` axis when a family carries any italic). Generic families
 * (`sans-serif` / `serif` / `monospace` / …) are skipped — they aren't Google Fonts. Null when
 * nothing references a real family.
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

/**
 * True when this path is [root] or a descendant of it (both normalized) — traversal containment.
 */
private fun Path.isUnder(root: Path): Boolean {
  var p: Path? = this
  while (p != null) {
    if (p == root) return true
    p = p.parent
  }
  return false
}
