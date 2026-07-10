package ee.schimke.composeai.data.layoutinspector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the hybrid **Canvas-draw background** path: a node that paints via an imperative
 * `drawBehind` / `drawWithContent` modifier (the `LinearProgressIndicator` track, the `Slider`
 * groove, a custom-drawn background — chrome the token-driven vector export can't see) carries the
 * drawn region as a `background` `<image>` cropped to that region, drawn *beneath* the node's own
 * shape/text and its children. Only in hybrid mode (`captureCanvasDraws`, where a frame PNG exists
 * to crop those pixels from), and the node's editable text/child layers are preserved on top.
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
  fun canvasDrawNodeCarriesABackgroundCroppedToTheDrawnRegion() {
    val m = model(canvasNode(), captureCanvasDraws = true)
    assertEquals("one raster target for the drawn bar", 1, m.rasterTargets.size)
    val target = m.rasterTargets.single()
    // Cropped to the draw modifier's bounds (the bar), not the padded Spacer box.
    assertEquals(8, target.left)
    assertEquals(18, target.top)
    assertEquals(92, target.right)
    assertEquals(22, target.bottom)
    // The node stays a vector layer; the drawn pixels ride on `background`, not as an opaque leaf.
    assertNull("not an opaque-leaf <image>", m.root.raster)
    val bg = m.root.background
    assertNotNull("the drawn region is carried as a background <image>", bg)
    assertEquals(8, bg!!.left)
    assertEquals(18, bg.top)
    assertEquals(92, bg.right)
    assertEquals(22, bg.bottom)
  }

  @Test
  fun vectorOnlyModeLeavesTheCanvasNodeWithoutABackground() {
    val m = model(canvasNode(), captureCanvasDraws = false)
    assertTrue("no raster targets in vector-only mode", m.rasterTargets.isEmpty())
    assertNull("no background raster in vector-only mode", m.root.background)
    assertNull(m.root.raster)
  }

  @Test
  fun aDrawnContainerKeepsItsChildrenAsVectorLayersOverTheBackground() {
    // A `Box(Modifier.drawBehind {…}) { child }` draws a background AND has a child: the background
    // rides on the container's `background` <image> while the child stays an editable vector layer
    // on top — the whole subtree is not collapsed into a bitmap.
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
    assertNull("the container is not an opaque-leaf <image>", m.root.raster)
    assertNotNull("the drawn background rides on the container", m.root.background)
    assertEquals("its child is preserved as a vector layer", 1, m.root.children.size)
    assertNull(m.root.children.single().raster)
    assertNull(m.root.children.single().background)
  }
}
