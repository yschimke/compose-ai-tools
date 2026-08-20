package ee.schimke.composeai.daemon

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `Modifier.drawWithCache` is a draw the recorder used to give up on, and it is the modifier every
 * Wear/Material determinate progress indicator paints through — a `Spacer` whose whole appearance
 * is one `drawWithCache { onDrawWithContent { drawArc(...) } }`. Reading its `onBuildDrawCache`
 * field and invoking it as if it were a draw lambda throws (it takes a `CacheDrawScope` and returns
 * a `DrawResult`), so the capture aborted and the export fell back to an `<image>` of the whole
 * ring — a raster where every other progress indicator, drawn through `Canvas`, exports as paths
 * (issue yschimke/wear-m3-catalog#62).
 *
 * These exercise the recovery: run the builder for the node's size, take the block it returns, and
 * record that.
 */
class CacheDrawCaptureTest {

  /**
   * The same two steps [DrawCaptureExtractor.extract] runs per modifier — read the draw lambda for
   * the node's size, record it — without the `ModifierInfo`/`LayoutCoordinates` a real layout pass
   * would supply.
   */
  private fun capture(modifier: Modifier, width: Int = 96, height: Int = 96) =
    DrawCaptureExtractor.drawLambda(
        modifier,
        DrawCaptureExtractor.CacheDrawParams(Size(width.toFloat(), height.toFloat()), density = 2f),
      )
      ?.let { DrawCaptureExtractor.captureDraw(it, width = width, height = height, density = 2f) }

  @Test
  fun cachedArcDraw_capturesAsVectorPaths() {
    // The shape of `SegmentedCircularProgressIndicator`: a chrome-only cached draw with no
    // `drawContent()`, whose geometry is built from `size` inside the cache block.
    val g =
      capture(
        Modifier.drawWithCache {
          val stroke = Stroke(width = size.minDimension / 12f)
          onDrawWithContent {
            drawArc(
              Color(0xFF332E3C),
              startAngle = -90f,
              sweepAngle = 360f,
              useCenter = false,
              style = stroke,
            )
            drawArc(
              Color(0xFFE9DDFF),
              startAngle = -90f,
              sweepAngle = 216f,
              useCenter = false,
              style = stroke,
            )
          }
        }
      )

    assertNotNull("a cached draw must capture as vector, not fall back to a raster", g)
    assertEquals(2, g!!.paths.size)
    assertEquals("#FF332E3C", g.paths[0].strokeArgb)
    assertEquals("#FFE9DDFF", g.paths[1].strokeArgb)
    // Built against the node's size: 96 / 12 = 8.
    assertEquals(8f, g.paths[0].strokeWidth)
    // The paths *are* the modifier's output, so the export must not raster the node over them.
    assertEquals(true, g.fromDrawCapture)
  }

  @Test
  fun cachedDraw_buildsAgainstTheNodesOwnSize() {
    val g = capture(Modifier.drawWithCache { onDrawBehind {} }, width = 40, height = 20)
    assertNull("nothing drawn stays null", g)

    val sized =
      capture(
        Modifier.drawWithCache {
          val box = size
          onDrawWithContent { drawRect(Color.Red, size = box) }
        },
        width = 40,
        height = 20,
      )
    assertEquals("M0,0 H40 V20 H0 Z", sized!!.paths.single().pathData)
  }

  @Test
  fun drawWithContentChrome_capturesAsVectorPaths() {
    // A `drawWithContent` lambda is declared against `ContentDrawScope`, so it could not even be
    // invoked against the recorder before — the receiver cast failed ahead of the first primitive.
    val g = capture(Modifier.drawWithContent { drawCircle(Color.Red, radius = 10f) })
    assertEquals("#FFFF0000", g!!.paths.single().fillArgb)
  }

  @Test
  fun drawThatWrapsItsContent_fallsBackToRaster() {
    // Content-wrapping draws keep the all-or-raster guarantee: the vector would stand in for the
    // whole node and silently drop whatever `drawContent()` paints.
    assertNull(
      capture(
        Modifier.drawWithContent {
          drawCircle(Color.Red, radius = 10f)
          drawContent()
        }
      )
    )
    assertNull(capture(Modifier.drawWithCache { onDrawBehind { drawCircle(Color.Red) } }))
  }

  @Test
  fun plainDrawBehind_isUnchanged() {
    val g = capture(Modifier.drawBehind { drawRect(Color.Red, size = Size(10f, 10f)) })
    assertEquals("#FFFF0000", g!!.paths.single().fillArgb)
  }
}
