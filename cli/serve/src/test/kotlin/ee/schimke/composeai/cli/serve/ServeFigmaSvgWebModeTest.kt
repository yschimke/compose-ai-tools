package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.bundle.downscaleRaster
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import okio.Path.Companion.toPath

/**
 * Unit tests for the `?mode=web` figma-svg transform ([webModeSvg] / [googleFontsImportUrl]): the
 * base64 `@font-face` blocks a self-contained export embeds are swapped for an external Google
 * Fonts `@import`, so a browser viewing the `.svg` directly pulls the faces from Google. Pure
 * string transform — no served host needed.
 */
class ServeFigmaSvgWebModeTest {

  private fun face(family: String, weight: Int, italic: Boolean = false, b64: String = "AAAA") =
    "@font-face{font-family:'$family';font-style:${if (italic) "italic" else "normal"};" +
      "font-weight:$weight;src:url(data:font/woff2;base64,$b64)format('woff2');}"

  private fun svg(vararg faces: String, body: String = "<text font-family=\"Roboto\">Hi</text>") =
    "<svg xmlns=\"http://www.w3.org/2000/svg\"><defs><style>${faces.joinToString("")}" +
      "</style></defs>$body</svg>"

  @Test
  fun `webModeSvg strips embedded faces and injects a Google Fonts import`() {
    val input = svg(face("Roboto", 400), face("Roboto", 500))
    val out = webModeSvg(input)
    // No base64 font bytes survive.
    assertFalse(out.contains("base64,"))
    assertFalse(out.contains("@font-face"))
    // A single @import covers both weights of the one family; the URL's `&` is XML-escaped so the
    // image/svg+xml document stays well-formed (no raw `&` entity starts).
    assertTrue(
      out.contains(
        "@import url('https://fonts.googleapis.com/css2?family=Roboto:wght@400;500&amp;display=swap');"
      )
    )
    // The raw (unescaped) separator must not appear — that would be a malformed entity start.
    assertFalse(out.contains("&display=swap"))
    // The text node's family is untouched, so the browser resolves it from the imported sheet.
    assertTrue(out.contains("font-family=\"Roboto\""))
  }

  @Test
  fun `webModeSvg leaves a vector-only svg untouched`() {
    val input = "<svg><text font-family=\"Roboto, sans-serif\">Hi</text></svg>"
    assertEquals(input, webModeSvg(input))
  }

  @Test
  fun `googleFontsImportUrl groups families, sorts and dedups weights`() {
    val url =
      googleFontsImportUrl(
        listOf(
          WebFontFace("Space Grotesk", 600, false),
          WebFontFace("Orbitron", 700, false),
          WebFontFace("Orbitron", 500, false),
          WebFontFace("Orbitron", 500, false), // dup
        )
      )
    // Families alphabetical; spaces → +; weights sorted & de-duplicated.
    assertEquals(
      "https://fonts.googleapis.com/css2?family=Orbitron:wght@500;700&family=Space+Grotesk:wght@600&display=swap",
      url,
    )
  }

  @Test
  fun `googleFontsImportUrl uses the ital,wght axis when a family has any italic`() {
    val url =
      googleFontsImportUrl(
        listOf(WebFontFace("Inter", 400, italic = false), WebFontFace("Inter", 700, italic = true))
      )
    assertEquals(
      "https://fonts.googleapis.com/css2?family=Inter:ital,wght@0,400;1,700&display=swap",
      url,
    )
  }

  @Test
  fun `googleFontsImportUrl rounds Compose intermediate weights`() {
    val url =
      googleFontsImportUrl(
        listOf(
          WebFontFace("Roboto", 599, italic = false),
          WebFontFace("Roboto", 550, italic = true),
        )
      )
    assertEquals(
      "https://fonts.googleapis.com/css2?family=Roboto:ital,wght@0,600;1,600&display=swap",
      url,
    )
  }

  @Test
  fun `generic families are not sent to Google Fonts`() {
    assertNull(googleFontsImportUrl(listOf(WebFontFace("sans-serif", 400, false))))
    // A mix keeps only the real family.
    val url =
      googleFontsImportUrl(
        listOf(WebFontFace("monospace", 400, false), WebFontFace("Roboto Mono", 500, false))
      )
    assertEquals("https://fonts.googleapis.com/css2?family=Roboto+Mono:wght@500&display=swap", url)
  }

  @Test
  fun `linkFigmaRasters rewrites crop hrefs onto the base url`() {
    val svg =
      "<svg><image href=\"ideal__default__dark__compact.figma-raster/232.png\" x=\"1\"/></svg>"
    val base =
      "https://raw.githubusercontent.com/joreilly/Confetti/design-artifacts/confetti-mobile/figma/speakerdetails/"
    val linked = linkFigmaRasters(svg, base)
    assertTrue(
      linked.contains(
        "href=\"https://raw.githubusercontent.com/joreilly/Confetti/design-artifacts/" +
          "confetti-mobile/figma/speakerdetails/ideal__default__dark__compact.figma-raster/232.png\""
      ),
      linked,
    )
  }

  @Test
  fun `linkFigmaRasters leaves traversing or absolute hrefs untouched`() {
    val traversal = "<svg><image href=\"x.figma-raster/../../secret.png\"/></svg>"
    assertEquals(traversal, linkFigmaRasters(traversal, "https://example.com/figma"))
    val absolute = "<svg><image href=\"https://evil.example/x.figma-raster/n.png\"/></svg>"
    assertEquals(absolute, linkFigmaRasters(absolute, "https://example.com/figma"))
    val vectorOnly = "<svg><rect/></svg>"
    assertEquals(vectorOnly, linkFigmaRasters(vectorOnly, "https://example.com/figma"))
  }

  @Test
  fun `downscaleRaster caps the longest edge and preserves aspect`() {
    val big = java.awt.image.BufferedImage(2048, 1024, java.awt.image.BufferedImage.TYPE_INT_ARGB)
    big.createGraphics().run {
      color = java.awt.Color(0x12, 0x34, 0x56)
      fillRect(0, 0, 2048, 1024)
      dispose()
    }
    val out = java.io.ByteArrayOutputStream()
    javax.imageio.ImageIO.write(big, "png", out)
    val png = out.toByteArray()

    val bounded = downscaleRaster(png, 1024)
    val decoded = javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(bounded))
    assertEquals(1024, decoded.width)
    assertEquals(512, decoded.height)
    assertTrue(bounded.size < png.size, "downscale must actually shrink the payload")
  }

  @Test
  fun `downscaleRaster leaves a small or undecodable png untouched`() {
    val small = java.awt.image.BufferedImage(64, 64, java.awt.image.BufferedImage.TYPE_INT_ARGB)
    val out = java.io.ByteArrayOutputStream()
    javax.imageio.ImageIO.write(small, "png", out)
    val png = out.toByteArray()
    assertTrue(png.contentEquals(downscaleRaster(png, 1024)), "within the cap → original bytes")

    val junk = byteArrayOf(1, 2, 3)
    assertTrue(junk.contentEquals(downscaleRaster(junk, 1024)), "undecodable → original bytes")
  }

  @Test
  fun `inlineFigmaRasters embeds a downscaled crop for an oversized raster`() {
    val dir = java.nio.file.Files.createTempDirectory("figma").toFile().also { it.deleteOnExit() }
    val rasterDir = java.io.File(dir, "figma-raster").apply { mkdirs() }
    val big = java.awt.image.BufferedImage(4096, 2048, java.awt.image.BufferedImage.TYPE_INT_ARGB)
    javax.imageio.ImageIO.write(big, "png", java.io.File(rasterDir, "n.png"))
    val svg = "<svg><image href=\"figma-raster/n.png\" x=\"0\" width=\"4096\"/></svg>"

    val inlined =
      inlineFigmaRasters(ee.schimke.composeai.io.SystemFileSystem, dir.absolutePath.toPath(), svg)
    val b64 = Regex("data:image/png;base64,([^\"]+)").find(inlined)?.groupValues?.get(1)
    assertTrue(b64 != null, "raster must be inlined: $inlined")
    val decoded =
      javax.imageio.ImageIO.read(
        java.io.ByteArrayInputStream(java.util.Base64.getDecoder().decode(b64))
      )
    assertEquals(1024, decoded.width, "longest edge capped at MAX_INLINE_RASTER_EDGE_PX")
    assertEquals(512, decoded.height)
  }

  @Test
  fun `webModeSvg with only generic faces keeps the svg self-describing (no import, faces dropped)`() {
    // A `sans-serif` @font-face (Roboto stand-in) yields no Google URL, so the transform makes no
    // claim it can't back — it returns the input unchanged rather than emit a broken @import.
    val input = svg(face("sans-serif", 400))
    assertEquals(input, webModeSvg(input))
  }
}
