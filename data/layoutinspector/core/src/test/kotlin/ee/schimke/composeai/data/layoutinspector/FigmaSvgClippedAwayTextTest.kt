package ee.schimke.composeai.data.layoutinspector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A semantics run whose `boundsInRoot` collapsed to the all-zero "no coordinates" signature must
 * not be planted on an unrelated layout node.
 *
 * Reproduces the Wear `EdgeButton` sticker in miniature. `ScreenScaffold` holds the edge button
 * collapsed at the list's resting top, so the button's label is clipped entirely away and its
 * semantics node reports `boundsInRoot = (0,0,0,0)`. The layout tree carries several *other*
 * zero-bounds nodes — a `TitleCard`'s 0×4 `Spacer` among them — and the text↔layout match is a pure
 * bounds-proximity search, so every one of them sat at distance 0 from the label. The first one
 * collected won, `recoverBounds` re-anchored that `Spacer` inside the visible card, and the export
 * drew "Start" straight over the card's title — text the PNG never painted.
 */
class FigmaSvgClippedAwayTextTest {

  private fun node(
    id: String,
    component: String,
    bounds: LayoutInspectorBounds,
    size: LayoutInspectorSize,
    children: List<LayoutInspectorNode> = emptyList(),
  ) =
    LayoutInspectorNode(
      nodeId = id,
      component = component,
      bounds = bounds,
      size = size,
      children = children,
    )

  private fun allLayers(layer: FigmaSvgLayer): List<FigmaSvgLayer> = buildList {
    add(layer)
    layer.children.forEach { addAll(allLayers(it)) }
  }

  /** The card's title, its zero-bounds spacer, and the collapsed edge-button label beside them. */
  private fun layout() =
    node(
      id = "1",
      component = "BoxMeasurePolicy",
      bounds = LayoutInspectorBounds(0, 0, 384, 384),
      size = LayoutInspectorSize(384, 384),
      children =
        listOf(
          node(
            id = "10",
            component = "ColumnMeasurePolicy",
            bounds = LayoutInspectorBounds(20, 144, 364, 272),
            size = LayoutInspectorSize(344, 128),
            children =
              listOf(
                node(
                  id = "26",
                  component = "EmptyMeasurePolicy",
                  bounds = LayoutInspectorBounds(44, 168, 227, 206),
                  size = LayoutInspectorSize(183, 38),
                ),
                // The TitleCard's inter-line Spacer: measured 0x4, but never given coordinates.
                node(
                  id = "27",
                  component = "SpacerMeasurePolicy",
                  bounds = LayoutInspectorBounds(0, 0, 0, 0),
                  size = LayoutInspectorSize(0, 4),
                ),
              ),
          ),
          // The collapsed EdgeButton: a zero-height row whose label keeps its measured size.
          node(
            id = "15",
            component = "RowMeasurePolicy",
            bounds = LayoutInspectorBounds(0, 0, 0, 0),
            size = LayoutInspectorSize(384, 0),
            children =
              listOf(
                node(
                  id = "16",
                  component = "EmptyMeasurePolicy",
                  bounds = LayoutInspectorBounds(0, 0, 0, 0),
                  size = LayoutInspectorSize(69, 36),
                )
              ),
          ),
        ),
    )

  private fun semantics() =
    ComposeSemanticsPayload(
      ComposeSemanticsNode(
        nodeId = "1",
        boundsInRoot = "0,0,384,384",
        children =
          listOf(
            ComposeSemanticsNode(
              nodeId = "26",
              boundsInRoot = "44,168,227,206",
              text = "Morning run",
              layoutText = "Morning run",
            ),
            // Clipped entirely away by the collapsed scaffold slot.
            ComposeSemanticsNode(
              nodeId = "16",
              boundsInRoot = "0,0,0,0",
              text = "Start",
              layoutText = "Start",
            ),
          ),
      )
    )

  private fun model() =
    FigmaSvgModel.from(layout = LayoutInspectorPayload(layout()), semantics = semantics())

  @Test
  fun `a clipped-away run is not planted on an unrelated zero-bounds node`() {
    val spacer = allLayers(model().root).single { it.name == "Spacer" }
    assertNull("the Spacer must carry no text (was ${spacer.text?.content})", spacer.text)
  }

  /**
   * Identity, not proximity, is what a coordinate-less run is matched on — so it stays on the
   * collapsed button's own label node, which the render drew nothing for: a zero-area layer, well
   * clear of the card the ghost used to land in.
   */
  @Test
  fun `a clipped-away run stays on its own collapsed node`() {
    val start = allLayers(model().root).single { it.text?.content == "Start" }
    assertEquals("the collapsed label draws no width", 0, start.right - start.left)
    assertEquals("the collapsed label draws no height", 0, start.bottom - start.top)
    val title = allLayers(model().root).single { it.text?.content == "Morning run" }
    assertTrue(
      "the run must not land inside the card ($start vs $title)",
      start.right <= title.left ||
        start.left >= title.right ||
        start.bottom <= title.top ||
        start.top >= title.bottom,
    )
  }

  @Test
  fun `the visible run keeps its own bounds`() {
    val title = allLayers(model().root).single { it.text?.content == "Morning run" }
    assertEquals(44, title.left)
    assertEquals(168, title.top)
  }
}
