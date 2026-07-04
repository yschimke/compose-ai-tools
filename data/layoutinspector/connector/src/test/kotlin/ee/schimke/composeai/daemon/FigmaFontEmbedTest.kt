package ee.schimke.composeai.daemon

import ee.schimke.composeai.data.layoutinspector.ComposeSemanticsNode
import ee.schimke.composeai.data.layoutinspector.ComposeSemanticsPayload
import ee.schimke.composeai.data.layoutinspector.ComposeSemanticsTypography
import ee.schimke.composeai.data.layoutinspector.LayoutInspectorBounds
import ee.schimke.composeai.data.layoutinspector.LayoutInspectorNode
import ee.schimke.composeai.data.layoutinspector.LayoutInspectorPayload
import ee.schimke.composeai.data.layoutinspector.LayoutInspectorSize
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Google-font embedding for the `compose/figma-svg` export: URL/CSS parsing, cache, and wiring. */
class FigmaFontEmbedTest {
  private lateinit var dir: File

  @Before
  fun setUp() {
    dir = Files.createTempDirectory("figma-font-embed").toFile()
  }

  @After
  fun tearDown() {
    dir.deleteRecursively()
  }

  @Test
  fun cssUrlEncodesFamilyAndAxis() {
    assertTrue(
      GoogleFontsWoff2Resolver.cssUrl("Roboto", 700, false).contains("family=Roboto:wght@700")
    )
    assertTrue(
      GoogleFontsWoff2Resolver.cssUrl("Roboto", 400, true).contains("family=Roboto:ital,wght@1,400")
    )
    assertTrue(GoogleFontsWoff2Resolver.cssUrl("Noto Sans", 400, false).contains("Noto%20Sans"))
  }

  @Test
  fun firstWoff2UrlPrefersLatinSubset() {
    val css =
      """
      /* cyrillic */
      @font-face { src: url(https://fonts.gstatic.com/cyr.woff2) format('woff2'); }
      /* latin */
      @font-face { src: url(https://fonts.gstatic.com/lat.woff2) format('woff2'); }
      """
        .trimIndent()
    assertEquals("https://fonts.gstatic.com/lat.woff2", GoogleFontsWoff2Resolver.firstWoff2Url(css))
  }

  @Test
  fun firstWoff2UrlFallsBackToAnySubset() {
    val css = "@font-face { src: url(https://fonts.gstatic.com/only.woff2) format('woff2'); }"
    assertEquals(
      "https://fonts.gstatic.com/only.woff2",
      GoogleFontsWoff2Resolver.firstWoff2Url(css),
    )
  }

  @Test
  fun resolverFetchesThenServesFromCache() {
    val calls = mutableListOf<String>()
    val css = "/* latin */ src: url(https://fonts.gstatic.com/lat.woff2) format('woff2');"
    val http: (String, String) -> ByteArray? = { url, _ ->
      calls.add(url)
      when {
        url.contains("css2") -> css.toByteArray()
        url.endsWith("lat.woff2") -> byteArrayOf(1, 2, 3, 4)
        else -> null
      }
    }
    val resolver = GoogleFontsWoff2Resolver(cacheDir = dir, offline = false, httpGet = http)

    assertArrayEquals(byteArrayOf(1, 2, 3, 4), resolver.woff2("Roboto", 400, false))
    assertTrue("caches under the renderer's slug scheme", File(dir, "roboto-400.woff2").exists())
    val afterFirst = calls.size
    // Second call is served from disk — no further HTTP.
    assertArrayEquals(byteArrayOf(1, 2, 3, 4), resolver.woff2("Roboto", 400, false))
    assertEquals(afterFirst, calls.size)
  }

  @Test
  fun offlineResolverReturnsNullOnCacheMiss() {
    val http: (String, String) -> ByteArray? = { _, _ ->
      error("must not hit network when offline")
    }
    val resolver = GoogleFontsWoff2Resolver(cacheDir = dir, offline = true, httpGet = http)
    assertEquals(null, resolver.woff2("Roboto", 400, false))
  }

  private fun textNode() =
    LayoutInspectorNode(
      nodeId = "Screen",
      component = "Screen",
      bounds = LayoutInspectorBounds(0, 0, 200, 100),
      size = LayoutInspectorSize(200, 100),
      children =
        listOf(
          LayoutInspectorNode(
            nodeId = "Text",
            component = "Text",
            bounds = LayoutInspectorBounds(8, 8, 192, 40),
            size = LayoutInspectorSize(184, 32),
          )
        ),
    )

  @Test
  fun writeSvgEmbedsResolvedFacesForTextNodes() {
    val semantics =
      ComposeSemanticsPayload(
        ComposeSemanticsNode(
          nodeId = "root",
          boundsInRoot = "0,0,200,100",
          children =
            listOf(
              ComposeSemanticsNode(
                nodeId = "Text",
                boundsInRoot = "8,8,192,40",
                text = "Hi",
                typography = ComposeSemanticsTypography(fontSize = "16.0sp", fontWeight = 400),
              )
            ),
        )
      )
    val resolver = FigmaFontResolver { family, weight, italic ->
      if (family == "Roboto" && weight == 400 && !italic) byteArrayOf(9, 9, 9) else null
    }

    ComposeFigmaSvgDataProducer.writeSvg(
      rootDir = dir,
      previewId = "p",
      layout = LayoutInspectorPayload(textNode()),
      semantics = semantics,
      fontResolver = resolver,
    )

    val svg = dir.resolve("p").resolve(ComposeFigmaSvgDataProducer.FILE_SVG).readText()
    assertTrue("embeds the resolved face", svg.contains("@font-face"))
    assertTrue("names the default Material family", svg.contains("font-family:'Roboto'"))
    assertTrue("base64 of {9,9,9}", svg.contains("data:font/woff2;base64,CQkJ"))
    assertFalse(
      "no bare sans-serif default when embedding",
      svg.contains("""font-family="sans-serif""""),
    )
  }

  @Test
  fun writeSvgEmbedsTheActualFontFileTheRenderLoaded() {
    // The capture records the *path* of the font the render loaded (a downloaded/bundled/custom
    // face). The export must embed that file's bytes directly — no name-based Google fetch — and
    // name the `<text>` by the font's family so it matches the `@font-face`.
    val fontFile = File(dir, "MyFont.ttf").apply { writeBytes(byteArrayOf(10, 20, 30)) }
    val semantics =
      ComposeSemanticsPayload(
        ComposeSemanticsNode(
          nodeId = "root",
          boundsInRoot = "0,0,200,100",
          children =
            listOf(
              ComposeSemanticsNode(
                nodeId = "Text",
                boundsInRoot = "8,8,192,40",
                text = "Hi",
                typography =
                  ComposeSemanticsTypography(
                    fontSize = "16.0sp",
                    fontFamily = fontFile.absolutePath,
                  ),
              )
            ),
        )
      )
    // Resolver returns nothing — the file path must NOT go through the Google fetch.
    val resolver = FigmaFontResolver { _, _, _ -> null }

    ComposeFigmaSvgDataProducer.writeSvg(
      rootDir = dir,
      previewId = "p",
      layout = LayoutInspectorPayload(textNode()),
      semantics = semantics,
      fontResolver = resolver,
    )

    val svg = dir.resolve("p").resolve(ComposeFigmaSvgDataProducer.FILE_SVG).readText()
    assertTrue("embeds a truetype @font-face", svg.contains("format('truetype')"))
    assertTrue("uses the ttf mime + the file's bytes", svg.contains("data:font/ttf;base64,ChQe"))
    // Garbage bytes → AWT can't read a family, so we fall back to the file stem, and the text names
    // it so it matches the embedded face.
    assertTrue("face named by the font (stem fallback here)", svg.contains("font-family:'MyFont'"))
    assertTrue("text uses the embedded family", svg.contains("""font-family="MyFont""""))
  }

  @Test
  fun writeSvgWithoutResolverStaysVectorOnly() {
    ComposeFigmaSvgDataProducer.writeSvg(
      rootDir = dir,
      previewId = "p",
      layout = LayoutInspectorPayload(textNode()),
    )
    val svg = dir.resolve("p").resolve(ComposeFigmaSvgDataProducer.FILE_SVG).readText()
    assertFalse(svg.contains("@font-face"))
    assertTrue(svg.contains("""font-family="sans-serif""""))
  }
}
