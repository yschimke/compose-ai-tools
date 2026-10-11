package ee.schimke.composeai.bundle

/**
 * Longest-edge bound (px) for a hybrid figma-svg's raster crops, shared by `bundle pack`
 * ([injectFigmaRasterIntoBundle]) and the serve host's inlining (`inlineFigmaRasters`) — change it
 * here, never at either site. Device-resolution crops can be megabytes and push a bundle past
 * serve's 25MiB fetch cap, while serve downsamples to this bound anyway. 1024px leaves
 * component-sized crops untouched.
 */
public const val MAX_FIGMA_RASTER_EDGE_PX: Int = 1024

/**
 * [png] with its longest edge capped at [maxEdgePx] (aspect preserved, bilinear), or the original
 * bytes when already within the cap, undecodable, or not actually smaller re-encoded. Never throws.
 */
public fun downscaleRaster(png: ByteArray, maxEdgePx: Int): ByteArray {
  if (maxEdgePx <= 0 || maxEdgePx == Int.MAX_VALUE) return png
  return try {
    val image = javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(png)) ?: return png
    val longest = maxOf(image.width, image.height)
    if (longest <= maxEdgePx) return png
    val scale = maxEdgePx.toDouble() / longest
    val w = (image.width * scale).toInt().coerceAtLeast(1)
    val h = (image.height * scale).toInt().coerceAtLeast(1)
    val scaled = java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB)
    scaled.createGraphics().run {
      setRenderingHint(
        java.awt.RenderingHints.KEY_INTERPOLATION,
        java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR,
      )
      drawImage(image, 0, 0, w, h, null)
      dispose()
    }
    val out = java.io.ByteArrayOutputStream()
    javax.imageio.ImageIO.write(scaled, "png", out)
    out.toByteArray().takeIf { it.isNotEmpty() && it.size < png.size } ?: png
  } catch (t: Throwable) {
    png
  }
}
