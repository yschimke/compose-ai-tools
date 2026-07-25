package ee.schimke.composeai.daemon

import ee.schimke.composeai.data.layoutinspector.ComposeSemanticsNode
import ee.schimke.composeai.data.layoutinspector.ComposeSemanticsPayload
import ee.schimke.composeai.data.layoutinspector.ComposeSemanticsTypography
import ee.schimke.composeai.data.layoutinspector.LayoutInspectorBounds
import ee.schimke.composeai.data.layoutinspector.LayoutInspectorNode
import ee.schimke.composeai.data.layoutinspector.LayoutInspectorPayload
import ee.schimke.composeai.data.layoutinspector.LayoutInspectorSize
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The export must tell two identical-looking situations apart:
 * - a preview that genuinely uses the platform default (no captured family, nothing branded drawn)
 *   — which correctly exports as the Material default, quietly;
 * - a preview whose branded family was lost somewhere between the render and the capture — which
 *   used to export as the Material default *too*, and is how a whole sticker sheet shipped in the
 *   wrong typeface without anyone noticing.
 *
 * The render's own record of what it drew ([FigmaSvgRenderedFonts]) is the only thing separating
 * them, so these tests drive that directly.
 */
class FigmaSvgTofuFallbackTest {

  @get:Rule val tempFolder: TemporaryFolder = TemporaryFolder()

  private val dir
    get() = tempFolder.root

  @Before fun reset() = FigmaSvgRenderedFonts.begin()

  @After fun cleanup() = FigmaSvgRenderedFonts.begin()

  /** A one-text tree whose typography carries no family — the shape a lost family produces. */
  private fun payload(family: String? = null) =
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
                  fontFamily = family,
                ),
            )
          ),
      )
    )

  /** Mirrors `FigmaFontEmbedTest.textNode()` — a Screen wrapping one Text at the same bounds. */
  private fun layout() =
    LayoutInspectorPayload(
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
    )

  private fun writeSvg(previewId: String): String {
    ComposeFigmaSvgDataProducer.writeSvg(
      rootDir = dir,
      previewId = previewId,
      layout = layout(),
      semantics = payload(),
    )
    return dir.resolve(previewId).resolve(ComposeFigmaSvgDataProducer.FILE_SVG).readText()
  }

  private fun warnings(previewId: String) =
    dir.resolve(previewId).resolve(ComposeFigmaSvgDataProducer.FILE_FONT_WARNINGS)

  @Test
  fun `text whose rendered family the export cannot name is exported as boxes`() {
    // The render drew Orbitron; the capture lost it, so the export has nothing to name.
    FigmaSvgRenderedFonts.record("Orbitron")

    val svg = writeSvg("lost-family")

    assertTrue("must name the tofu family", svg.contains(TofuFont.FAMILY))
    assertTrue("must embed the tofu face", svg.contains("@font-face"))
    assertFalse(
      "must not silently substitute the Material default",
      svg.contains("font-family=\"${ComposeFigmaSvgDataProducer.DEFAULT_EMBED_FAMILY}"),
    )
  }

  @Test
  fun `the degraded export records which face it could not reproduce`() {
    FigmaSvgRenderedFonts.record("Orbitron")

    writeSvg("lost-family")

    val sidecar = warnings("lost-family")
    assertTrue("a degraded export must leave a warning", sidecar.exists())
    val json = sidecar.readText()
    assertTrue("must name the unreproducible face, got $json", json.contains("Orbitron"))
    assertTrue("must say what it substituted, got $json", json.contains(TofuFont.FAMILY))
  }

  @Test
  fun `a preview that legitimately uses the platform default stays quiet`() {
    // Nothing branded was drawn, so an absent captured family is the truth, not a defect.
    val svg = writeSvg("stock-material")

    assertFalse("must not tofu ordinary default-font text", svg.contains(TofuFont.FAMILY))
    assertFalse("must not warn", warnings("stock-material").exists())
  }

  @Test
  fun `a face the export does name does not trigger the fallback`() {
    FigmaSvgRenderedFonts.record("Orbitron")

    ComposeFigmaSvgDataProducer.writeSvg(
      rootDir = dir,
      previewId = "named",
      layout = layout(),
      semantics = payload(family = "Orbitron"),
    )

    val svg = dir.resolve("named").resolve(ComposeFigmaSvgDataProducer.FILE_SVG).readText()
    assertTrue("the real family must survive", svg.contains("Orbitron"))
    assertFalse("nothing was lost, so no boxes", svg.contains(TofuFont.FAMILY))
    assertFalse("nothing was lost, so no warning", warnings("named").exists())
  }

  @Test
  fun `the cross-check ignores case so the two naming routes agree`() {
    // A family reaches the recorder as a declared GoogleFont name and the export as a name read out
    // of font bytes; casing between those is not guaranteed to match.
    FigmaSvgRenderedFonts.record("orbitron")

    ComposeFigmaSvgDataProducer.writeSvg(
      rootDir = dir,
      previewId = "case",
      layout = layout(),
      semantics = payload(family = "Orbitron"),
    )

    assertFalse(
      "a case difference must not read as a lost family",
      dir
        .resolve("case")
        .resolve(ComposeFigmaSvgDataProducer.FILE_SVG)
        .readText()
        .contains(TofuFont.FAMILY),
    )
  }
}
