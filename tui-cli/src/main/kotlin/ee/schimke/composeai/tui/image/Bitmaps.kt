package ee.schimke.composeai.tui.image

import com.jakewharton.mosaic.ui.Bitmap
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO

/**
 * Decode a PNG (or anything else ImageIO understands) into a Mosaic [Bitmap]. The fork's
 * [com.jakewharton.mosaic.ui.Image] composable owns the resampling and tier selection (Kitty
 * Graphics / half-block / ASCII); the consumer only has to hand it a packed-ARGB pixel grid at the
 * source resolution.
 *
 * Returns null on any decode failure so callers can render a "couldn't read this file" hint without
 * a try/catch.
 */
object Bitmaps {
  fun readPng(file: File): Bitmap? =
    try {
      toBitmap(ImageIO.read(file))
    } catch (_: Throwable) {
      null
    }

  /** Decode raw PNG/JPEG/etc. bytes (e.g. a baked `previews/<id>.png` bundle entry) into a Bitmap. */
  fun decode(bytes: ByteArray): Bitmap? =
    try {
      toBitmap(ByteArrayInputStream(bytes).use { ImageIO.read(it) })
    } catch (_: Throwable) {
      null
    }

  private fun toBitmap(img: BufferedImage?): Bitmap? {
    if (img == null) return null
    val w = img.width
    val h = img.height
    if (w <= 0 || h <= 0) return null
    val pixels = IntArray(w * h)
    img.getRGB(0, 0, w, h, pixels, 0, w)
    return Bitmap(width = w, height = h, pixels = pixels)
  }
}
