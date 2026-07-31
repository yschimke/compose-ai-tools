@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package ee.schimke.composeai.daemon

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ee.schimke.composeai.data.layoutinspector.ComposeSemanticsNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the capture side of issue #3024: `compose/semantics` must carry the **resolved px** of every
 * typographic value, read through the layout's own `Density`, plus each wrapped line's measured
 * width.
 *
 * Why it matters: a consumer cannot recompute px from `sp`. Compose resolves `sp` through the
 * platform `FontScaleConverter` on API 34+, whose curve is non-linear in the font scale — body text
 * takes the full multiplier while a display-sized heading takes almost none. The
 * `compose/figma-svg` export used to recompute `sp × density × fontScale`, which over-sized
 * JetNews's 32sp article title by 50% on a `fontScale = 1.5` render and pushed its captured line
 * breaks past the card they were measured in.
 *
 * A desktop `ImageComposeScene` carries no `FontScaleConverter` — the curve is Android's — so what
 * runs here is the *plumbing*: that the capture resolves against the scene's `Density` (font scale
 * included, which a `sp × density` capture would miss) and reports the width the render measured
 * each line at. The Android-tier end-to-end behaviour, where the curve actually bends, is covered
 * by `ScaledTypographyExportTest`; the export's preference for these fields by
 * `FigmaSvgResolvedTextMetricsTest`.
 */
class DesktopResolvedTextMetricsTest {

  private val density = 2.625f
  private val fontScale = 1.5f
  private val paragraphWidthDp = 120

  private fun buildTree(content: @Composable () -> Unit): ComposeSemanticsNode {
    val scene =
      ImageComposeScene(
        width = 600,
        height = 600,
        density = Density(density, fontScale),
        content = content,
      )
    try {
      scene.render()
      val root: SemanticsNode = scene.semanticsOwners.first().unmergedRootSemanticsNode
      return ComposeSemanticsDataProducer.buildPayload(root, density).root
    } finally {
      scene.close()
    }
  }

  private fun ComposeSemanticsNode.find(tag: String): ComposeSemanticsNode? {
    if (testTag == tag) return this
    return children.firstNotNullOfOrNull { it.find(tag) }
  }

  private fun tree(): ComposeSemanticsNode = buildTree {
    Column(modifier = Modifier.width(paragraphWidthDp.dp)) {
      Text(
        text = "From Java Programming Language to Kotlin",
        modifier = Modifier.testTag("heading"),
        style =
          TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontSize = 32.sp,
            lineHeight = 40.sp,
            letterSpacing = 0.5.sp,
          ),
      )
    }
  }

  @Test
  fun `typography carries the px the render resolved, font scale included`() {
    val heading = checkNotNull(tree().find("heading")) { "no heading node captured" }
    val typography = checkNotNull(heading.typography) { "heading carried no typography" }

    // The nominal sp is still reported…
    assertEquals("32.0sp", typography.fontSize)
    // …alongside the px the scene's Density resolved it to. A capture that ignored the scene's
    // font scale would report 32 × 2.625 = 84 here.
    assertNotNull("resolved font size must be captured", typography.fontSizePx)
    assertEquals(32.0 * density * fontScale, typography.fontSizePx!!, 0.5)

    assertNotNull("resolved line height must be captured", typography.lineHeightPx)
    assertEquals(40.0 * density * fontScale, typography.lineHeightPx!!, 0.5)
    assertNotNull("resolved letter spacing must be captured", typography.letterSpacingPx)
    assertEquals(0.5 * density * fontScale, typography.letterSpacingPx!!, 0.5)
  }

  @Test
  fun `wrapped lines carry the width the render measured them at`() {
    val overflow =
      checkNotNull(tree().find("heading")?.textOverflow) { "heading carried no overflow metrics" }
    val lines = overflow.lines
    assertNotNull("a paragraph this narrow must wrap", lines)
    assertTrue("wrapping must produce several lines", lines!!.size > 1)

    val available = paragraphWidthDp * density
    for (line in lines) {
      val width = line.width
      assertNotNull("every line must carry its measured width: ${line.text}", width)
      assertTrue("a measured width is positive: ${line.text}", width!! > 0)
      // The invariant the export now relies on: a line never claims to be wider than the box it
      // was measured in, so pinning the SVG run to it can't reintroduce the overflow.
      assertTrue(
        "line '${line.text}' (${width}px) must fit the ${available}px paragraph",
        width <= available + 1,
      )
    }
  }
}
