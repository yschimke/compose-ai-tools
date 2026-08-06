package ee.schimke.composeai.data.layoutinspector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A **retired** subcomposition slot — composed, still carrying its content, but no longer *placed*
 * — must not reach the export (issue #3324).
 *
 * A lazy row that scrolls out of the viewport stays composed while Compose stops placing it, and an
 * unplaced node's `LayoutCoordinates` report `(0,0,0,0)`. `FigmaSvgModel.recoverBounds` — which
 * exists for a *placed* subcomposed child whose coordinates were detached — then anchored that
 * retired content at its **parent's** origin, painting it over the top of the screen. Sibling of
 * [FigmaSvgZeroBoundsTest], which pins the recovery this must not undo.
 */
class FigmaSvgRetiredSlotTest {

  private fun node(
    component: String,
    bounds: LayoutInspectorBounds,
    size: LayoutInspectorSize,
    placed: Boolean = true,
    children: List<LayoutInspectorNode> = emptyList(),
  ) =
    LayoutInspectorNode(
      nodeId = component,
      component = component,
      bounds = bounds,
      size = size,
      placed = placed,
      children = children,
    )

  private fun allLayers(layer: FigmaSvgLayer): List<FigmaSvgLayer> = buildList {
    add(layer)
    layer.children.forEach { addAll(allLayers(it)) }
  }

  private val root =
    node(
      component = "ColumnMeasurePolicy",
      bounds = LayoutInspectorBounds(0, 0, 200, 100),
      size = LayoutInspectorSize(200, 100),
      children =
        listOf(
          node(
            component = "VisibleRow",
            bounds = LayoutInspectorBounds(0, 0, 200, 40),
            size = LayoutInspectorSize(200, 40),
          ),
          node(
            component = "RetiredRow",
            bounds = LayoutInspectorBounds(0, 0, 0, 0),
            size = LayoutInspectorSize(200, 40),
            placed = false,
            children =
              listOf(
                node(
                  component = "RetiredRowText",
                  bounds = LayoutInspectorBounds(0, 0, 0, 0),
                  size = LayoutInspectorSize(120, 24),
                  placed = false,
                )
              ),
          ),
        ),
    )

  @Test
  fun `an unplaced node and its subtree are not exported`() {
    val model = FigmaSvgModel.from(layout = LayoutInspectorPayload(root))
    val names = allLayers(model.root).map { it.name }

    assertTrue("the placed row must still be exported (got $names)", names.contains("VisibleRow"))
    assertEquals(
      "the retired row must not be exported (got $names)",
      emptyList<String>(),
      names.filter { it == "RetiredRow" || it == "RetiredRowText" },
    )
  }

  /**
   * The retired row's text is matched from the semantics tree by bounds, so pruning has to happen
   * before that match — otherwise the text lands on a node that is no longer in the tree at best,
   * and steals the match from a real node at worst.
   */
  @Test
  fun `text is not matched onto a retired node`() {
    val semantics =
      ComposeSemanticsPayload(
        root =
          ComposeSemanticsNode(
            nodeId = "1",
            boundsInRoot = "0,0,200,100",
            children =
              listOf(
                ComposeSemanticsNode(
                  nodeId = "2",
                  boundsInRoot = "0,0,0,0",
                  layoutText = "Retired row",
                )
              ),
          )
      )

    val model = FigmaSvgModel.from(layout = LayoutInspectorPayload(root), semantics = semantics)

    assertEquals(
      "no exported layer may carry the retired row's text",
      emptyList<String?>(),
      allLayers(model.root).mapNotNull { it.text?.content }.filter { it == "Retired row" },
    )
  }
}
