package ee.schimke.composeai.daemon

import ee.schimke.composeai.data.layoutinspector.ComposeSemanticsPayload
import ee.schimke.composeai.data.layoutinspector.WireframeModel
import ee.schimke.composeai.data.layoutinspector.WireframeStyle
import ee.schimke.composeai.io.SystemFileSystem
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.FontMetrics
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import okio.FileSystem
import okio.Path.Companion.toPath

/**
 * AWT raster baker for the semantics wireframe — the desktop counterpart of the Android
 * `android.graphics` baker. Draws the shared [WireframeModel] (boxes + style from
 * `:data-layoutinspector-core`) onto a [BufferedImage] and writes a PNG, so both backends emit a
 * pixel-matching `compose-semantics-wireframe.png` next to the SVG.
 *
 * Unlike [DesktopAccessibilityOverlay] this needs **no source screenshot** — the wireframe is a
 * pure schematic drawn on a white ground, so it bakes from the semantics tree alone. Rendered at
 * [SCALE]× to match the desktop renderer's 2× density (crisp strokes + text without re-tuning the
 * model's px-space coordinates).
 *
 * AWT mapping mirrors [DesktopAccessibilityOverlay]: `BasicStroke` for solid/dashed strokes,
 * `FontMetrics.stringWidth` for precise label fitting, `Color(r,g,b,a)` for the translucent
 * clickable fill, and the `ImageIO.write(.., outputStream())` Okio bridge for the write.
 */
object DesktopSemanticsWireframe {

  /** Supersampling factor — matches the desktop renderer's 2× density. */
  private const val SCALE = 2

  /** Bakes [payload]'s wireframe to [destPng]. Returns the file, or null if the bake failed. */
  fun generate(
    payload: ComposeSemanticsPayload,
    destPng: File,
    padding: Int = 16,
    fileSystem: FileSystem = SystemFileSystem,
  ): File? = generate(WireframeModel.from(payload, padding), destPng, fileSystem)

  fun generate(
    model: WireframeModel,
    destPng: File,
    fileSystem: FileSystem = SystemFileSystem,
  ): File? =
    try {
      val image = render(model)
      destPng.parentFile?.mkdirs()
      fileSystem.write(destPng.path.toPath()) { ImageIO.write(image, "png", outputStream()) }
      destPng
    } catch (t: Throwable) {
      // A bake failure must not take down the render — the SVG is still written, and a missing PNG
      // degrades to "SVG only" rather than failing the whole data product.
      System.err.println(
        "[compose-wireframe] desktop bake failed: ${t.javaClass.simpleName}: ${t.message}"
      )
      null
    }

  private fun render(model: WireframeModel): BufferedImage {
    val width = (model.width * SCALE).coerceAtLeast(1)
    val height = (model.height * SCALE).coerceAtLeast(1)
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    val g = image.createGraphics()
    try {
      g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
      g.setRenderingHint(
        RenderingHints.KEY_TEXT_ANTIALIASING,
        RenderingHints.VALUE_TEXT_ANTIALIAS_ON,
      )
      g.scale(SCALE.toDouble(), SCALE.toDouble())

      g.color = awt(WireframeStyle.ground)
      g.fillRect(0, 0, model.width, model.height)

      g.font = Font(Font.SANS_SERIF, Font.PLAIN, WireframeStyle.fontSize)
      val fm = g.fontMetrics

      for (box in model.boxes) {
        val x = box.left + model.tx
        val y = box.top + model.ty
        val color = awt(WireframeStyle.strokeColor(box))

        if (box.clickable) {
          g.color = awt(WireframeStyle.clickAccent, (WireframeStyle.clickFillOpacity * 255).toInt())
          g.fillRect(x, y, box.width, box.height)
        }

        g.color = color
        g.stroke =
          if (box.clearAndSet) {
            BasicStroke(
              WireframeStyle.strokeWidth(box).toFloat(),
              BasicStroke.CAP_BUTT,
              BasicStroke.JOIN_MITER,
              10f,
              WireframeStyle.clearAndSetDash,
              0f,
            )
          } else {
            BasicStroke(WireframeStyle.strokeWidth(box).toFloat())
          }
        g.drawRect(x, y, box.width, box.height)

        val label = box.label
        if (label != null && box.width > WireframeStyle.fontSize) {
          val text = fitLabel(label, box.width - 4, fm)
          if (text.isNotEmpty()) {
            g.color = color
            g.drawString(text, x + 2, y + fm.ascent + 1)
          }
        }
      }
    } finally {
      g.dispose()
    }
    return image
  }

  /** Precise label fit using AWT [FontMetrics] (the model's estimate is only an upper bound). */
  private fun fitLabel(text: String, maxWidthPx: Int, fm: FontMetrics): String {
    if (maxWidthPx <= 0) return ""
    if (fm.stringWidth(text) <= maxWidthPx) return text
    val ellipsis = "…"
    val ellipsisWidth = fm.stringWidth(ellipsis)
    if (ellipsisWidth > maxWidthPx) return ""
    var end = text.length
    while (end > 0 && fm.stringWidth(text.substring(0, end)) + ellipsisWidth > maxWidthPx) end--
    return if (end <= 0) "" else text.substring(0, end) + ellipsis
  }

  private fun awt(rgb: Int, alpha: Int = 255): Color =
    Color(WireframeStyle.red(rgb), WireframeStyle.green(rgb), WireframeStyle.blue(rgb), alpha)
}
