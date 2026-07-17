package ee.schimke.composeai.cli.serve

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Unit coverage for the pure Storybook-compat id / index / iframe logic ([StorybookCompat]). */
class StorybookCompatTest {

  private fun preview(id: String, label: String) = ServePreview(id = id, label = label)

  private fun pngBytes(w: Int, h: Int): ByteArray =
    ByteArrayOutputStream()
      .also { ImageIO.write(BufferedImage(w, h, BufferedImage.TYPE_INT_RGB), "png", it) }
      .toByteArray()

  @Test
  fun `sanitize follows the CSF kebab convention`() {
    assertEquals("red-box", StorybookCompat.sanitize("Red Box"))
    assertEquals("previews", StorybookCompat.sanitize("  Previews  "))
    assertEquals("a-b-c", StorybookCompat.sanitize("a.b/c"))
    assertEquals("", StorybookCompat.sanitize("---"))
  }

  @Test
  fun `toId joins title and name with a double dash`() {
    assertEquals("previews--red-box", StorybookCompat.toId("Previews", "Red Box"))
    // A blank side is dropped rather than leaving a dangling separator.
    assertEquals("greeting", StorybookCompat.toId("", "Greeting"))
    assertEquals("previews", StorybookCompat.toId("Previews", "!!!"))
  }

  @Test
  fun `story ids derive class-grouped titles from an FQN`() {
    val stories =
      StorybookCompat.stories(
        listOf(
          preview("com.example.PreviewsKt.RedBoxPreview_Red Box", "Red Box"),
          preview("com.example.MainKt.GreetingPreview", "Greeting"),
        )
      )
    assertEquals(listOf("previews--red-box", "main--greeting"), stories.map { it.storyId })
    assertEquals("Previews", stories[0].title)
    assertEquals("Red Box", stories[0].name)
    // Round-trips back to the native preview id.
    assertEquals("com.example.PreviewsKt.RedBoxPreview_Red Box", stories[0].previewId)
  }

  @Test
  fun `catalog axis ids group by the component slug`() {
    val stories =
      StorybookCompat.stories(
        listOf(preview("button__dark", "Button"), preview("button__light", "Button"))
      )
    assertEquals("button", stories[0].title)
    // Two previews minting the same slug get a deterministic collision suffix, staying 1:1.
    assertNotEquals(stories[0].storyId, stories[1].storyId)
    assertEquals("button--button", stories[0].storyId)
    assertEquals("button--button-2", stories[1].storyId)
  }

  @Test
  fun `index carries the version and one entry per preview`() {
    val index =
      StorybookCompat.index(listOf(preview("com.example.MainKt.GreetingPreview", "Greeting")))
    assertEquals(StorybookCompat.INDEX_VERSION, index.v)
    val entry = index.entries.getValue("main--greeting")
    assertEquals("main--greeting", entry.id)
    assertEquals("story", entry.type)
    assertEquals("virtual:compose-preview/com.example.MainKt.GreetingPreview", entry.importPath)
    assertTrue(entry.tags.contains("compose-preview"))
  }

  @Test
  fun `resolvePreviewId accepts both the minted story id and a raw native id`() {
    val previews = listOf(preview("com.example.MainKt.GreetingPreview", "Greeting"))
    assertEquals(
      "com.example.MainKt.GreetingPreview",
      StorybookCompat.resolvePreviewId("main--greeting", previews),
    )
    // Native id escape hatch.
    assertEquals(
      "com.example.MainKt.GreetingPreview",
      StorybookCompat.resolvePreviewId("com.example.MainKt.GreetingPreview", previews),
    )
    assertNull(StorybookCompat.resolvePreviewId("no--such", previews))
  }

  @Test
  fun `an advertised story id wins over a colliding native preview id`() {
    // Preview B's native id is literally "greeting"; preview A (labelled "Greeting" in file
    // Main.kt)
    // mints the story id "main--greeting". Now craft the collision: give B a native id equal to A's
    // minted id. `/index.json` advertises "main--greeting" for A, so it must round-trip to A even
    // though a preview whose raw id is "main--greeting" also exists.
    val a = preview("com.example.MainKt.GreetingPreview", "Greeting")
    val b = preview("main--greeting", "Other")
    val previews = listOf(a, b)
    // Sanity: A really does mint that id.
    assertEquals(
      "main--greeting",
      StorybookCompat.index(previews).entries.getValue("main--greeting").id,
    )
    // Advertised id resolves to A (round-trips), not to the raw-id preview B.
    assertEquals(
      "com.example.MainKt.GreetingPreview",
      StorybookCompat.resolvePreviewId("main--greeting", previews),
    )
    // B is still reachable by its own advertised story id.
    val bStoryId =
      StorybookCompat.stories(previews).first { it.previewId == "main--greeting" }.storyId
    assertEquals("main--greeting", StorybookCompat.resolvePreviewId(bStoryId, previews))
  }

  @Test
  fun `iframe page embeds the png as a data uri sized to its pixels`() {
    val page = StorybookCompat.iframePage("main--greeting", pngBytes(24, 8))
    assertTrue(page.startsWith("<!doctype html>"), "is an html document")
    assertTrue(page.contains("src=\"data:image/png;base64,"), "inlines the png: $page")
    assertTrue(page.contains("width=\"24\" height=\"8\""), "sizes to the png dimensions: $page")
    assertTrue(page.contains("main--greeting"), "labels with the story id")
  }

  @Test
  fun `iframe svg page embeds the vector as an inert svg image not inline markup`() {
    // A hostile SVG (e.g. from an unverified catalog) must not run when the page is opened. Serving
    // it via <img src=data:image/svg+xml> keeps it in the browser's restricted (non-scripting)
    // mode.
    val svg =
      "<?xml version=\"1.0\"?>\n" +
        "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"10\" height=\"4\">" +
        "<script>alert(1)</script><rect width=\"10\" height=\"4\"/></svg>"
    val page = StorybookCompat.iframeSvgPage("main--greeting", svg.toByteArray())
    assertTrue(page.startsWith("<!doctype html>"), "is an html document")
    assertTrue(page.contains("src=\"data:image/svg+xml;base64,"), "embeds as an svg image: $page")
    // The raw SVG (and any embedded <script>) is base64'd inside the data URI — never live markup.
    assertTrue(!page.contains("<svg"), "no inline svg markup in the document: $page")
    assertTrue(!page.contains("<script>alert"), "no live script from the svg: $page")
    assertTrue(page.contains("main--greeting"), "labels with the story id")
  }
}
