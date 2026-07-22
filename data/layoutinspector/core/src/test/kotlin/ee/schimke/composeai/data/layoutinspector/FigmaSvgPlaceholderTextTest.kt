package ee.schimke.composeai.data.layoutinspector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression for the "text rendered twice" figma-svg bug (Confetti wear `SessionCard`): a `Text`
 * carrying a `drawWithContent` draw (Wear M3 `Modifier.placeholder`) nested under a wrapper that
 * shares its bounds (the `TitleCard` title slot, a `fillMaxWidth` parent).
 *
 * The semantics run matches the wrapper and the `Text` leaf equally well; it must land on the leaf.
 * When it landed on the wrapper instead, the (now text-less) `drawWithContent` leaf was treated as
 * un-vectorisable canvas chrome and cropped out of the frame as an opaque `<image>` — doubling the
 * `<text>` the wrapper already emitted. The fix: a bounds tie in `assignTextToLayers` resolves to
 * the deepest (innermost) node, so the leaf keeps its editable text and never rasterises.
 */
class FigmaSvgPlaceholderTextTest {

  private fun bounds(l: Int, t: Int, r: Int, b: Int) = LayoutInspectorBounds(l, t, r, b)

  /** A `Text` leaf drawing via `Modifier.placeholder` (⇒ a `drawWithContent` modifier). */
  private fun placeholderTextLeaf(nodeId: String, l: Int, t: Int, r: Int, b: Int) =
    LayoutInspectorNode(
      nodeId = nodeId,
      component = "Text",
      bounds = bounds(l, t, r, b),
      size = LayoutInspectorSize(r - l, b - t),
      modifiers = listOf(LayoutInspectorModifier(name = "drawWithContent")),
    )

  /** A pass-through wrapper (a slot `Box` / `fillMaxWidth` parent) sharing the leaf's bounds. */
  private fun wrapper(nodeId: String, l: Int, t: Int, r: Int, b: Int, child: LayoutInspectorNode) =
    LayoutInspectorNode(
      nodeId = nodeId,
      component = "Box",
      bounds = bounds(l, t, r, b),
      size = LayoutInspectorSize(r - l, b - t),
      children = listOf(child),
    )

  private fun semanticsText(l: Int, t: Int, r: Int, b: Int, text: String) =
    ComposeSemanticsNode(nodeId = "sem", boundsInRoot = "$l,$t,$r,$b", text = text)

  private fun model(layout: LayoutInspectorNode, semanticsRoot: ComposeSemanticsNode) =
    FigmaSvgModel.from(
      layout = LayoutInspectorPayload(layout),
      semantics = ComposeSemanticsPayload(semanticsRoot),
      // Hybrid mode (a frame PNG exists to crop from) — the mode the SessionCard renders in, and
      // the only mode in which the canvas-draw raster path is even reachable.
      captureCanvasDraws = true,
    )

  private fun leafLayer(m: FigmaSvgModel): FigmaSvgLayer {
    fun find(l: FigmaSvgLayer): FigmaSvgLayer? =
      if (l.name == "Text" || l.text != null || l.raster != null || l.background != null) l
      else l.children.firstNotNullOfOrNull(::find)
    return find(m.root) ?: error("no leaf layer")
  }

  @Test
  fun placeholderTextUnderASharedBoundsWrapperStaysEditableTextNotAnImage() {
    val leaf = placeholderTextLeaf("text-1", 0, 0, 200, 50)
    val root = wrapper("slot", 0, 0, 200, 50, leaf)
    val m = model(root, semanticsText(0, 0, 200, 50, "Confetti"))

    assertTrue("the placeholder Text leaf must not rasterise", m.rasterTargets.isEmpty())
    val layer = leafLayer(m)
    assertNull("no opaque <image> for a text node", layer.raster)
    assertNull("no background <image> for a text node", layer.background)
    assertNotNull("the run lands on the tight Text leaf as editable text", layer.text)
    assertEquals("Confetti", layer.text!!.content)
  }

  @Test
  fun renderedSvgHasTheTextOnceAndNoImage() {
    val leaf = placeholderTextLeaf("text-1", 0, 0, 200, 50)
    val root = wrapper("slot", 0, 0, 200, 50, leaf)
    val svg = FigmaLayeredSvg.render(model(root, semanticsText(0, 0, 200, 50, "Confetti")))

    assertTrue("emitted as vector <text>", svg.contains("<text"))
    assertTrue("the run is not baked into a raster <image>", !svg.contains("<image"))
    assertEquals("the string appears exactly once", 1, svg.split(">Confetti<").size - 1)
  }

  @Test
  fun aTieResolvesToTheDeeperNodeRegardlessOfSiblingOrder() {
    // Two wrapper levels share the leaf's bounds; the deepest (the Text leaf) must win the tie.
    val leaf = placeholderTextLeaf("text-1", 0, 0, 200, 50)
    val inner = wrapper("inner", 0, 0, 200, 50, leaf)
    val outer = wrapper("outer", 0, 0, 200, 50, inner)
    val m = model(outer, semanticsText(0, 0, 200, 50, "Confetti"))

    assertTrue(m.rasterTargets.isEmpty())
    assertEquals("Confetti", leafLayer(m).text?.content)
  }
}
