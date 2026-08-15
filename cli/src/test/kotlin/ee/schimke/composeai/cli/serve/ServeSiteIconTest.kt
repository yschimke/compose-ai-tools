package ee.schimke.composeai.cli.serve

import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The site icon ([ServeSiteIcon]).
 *
 * The server shipped none at all before this — no icon links on any page and a 404 at
 * `/favicon.ico` — which is why an unfurled link showed a generic globe beside its card. Each form
 * exists for a consumer that accepts no other, so this pins that all three are actually produced
 * and are the format they claim: an SVG for tabs, a PNG for the chat clients that read
 * `apple-touch-icon`, and a real ICO container at the path naive fetchers probe.
 */
class ServeSiteIconTest {

  @Test
  fun `the svg is an svg, drawn in the brand palette`() {
    val icon = ServeSiteIcon.svg
    val text = icon.bytes.decodeToString()

    assertEquals("image/svg+xml", icon.contentType)
    assertTrue(text.startsWith("<svg"), text)
    assertTrue(text.contains("viewBox=\"0 0 32 32\""), text)
    // Generated from ServeBrand rather than hand-committed, so the icon cannot drift from the mark
    // in the site header or on the unfurl card.
    assertTrue(text.contains("#%02x%02x%02x".format(79, 55, 139)), "the mark's tonal container")
    assertTrue(text.contains("#%02x%02x%02x".format(234, 221, 255)), "the diamond")
  }

  @Test
  fun `the apple touch icon is a 180 pixel png`() {
    val icon = ServeSiteIcon.appleTouchIcon
    val image = assertNotNull(ImageIO.read(ByteArrayInputStream(icon.bytes)))

    assertEquals("image/png", icon.contentType)
    assertEquals(180, image.width)
    assertEquals(180, image.height)
  }

  /**
   * The ICO container is hand-written, so its 22 bytes of header are worth pinning: a wrong offset
   * or length field is not a visibly broken image, it is an icon that silently doesn't load.
   */
  @Test
  fun `the ico wraps a 32 pixel png in a well-formed container`() {
    val bytes = ServeSiteIcon.ico.bytes

    assertEquals("image/vnd.microsoft.icon", ServeSiteIcon.ico.contentType)
    // ICONDIR: reserved 0, type 1 (icon), one image.
    assertEquals(0, le16(bytes, 0))
    assertEquals(1, le16(bytes, 2))
    assertEquals(1, le16(bytes, 4))
    // ICONDIRENTRY: 32×32, no palette, one plane, 32bpp.
    assertEquals(32, bytes[6].toInt() and 0xff)
    assertEquals(32, bytes[7].toInt() and 0xff)
    assertEquals(0, bytes[8].toInt() and 0xff)
    assertEquals(1, le16(bytes, 10))
    assertEquals(32, le16(bytes, 12))

    val length = le32(bytes, 14)
    val offset = le32(bytes, 18)
    assertEquals(22, offset, "the payload starts right after the 6+16 byte header")
    assertEquals(bytes.size - offset, length, "the declared length covers the rest of the file")

    val payload = bytes.copyOfRange(offset, offset + length)
    val image = assertNotNull(ImageIO.read(ByteArrayInputStream(payload)), "payload decodes as PNG")
    assertEquals(32, image.width)
    assertEquals(32, image.height)
  }

  /** Every page carries all three, because no single form is understood by every consumer. */
  @Test
  fun `the head links name all three forms`() {
    val tags = ServeSiteIcon.linkTags()

    assertTrue(
      tags.contains("<link rel=\"icon\" href=\"/favicon.svg\" type=\"image/svg+xml\">"),
      tags,
    )
    assertTrue(tags.contains("<link rel=\"icon\" href=\"/favicon.ico\" sizes=\"32x32\">"), tags)
    assertTrue(
      tags.contains("<link rel=\"apple-touch-icon\" href=\"/apple-touch-icon.png\">"),
      tags,
    )
  }

  private fun le16(bytes: ByteArray, at: Int): Int =
    (bytes[at].toInt() and 0xff) or ((bytes[at + 1].toInt() and 0xff) shl 8)

  private fun le32(bytes: ByteArray, at: Int): Int = le16(bytes, at) or (le16(bytes, at + 2) shl 16)
}
