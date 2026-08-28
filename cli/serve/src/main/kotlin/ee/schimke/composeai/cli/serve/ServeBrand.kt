package ee.schimke.composeai.cli.serve

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * The pixels the site draws of *itself* — the product mark, the palette behind it, and the typeface
 * they're set in. Shared by the two server-rendered brand images: the link-unfurl card
 * ([ServeSocialCard]) and the favicons ([ServeSiteIcon]).
 *
 * These exist as **drawn** images rather than committed PNGs because both are derived from live
 * server state (which catalogs are published, how many previews they carry) and from the same
 * palette the pages use. A committed asset would be a second place the brand is decided, and it
 * would go stale the moment a catalog is added.
 *
 * ## Colours
 *
 * The values below are the **dark half** of the M3 baseline scheme `serve.css` declares — copied,
 * not re-derived, so the card reads as the same product as the page it links to. Dark rather than
 * `light-dark()`: a raster has no `prefers-color-scheme`, and every surface that shows an unfurl
 * card (Slack, iMessage, Discord, X) mats an image against a neutral chrome where the dark card
 * reads as deliberate and a light one reads as a screenshot of a browser window.
 *
 * ## Type
 *
 * Text is set in the **vendored Roboto** ([ServeRcFonts]) rather than a logical AWT family. The
 * logical families (`SansSerif`, `Dialog`) resolve through fontconfig, so on a slim container image
 * with no system fonts installed they map to whatever is left — which in the empty case is a
 * fallback that draws no glyphs at all. That failure is invisible on the server and lands as a card
 * with a blank text column in someone's chat. The vendored faces ride in the jar and rasterize the
 * same everywhere, which is the same argument [ServeRcFonts] makes for the browser lane. A build
 * that somehow didn't stage them falls back to the logical family rather than failing the bake: a
 * card set in the wrong face still beats no card.
 */
internal object ServeBrand {

  // --- Palette: the dark half of the M3 baseline in `serve.css`. -------------------------------

  /** `surface` — the card's ground. */
  val BG: Color = Color(0x14, 0x12, 0x18)

  /** `surface-container-high` — the panel each preview thumbnail sits on. */
  val PANEL: Color = Color(0x2b, 0x29, 0x30)

  /** `outline-variant` — hairline edges. */
  val BORDER: Color = Color(0x49, 0x45, 0x4f)

  /** `on-surface` — headline text. */
  val FG: Color = Color(0xe6, 0xe0, 0xe9)

  /** `on-surface-variant` — supporting text. */
  val FG_MUTED: Color = Color(0xca, 0xc4, 0xd0)

  /** `primary` — the accent rule. */
  val ACCENT: Color = Color(0xd0, 0xbc, 0xff)

  /** `primary-container` — the mark's tonal container, as in `.cp-site-mark`. */
  val MARK_BG: Color = Color(0x4f, 0x37, 0x8b)

  /** `on-primary-container` — the diamond itself. */
  val MARK_FG: Color = Color(0xea, 0xdd, 0xff)

  // --- Type ------------------------------------------------------------------------------------

  /**
   * The vendored Roboto faces, keyed by whether the caller wants Medium. Resolved once — deriving a
   * size from a loaded [Font] is cheap, loading the file is not — and null when this build carries
   * no such face, which sends [font] to the logical fallback.
   */
  private val vendored: Map<Boolean, Font?> by lazy {
    mapOf(false to loadVendored("Roboto-Regular.ttf"), true to loadVendored("Roboto-Medium.ttf"))
  }

  private fun loadVendored(file: String): Font? {
    val path = ServeRcFonts.resourceFor(file) ?: return null
    val stream = ServeBrand::class.java.getResourceAsStream(path) ?: return null
    return stream.use { runCatching { Font.createFont(Font.TRUETYPE_FONT, it) }.getOrNull() }
  }

  /** Roboto at [size] px, Medium when [medium]; the logical sans if the vendored face is absent. */
  fun font(size: Float, medium: Boolean = false): Font =
    vendored[medium]?.deriveFont(size)
      ?: Font(Font.SANS_SERIF, if (medium) Font.BOLD else Font.PLAIN, size.toInt())

  // --- Drawing ---------------------------------------------------------------------------------

  /** Antialiased, quality-biased rendering hints — every brand image wants the same set. */
  fun quality(g: Graphics2D) {
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
    g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
    g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
    g.setRenderingHint(
      RenderingHints.KEY_INTERPOLATION,
      RenderingHints.VALUE_INTERPOLATION_BILINEAR,
    )
  }

  /**
   * The product mark — `.cp-site-mark`'s full-round tonal container with the `◇` inside it —
   * filling a [size]-square box at ([x], [y]).
   *
   * The diamond is **stroked geometry, not the `◇` character**. A glyph would depend on the face
   * having U+25C7, which Roboto's vendored subset is under no obligation to carry, and a missing
   * glyph rasterizes as a tofu box in the middle of the product mark. Four points and a stroke draw
   * the same shape with nothing to look up.
   */
  fun drawMark(g: Graphics2D, x: Double, y: Double, size: Double) {
    g.color = MARK_BG
    g.fill(Ellipse2D.Double(x, y, size, size))
    // The glyph occupies a little over half the container, matching the CSS mark's 0.9rem-in-32px.
    val r = size * 0.28
    val cx = x + size / 2
    val cy = y + size / 2
    val diamond =
      Path2D.Double().apply {
        moveTo(cx, cy - r)
        lineTo(cx + r, cy)
        lineTo(cx, cy + r)
        lineTo(cx - r, cy)
        closePath()
      }
    g.color = MARK_FG
    g.stroke = BasicStroke((size * 0.075).toFloat(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
    g.draw(diamond)
  }

  // --- Encoding --------------------------------------------------------------------------------

  /** [image] as PNG bytes, or null if the encoder refuses it. */
  fun encodePng(image: BufferedImage): ByteArray? {
    val buffer = ByteArrayOutputStream()
    if (!runCatching { ImageIO.write(image, "png", buffer) }.getOrDefault(false)) return null
    return buffer.toByteArray()
  }

  /** Decode [png], or null when the bytes aren't a readable image. */
  fun decodePng(png: ByteArray): BufferedImage? = runCatching {
    ImageIO.read(ByteArrayInputStream(png))
  }
    .getOrNull()
    ?.takeIf { it.width > 0 && it.height > 0 }
}
