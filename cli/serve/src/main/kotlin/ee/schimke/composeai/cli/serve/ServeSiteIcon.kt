package ee.schimke.composeai.cli.serve

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/**
 * The site icon, in the three forms the web actually asks for.
 *
 * The server had **none** before this: no `<link rel="icon">` on any page, and `/favicon.ico`
 * answered 404 with an HTML body. That is not only a blank browser tab. Every surface that renders
 * a link-unfurl card — Slack, iMessage, Discord, Google's result rows — puts the site's icon beside
 * the card, and resolves it by reading the page's icon links and then, failing that, probing
 * `/favicon.ico`. With nothing to find, each fell back to a generic globe, and the card read as an
 * anonymous link rather than as this product.
 *
 * Three forms, because no single one is understood everywhere:
 * * [svg] — a vector, for browser tabs. Crisp at every density and a few hundred bytes, but not
 *   accepted by any of the unfurlers above.
 * * [appleTouchIcon] — a 180×180 PNG. Declared as `apple-touch-icon`, which despite the name is
 *   what most chat clients and link fetchers read first, and the only form several of them accept.
 * * [ico] — a 32×32 PNG wrapped in an ICO container, served at the well-known `/favicon.ico` path
 *   for the fetchers that probe it directly and never read the page's markup at all.
 *
 * All three are drawn from [ServeBrand], so the icon is the same mark, in the same palette, as the
 * one in the site header and on the unfurl card. Rasters are baked once per process (the mark is
 * fixed at build time) and served with a content ETag.
 */
internal object ServeSiteIcon {

  /** Well-known path for [svg]. */
  const val SVG_PATH = "/favicon.svg"

  /** Well-known path for [ico] — the one naive fetchers probe without reading any markup. */
  const val ICO_PATH = "/favicon.ico"

  /** Well-known path for [appleTouchIcon]. */
  const val APPLE_TOUCH_PATH = "/apple-touch-icon.png"

  private const val APPLE_TOUCH_SIZE = 180

  /** ICO carries a single 32×32 entry: the size every browser picks for a tab anyway. */
  private const val ICO_SIZE = 32

  /** One baked icon: bytes, content type, and the ETag that validates them. */
  data class Icon(val bytes: ByteArray, val contentType: String, val etag: String) {
    // See [ServeHeroImages.Hero]: identity equality, for the same reason.
    override fun equals(other: Any?): Boolean = this === other

    override fun hashCode(): Int = System.identityHashCode(this)
  }

  /**
   * The mark as SVG.
   *
   * Generated from [ServeBrand]'s colours rather than committed as a static asset, so the icon
   * cannot drift from the palette the pages and the unfurl card are drawn in — there is one place
   * the brand is decided. The diamond is a path for the same reason [ServeBrand.drawMark] strokes
   * one: `◇` as a character depends on the viewer having a font that carries U+25C7.
   */
  val svg: Icon by lazy {
    val text =
      """
      <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 32 32" width="32" height="32">
        <circle cx="16" cy="16" r="16" fill="${hex(ServeBrand.MARK_BG)}"/>
        <path d="M16 7 L25 16 L16 25 L7 16 Z" fill="none" stroke="${hex(ServeBrand.MARK_FG)}"
              stroke-width="2.4" stroke-linejoin="round"/>
      </svg>
      """
        .trimIndent()
    val bytes = text.toByteArray()
    Icon(bytes, "image/svg+xml", etagOf(bytes))
  }

  /** The 180×180 PNG most chat clients and link fetchers read first. */
  val appleTouchIcon: Icon by lazy { pngIcon(APPLE_TOUCH_SIZE) }

  /** The 32×32 raster, as a PNG — the payload [ico] wraps, and useful on its own for tests. */
  private val png32: Icon by lazy { pngIcon(ICO_SIZE) }

  /**
   * The ICO served at `/favicon.ico`: an ICO container wrapping [png32]'s PNG.
   *
   * PNG-in-ICO rather than the older BMP-in-ICO encoding. It is a fifth of the bytes, needs no
   * bottom-up row order or AND-mask padding to get right, and every browser and fetcher that
   * matters has read it for well over a decade. The container itself is 22 bytes: a 6-byte
   * directory header and one 16-byte entry pointing at the payload.
   */
  val ico: Icon by lazy {
    val payload = png32.bytes
    val out = ByteArrayOutputStream()
    // ICONDIR: reserved, type (1 = icon), image count.
    out.writeLe16(0)
    out.writeLe16(1)
    out.writeLe16(1)
    // ICONDIRENTRY: width, height (a byte each — 0 would mean 256), palette size, reserved,
    // colour planes, bits per pixel, payload length, payload offset.
    out.write(ICO_SIZE)
    out.write(ICO_SIZE)
    out.write(0)
    out.write(0)
    out.writeLe16(1)
    out.writeLe16(32)
    out.writeLe32(payload.size)
    out.writeLe32(ICO_HEADER_BYTES)
    out.write(payload)
    val bytes = out.toByteArray()
    Icon(bytes, "image/vnd.microsoft.icon", etagOf(bytes))
  }

  /**
   * The `<head>` links every page carries. Constant, well-known paths rather than content-hashed
   * ones: an icon fetcher that guesses a URL guesses these, and unlike a page asset an icon is
   * small enough that a day's cache costs nothing to get wrong.
   */
  fun linkTags(): String =
    """
    <link rel="icon" href="$SVG_PATH" type="image/svg+xml">
    <link rel="icon" href="$ICO_PATH" sizes="32x32">
    <link rel="apple-touch-icon" href="$APPLE_TOUCH_PATH">
    """
      .trimIndent()

  private fun pngIcon(size: Int): Icon {
    val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
    val g = image.createGraphics()
    try {
      ServeBrand.quality(g)
      ServeBrand.drawMark(g, 0.0, 0.0, size.toDouble())
    } finally {
      g.dispose()
    }
    // An icon the encoder refused would be a broken `<link>` on every page, so fall back to no
    // bytes rather than throwing during page render; the routes 404 and the tab keeps its globe.
    val bytes = ServeBrand.encodePng(image) ?: ByteArray(0)
    return Icon(bytes, "image/png", etagOf(bytes))
  }

  private fun hex(color: java.awt.Color): String =
    "#%02x%02x%02x".format(color.red, color.green, color.blue)

  private fun etagOf(bytes: ByteArray): String =
    "\"" +
      MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }
        .take(16) +
      "\""

  /** ICONDIR (6) + one ICONDIRENTRY (16): where the wrapped PNG starts. */
  private const val ICO_HEADER_BYTES = 22

  private fun ByteArrayOutputStream.writeLe16(value: Int) {
    write(value and 0xff)
    write((value ushr 8) and 0xff)
  }

  private fun ByteArrayOutputStream.writeLe32(value: Int) {
    writeLe16(value and 0xffff)
    writeLe16((value ushr 16) and 0xffff)
  }
}
