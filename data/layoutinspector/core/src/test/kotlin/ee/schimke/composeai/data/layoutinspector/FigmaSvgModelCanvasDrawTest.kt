package ee.schimke.composeai.data.layoutinspector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the hybrid **Canvas-draw background** path: a *leaf* node that paints via an imperative
 * `drawBehind` / `drawWithContent` modifier (the `LinearProgressIndicator` track, the `Slider`
 * groove) carries the drawn region as a `background` `<image>` cropped to that region, drawn
 * *beneath* the node's own shape/text. Only in hybrid mode (`captureCanvasDraws`, where a frame PNG
 * exists to crop those pixels from). A drawn *container* (with children/text) stays fully vector:
 * its background would be cropped from the composited frame — baking in the descendants' pixels —
 * so re-drawing the editable children over it would double-render them.
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
  fun leafCanvasDrawNodeCarriesABackgroundCroppedToTheDrawnRegion() {
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
  fun delegatedDrawNodeWithoutAnInspectableDrawLambdaStillUsesAFrameCrop() {
    // A custom Modifier.NodeElement can delegate to CacheDrawModifierNode without exposing an
    // inspectable `drawWithCache` element. The live node-chain capability is then the only evidence
    // that this otherwise-empty Spacer paints (Material 3's wavy progress indicators).
    val node =
      LayoutInspectorNode(
        nodeId = "delegated-draw",
        component = "Spacer",
        bounds = bounds(3, 5, 103, 45),
        size = LayoutInspectorSize(100, 40),
        drawsContent = true,
      )

    val m = model(node, captureCanvasDraws = true)

    assertEquals("one full-node raster target", 1, m.rasterTargets.size)
    val target = m.rasterTargets.single()
    assertEquals(3, target.left)
    assertEquals(5, target.top)
    assertEquals(103, target.right)
    assertEquals(45, target.bottom)
    assertNotNull("the delegated draw survives as a background image", m.root.background)
  }

  @Test
  fun aDelegatedDrawIsNotSuppressedByATokenThatPaintsNothing() {
    // A token that exists is not a token that paints. A fully transparent border (a `Switch`
    // on-track carries `borderColor` at alpha 0) is dropped when the stroke is built, so counting
    // its presence as "this node has content" leaves the node with no stroke AND no raster — an
    // empty layer where the delegated pixels were.
    val node =
      LayoutInspectorNode(
        nodeId = "delegated-draw",
        component = "Spacer",
        bounds = bounds(3, 5, 103, 45),
        size = LayoutInspectorSize(100, 40),
        drawsContent = true,
        tokens = ComposeSemanticsTokens(borderColor = "#00000000"),
      )

    val m = model(node, captureCanvasDraws = true)

    assertEquals("the invisible border must not suppress the raster", 1, m.rasterTargets.size)
    assertNotNull("the delegated draw survives as a background image", m.root.background)
  }

  @Test
  fun aVisibleTokenStillKeepsTheNodeVector() {
    // The other side of the same line: a border that does paint is content the vector model can
    // represent, so the node stays a vector layer and nothing is cropped from the frame.
    val node =
      LayoutInspectorNode(
        nodeId = "bordered",
        component = "Spacer",
        bounds = bounds(3, 5, 103, 45),
        size = LayoutInspectorSize(100, 40),
        drawsContent = true,
        tokens = ComposeSemanticsTokens(borderColor = "#FF6750A4"),
      )

    val m = model(node, captureCanvasDraws = true)

    assertTrue("a painting border keeps the node vector", m.rasterTargets.isEmpty())
    assertNull("no frame crop for a representable node", m.root.background)
  }

  @Test
  fun aBackgroundOnlyLeafStillCountsAsPaintingSoItContributesExtent() {
    // A draw-only Spacer paints nothing but its background raster; `paints` must see the background
    // or the layer contributes no extent and the export falls back to the 32×32 padding canvas.
    val m = model(canvasNode(), captureCanvasDraws = true)
    assertTrue("a background-only layer paints", m.root.paints)
    // The export sizes to the node (a superset of the drawn region) + padding, not the 32×32 stub.
    assertTrue("extent reflects the drawn node, not the padding stub", m.width > 40)
  }

  @Test
  fun vectorOnlyModeLeavesTheCanvasNodeWithoutABackground() {
    val m = model(canvasNode(), captureCanvasDraws = false)
    assertTrue("no raster targets in vector-only mode", m.rasterTargets.isEmpty())
    assertNull("no background raster in vector-only mode", m.root.background)
    assertNull(m.root.raster)
  }

  @Test
  fun aDrawnContainerStaysVectorSoItsChildrenAreNotDoubleRendered() {
    // A `Box(Modifier.drawBehind {…}) { child }` draws a background AND has a child. Its background
    // would be cropped from the composited frame (already containing the child's pixels), so
    // capturing it and re-drawing the editable child on top would double-render the child. The
    // container therefore stays fully vector — no background — and keeps its child as a vector
    // layer.
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
    assertNull("no background on a drawn container", m.root.background)
    assertNull("the container is not an opaque-leaf <image>", m.root.raster)
    assertEquals("its child is preserved as a vector layer", 1, m.root.children.size)
    assertNull(m.root.children.single().raster)
  }
}
