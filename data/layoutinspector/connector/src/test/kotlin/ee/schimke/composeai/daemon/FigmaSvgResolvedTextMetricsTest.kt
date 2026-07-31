package ee.schimke.composeai.daemon

import ee.schimke.composeai.data.layoutinspector.ComposeSemanticsNode
import ee.schimke.composeai.data.layoutinspector.ComposeSemanticsPayload
import ee.schimke.composeai.data.layoutinspector.ComposeSemanticsTextLine
import ee.schimke.composeai.data.layoutinspector.ComposeSemanticsTextOverflow
import ee.schimke.composeai.data.layoutinspector.ComposeSemanticsTypography
import ee.schimke.composeai.data.layoutinspector.LayoutInspectorBounds
import ee.schimke.composeai.data.layoutinspector.LayoutInspectorNode
import ee.schimke.composeai.data.layoutinspector.LayoutInspectorPayload
import ee.schimke.composeai.data.layoutinspector.LayoutInspectorSize
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The `compose/figma-svg` export must size text at the px the **render resolved**, not at `sp ×
 * density × fontScale` (issue #3024).
 *
 * The linear formula is only right at `fontScale = 1`. Compose resolves `sp` through the platform
 * `FontScaleConverter` on API 34+, whose curve is non-linear in the font scale: body sizes take the
 * full multiplier while display sizes flatten toward identity. On a `fontScale = 1.5` JetNews
 * render that made the export declare a 32sp article title at 126px where the render had drawn it
 * at ~84px — 50% oversized, so the captured line breaks no longer fit the card they were measured
 * in and the last line ran past its right edge, while the 14sp body around it matched fine.
 *
 * The capture now ships the resolved px ([ComposeSemanticsTypography.fontSizePx] and friends) and
 * these pin that the export prefers them, still falling back to the linear conversion for captures
 * older than schema v12.
 */
class FigmaSvgResolvedTextMetricsTest {
  private lateinit var dir: File

  @Before
  fun setUp() {
    dir = Files.createTempDirectory("figma-resolved-metrics").toFile()
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
        bounds = LayoutInspectorBounds(0, 0, 400, 200),
        size = LayoutInspectorSize(400, 200),
        children =
          listOf(
            LayoutInspectorNode(
              nodeId = "Text",
              component = "Text",
              bounds = LayoutInspectorBounds(8, 8, 392, 120),
              size = LayoutInspectorSize(384, 112),
            )
          ),
      )
    )

  /**
   * A heading the way a `fontScale = 1.5` capture reports it: nominally 32sp, but the render's own
   * `Density` resolved it to 84px — nowhere near the 32 × 2.625 × 1.5 = 126px the linear formula
   * predicts. [resolved] switches the v12 px fields on and off so one payload covers both the fix
   * and the legacy fallback.
   */
  private fun semantics(resolved: Boolean, lineWidth: Int? = null) =
    ComposeSemanticsPayload(
      ComposeSemanticsNode(
        nodeId = "root",
        boundsInRoot = "0,0,400,200",
        children =
          listOf(
            ComposeSemanticsNode(
              nodeId = "Text",
              boundsInRoot = "8,8,392,120",
              text = "Language to Kotlin",
              typography =
                ComposeSemanticsTypography(
                  fontSize = "32.0sp",
                  fontSizePx = if (resolved) 84.0 else null,
                  fontWeight = 400,
                  lineHeight = "40.0sp",
                  lineHeightPx = if (resolved) 105.0 else null,
                  letterSpacing = "0.5sp",
                  letterSpacingPx = if (resolved) 1.31 else null,
                ),
              textOverflow =
                ComposeSemanticsTextOverflow(
                  lineCount = 2,
                  lines =
                    listOf(
                      ComposeSemanticsTextLine(
                        text = "Language to",
                        left = 0,
                        baseline = 60,
                        start = 0,
                        end = 11,
                        width = lineWidth,
                      ),
                      ComposeSemanticsTextLine(
                        text = "Kotlin",
                        left = 0,
                        baseline = 165,
                        start = 12,
                        end = 18,
                        width = lineWidth?.let { it / 2 },
                      ),
                    ),
                ),
            )
          ),
      )
    )

  private fun render(resolved: Boolean, lineWidth: Int? = null): String {
    dir.resolve("p").deleteRecursively()
    ComposeFigmaSvgDataProducer.writeSvg(
      rootDir = dir,
      previewId = "p",
      layout = layout(),
      semantics = semantics(resolved, lineWidth),
      // The render this stands in for: 420dpi (density 2.625) at fontScale 1.5, exactly where the
      // linear conversion and the platform curve part company.
      density = 2.625f,
      fontScale = 1.5f,
    )
    return dir.resolve("p").resolve(ComposeFigmaSvgDataProducer.FILE_SVG).readText()
  }

  private fun fontSizeOf(svg: String): Double? =
    Regex("<text\\b[^>]*\\bfont-size=\"([0-9.]+)\"").find(svg)?.groupValues?.get(1)?.toDouble()

  private fun letterSpacingOf(svg: String): Double? =
    Regex("<text\\b[^>]*\\bletter-spacing=\"([0-9.]+)\"").find(svg)?.groupValues?.get(1)?.toDouble()

  @Test
  fun resolvedFontSizeWinsOverTheLinearConversion() {
    val size = fontSizeOf(render(resolved = true))
    assertNotNull("export emits a sized <text>", size)
    // The px the render resolved — NOT 32 × 2.625 × 1.5 = 126.
    assertEquals(84.0, size!!, 0.01)
  }

  @Test
  fun withoutResolvedPxTheLinearConversionStillApplies() {
    // Captures older than schema v12 carry no resolved px. They keep the historical behaviour
    // rather than losing their size entirely — wrong on a scaled render, but no worse than before.
    val size = fontSizeOf(render(resolved = false))
    assertNotNull(size)
    assertEquals(32.0 * 2.625 * 1.5, size!!, 0.01)
  }

  @Test
  fun resolvedLetterSpacingWinsOverTheLinearConversion() {
    val spacing = letterSpacingOf(render(resolved = true))
    assertNotNull("export emits tracking", spacing)
    // 1.31px as resolved, not 0.5 × 2.625 × 1.5 = 1.97.
    assertEquals(1.31, spacing!!, 0.01)
  }

  @Test
  fun measuredLineWidthIsEmittedAsSpacingOnlyTextLength() {
    val svg = render(resolved = true, lineWidth = 300)
    assertTrue("first line pinned to its measured width", svg.contains("""textLength="300""""))
    assertTrue("second line pinned to its own width", svg.contains("""textLength="150""""))
    // Spacing-only: adjusting the glyphs too would squeeze a wrong-sized run into a right-sized
    // box, hiding exactly the bug this test guards.
    assertTrue(svg.contains("""lengthAdjust="spacing""""))
    assertFalse(svg.contains("spacingAndGlyphs"))
  }

  @Test
  fun linesWithoutAMeasuredWidthEmitNoTextLength() {
    // Pre-v12 captures carry no per-line width; forcing one would be inventing a measurement.
    assertFalse(render(resolved = true, lineWidth = null).contains("textLength"))
  }
}
