package ee.schimke.composeai.data.layoutinspector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the hybrid **Canvas-draw raster** path: a node that paints via an imperative `drawBehind`
 * / `drawWithContent` modifier (the `LinearProgressIndicator` track, the `Slider` groove — chrome
 * the token-driven vector export can't see) is emitted as an `<image>` cropped to the *drawn*
 * region, not the padded node box, and only when `captureCanvasDraws` is on (hybrid mode, where a
 * frame PNG exists to crop those pixels from).
 */
class FigmaSvgModelCanvasDrawTest {

  private fun bounds(l: Int, t: Int, r: Int, b: Int) = LayoutInspectorBounds(l, t, r, b)

  /**
   * A bare `Spacer` (padded box) whose progress bar is painted by a `drawBehind` on a sub-region.
   */
  private fun canvasNode() =
    LayoutInspectorNode(
      nodeId = "spacer-1",
      component = "Spacer",
      bounds = bounds(0, 0, 100, 40),
      size = LayoutInspectorSize(100, 40),
      modifiers =
        listOf(LayoutInspectorModifier(name = "drawBehind", bounds = bounds(8, 18, 92, 22))),
    )

  private fun model(node: LayoutInspectorNode, captureCanvasDraws: Boolean) =
    FigmaSvgModel.from(
      layout = LayoutInspectorPayload(node),
      captureCanvasDraws = captureCanvasDraws,
    )

  @Test
  fun canvasDrawNodeBecomesRasterCroppedToTheDrawnRegion() {
    val m = model(canvasNode(), captureCanvasDraws = true)
    assertEquals("one raster target for the drawn bar", 1, m.rasterTargets.size)
    val target = m.rasterTargets.single()
    // Cropped to the draw modifier's bounds (the bar), not the padded Spacer box.
    assertEquals(8, target.left)
    assertEquals(18, target.top)
    assertEquals(92, target.right)
    assertEquals(22, target.bottom)
    assertTrue("the node is emitted as an <image> layer", m.root.raster != null)
    assertEquals(8, m.root.left)
    assertEquals(22, m.root.bottom)
  }

  @Test
  fun vectorOnlyModeLeavesTheCanvasNodeUnrastered() {
    val m = model(canvasNode(), captureCanvasDraws = false)
    assertTrue("no raster targets in vector-only mode", m.rasterTargets.isEmpty())
    assertNull("the node stays a vector layer, not an <image>", m.root.raster)
  }

  @Test
  fun aDrawnContainerKeepsItsChildrenAsVectorLayers() {
    // A `Box(Modifier.drawBehind {…}) { child }` draws a background but is NOT a leaf — rasterising
    // it wholesale would collapse the child's editable layer into a bitmap. It must stay vector.
    val container =
      LayoutInspectorNode(
        nodeId = "box-1",
        component = "Box",
        bounds = bounds(0, 0, 100, 40),
        size = LayoutInspectorSize(100, 40),
        modifiers =
          listOf(LayoutInspectorModifier(name = "drawBehind", bounds = bounds(0, 0, 100, 40))),
        children =
          listOf(
            LayoutInspectorNode(
              nodeId = "label-1",
              component = "Text",
              bounds = bounds(8, 8, 92, 32),
              size = LayoutInspectorSize(84, 24),
            )
          ),
      )
    val m = model(container, captureCanvasDraws = true)
    assertTrue("a drawn container is not rasterised", m.rasterTargets.isEmpty())
    assertNull("the container stays a vector layer", m.root.raster)
    assertEquals("its child is preserved as a vector layer", 1, m.root.children.size)
    assertNull(m.root.children.single().raster)
  }
}
