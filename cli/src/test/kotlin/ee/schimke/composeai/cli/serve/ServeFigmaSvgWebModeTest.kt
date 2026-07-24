package ee.schimke.composeai.cli.serve

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
  fun `webModeSvg with only generic faces keeps the svg self-describing (no import, faces dropped)`() {
    // A `sans-serif` @font-face (Roboto stand-in) yields no Google URL, so the transform makes no
    // claim it can't back — it returns the input unchanged rather than emit a broken @import.
    val input = svg(face("sans-serif", 400))
    assertEquals(input, webModeSvg(input))
  }
}
