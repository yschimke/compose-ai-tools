package ee.schimke.composeai.data.layoutinspector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the **isolated draw capture** path (issue #2937): a node whose own imperative draw the
 * connector re-rendered offscreen carries those pixels as a `background` `<image>` — including on a
 * *container*, and including with no frame to crop from.
 *
 * The sibling [FigmaSvgModelCanvasDrawTest] pins the frame-crop path, which is deliberately
 * narrower (leaf nodes, hybrid mode only) because its pixels come from the composited render. These
 * two are the same `<image>` slot fed from opposite sources, so the split between them is what this
 * file asserts: a captured node exports its chrome and *keeps its editable children over it*, which
 * a frame crop can never do.
 */
class FigmaSvgDrawRasterTest {

  private fun bounds(l: Int, t: Int, r: Int, b: Int) = LayoutInspectorBounds(l, t, r, b)

  /** A stand-in for the connector's PNG; the model never decodes it, it only carries it through. */
  private val png = "iVBORw0KGgo="

  /**
   * The shape every Remote Compose component takes: a container whose chrome is drawn by a
   * `drawWithContent` reaching for the native canvas (so no vector capture), over a text child. The
   * draw sits *above* the padding, so it covers more than the node's own (padded) box.
   */
  private fun capturedContainer() =
    LayoutInspectorNode(
      nodeId = "card-1",
      component = "Column",
      bounds = bounds(24, 24, 176, 96),
      size = LayoutInspectorSize(152, 72),
      modifiers =
        listOf(LayoutInspectorModifier(name = "drawWithContent", bounds = bounds(0, 0, 200, 120))),
      drawRaster =
        LayoutInspectorDrawRaster(left = 0, top = 0, right = 200, bottom = 120, pngBase64 = png),
      children =
        listOf(
          LayoutInspectorNode(
            nodeId = "label-1",
            component = "Text",
            bounds = bounds(24, 24, 176, 60),
            size = LayoutInspectorSize(152, 36),
          )
        ),
    )

  private fun model(node: LayoutInspectorNode, captureCanvasDraws: Boolean) =
    FigmaSvgModel.from(
      layout = LayoutInspectorPayload(node),
      captureCanvasDraws = captureCanvasDraws,
    )

  @Test
  fun aCapturedContainerExportsItsChromeAndKeepsItsChildren() {
    val m = model(capturedContainer(), captureCanvasDraws = true)

    val bg = m.root.background
    assertNotNull("the captured draw rides as a background <image>", bg)
    assertEquals(0, bg!!.left)
    assertEquals(120, bg.bottom)
    assertFalse("it is an isolated re-draw, not a frame crop", bg.fromFrame)
    assertNull("the container is not collapsed to an opaque leaf", m.root.raster)
    assertEquals("its child stays an editable vector layer", 1, m.root.children.size)
    assertNull(m.root.children.single().raster)
  }

  @Test
  fun theCapturedPixelsRideOnTheRasterTargetSoNoFrameCropIsNeeded() {
    val m = model(capturedContainer(), captureCanvasDraws = true)
    val target = m.rasterTargets.single()
    assertEquals(png, target.pngBase64)
    assertEquals(m.root.background!!.href, target.href)
    assertEquals(0, target.left)
    assertEquals(200, target.right)
  }

  @Test
  fun vectorOnlyModeStillExportsTheCapturedChrome() {
    // The frame-crop path is off without a frame; this one has its own pixels, so it must survive.
    val m = model(capturedContainer(), captureCanvasDraws = false)
    assertNotNull("a captured draw needs no frame", m.root.background)
    assertEquals(1, m.rasterTargets.size)
    assertNotNull(m.rasterTargets.single().pngBase64)
  }

  @Test
  fun theCanvasCoversTheCapturedRegionEvenWhenItOverflowsTheLayerBox() {
    // The chrome is drawn above the padding, so it reaches outside the layer's own box. Sizing the
    // canvas off the boxes alone would clip pixels the SVG really draws.
    val m = model(capturedContainer(), captureCanvasDraws = true)
    assertEquals("canvas spans the drawn region + padding", 200 + 2 * m.padding, m.width)
    assertEquals(120 + 2 * m.padding, m.height)
  }

  @Test
  fun aCurvedTextNodeKeepsItsVectorRunsInsteadOfDoubleDrawingThem() {
    // A Wear `CurvedLayout`/`TimeText` paints its runs through a draw modifier *and* carries them
    // as
    // `curvedTexts`, which the export emits as `<textPath>`. Laying the capture underneath would
    // paint the clock twice — once as pixels, once as live text — so the vector wins.
    val curved =
      LayoutInspectorNode(
        nodeId = "timetext-1",
        component = "CurvedLayout",
        bounds = bounds(0, 0, 227, 227),
        size = LayoutInspectorSize(227, 227),
        modifiers =
          listOf(
            LayoutInspectorModifier(name = "drawWithContent", bounds = bounds(0, 0, 227, 227))
          ),
        curvedTexts =
          listOf(
            LayoutInspectorCurvedText(
              text = "10:10",
              centerXPx = 113.0,
              centerYPx = 113.0,
              radiusPx = 105.0,
              startAngleRadians = 4.5,
              sweepRadians = 0.6,
              clockwise = true,
              fontSizePx = 14.0,
            )
          ),
        drawRaster =
          LayoutInspectorDrawRaster(left = 0, top = 0, right = 227, bottom = 227, pngBase64 = png),
        // The curved layout wraps its runs, as the real `TimeText` does. The child is what takes
        // this out of the frame-crop branch (leaf-only) and into the captured-draw one, which is
        // the branch under test — a childless curved leaf still crops from the frame as before.
        children =
          listOf(
            LayoutInspectorNode(
              nodeId = "curved-child-1",
              component = "CurvedText",
              bounds = bounds(60, 8, 167, 40),
              size = LayoutInspectorSize(107, 32),
            )
          ),
      )
    val m = model(curved, captureCanvasDraws = true)
    assertNull("no raster under text the export already draws as vector", m.root.background)
    assertTrue(m.rasterTargets.isEmpty())
    assertEquals(1, m.root.curvedTexts.size)
  }

  @Test
  fun theFrameCropStillWinsOnALeafSoExistingBehaviourIsUnchanged() {
    // A childless, text-less draw node in hybrid mode is the frame-crop path's own case. It keeps
    // it: those pixels are the composited truth for a leaf, and nothing about them double-renders.
    val leaf =
      LayoutInspectorNode(
        nodeId = "spacer-1",
        component = "Spacer",
        bounds = bounds(0, 0, 100, 40),
        size = LayoutInspectorSize(100, 40),
        modifiers =
          listOf(LayoutInspectorModifier(name = "drawBehind", bounds = bounds(8, 18, 92, 22))),
        drawRaster =
          LayoutInspectorDrawRaster(left = 8, top = 18, right = 92, bottom = 22, pngBase64 = png),
      )
    val m = model(leaf, captureCanvasDraws = true)
    assertTrue("cropped from the frame, not from the payload", m.root.background!!.fromFrame)
    assertNull(m.rasterTargets.single().pngBase64)
  }
}
