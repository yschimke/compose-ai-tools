package ee.schimke.composeai.cli.serve

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Unit coverage for [WebEmbed] — the bundle → web-embed ("js bundle") generator. */
class WebEmbedTest {

  private fun png(w: Int, h: Int): ByteArray {
    val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
    img.setRGB(0, 0, 0x336699)
    val baos = ByteArrayOutputStream()
    ImageIO.write(img, "png", baos)
    return baos.toByteArray()
  }

  @Test
  fun `inline mode bakes one self-contained script and a demo page`() {
    val out =
      WebEmbed.generate(
        title = "My Previews",
        modulePath = ":samples:cmp",
        previews =
          listOf(
            WebEmbed.Preview("a", "HomePreview", png(4, 8), isCover = true),
            WebEmbed.Preview("b", "DetailPreview", png(6, 6)),
          ),
      )

    assertEquals(2, out.previewCount)
    // Inline mode emits exactly the script + the demo page — no separate image files.
    assertEquals(setOf(WebEmbed.SCRIPT_NAME, WebEmbed.INDEX_NAME), out.files.keys)

    val script = out.files.getValue(WebEmbed.SCRIPT_NAME).toString(Charsets.UTF_8)
    // The component is defined and registered.
    assertTrue("customElements.define(\"compose-preview-gallery\"" in script)
    // Both previews' bytes are inlined as data: URIs, so the file is self-contained.
    assertTrue("data:image/png;base64," in script)
    // The IHDR-derived dimensions travelled into the data so the cards avoid layout shift.
    assertTrue("\"width\":4" in script && "\"height\":8" in script)
    // Labels and the cover flag are present.
    assertTrue("HomePreview" in script && "DetailPreview" in script)
    assertTrue("\"cover\":true" in script)

    val index = out.files.getValue(WebEmbed.INDEX_NAME).toString(Charsets.UTF_8)
    assertTrue("<compose-preview-gallery embed=" in index)
    assertTrue("</compose-preview-gallery>" in index)
    assertTrue("<script src=\"${WebEmbed.SCRIPT_NAME}\">" in index)
    assertTrue("My Previews" in index)
  }

  @Test
  fun `external mode writes png files and references them by url`() {
    val bytes = png(10, 20)
    val out =
      WebEmbed.generate(
        title = "t",
        modulePath = ":m",
        previews = listOf(WebEmbed.Preview("only", "OnlyPreview", bytes)),
        mode = WebEmbed.InlineMode.EXTERNAL,
      )

    // The PNG is emitted as a sibling file, byte-identical to the input.
    assertTrue("previews/only.png" in out.files.keys)
    assertEquals(bytes.toList(), out.files.getValue("previews/only.png").toList())

    val script = out.files.getValue(WebEmbed.SCRIPT_NAME).toString(Charsets.UTF_8)
    // The script references the file by relative URL, not a data: URI.
    assertTrue("\"src\":\"previews/only.png\"" in script)
    assertFalse("data:image/png" in script)
  }

  @Test
  fun `titles and labels are html-escaped against injection`() {
    val out =
      WebEmbed.generate(
        title = "<img onerror=alert(1)>",
        modulePath = ":m",
        previews = listOf(WebEmbed.Preview("x", "Plain", png(2, 2))),
      )
    val index = out.files.getValue(WebEmbed.INDEX_NAME).toString(Charsets.UTF_8)
    // The raw tag must not appear unescaped in the demo page <title>.
    assertFalse("<img onerror=alert(1)>" in index)
    assertTrue("&lt;img onerror=alert(1)&gt;" in index)
  }

  @Test
  fun `closing script sequences in data are defused`() {
    // A label containing </script> must not be able to close an inline <script> block if the
    // generated JS is ever pasted inline rather than referenced as an external file.
    val out =
      WebEmbed.generate(
        title = "t",
        modulePath = ":m",
        previews = listOf(WebEmbed.Preview("x", "</script><b>", png(2, 2))),
      )
    val script = out.files.getValue(WebEmbed.SCRIPT_NAME).toString(Charsets.UTF_8)
    assertFalse("</script>" in script)
  }

  @Test
  fun `external image urls are percent-encoded while files keep the raw id`() {
    val bytes = png(2, 2)
    val id = "Foo_#dark and?more"
    val out =
      WebEmbed.generate(
        title = "t",
        modulePath = ":m",
        previews = listOf(WebEmbed.Preview(id, "Foo", bytes)),
        mode = WebEmbed.InlineMode.EXTERNAL,
      )

    // The file on disk keeps the raw id (a single path segment); the URL is percent-encoded so the
    // browser doesn't treat `#dark...` as a fragment or `?more` as a query.
    assertTrue("previews/$id.png" in out.files.keys)
    val script = out.files.getValue(WebEmbed.SCRIPT_NAME).toString(Charsets.UTF_8)
    assertTrue("\"src\":\"previews/Foo_%23dark%20and%3Fmore.png\"" in script)
    // The raw, unencoded URL must not appear.
    assertFalse("\"src\":\"previews/Foo_#dark" in script)
  }

  @Test
  fun `url encoding leaves unreserved characters untouched`() {
    assertEquals("Abc-_.~09", WebEmbed.urlEncodeSegment("Abc-_.~09"))
    assertEquals("a%20b", WebEmbed.urlEncodeSegment("a b"))
    assertEquals("%23%2F%3F", WebEmbed.urlEncodeSegment("#/?"))
  }

  @Test
  fun `two embeds get distinct keys and register into a shared registry`() {
    val a = WebEmbed.generate("A", ":app-a", listOf(WebEmbed.Preview("x", "X", png(2, 2))))
    val b = WebEmbed.generate("B", ":app-b", listOf(WebEmbed.Preview("y", "Y", png(2, 2))))

    val keyA = WebEmbed.embedKey("A", ":app-a", listOf("x"))
    val keyB = WebEmbed.embedKey("B", ":app-b", listOf("y"))
    assertTrue(keyA != keyB)
    // The key is stable across regenerations of the same bundle.
    assertEquals(keyA, WebEmbed.embedKey("A", ":app-a", listOf("x")))

    val scriptA = a.files.getValue(WebEmbed.SCRIPT_NAME).toString(Charsets.UTF_8)
    val scriptB = b.files.getValue(WebEmbed.SCRIPT_NAME).toString(Charsets.UTF_8)
    // Both scripts push into the same global registry rather than closing over a single constant.
    assertTrue("window.__composePreviewEmbeds__" in scriptA)
    assertTrue("\"key\":\"$keyA\"" in scriptA)
    assertTrue("\"key\":\"$keyB\"" in scriptB)

    // Each demo page targets its own embed by key, so combining them shows the right bundle each.
    val indexA = a.files.getValue(WebEmbed.INDEX_NAME).toString(Charsets.UTF_8)
    assertTrue("<compose-preview-gallery embed=\"$keyA\">" in indexA)
  }

  @Test
  fun `png dimensions read the IHDR width and height`() {
    assertEquals(13 to 27, WebEmbed.pngDimensions(png(13, 27)))
    // Non-PNG bytes fall back to "unknown" rather than throwing.
    assertEquals(0 to 0, WebEmbed.pngDimensions(byteArrayOf(1, 2, 3)))
  }

  @Test
  fun `data uri round-trips the original png bytes`() {
    val bytes = png(3, 5)
    val out = WebEmbed.generate("t", ":m", listOf(WebEmbed.Preview("x", "X", bytes)))
    val script = out.files.getValue(WebEmbed.SCRIPT_NAME).toString(Charsets.UTF_8)
    val b64 = Base64.getEncoder().encodeToString(bytes)
    assertTrue("data:image/png;base64,$b64" in script)
  }
}
