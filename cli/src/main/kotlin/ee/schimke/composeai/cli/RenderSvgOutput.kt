package ee.schimke.composeai.cli

import ee.schimke.composeai.data.layoutinspector.ComposeFigmaSvgProduct
import ee.schimke.composeai.io.SystemFileSystem
import java.io.File
import okio.FileSystem
import okio.Path.Companion.toPath

/**
 * Writes the daemon-produced `compose/figma-svg` export for one preview to a standalone `.svg` on
 * disk — the `compose-preview render --format svg` output. Mirrors how `bundle pack
 * --with-semantics` carries the same bytes inside a bundle, but lands them as loose files a user
 * can open directly.
 *
 * # Hybrid crops
 *
 * A pure-vector export (the common case for a component catalog) is a single self-contained `.svg`.
 * A **hybrid** export — one whose opaque `Image`/`Icon`/`Canvas` nodes are emitted as `<image>`
 * placeholders — references sibling `figma-raster/<node>.png` crops via **relative** hrefs. The
 * daemon writes those crops beside the sidecar in its own `data/<id>/figma-raster/` dir. When we
 * flatten the SVG to `renders/<id>.svg`, the crops move to a sibling `renders/<id>.figma-raster/`
 * dir, so the `figma-raster/` href prefix is rewritten to `<id>.figma-raster/` to keep the
 * `<image>` layers resolving. Naming the crop dir after the SVG stem also avoids collisions when
 * several previews' SVGs share one `renders/` dir.
 */
internal object RenderSvgOutput {
  /**
   * Strip filesystem-hostile characters from a preview id (mirrors `BundleRenderer.safeFilename`).
   */
  fun safeFilename(id: String): String =
    id.map { c -> if (c.isLetterOrDigit() || c in "._-") c else '_' }.joinToString("")

  /**
   * Write [svgBytes] to [target] (an `.svg` path), creating parent dirs as needed. When [crops] is
   * non-empty (a hybrid export), also write each `<node>.png` into a sibling
   * `<target-stem>.figma-raster/` dir and rewrite the SVG's `figma-raster/` href prefix to point at
   * it. Returns the number of files written (the SVG plus any crops).
   */
  fun write(
    target: File,
    svgBytes: ByteArray,
    crops: Map<String, ByteArray> = emptyMap(),
    fileSystem: FileSystem = SystemFileSystem,
  ): Int {
    target.parentFile?.mkdirs()
    if (crops.isEmpty()) {
      fileSystem.write(target.path.toPath()) { write(svgBytes) }
      return 1
    }

    val stem = target.name.removeSuffix(".svg")
    val rasterDirName = "$stem.${ComposeFigmaSvgProduct.RASTER_DIR}"
    val rewritten =
      svgBytes.decodeToString().replace("${ComposeFigmaSvgProduct.RASTER_DIR}/", "$rasterDirName/")
    fileSystem.write(target.path.toPath()) { write(rewritten.encodeToByteArray()) }

    var written = 1
    val rasterDir = File(target.parentFile, rasterDirName).also { it.mkdirs() }
    for ((name, bytes) in crops) {
      fileSystem.write(File(rasterDir, name).path.toPath()) { write(bytes) }
      written++
    }
    return written
  }
}
