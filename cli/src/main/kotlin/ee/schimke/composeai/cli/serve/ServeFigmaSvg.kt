package ee.schimke.composeai.cli.serve

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
internal fun figmaRasterHrefs(svg: String): List<String> =
  FIGMA_RASTER_HREF.findAll(svg).map { it.groupValues[1] }.toList()

/**
 * Inline an SVG's `figma-raster/<node>.png` crops as `data:image/png;base64` URIs, reading each
 * crop (relative to [dir], where its href resolves) via [fileSystem], so the served SVG is
 * self-contained. A vector-only SVG passes through; a crop missing on disk is left as a plain ref.
 */
internal fun inlineFigmaRasters(fileSystem: FileSystem, dir: Path, svg: String): String {
  if (!svg.contains("figma-raster/")) return svg
  val root = dir.normalized()
  return FIGMA_RASTER_HREF.replace(svg) { match ->
    val href = match.groupValues[1]
    // Resolve + contain: an untrusted catalog SVG must not read outside `dir` via `..`/absolute
    // hrefs — a crop that would escape is left as a plain ref, never followed.
    val cropPath = "$dir/$href".toPath().normalized()
    if (!cropPath.isUnder(root) || !fileSystem.exists(cropPath)) return@replace match.value
    val crop = fileSystem.read(cropPath) { readByteArray() }
    "href=\"data:image/png;base64,${Base64.getEncoder().encodeToString(crop)}\""
  }
}

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
