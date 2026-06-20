package ee.schimke.composeai.data.deviceframe

import ee.schimke.composeai.data.deviceframe.DeviceArtCatalog.DeviceArtSpec
import java.awt.AlphaComposite
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage

/**
 * Composites a rendered preview screenshot into a device-art [DeviceArtSpec] frame, reproducing the
 * layering the Android Device Art Generator does in `device-art-generator.js`:
 *
 * 1. draw the optional `shadow` layer, then the `back` bezel;
 * 2. scale the screenshot into the screen rectangle and clip it to the rounded-rect / circle;
 * 3. for notched devices, redraw `back` over the screen so the cutout occludes it;
 * 4. draw the optional `fore` glare layer.
 *
 * Pure `java.awt` (BufferedImage / Graphics2D) so it runs unchanged under the Robolectric host JVM
 * and the Desktop renderer. The output canvas is the natural size of the `back` layer.
 */
object DeviceFrameCompositor {

  /**
   * @param screenshot the captured preview PNG, decoded.
   * @param layers frame layers keyed by resource name ([DeviceArtCatalog.BACK] required;
   *   [DeviceArtCatalog.SHADOW] / [DeviceArtCatalog.FORE] optional). Sizes must match the `back`
   *   layer.
   * @param includeShadow / includeGlare let callers drop the soft-shadow / glare layers (e.g. for a
   *   flat marketing background) without changing the catalog.
   */
  fun composite(
    screenshot: BufferedImage,
    layers: Map<String, BufferedImage>,
    spec: DeviceArtSpec,
    includeShadow: Boolean = true,
    includeGlare: Boolean = true,
  ): BufferedImage {
    val back =
      layers[DeviceArtCatalog.BACK]
        ?: error("Device frame '${spec.artId}' is missing its required 'back' layer.")
    val width = back.width
    val height = back.height

    val canvas = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    val g = canvas.createGraphics()
    g.applyQualityHints()

    if (includeShadow) layers[DeviceArtCatalog.SHADOW]?.let { g.drawImage(it, 0, 0, null) }
    g.drawImage(back, 0, 0, null)

    // Build the screen on its own ARGB layer so the rounded-corner / circular clip is anti-aliased
    // (a Graphics2D clip region is not), then composite it over the bezel.
    val screen = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    val sg = screen.createGraphics()
    sg.applyQualityHints()
    sg.drawImage(screenshot, spec.screenX, spec.screenY, spec.screenWidth, spec.screenHeight, null)
    // Mask the screen to the rounded-rect / circular screen shape. A `fill(shape)` under DstIn only
    // rasterises pixels *inside* the shape and would leave the screenshot outside it untouched, so
    // instead draw a full-canvas mask (opaque inside the shape, transparent elsewhere): DstIn then
    // multiplies the screen's alpha by the mask everywhere, clearing the corners. Anti-aliased.
    val mask = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    val mg = mask.createGraphics()
    mg.applyQualityHints()
    mg.color = Color.WHITE
    mg.fill(screenClip(spec))
    mg.dispose()
    sg.composite = AlphaComposite.DstIn
    sg.drawImage(mask, 0, 0, null)
    sg.dispose()
    g.drawImage(screen, 0, 0, null)

    // Notched phones: the cutout is part of the bezel art, so redraw `back` on top of the screen.
    if (spec.notch) g.drawImage(back, 0, 0, null)
    if (includeGlare) layers[DeviceArtCatalog.FORE]?.let { g.drawImage(it, 0, 0, null) }

    g.dispose()
    return canvas
  }

  private fun screenClip(spec: DeviceArtSpec): java.awt.Shape {
    val x = spec.screenX.toDouble()
    val y = spec.screenY.toDouble()
    val w = spec.screenWidth.toDouble()
    val h = spec.screenHeight.toDouble()
    val diameter = spec.cornerRadius * 2
    return when {
      spec.cornerRadius <= 0 -> RoundRectangle2D.Double(x, y, w, h, 0.0, 0.0)
      diameter >= spec.screenWidth && diameter >= spec.screenHeight -> Ellipse2D.Double(x, y, w, h)
      else -> RoundRectangle2D.Double(x, y, w, h, diameter.toDouble(), diameter.toDouble())
    }
  }

  private fun java.awt.Graphics2D.applyQualityHints() {
    setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
    setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
  }
}
