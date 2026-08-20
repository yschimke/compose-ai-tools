package ee.schimke.composeai.data.layoutinspector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A rotated node is the one case where the captured `bounds` is **not** the rect the node drew.
 * `boundsIn(root)` maps the turned rect and takes its axis-aligned extent, so a node turned 45° is
 * reported bigger on both axes and square where it isn't.
 *
 * Wear's `AlertDialog` confirm button is the case that surfaced it: a 126×108 pill turned -45°
 * reports a 166×166 box, and the export drew a 166px **circle** (`rx = min(w, h) / 2 = 83`) over a
 * render 120px across. The `<rect rx>` was a confident wrong answer at every level — wrong size,
 * wrong aspect, wrong corner — painted over correctly shaped pixels.
 *
 * The fix has two halves and this pins both: take the node's own measured extent centred on the
 * bounding box, and hand the turn to the renderer so the shape goes back where the render put it.
 * The second half matters as much as the first — an un-rotated 126×108 pill is the right *shape* in
 * the wrong *orientation*, which for anything but a circle is still wrong pixels.
 */
class FigmaSvgRotatedLayerTest {

  private fun bounds(l: Int, t: Int, r: Int, b: Int) = LayoutInspectorBounds(l, t, r, b)

  /**
   * The confirm button as captured: a 126×108 container with a 50% corner, turned -45°, whose
   * `bounds` is therefore the 166×166 bounding box centred on (263, 283). The icon under it is the
   * second half of the same defect — it reports no turn of its own (the render draws it upright)
   * but sits inside the turned subtree, so its `bounds` is a bounding box too: 112×112 for a 56×56
   * glyph.
   */
  private fun turnedButton(rotationDegrees: Float) =
    LayoutInspectorNode(
      nodeId = "root-1",
      component = "Box",
      bounds = bounds(0, 0, 384, 384),
      size = LayoutInspectorSize(384, 384),
      children =
        listOf(
          LayoutInspectorNode(
            nodeId = "confirm-1",
            component = "Box",
            bounds = bounds(180, 200, 346, 366),
            size = LayoutInspectorSize(126, 108),
            transform =
              LayoutInspectorTransform(
                scaleX = 1f,
                scaleY = 1f,
                rotationDegrees = rotationDegrees,
              ),
            tokens = ComposeSemanticsTokens(backgroundColor = "#FFE9DDFF", shape = "circle"),
            children =
              listOf(
                LayoutInspectorNode(
                  nodeId = "icon-1",
                  component = "Box",
                  bounds = bounds(207, 227, 319, 339),
                  size = LayoutInspectorSize(56, 56),
                  tokens = ComposeSemanticsTokens(backgroundColor = "#FF210F48"),
                )
              ),
          )
        ),
    )

  private fun layerNamed(root: FigmaSvgLayer, fill: String): FigmaSvgLayer? =
    if (root.fill?.hex.equals(fill, ignoreCase = true)) root
    else root.children.firstNotNullOfOrNull { layerNamed(it, fill) }

  private fun modelOf(rotationDegrees: Float): FigmaSvgLayer =
    FigmaSvgModel.from(layout = LayoutInspectorPayload(turnedButton(rotationDegrees)), density = 2f)
      .root

  @Test
  fun `a turned layer takes its own measured extent, not the bounding box`() {
    val confirm = layerNamed(modelOf(-45f), "#E9DDFF") ?: error("confirm layer not found")
    assertEquals("width is the measured extent, not the bounding box", 126, confirm.width)
    assertEquals("height is the measured extent, not the bounding box", 108, confirm.height)
    // Centred on the bounding box: rotation maps a rect's centre onto its bounding box's centre.
    assertEquals(263, (confirm.left + confirm.right) / 2)
    assertEquals(283, (confirm.top + confirm.bottom) / 2)
    assertEquals(-45.0, confirm.rotationDegrees, 0.001)
  }

  @Test
  fun `a node inside a turned subtree is re-centred too, without inheriting the turn`() {
    val icon = layerNamed(modelOf(-45f), "#210F48") ?: error("icon layer not found")
    // 56×56, not the 112×112 bounding box the capture reports for it.
    assertEquals(56, icon.width)
    assertEquals(56, icon.height)
    assertEquals(263, (icon.left + icon.right) / 2)
    assertEquals(283, (icon.top + icon.bottom) / 2)
    // Its own axes measured un-turned and the render draws it upright, so it must NOT be turned:
    // the parent's `rotate(…)` is emitted on the parent's own drawing only, never on a group
    // wrapping children, precisely so this does not get the turn twice.
    assertEquals(0.0, icon.rotationDegrees, 0.001)
  }

  @Test
  fun `an un-turned node keeps its captured bounds exactly`() {
    val confirm = layerNamed(modelOf(0f), "#E9DDFF") ?: error("confirm layer not found")
    // The identity case must be untouched — this is the overwhelmingly common path.
    assertEquals(180, confirm.left)
    assertEquals(200, confirm.top)
    assertEquals(346, confirm.right)
    assertEquals(366, confirm.bottom)
    assertEquals(0.0, confirm.rotationDegrees, 0.001)
  }

  @Test
  fun `the emitted shape carries the turn about its own centre`() {
    val svg =
      FigmaLayeredSvg.render(
        FigmaSvgModel.from(
          layout = LayoutInspectorPayload(turnedButton(-45f)),
          density = 2f,
        )
      )
    assertTrue(
      "the turned container must be a 126x108 rect, not a 166px square:\n$svg",
      svg.contains("""width="126" height="108""""),
    )
    assertTrue(
      "the turned container must carry its rotation about its own centre:\n$svg",
      svg.contains("""transform="rotate(-45 263 283)""""),
    )
    assertFalse(
      "the bounding box must not survive as the drawn shape:\n$svg",
      svg.contains("""width="166" height="166""""),
    )
  }

  @Test
  fun `an un-turned export emits no rotation at all`() {
    val svg =
      FigmaLayeredSvg.render(
        FigmaSvgModel.from(layout = LayoutInspectorPayload(turnedButton(0f)), density = 2f)
      )
    assertFalse("no layer is turned, so nothing may carry a rotate:\n$svg", svg.contains("rotate("))
  }

  @Test
  fun `a rotation below the epsilon is placement noise and is ignored`() {
    // Sub-degree "rotation" is float noise on a mapped axis; honouring it would re-centre a node
    // the render drew exactly on its bounds.
    val confirm = layerNamed(modelOf(0.1f), "#E9DDFF") ?: error("confirm layer not found")
    assertEquals(180, confirm.left)
    assertEquals(366, confirm.bottom)
    assertEquals(0.0, confirm.rotationDegrees, 0.001)
  }
}
