package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.bundle.WebEmbed
import ee.schimke.composeai.bundle.ZIP_DOS_EPOCH_MS
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Turns a `serve` render session into a **portable preview bundle** — the same `WebEmbed` static
 * gallery the offline `bundle`/`share-preview` paths produce (an `index.html` + script + the
 * rendered `previews/<id>.png`), packaged as either a directory or a single `.zip`. The live link
 * and the offline bundle are then the *same* render output: a teammate can open the URL now, or you
 * can hand them a self-contained zip that needs no daemon.
 *
 * Decoupled from [ServeRenderHost] via a [render] lambda (one preview → PNG bytes, or null when it
 * failed/was skipped) so the packaging is pure and unit-testable without a render session.
 */
object ServeBundle {

  /** Result of building a bundle: the file map plus what rendered and what didn't. */
  data class Built(
    val files: Map<String, ByteArray>,
    val previewCount: Int,
    val renderedCount: Int,
    val failed: List<String>,
  )

  /**
   * Render every preview in [previews] (in order) via [render] and assemble the [WebEmbed] gallery.
   * The first preview that renders becomes the cover. Failures are collected in [Built.failed] and
   * skipped rather than aborting the whole bundle. [inline] bakes the PNGs into the script as
   * `data:` URIs ([WebEmbed.InlineMode.INLINE]) for a maximally self-contained gallery; the default
   * emits separate `previews/<id>.png` files ([WebEmbed.InlineMode.EXTERNAL]), the natural shape
   * for a zip.
   */
  fun build(
    previews: List<ServePreview>,
    title: String,
    modulePath: String,
    inline: Boolean = false,
    render: (ServePreview) -> ByteArray?,
  ): Built {
    val failed = mutableListOf<String>()
    var cover = true
    val embedPreviews = previews.mapNotNull { p ->
      val png = render(p)
      if (png == null) {
        failed += p.id
        null
      } else {
        WebEmbed.Preview(id = p.id, label = p.label, pngBytes = png, isCover = cover).also {
          cover = false
        }
      }
    }
    val out =
      WebEmbed.generate(
        title = title,
        modulePath = modulePath,
        previews = embedPreviews,
        mode = if (inline) WebEmbed.InlineMode.INLINE else WebEmbed.InlineMode.EXTERNAL,
      )
    return Built(
      files = out.files,
      previewCount = previews.size,
      renderedCount = embedPreviews.size,
      failed = failed,
    )
  }

  /**
   * Pack [files] into a reproducible zip: insertion order preserved, every entry pinned to the
   * shared [ZIP_DOS_EPOCH_MS] DOS-epoch floor so the same previews produce byte-identical zips
   * across runs (matching the plugin's reproducible-bundle writer).
   */
  fun zip(files: Map<String, ByteArray>): ByteArray {
    val baos = ByteArrayOutputStream()
    ZipOutputStream(baos).use { zout ->
      for ((path, bytes) in files) {
        zout.putNextEntry(ZipEntry(path).apply { time = ZIP_DOS_EPOCH_MS })
        zout.write(bytes)
        zout.closeEntry()
      }
    }
    return baos.toByteArray()
  }

  /** Write [files] under [dir] (creating parent dirs for nested `previews/<id>.png` entries). */
  fun writeDir(files: Map<String, ByteArray>, dir: File) {
    for ((path, bytes) in files) {
      val target = File(dir, path)
      target.parentFile?.mkdirs()
      target.writeBytes(bytes)
    }
  }
}
