package ee.schimke.composeai.cli.serve

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The sniff is the lane's whole admission policy — an upload gets in because its *bytes* are an
 * image, never because of what it was called — so these pin both halves: every known format is
 * recognised with its declared size, and the shapes an attacker would reach for are not.
 */
class ServeImageFormatsTest {

  @Test
  fun `each known format is sniffed from its bytes and reports its declared size`() {
    val cases =
      listOf(
        Triple(ServeImageFixtures.png(width = 4, height = 3), "png", ServeDocSize(4, 3)),
        Triple(ServeImageFixtures.gif(width = 6, height = 5), "gif", ServeDocSize(6, 5)),
        Triple(ServeImageFixtures.webp(width = 8, height = 6), "webp", ServeDocSize(8, 6)),
        Triple(ServeImageFixtures.jpeg(width = 12, height = 9), "jpeg", ServeDocSize(12, 9)),
      )
    for ((bytes, expectedId, expectedSize) in cases) {
      val format = ServeImageFormats.detect(bytes)
      assertEquals(expectedId, format?.id, "format of $expectedId fixture")
      assertEquals(expectedSize, format?.size?.invoke(bytes), "size of $expectedId fixture")
    }
  }

  @Test
  fun `things that are not images are refused`() {
    // An SVG is the pointed one: it is an image to a human and a scriptable document to a browser,
    // which is exactly why this lane doesn't take it.
    val svg = """<svg xmlns="http://www.w3.org/2000/svg"><script>alert(1)</script></svg>"""
    assertNull(ServeImageFormats.detect(svg.toByteArray()))
    assertNull(ServeImageFormats.detect("<html><body>hi</body></html>".toByteArray()))
    assertNull(ServeImageFormats.detect(byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0, 0, 0, 0)))
    assertNull(ServeImageFormats.detect(ByteArray(0)))
    // A PNG signature with nothing behind it: a name-trusting server would serve this as an image.
    assertNull(
      ServeImageFormats.detect(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
    )
  }

  @Test
  fun `an animated png is served as apng and a still one is not`() {
    val still = ServeImageFixtures.png()
    val animated = ServeImageFixtures.apng()
    // Both are PNGs on the way in — the distinction is a property of the document, not the format.
    assertEquals("png", ServeImageFormats.detect(still)?.id)
    assertEquals("png", ServeImageFormats.detect(animated)?.id)
    assertEquals("image/png", ServeImageFormats.contentTypeOf(ServeImageFormats.PNG, still))
    assertEquals("image/apng", ServeImageFormats.contentTypeOf(ServeImageFormats.PNG, animated))
  }

  @Test
  fun `a truncated header yields no size rather than a wrong one`() {
    val png = ServeImageFixtures.png()
    assertNull(ServeImageFormats.detect(png.copyOfRange(0, 20)))
    assertNull(ServeImageFormats.WEBP.size(ServeImageFixtures.webp().copyOfRange(0, 20)))
    assertNull(ServeImageFormats.JPEG.size(ServeImageFixtures.jpeg().copyOfRange(0, 6)))
  }
}
