package ee.schimke.composeai.guidelines

import ee.schimke.composeai.guidelines.protocol.GuidelineVerdictV1
import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * Draws a preview's findings over its render: an outline on every node a `fail` names (from the
 * accessibility bounds, which are pixels of the same render) and a soft translucent box for every
 * region, each labelled with its rule id. Plain AWT, so it runs headless in CI.
 */
public object GuidelineAnnotator {
  /**
   * [png] with [failures] and [regions] drawn on it; the input unchanged when it cannot be read.
   */
  public fun annotate(
    png: ByteArray,
    nodes: List<PreviewNode>,
    failures: List<GuidelineVerdictV1>,
    regions: List<GuidelineRegion>,
  ): ByteArray {
    val source = runCatching { ImageIO.read(ByteArrayInputStream(png)) }.getOrNull() ?: return png
    val image = BufferedImage(source.width, source.height, BufferedImage.TYPE_INT_ARGB)
    val g = image.createGraphics()
    try {
      g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
      g.drawImage(source, 0, 0, null)
      val stroke = (source.width / 200f).coerceAtLeast(2f)
      g.font = Font(Font.SANS_SERIF, Font.BOLD, (source.width / 40).coerceIn(10, 28))
      val byId = nodes.associateBy { it.id }
      failures.forEach { verdict ->
        verdict.nodeIds.mapNotNull(byId::get).forEach { node ->
          g.color = WARNING
          g.stroke = BasicStroke(stroke)
          g.drawRect(node.left, node.top, node.right - node.left, node.bottom - node.top)
          label(g, verdict.ruleId, node.left, node.top)
        }
      }
      regions.forEach { region ->
        val x = (region.x * source.width).toInt()
        val y = (region.y * source.height).toInt()
        val w = (region.width * source.width).toInt().coerceAtLeast(1)
        val h = (region.height * source.height).toInt().coerceAtLeast(1)
        g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.25f)
        g.color = REGION
        g.fillRect(x, y, w, h)
        g.composite = AlphaComposite.SrcOver
        g.stroke =
          BasicStroke(
            stroke,
            BasicStroke.CAP_BUTT,
            BasicStroke.JOIN_MITER,
            10f,
            floatArrayOf(8f, 6f),
            0f,
          )
        g.drawRect(x, y, w, h)
        label(g, region.ruleId, x, y)
      }
    } finally {
      g.dispose()
    }
    return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
  }

  private fun label(g: java.awt.Graphics2D, text: String, x: Int, y: Int) {
    val metrics = g.fontMetrics
    val width = metrics.stringWidth(text) + 8
    val height = metrics.height
    val top = (y - height).coerceAtLeast(0)
    g.color = Color(0, 0, 0, 180)
    g.fillRect(x, top, width, height)
    g.color = Color.WHITE
    g.drawString(text, x + 4, top + metrics.ascent)
  }

  private val WARNING = Color(0xF5, 0x7C, 0x00)
  private val REGION = Color(0x29, 0x79, 0xFF)
}
