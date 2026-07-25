package ee.schimke.composeai.cli

/**
 * Longest-edge bound (px) for a hybrid figma-svg's raster crops, applied in **two** places that
 * must agree:
 * - `bundle pack` / the export, when a crop is written into the bundle
 *   ([injectFigmaRasterIntoBundle]);
 * - the serve host, when a crop is base64-inlined into a self-contained figma-svg
 *   (`inlineFigmaRasters`).
 *
 * One constant, deliberately. A crop is captured at device resolution, so a full-screen photo
 * region runs to megabytes, and the serve host has always downsampled to this bound before anyone
 * saw the pixels — storing the originals bought nothing for the only consumer and cost the bundle
 * real bytes. Jetchat's profile stickers were 1176x1050 crops at ~1.9MB each; six of them made its
 * live bundle 27MB, past the serve host's 25MiB per-file fetch cap, so the catalog silently fell
 * back to baked PNGs. Bounding at pack time took that bundle to ~22.5MB and the crops stayed
 * pixel-identical to what the server was already serving.
 *
 * 1024px keeps a component-sized crop untouched and a screen-sized one at roughly
 * thumbnail-to-retina fidelity — right for a design reference layer. If these two call sites ever
 * diverge, the waste comes straight back, so change the bound here rather than at either site.
 */
internal const val MAX_FIGMA_RASTER_EDGE_PX: Int = 1024

/**
 * [png] re-encoded with its longest edge capped at [maxEdgePx] (aspect preserved, bilinear), or the
 * original bytes when it's already within the cap, fails to decode, or the re-encode doesn't
 * actually shrink the payload (a tiny palette PNG can grow when re-encoded as ARGB). Never throws —
 * both callers must degrade to the full-resolution bytes rather than a broken layer or a failed
 * pack.
 */
internal fun downscaleRaster(png: ByteArray, maxEdgePx: Int): ByteArray {
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
