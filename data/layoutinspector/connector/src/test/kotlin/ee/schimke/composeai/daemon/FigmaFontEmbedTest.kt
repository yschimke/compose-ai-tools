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
    // The resource-font registry is process-wide by design (a resId maps to the same bytes for the
    // life of the render JVM), so a test that seeds it must not leak into its siblings.
    FigmaResourceFonts.clear()
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
  fun resolverRetriesTransientFailureThenSucceeds() {
    // The first CSS fetch fails (a cold DNS/TLS path on a fresh daemon subprocess); the retry
    // succeeds. The face must embed rather than be permanently stranded on one transient miss.
    val css = "/* latin */ src: url(https://fonts.gstatic.com/lat.woff2) format('woff2');"
    var cssAttempts = 0
    val http: (String, String) -> ByteArray? = { url, _ ->
      when {
        url.contains("css2") -> if (++cssAttempts >= 2) css.toByteArray() else null
        url.endsWith("lat.woff2") -> byteArrayOf(1, 2, 3, 4)
        else -> null
      }
    }
    val slept = mutableListOf<Long>()
    val resolver =
      GoogleFontsWoff2Resolver(
        cacheDir = dir,
        offline = false,
        httpGet = http,
        sleep = { slept.add(it) },
      )

    assertArrayEquals(byteArrayOf(1, 2, 3, 4), resolver.woff2("Roboto Flex", 500, false))
    assertEquals("retried the failed CSS fetch once", 2, cssAttempts)
    assertEquals("backed off once between the two attempts", listOf(300L), slept)
  }

  @Test
  fun resolverGivesUpAfterMaxAttempts() {
    // A genuinely unresolvable family exhausts the bounded attempts and returns null (the same
    // degradation as before, just after a bounded wait) — it never loops forever.
    var attempts = 0
    val http: (String, String) -> ByteArray? = { _, _ ->
      attempts++
      null
    }
    val slept = mutableListOf<Long>()
    val resolver =
      GoogleFontsWoff2Resolver(
        cacheDir = dir,
        offline = false,
        httpGet = http,
        maxAttempts = 3,
        sleep = { slept.add(it) },
      )

    assertEquals(null, resolver.woff2("No Such Family", 400, false))
    assertEquals("one CSS fetch per attempt", 3, attempts)
    assertEquals("exponential backoff between attempts", listOf(300L, 600L), slept)
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
  fun writeSvgEmbedsAndNamesADownloadableBrandedFamily() {
    // Regression: a branded downloadable face (`Font(GoogleFont("Orbitron"), …)`) now captures its
    // family name (see `googleFontFamilyName`) instead of null, so the export fetches + embeds the
    // real Orbitron face and names the `<text>` by it — rather than collapsing to the Roboto
    // default
    // that made the published meshcore sticker render in Roboto (incl. `?mode=web`, whose @import
    // is
    // derived from these @font-face blocks).
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
                text = "MeshCore",
                typography =
                  ComposeSemanticsTypography(
                    fontSize = "16.0sp",
                    fontWeight = 500,
                    fontFamily = "Orbitron",
                  ),
              )
            ),
        )
      )
    val resolver = FigmaFontResolver { family, weight, italic ->
      if (family == "Orbitron" && weight == 500 && !italic) byteArrayOf(4, 2) else null
    }

    ComposeFigmaSvgDataProducer.writeSvg(
      rootDir = dir,
      previewId = "p",
      layout = LayoutInspectorPayload(textNode()),
      semantics = semantics,
      fontResolver = resolver,
    )

    val svg = dir.resolve("p").resolve(ComposeFigmaSvgDataProducer.FILE_SVG).readText()
    assertTrue("embeds the branded downloadable face", svg.contains("font-family:'Orbitron'"))
    assertTrue("text names the branded family", svg.contains("""font-family="Orbitron"""))
    assertFalse("branded family must not collapse to Roboto", svg.contains("'Roboto'"))
  }

  @Test
  fun writeSvgEmbedsAConcreteSerifForAGenericSerifSpecimen() {
    // A `FontFamily.Serif` specimen captures the generic name "serif" (Compose resolves the real
    // face out of reach). The export must embed a concrete *serif* (Noto Serif) and name the text
    // by it — not collapse to the sans default, which erased serif/monospace specimens' identity.
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
                    fontWeight = 400,
                    fontFamily = "serif",
                  ),
              )
            ),
        )
      )
    val resolver = FigmaFontResolver { family, weight, italic ->
      if (family == "Noto Serif" && weight == 400 && !italic) byteArrayOf(7, 7, 7) else null
    }

    ComposeFigmaSvgDataProducer.writeSvg(
      rootDir = dir,
      previewId = "p",
      layout = LayoutInspectorPayload(textNode()),
      semantics = semantics,
      fontResolver = resolver,
    )

    val svg = dir.resolve("p").resolve(ComposeFigmaSvgDataProducer.FILE_SVG).readText()
    assertTrue("embeds a concrete serif face", svg.contains("font-family:'Noto Serif'"))
    assertTrue("text names the embedded serif", svg.contains("""font-family="Noto Serif""""))
    assertFalse("serif must not collapse to the Roboto sans default", svg.contains("'Roboto'"))
  }

  @Test
  fun writeSvgVectorOnlyKeepsSerifGenericForTheViewer() {
    // Without embedding, a serif specimen keeps `font-family="serif"` so the viewer renders a real
    // serif — the previous default swallowed it into `sans-serif`.
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
                typography = ComposeSemanticsTypography(fontSize = "16.0sp", fontFamily = "serif"),
              )
            ),
        )
      )
    ComposeFigmaSvgDataProducer.writeSvg(
      rootDir = dir,
      previewId = "p",
      layout = LayoutInspectorPayload(textNode()),
      semantics = semantics,
    )
    val svg = dir.resolve("p").resolve(ComposeFigmaSvgDataProducer.FILE_SVG).readText()
    assertFalse("vector-only, no embedding", svg.contains("@font-face"))
    assertTrue("keeps the serif generic", svg.contains("""font-family="serif""""))
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
  fun writeSvgEmbedsAnAndroidResourceFontRecoveredByTheRender() {
    // Issue #2886. An Android resource-backed family (`FontFamily(Font(R.font.montserrat_regular,
    // …))`) reaches the capture as the bare `res/font/<resId>` handle — a numeric id that names
    // nothing a browser or Figma can resolve, and that used to be emitted verbatim as the CSS
    // family with no matching `@font-face`, so text silently fell back to sans-serif and its glyph
    // widths / line wrapping / ellipsis positions drifted from the PNG. The render side now
    // extracts the resource's bytes and publishes the file here; the export must embed *that* file
    // and name the `<text>` after it, never after the id.
    val fontFile = File(dir, "Montserrat-Regular.ttf").apply { writeBytes(byteArrayOf(10, 20, 30)) }
    val identity = FigmaResourceFonts.identityFor(2131230721)
    FigmaResourceFonts.register(identity, fontFile.absolutePath)
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
                typography = ComposeSemanticsTypography(fontSize = "16.0sp", fontFamily = identity),
              )
            ),
        )
      )
    // The resolver returns nothing: a recovered resource face must never take the Google fetch.
    val resolver = FigmaFontResolver { _, _, _ -> null }

    ComposeFigmaSvgDataProducer.writeSvg(
      rootDir = dir,
      previewId = "p",
      layout = LayoutInspectorPayload(textNode()),
      semantics = semantics,
      fontResolver = resolver,
    )

    val svg = dir.resolve("p").resolve(ComposeFigmaSvgDataProducer.FILE_SVG).readText()
    assertTrue("embeds the recovered resource face", svg.contains("format('truetype')"))
    assertTrue("uses the resource's own bytes", svg.contains("data:font/ttf;base64,ChQe"))
    assertTrue("the face is named", svg.contains("font-family:'Montserrat-Regular'"))
    assertTrue(
      "the text names the embedded family",
      svg.contains("""font-family="Montserrat-Regular"""),
    )
    assertFalse("the numeric resource id must never reach the SVG", svg.contains("2131230721"))
  }

  @Test
  fun writeSvgSkipsTtcCollectionsRatherThanEmbedThemAsTruetype() {
    // A `.ttc` collection can't be embedded as a bare `format('truetype')` src (it needs
    // `format('collection')` + a face selection we don't emit), so the file path is not treated as
    // an embeddable font — it falls through to the name path (which here resolves nothing).
    val ttc = File(dir, "SomeCollection.ttc").apply { writeBytes(byteArrayOf(1, 2, 3)) }
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
                  ComposeSemanticsTypography(fontSize = "16.0sp", fontFamily = ttc.absolutePath),
              )
            ),
        )
      )
    ComposeFigmaSvgDataProducer.writeSvg(
      rootDir = dir,
      previewId = "p",
      layout = LayoutInspectorPayload(textNode()),
      semantics = semantics,
      fontResolver = FigmaFontResolver { _, _, _ -> null },
    )
    val svg = dir.resolve("p").resolve(ComposeFigmaSvgDataProducer.FILE_SVG).readText()
    assertFalse("a .ttc must not be embedded as truetype", svg.contains("@font-face"))
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
