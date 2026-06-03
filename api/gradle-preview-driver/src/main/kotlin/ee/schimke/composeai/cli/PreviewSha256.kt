package ee.schimke.composeai.cli

import ee.schimke.composeai.io.SystemFileSystem
import java.awt.image.BufferedImage
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import javax.imageio.ImageIO
import okio.Path.Companion.toPath

/**
 * Hash used for change detection of a rendered preview file.
 *
 * For PNGs (the common case) this is just sha256 of the file bytes — the renderer is deterministic
 * so the encoded bytes are stable across runs.
 *
 * For GIFs (`@ScrollingPreview(modes = [GIF])` output) we instead hash the first and last frames'
 * pixels. The scripted scroll walk reads `liveRemaining` from a `LazyColumn` mid-walk, so
 * progressive item materialisation produces a slightly different frame sequence on every run — and
 * therefore a different encoded GIF — even when the source composable hasn't changed (issue #209).
 * The bookend frames are the hold-start dwell at scroll position 0 and the settled hold-end at
 * content end, both of which are stable for fixed source content. Mid-scroll frames are ignored, so
 * changes that only manifest while scrolling won't show as Changed.
 *
 * Public surface in `:gradle-preview-driver`: contrib consumers driving renders without going
 * through `:cli` need the same change-detection hash function so their state files stay compatible
 * with the CLI's.
 */
fun previewSha256(file: File): String =
  if (file.extension.equals("gif", ignoreCase = true)) {
    gifBookendFrameSha256(file) ?: sha256(readAllBytes(file))
  } else {
    sha256(readAllBytes(file))
  }

/** Read [file]'s bytes through Okio's `FileSystem`. */
private fun readAllBytes(file: File): ByteArray =
  SystemFileSystem.read(file.path.toPath()) { readByteArray() }

internal fun sha256(bytes: ByteArray): String {
  val md = MessageDigest.getInstance("SHA-256")
  return md.digest(bytes).joinToString("") { "%02x".format(it) }
}

/** Hash a GIF's first + last frames as `(w:int)(h:int)(pixels:int[w*h])` ARGB bytes per frame. */
private fun gifBookendFrameSha256(file: File): String? {
  val reader = ImageIO.getImageReadersByFormatName("gif").asSequence().firstOrNull() ?: return null
  return try {
    ImageIO.createImageInputStream(file).use { stream ->
      reader.input = stream
      val numFrames = reader.getNumImages(true)
      if (numFrames <= 0) return null
      val first = reader.read(0) ?: return null
      val last = if (numFrames == 1) first else reader.read(numFrames - 1) ?: return null
      sha256(framesToBytes(listOf(first, last)))
    }
  } catch (_: Exception) {
    null
  } finally {
    reader.dispose()
  }
}

private fun framesToBytes(frames: List<BufferedImage>): ByteArray {
  val totalPixels = frames.sumOf { it.width * it.height }
  val buffer = ByteBuffer.allocate(frames.size * 8 + totalPixels * 4)
  for (img in frames) {
    val w = img.width
    val h = img.height
    val pixels = img.getRGB(0, 0, w, h, null, 0, w)
    buffer.putInt(w)
    buffer.putInt(h)
    for (p in pixels) buffer.putInt(p)
  }
  return buffer.array()
}
