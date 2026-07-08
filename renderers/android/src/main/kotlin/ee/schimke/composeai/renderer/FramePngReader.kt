package ee.schimke.composeai.renderer

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO

/**
 * Robust frame-PNG decoder for the animated-capture paths (`@AnimatedPreview` GIF, scroll GIF,
 * focus GIF). Those paths write N per-frame PNGs with Roborazzi and then read every frame back to
 * assemble the animation.
 *
 * The old read — `ImageIO.read(file) ?: error(...)` — turned a single bad frame into an inscrutable
 * CI failure: on a half-written / truncated frame `ImageIO.read(File)` *throws* `IIOException:
 * Caught exception during read` from deep inside `PNGImageReader` (the `?: error` Elvis never runs,
 * because the call threw rather than returned null), so the surfaced message named neither the
 * preview nor the frame — triage meant downloading the `composePreviewRender-reports` artifact and
 * guessing.
 *
 * [decode] reads the bytes once, classifies the fault up front (missing / empty / not-a-PNG /
 * truncated-before-IEND / undecodable) and only then decodes, from the fully-buffered bytes. It
 * still throws on a bad frame — the render stays red — it just makes the red name the role, path
 * and exact fault. Fatal [Error]s (e.g. `OutOfMemoryError`) are left to propagate.
 */
internal object FramePngReader {
  // PNG signature: 0x89 'P' 'N' 'G' \r \n 0x1A \n, as signed bytes.
  private val PNG_SIGNATURE = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)

  // Final bytes of the mandatory IEND chunk: "IEND" + its fixed CRC
  // 0xAE426082. IEND is always the last chunk, so a different tail means the
  // writer was interrupted mid-frame.
  private val IEND_TRAILER = byteArrayOf(73, 69, 78, 68, -82, 66, 96, -126)

  fun decode(file: File, role: String): BufferedImage {
    val tag = "$role frame PNG ${file.path}"
    if (!file.isFile) error("Failed to decode $tag: file is missing on disk")
    val bytes = file.readBytes()
    if (bytes.isEmpty()) error("Failed to decode $tag: empty file (render wrote nothing)")
    if (!bytes.hasPngSignature()) error("Failed to decode $tag: not a PNG (no signature)")
    if (!bytes.hasIendTrailer()) error("Failed to decode $tag: truncated before IEND")
    return decodePng(bytes) ?: error("Failed to decode $tag: ImageIO could not read it")
  }

  /**
   * Decode a signature- and IEND-complete PNG from the in-memory bytes. Returns null when no reader
   * accepts it or a normal decode [Exception] is raised; fatal [Error]s (`OutOfMemoryError`, test
   * cancellation) propagate rather than being masked as a diagnostic.
   */
  private fun decodePng(bytes: ByteArray): BufferedImage? {
    return try {
      ByteArrayInputStream(bytes).use { ImageIO.read(it) }
    } catch (e: Exception) {
      null
    }
  }

  private fun ByteArray.hasPngSignature(): Boolean {
    if (size < PNG_SIGNATURE.size) return false
    for (i in PNG_SIGNATURE.indices) {
      if (this[i] != PNG_SIGNATURE[i]) return false
    }
    return true
  }

  private fun ByteArray.hasIendTrailer(): Boolean {
    if (size < IEND_TRAILER.size) return false
    val base = size - IEND_TRAILER.size
    for (i in IEND_TRAILER.indices) {
      if (this[base + i] != IEND_TRAILER[i]) return false
    }
    return true
  }
}
