package ee.schimke.composeai.daemon

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The draw recorder translates a control's `DrawScope` primitives into editable SVG `<path>`s with
 * the real colours/styles — no native Paint/Canvas, so it runs headless. (`drawPath` samples a live
 * `Path` via `PathMeasure`, which is Skia-backed, so it's exercised in the render harness, not
 * here.)
 */
class DrawCaptureExtractorTest {

  private fun capture(onDraw: DrawScope.() -> Unit) =
    DrawCaptureExtractor.captureDraw(onDraw, width = 96, height = 96, density = 2f)

  @Test
  fun capturesFilledCircle_asSolidPath() {
    val g = capture { drawCircle(Color(0xFF6750A4), radius = 20f, center = Offset(48f, 48f)) }
    assertNotNull(g)
    assertEquals(96f, g!!.viewportWidth)
    val p = g.paths.single()
    assertEquals("#FF6750A4", p.fillArgb)
    assertNull(p.strokeArgb)
    assertTrue("arc path", p.pathData.contains("A20,20"))
  }

  @Test
  fun capturesStrokedArc_asStrokePath() {
    val g = capture {
      drawArc(
        Color(0xFF6750A4),
        startAngle = -90f,
        sweepAngle = 216f,
        useCenter = false,
        style = Stroke(width = 8f),
      )
    }
    val p = g!!.paths.single()
    assertEquals("#FF6750A4", p.strokeArgb)
    assertEquals(8f, p.strokeWidth)
    assertNull(p.fillArgb)
    assertTrue("large-arc flag set for >180deg sweep", p.pathData.contains(" 1 1 "))
  }

  @Test
  fun capturesRoundedBoxAndTrack_inOrder() {
    val g = capture {
      drawRoundRect(Color(0xFFE7E0EC), size = Size(80f, 8f), cornerRadius = CornerRadius(4f, 4f))
      drawRoundRect(Color(0xFF6750A4), size = Size(48f, 8f), cornerRadius = CornerRadius(4f, 4f))
    }
    assertEquals(2, g!!.paths.size)
    assertEquals("#FFE7E0EC", g.paths[0].fillArgb)
    assertEquals("#FF6750A4", g.paths[1].fillArgb)
    assertTrue(g.paths[0].pathData.startsWith("M"))
  }

  @Test
  fun capturesLine_asStroke() {
    val g = capture {
      drawLine(Color(0xFF6750A4), Offset(0f, 4f), Offset(48f, 4f), strokeWidth = 8f)
    }
    val p = g!!.paths.single()
    assertEquals("M0,4 L48,4", p.pathData)
    assertEquals(8f, p.strokeWidth)
    assertEquals("#FF6750A4", p.strokeArgb)
  }

  @Test
  fun transformBlock_fallsBackToNull() {
    // A translate/scale/clip block routes through the (unsupported) canvas/transform → capture
    // aborts
    // → null, so the node keeps its raster crop rather than being mis-drawn.
    val g = capture {
      translate(10f, 10f) { drawCircle(Color(0xFF6750A4), radius = 5f, center = Offset(0f, 0f)) }
    }
    assertNull(g)
  }

  @Test
  fun nothingDrawn_isNull() {
    assertNull(capture {})
  }

  @Test
  fun gradientBrush_isSkipped() {
    // Only solid colours are captured (matching the vector-vs-raster rule); a brush fill is
    // dropped.
    val g = capture {
      drawRect(
        androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(Color.Red, Color.Blue)),
        size = Size(40f, 40f),
      )
    }
    assertNull(g)
  }
}
