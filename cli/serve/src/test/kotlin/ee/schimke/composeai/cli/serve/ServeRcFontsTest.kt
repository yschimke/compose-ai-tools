package ee.schimke.composeai.cli.serve

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The vendored typefaces the served viewer registers for its client-side Remote Compose lanes.
 *
 * The load-bearing assertion here is the **first** one: the face files reach the jar through a
 * `processResources` copy in `cli/build.gradle.kts`, so a declared face whose file was never staged
 * would otherwise degrade silently — the stylesheet would simply omit it and the lane would paint
 * that generic in the visitor's own fallback, which is exactly the bug (#3480) this closes and is
 * invisible in the output. Whether the table itself still matches the offline parity harness's is
 * checked from the other side, in `scripts/design-artifacts/rc-fonts.test.mjs`.
 */
class ServeRcFontsTest {

  @Test
  fun `every declared face is staged into the jar`() {
    for (face in ServeRcFonts.FACES) {
      val resource =
        assertNotNull(ServeRcFonts.resourceFor(face.file), "${face.file} resource path")
      val bytes =
        ServeRcFonts::class.java.getResourceAsStream(resource)?.use { it.readBytes() }
          ?: error(
            "${face.file} is declared by ServeRcFonts but not on the classpath — add it to " +
              "stageRcFontResources in cli/build.gradle.kts"
          )
      assertTrue(bytes.size > 1024, "${face.file} looks truncated (${bytes.size} bytes)")
      // TrueType's sfnt version, so a staged-but-wrong file (an OFL text file, an LFS
      // pointer) fails here rather than as an unexplained fallback in someone's browser.
      assertEquals(
        listOf(0x00, 0x01, 0x00, 0x00),
        bytes.take(4).map { it.toInt() and 0xff },
        "${face.file} is not a TrueType file",
      )
    }
  }

  @Test
  fun `stylesheet declares each face against its served url`() {
    val css = ServeRcFonts.css()
    for (face in ServeRcFonts.FACES) {
      assertTrue(
        css.contains(
          "@font-face{font-family:\"${face.family}\";font-weight:${face.weightRange};" +
            "font-style:normal;src:url(\"/rc-fonts/${face.file}\") format(\"truetype\");}"
        ),
        "missing rule for ${face.file}:\n$css",
      )
    }
    assertEquals(ServeRcFonts.FACES.size, css.trim().lines().size)
  }

  @Test
  fun `the weight ranges are contiguous so an in-between request resolves to a real file`() {
    // Wear M3's `bodyLarge` asks for 450, and CSS matching for a 400..500 target searches upward
    // first: declared at discrete weights, that lands on Medium and renders heavier than the baked
    // raster. Regular must therefore serve everything below Medium's nominal 500.
    val roboto = ServeRcFonts.FACES.filter { it.family == "Roboto" }
    assertEquals(listOf("1 499", "500 1000"), roboto.map { it.weightRange })
  }

  @Test
  fun `only declared faces resolve to a resource`() {
    assertNull(ServeRcFonts.resourceFor("../rc-player/bundle.js"))
    assertNull(ServeRcFonts.resourceFor("Roboto-Black.ttf"))
    assertNull(ServeRcFonts.resourceFor(""))
  }

  @Test
  fun `a preview carrying a remote compose document registers the faces`() {
    val preview = ServePreview("plain.Button", "button")
    val withDoc =
      ServeWeb.viewerPage(
        preview,
        token = "t",
        siblings = listOf(preview),
        hasRemoteComposeDoc = true,
      )
    assertTrue(withDoc.contains(ServeRcFonts.linkTag()), withDoc)
    // Declaring the faces is only half of it — they also have to be *loaded* before canvas paints,
    // which is `window.cpRcFonts` in the component bundle (`cli/serve-web/src/rcFonts.ts`, tested
    // in `cli/serve-web/test/rcFonts.test.ts`). The viewer already loads that bundle for its
    // elements, so what this pins is that the page carrying a document carries the loader at all.
    assertTrue(
      withDoc.contains("""<script src="${ServeWebAssets.href("serve-components.js")}"></script>"""),
      withDoc,
    )

    // A preview with no document has no client-side lane to serve, so the page is untouched.
    val withoutDoc = ServeWeb.viewerPage(preview, token = "t", siblings = listOf(preview))
    assertFalse(withoutDoc.contains("/rc-fonts/"), withoutDoc)
  }
}
