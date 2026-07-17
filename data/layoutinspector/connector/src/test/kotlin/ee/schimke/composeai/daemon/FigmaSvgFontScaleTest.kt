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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The `compose/figma-svg` export must size `sp` text at the render's **font scale**. Compose sizes
 * `sp` text as `sp × density × fontScale`, and the layer geometry the export places text into is
 * captured *after* that fontScale is applied (the boxes were measured for scaled text). So a
 * `fontScale` render whose export still emits text at 1.0 draws undersized glyphs floating in
 * oversized boxes — the drift the compare page surfaces. These tests pin that the emitted `<text
 * font-size>` carries the fontScale.
 */
class FigmaSvgFontScaleTest {
  private lateinit var dir: File

  @Before
  fun setUp() {
    dir = Files.createTempDirectory("figma-font-scale").toFile()
  }

  @After
  fun tearDown() {
    dir.deleteRecursively()
  }

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

  private fun semantics() =
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
                  lineHeight = "24.0sp",
                  letterSpacing = "1.0sp",
                ),
            )
          ),
      )
    )

  /** The first `<text …>`'s `font-size` value, or null when the export emitted no sized text. */
  private fun fontSizeOf(svg: String): Double? =
    Regex("<text\\b[^>]*\\bfont-size=\"([0-9.]+)\"").find(svg)?.groupValues?.get(1)?.toDouble()

  private fun render(density: Float, fontScale: Float): String {
    ComposeFigmaSvgDataProducer.writeSvg(
      rootDir = dir,
      previewId = "p",
      layout = layout(),
      semantics = semantics(),
      density = density,
      fontScale = fontScale,
    )
    return dir.resolve("p").resolve(ComposeFigmaSvgDataProducer.FILE_SVG).readText()
  }

  @Test
  fun defaultFontScaleEmitsSpTimesDensity() {
    // 16.0sp × density 2.0 × fontScale 1.0 = 32px.
    val fs = fontSizeOf(render(density = 2f, fontScale = 1f))
    assertNotNull("export emits a sized <text>", fs)
    assertEquals(32.0, fs!!, 0.01)
  }

  @Test
  fun fontScaleMultipliesTheEmittedTextSize() {
    val base = fontSizeOf(render(density = 2f, fontScale = 1f))
    dir.resolve("p").deleteRecursively()
    val scaled = fontSizeOf(render(density = 2f, fontScale = 2f))
    assertNotNull(base)
    assertNotNull(scaled)
    // 16.0sp × density 2.0 × fontScale 2.0 = 64px — twice the un-scaled size.
    assertEquals(64.0, scaled!!, 0.01)
    assertEquals(2.0, scaled / base!!, 0.01)
  }

  @Test
  fun fontScaleAlsoScalesLineHeightAndLetterSpacing() {
    // line-height and letter-spacing are sp metrics too, so they carry the same fontScale — a
    // faithful vector grows the leading/tracking with the glyphs, not just the glyph size.
    val svg = render(density = 2f, fontScale = 2f)
    // 24.0sp × 2 × 2 = 96 line-height feeds the wrapped-line baseline math; 1.0sp × 2 × 2 = 4
    // letter-spacing is emitted directly. The scaled tracking is the observable one.
    assertTrue("letter-spacing scaled by fontScale", svg.contains("letter-spacing=\"4\""))
  }
}
