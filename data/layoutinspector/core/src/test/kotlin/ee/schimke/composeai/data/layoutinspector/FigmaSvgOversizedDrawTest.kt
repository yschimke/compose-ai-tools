package ee.schimke.composeai.data.layoutinspector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * A node whose `drawWithContent` covers **more** than the layout node it hangs off must be
 * preserved from the frame at the region it actually draws, not at its placed box.
 *
 * Wear's `EdgeButton` is the shape that proves it. A trailing
 * `layout`/`ScaleAndAlignContentElement`/`SizeElement` chain shrinks the layout node down to its
 * label, while `paint`, `drawWithContent` and the `EdgeButtonShape` `graphicsLayer` all still cover
 * the full screen-hugging capsule — the node reports a 69×36 box and a 384×204 measured size.
 * Because the draw clips its content (`modifiesDrawnContent`), the export flattens it to a frame
 * crop; cropping the placed box took a sliver from behind the word "Start" and threw the capsule
 * away.
 */
class FigmaSvgOversizedDrawTest {

  private fun bounds(l: Int, t: Int, r: Int, b: Int) = LayoutInspectorBounds(l, t, r, b)

  /** The capsule's own box, as every drawing modifier on the chain reports it. */
  private val capsule = bounds(0, 186, 384, 378)

  /** Where the shrunken layout node — and its label child — are placed. */
  private val label = bounds(158, 262, 227, 298)

  private fun edgeButton(
    drawBounds: LayoutInspectorBounds = capsule,
    modifiesDrawnContent: Boolean = true,
  ) =
    LayoutInspectorNode(
      nodeId = "15",
      component = "RowMeasurePolicy",
      bounds = label,
      size = LayoutInspectorSize(384, 204),
      modifiers =
        listOf(
          LayoutInspectorModifier(name = "paint", bounds = drawBounds),
          LayoutInspectorModifier(name = "drawWithContent", bounds = drawBounds),
        ),
      modifiesDrawnContent = modifiesDrawnContent,
      children =
        listOf(
          LayoutInspectorNode(
            nodeId = "16",
            component = "EmptyMeasurePolicy",
            bounds = label,
            size = LayoutInspectorSize(69, 36),
          )
        ),
    )

  private fun screen(button: LayoutInspectorNode) =
    LayoutInspectorNode(
      nodeId = "1",
      component = "BoxMeasurePolicy",
      bounds = bounds(0, 0, 384, 384),
      size = LayoutInspectorSize(384, 384),
      children = listOf(button),
    )

  private fun model(button: LayoutInspectorNode) =
    FigmaSvgModel.from(layout = LayoutInspectorPayload(screen(button)), captureCanvasDraws = true)

  @Test
  fun `an over-covering draw is cropped at the region it draws`() {
    val m = model(edgeButton())
    val layer = m.root.children.single()
    assertNotNull("the clipping draw is flattened to a frame crop", layer.raster)
    assertEquals("left spans the capsule, not the label", capsule.left, layer.left)
    assertEquals(capsule.top, layer.top)
    assertEquals(capsule.right, layer.right)
    assertEquals(capsule.bottom, layer.bottom)

    val target = m.rasterTargets.single { it.nodeId == "15" }
    assertEquals("the crop asked of the frame matches the layer", capsule.left, target.left)
    assertEquals(capsule.top, target.top)
    assertEquals(capsule.right, target.right)
    assertEquals(capsule.bottom, target.bottom)
  }

  /**
   * The expansion is held to the node's **own** mask, not just its ancestors'. A node that clips
   * itself tighter than its draw modifier reports would otherwise crop in frame pixels its own clip
   * removes — and the raster leaf carries no mask of its own to trim them again.
   */
  @Test
  fun `the expanded crop is held to the node's own clip`() {
    val mask = bounds(40, 120, 344, 280)
    val overflowingDraw = bounds(0, 90, 384, 320)
    val clipped =
      LayoutInspectorNode(
        nodeId = "15",
        component = "BoxMeasurePolicy",
        bounds = bounds(0, 100, 384, 300),
        size = LayoutInspectorSize(384, 200),
        tokens = ComposeSemanticsTokens(clipsContent = true),
        modifiers =
          listOf(
            LayoutInspectorModifier(
              name = "graphicsLayer",
              properties = mapOf("clip" to "true"),
              bounds = mask,
            ),
            LayoutInspectorModifier(name = "drawWithContent", bounds = overflowingDraw),
          ),
        modifiesDrawnContent = true,
      )
    val layer = model(clipped).root.children.single()
    assertEquals("left clamped to the node's own clip", mask.left, layer.left)
    assertEquals(mask.top, layer.top)
    assertEquals(mask.right, layer.right)
    assertEquals(mask.bottom, layer.bottom)
  }

  /**
   * The ordinary case is untouched: a draw that stays inside its node crops the node's box, because
   * the union never shrinks below it — the content being clipped still has to be in the crop.
   */
  @Test
  fun `a draw inside its node still crops the node box`() {
    val inner = bounds(170, 270, 210, 290)
    val layer = model(edgeButton(drawBounds = inner)).root.children.single()
    assertEquals(label.left, layer.left)
    assertEquals(label.top, layer.top)
    assertEquals(label.right, layer.right)
    assertEquals(label.bottom, layer.bottom)
  }
}
