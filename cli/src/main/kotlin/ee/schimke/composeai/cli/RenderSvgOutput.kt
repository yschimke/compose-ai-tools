package ee.schimke.composeai.cli

import ee.schimke.composeai.data.layoutinspector.ComposeFigmaSvgProduct
import ee.schimke.composeai.io.SystemFileSystem
import java.io.File
import okio.FileSystem
import okio.Path.Companion.toPath

/**
 * Writes the daemon's `compose/figma-svg` export for one preview to a standalone `.svg` (the
 * `render --format svg` output).
 *
 * A hybrid export references sibling `figma-raster/<node>.png` crops by relative href. Flattened to
 * `renders/<id>.svg`, the crops move to `renders/<id>.figma-raster/` and the href prefix is
 * rewritten to match, which also avoids collisions between previews sharing `renders/`.
 */
internal object RenderSvgOutput {
  /**
   * Strip filesystem-hostile characters from a preview id (mirrors `BundleRenderer.safeFilename`).
   */
  fun safeFilename(id: String): String =
    id.map { c -> if (c.isLetterOrDigit() || c in "._-") c else '_' }.joinToString("")

  /**
   * Write [svgBytes] to [target], creating parents. For a hybrid export, also write [crops] into a
   * sibling `<target-stem>.figma-raster/` and rewrite the hrefs. Returns the number of files
   * written.
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
