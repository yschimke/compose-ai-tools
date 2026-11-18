package ee.schimke.composeai.rccreation.dwf

import java.awt.BasicStroke
import java.awt.Color
import java.awt.GradientPaint
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.geom.GeneralPath
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO

/**
 * The placeholder images a complication or photo slot shows before a host supplies its own: the
 * pixels behind [DwfVariables.DEFAULT_MONOCHROMATIC_IMAGE_URL],
 * [DwfVariables.DEFAULT_SMALL_IMAGE_URL] and [DwfVariables.DEFAULT_PHOTO_IMAGE_URL].
 *
 * **Why the translator owns them.** They used to exist only behind those synthetic URLs, served by
 * `rc-render-jvm`'s image loader. A document that references them by URL needs a player with an
 * image host behind it, and the browser (CMP Wasm) player has none, so it refused every face with a
 * complication. The translator now inlines these bytes as *named* bitmaps: a host can still replace
 * them by name, and a player with no host draws the same placeholder `rc-render-jvm` always drew.
 * `rc-render-jvm` serves its URLs from here too, so the two cannot drift.
 */
public object DwfDefaultImages {
  private val cache = ConcurrentHashMap<String, ByteArray>()

  private val renderers: Map<String, () -> ByteArray> =
    mapOf(
      DwfVariables.DEFAULT_MONOCHROMATIC_IMAGE_URL to ::renderDefaultMonochromaticPng,
      DwfVariables.DEFAULT_SMALL_IMAGE_URL to ::renderDefaultSmallPng,
      DwfVariables.DEFAULT_PHOTO_IMAGE_URL to ::renderDefaultPhotoPng,
    )

  /** The default-image URLs this object can render. */
  public val urls: Set<String>
    get() = renderers.keys

  /** PNG bytes for the default image [url] names, or null when [url] is not one of [urls]. */
  public fun png(url: String?): ByteArray? {
    val render = renderers[url ?: return null] ?: return null
    return cache.getOrPut(url) { render() }
  }

  private fun encodePng(width: Int, height: Int, draw: (Graphics2D) -> Unit): ByteArray {
    val img = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    val g = img.createGraphics()
    try {
      g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
      g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
      draw(g)
    } finally {
      g.dispose()
    }
    val out = ByteArrayOutputStream()
    ImageIO.write(img, "PNG", out)
    return out.toByteArray()
  }

  /**
   * Default monochromatic complication icon: the bullseye the Wear OS runtime shows for system
   * providers that carry no icon of their own.
   */
  private fun renderDefaultMonochromaticPng(): ByteArray =
    encodePng(48, 48) { g ->
      g.color = Color.WHITE
      g.stroke = BasicStroke(5f)
      g.draw(Ellipse2D.Float(9f, 9f, 30f, 30f))
      g.fill(Ellipse2D.Float(17f, 17f, 14f, 14f))
    }

  private fun renderDefaultSmallPng(): ByteArray =
    encodePng(96, 96) { g ->
      g.paint = GradientPaint(0f, 0f, Color(0x3D, 0x5A, 0xFE), 96f, 96f, Color(0x00, 0xBF, 0xA5))
      g.fill(Ellipse2D.Float(4f, 4f, 88f, 88f))
      g.color = Color.WHITE
      val bolt = GeneralPath()
      bolt.moveTo(52f, 16f)
      bolt.lineTo(28f, 52f)
      bolt.lineTo(46f, 52f)
      bolt.lineTo(42f, 80f)
      bolt.lineTo(68f, 44f)
      bolt.lineTo(50f, 44f)
      bolt.closePath()
      g.fill(bolt)
    }

  private fun renderDefaultPhotoPng(): ByteArray =
    encodePng(450, 450) { g ->
      g.paint = GradientPaint(0f, 0f, Color(0x18, 0x2B, 0x49), 0f, 450f, Color(0x09, 0x10, 0x1B))
      g.fillRect(0, 0, 450, 450)
      g.color = Color(0x3D, 0x5A, 0xFE, 120)
      g.fill(Ellipse2D.Float(-90f, -150f, 630f, 500f))
      g.color = Color(0xFF, 0xD1, 0x80, 195)
      g.fill(Ellipse2D.Float(243f, 72f, 126f, 126f))
      g.color = Color(0x1E, 0x3A, 0x5F, 210)
      g.fill(Ellipse2D.Float(-135f, 252f, 550f, 355f))
      g.color = Color(0x09, 0x10, 0x1B, 230)
      g.fill(Ellipse2D.Float(54f, 292f, 555f, 340f))
    }
}
