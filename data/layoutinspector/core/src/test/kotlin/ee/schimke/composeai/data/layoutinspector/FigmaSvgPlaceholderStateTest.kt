package ee.schimke.composeai.data.layoutinspector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The state-aware placeholder export (issue #2646) — the follow-up that replaces the two
 * point-fixes (#2644 text, #2645 corner) with one model of what a placeholder *is*.
 *
 * A Wear M3 `Modifier.placeholder` draws through a `drawWithContent`, which the hybrid canvas-draw
 * path otherwise crops to an `<image>`. Whether that is right depends on state the modifier chain
 * alone doesn't carry:
 * - **inactive** (content loaded — the `__ideal__` variants): the draw is a pass-through, so the
 *   node's real text/children are perfectly vectorisable. No raster.
 * - **active** (loading): the placeholder block covers the content, so the export emits *it* — a
 *   rounded rect in the placeholder's own colour/shape — as its own editable layer, rather than
 *   baking the composited frame.
 */
class FigmaSvgPlaceholderStateTest {

  private fun bounds(l: Int, t: Int, r: Int, b: Int) = LayoutInspectorBounds(l, t, r, b)

  /** A `Text` leaf drawn through a placeholder (⇒ a `drawWithContent` on its chain). */
  private fun placeholderText(
    visible: Boolean?,
    colorArgb: String? = "#FF334455",
    cornerRadius: String? = "12dp",
    shape: String? = null,
  ) =
    LayoutInspectorNode(
      nodeId = "text-1",
      component = "Text",
      bounds = bounds(0, 0, 200, 50),
      size = LayoutInspectorSize(200, 50),
      modifiers = listOf(LayoutInspectorModifier(name = "drawWithContent", placeholder = true)),
      placeholder =
        LayoutInspectorPlaceholder(
          kind = PlaceholderModifiers.KIND_PLACEHOLDER,
          visible = visible,
          colorArgb = colorArgb,
          cornerRadius = cornerRadius,
          shape = shape,
        ),
    )

  private fun model(node: LayoutInspectorNode, text: String? = "Confetti") =
    FigmaSvgModel.from(
      layout = LayoutInspectorPayload(node),
      semantics =
        text?.let {
          ComposeSemanticsPayload(
            ComposeSemanticsNode(nodeId = "sem", boundsInRoot = "0,0,200,50", text = it)
          )
        },
      // Hybrid mode — a frame PNG exists to crop from, the only mode in which the canvas-draw
      // raster path is reachable at all.
      captureCanvasDraws = true,
      density = 2f,
    )

  @Test
  fun `an inactive placeholder keeps its content as editable vector`() {
    val m = model(placeholderText(visible = false))

    assertTrue("no crop of the composited frame", m.rasterTargets.isEmpty())
    assertNull("no drawWithContent background raster", m.root.background)
    assertEquals("Confetti", m.root.text?.content)
  }

  @Test
  fun `an unreadable placeholder state is treated as inactive`() {
    // Never blank real content on a guess: a placeholder whose state couldn't be read must still
    // render the content that is actually in the frame.
    val m = model(placeholderText(visible = null))

    assertTrue(m.rasterTargets.isEmpty())
    assertEquals("Confetti", m.root.text?.content)
  }

  @Test
  fun `an active placeholder becomes its own vector layer`() {
    val m = model(placeholderText(visible = true))

    assertTrue("still no frame crop — the block is vector", m.rasterTargets.isEmpty())
    assertNull("the covered content is not drawn under the block", m.root.text)
    assertEquals("#334455", m.root.fill?.hex)
    // 12dp at the capture's density 2 = 24px, on all four corners.
    assertEquals(listOf(24.0, 24.0, 24.0, 24.0), m.root.cornerRadiiPx)
    assertTrue("named for what it is", m.root.name.endsWith("Placeholder"))
  }

  @Test
  fun `an active placeholder keeps a non-dp shape descriptor`() {
    val m = model(placeholderText(visible = true, cornerRadius = null, shape = "circle"))

    assertTrue(m.root.circle)
    assertNull(m.root.cornerRadiiPx)
  }

  @Test
  fun `the active placeholder renders as a rect, not an image`() {
    val svg = FigmaLayeredSvg.render(model(placeholderText(visible = true)))

    assertTrue("emitted as an editable shape", svg.contains("<rect"))
    assertTrue("nothing baked from the frame", !svg.contains("<image"))
    assertTrue("the covered string is gone", !svg.contains("Confetti"))
  }

  @Test
  fun `an unrelated draw on a placeholdered node keeps its raster`() {
    // `Modifier.drawBehind { … }.placeholder(state)`: the placeholder's own draw is a pass-through
    // in the ideal state, but the app's imperative art really is in the frame. Suppressing every
    // draw on the node would silently lose it, so the exclusion is per-entry, not per-node.
    val node =
      LayoutInspectorNode(
        nodeId = "chip",
        component = "Spacer",
        bounds = bounds(0, 0, 200, 50),
        size = LayoutInspectorSize(200, 50),
        modifiers =
          listOf(
            LayoutInspectorModifier(name = "drawBehind"),
            LayoutInspectorModifier(name = "drawWithContent", placeholder = true),
          ),
        placeholder =
          LayoutInspectorPlaceholder(kind = PlaceholderModifiers.KIND_PLACEHOLDER, visible = false),
      )
    val m = model(node, text = null)

    assertEquals(1, m.rasterTargets.size)
    assertNotNull(m.root.background)
  }

  @Test
  fun `a node with a real custom draw and no placeholder still rasterises`() {
    // The guard is placeholder-scoped: an ordinary `Modifier.drawBehind` leaf (a progress track, a
    // slider groove) keeps its hybrid crop.
    val drawn =
      LayoutInspectorNode(
        nodeId = "spacer",
        component = "Spacer",
        bounds = bounds(0, 0, 200, 50),
        size = LayoutInspectorSize(200, 50),
        modifiers = listOf(LayoutInspectorModifier(name = "drawBehind")),
      )
    val m = model(drawn, text = null)

    assertEquals(1, m.rasterTargets.size)
    assertNotNull(m.root.background)
  }
}
