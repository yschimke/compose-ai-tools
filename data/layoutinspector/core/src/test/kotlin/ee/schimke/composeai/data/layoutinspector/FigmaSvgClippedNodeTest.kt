package ee.schimke.composeai.data.layoutinspector

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Two clip-edge defects the export used to have, at the model level:
 * - **#3057** — a node straddling a clip edge lost its text. The layout-inspector records
 *   **unclipped** bounds while a semantics node's `boundsInRoot` is **clipped**, so the two boxes
 *   disagree by the clipped-away strip and the bounds-match dropped the text.
 * - **#3056** — a node whose innermost coordinator reports a lookahead/scroll-content box (the
 *   `sharedBounds … verticalScroll … skipToLookaheadSize` chain) published a clip taller than the
 *   frame, leaking below-fold children. Its **clipping modifier** still carries the rendered box.
 */
class FigmaSvgClippedNodeTest {

  private fun bounds(l: Int, t: Int, r: Int, b: Int) = LayoutInspectorBounds(l, t, r, b)

  /** A 200×200 viewport that clips, holding a row whose top half sits above the viewport. */
  private fun straddlingRow() =
    LayoutInspectorNode(
      nodeId = "screen",
      component = "Box",
      bounds = bounds(0, 0, 200, 200),
      size = LayoutInspectorSize(200, 200),
      tokens = ComposeSemanticsTokens(clipsContent = true),
      children =
        listOf(
          LayoutInspectorNode(
            nodeId = "row",
            component = "Text",
            // Unclipped: the row starts 20px above the viewport's top edge.
            bounds = bounds(10, -20, 190, 40),
            size = LayoutInspectorSize(180, 60),
          )
        ),
    )

  @Test
  fun textOnANodeStraddlingAClipEdgeStillAttaches() {
    val semantics =
      ComposeSemanticsPayload(
        root =
          ComposeSemanticsNode(
            nodeId = "root",
            boundsInRoot = "0,0,200,200",
            children =
              listOf(
                // What Compose reports: clipped to the viewport, so the top 20px are gone.
                ComposeSemanticsNode(nodeId = "t", boundsInRoot = "10,0,190,40", text = "Visible")
              ),
          )
      )
    val svg =
      FigmaLayeredSvg.render(
        FigmaSvgModel.from(
          layout = LayoutInspectorPayload(straddlingRow()),
          semantics = semantics,
          density = 1f,
        )
      )
    assertTrue("the straddling row must keep its text:\n$svg", svg.contains(">Visible</text>"))
  }

  @Test
  fun textOnARowStraddlingALookaheadInflatedNodesRealViewportStillAttaches() {
    // The two fixes have to agree on ONE box: the clipping modifier's. If the layers clip a child
    // to the rendered 100px viewport while text matching clips its candidate to the node's inflated
    // 300px bounds, a row straddling the real edge mismatches again and exports blank.
    val layout =
      LayoutInspectorNode(
        nodeId = "screen",
        component = "Box",
        bounds = bounds(0, 0, 200, 400),
        size = LayoutInspectorSize(200, 400),
        children =
          listOf(
            LayoutInspectorNode(
              nodeId = "surface",
              component = "Column",
              bounds = bounds(0, 0, 200, 300),
              size = LayoutInspectorSize(200, 300),
              modifiers =
                listOf(
                  LayoutInspectorModifier(
                    name = "graphicsLayer",
                    properties = mapOf("clip" to "true"),
                    bounds = bounds(0, 0, 200, 100),
                  )
                ),
              tokens = ComposeSemanticsTokens(clipsContent = true),
              children =
                listOf(
                  // Unclipped, this row runs from 80 to 140 — past the rendered 100px edge.
                  LayoutInspectorNode(
                    nodeId = "row",
                    component = "Text",
                    bounds = bounds(10, 80, 190, 140),
                    size = LayoutInspectorSize(180, 60),
                  )
                ),
            )
          ),
      )
    val semantics =
      ComposeSemanticsPayload(
        root =
          ComposeSemanticsNode(
            nodeId = "root",
            boundsInRoot = "0,0,200,400",
            children =
              listOf(
                // Compose clips it to the viewport the surface actually rendered at.
                ComposeSemanticsNode(nodeId = "t", boundsInRoot = "10,80,190,100", text = "Edge")
              ),
          )
      )
    val svg =
      FigmaLayeredSvg.render(
        FigmaSvgModel.from(
          layout = LayoutInspectorPayload(layout),
          semantics = semantics,
          density = 1f,
        )
      )
    assertTrue("the edge row must keep its text:\n$svg", svg.contains(">Edge</text>"))
  }

  @Test
  fun aClippingModifiersOwnBoxWinsOverALookaheadInflatedNodeBox() {
    val scrollSurface =
      LayoutInspectorNode(
        nodeId = "screen",
        component = "Box",
        bounds = bounds(0, 0, 200, 400),
        size = LayoutInspectorSize(200, 400),
        children =
          listOf(
            LayoutInspectorNode(
              nodeId = "surface",
              component = "Column",
              // The innermost coordinator reports the whole scroll CONTENT (300 tall)…
              bounds = bounds(0, 0, 200, 300),
              size = LayoutInspectorSize(200, 300),
              modifiers =
                listOf(
                  // The runtime scroll clip reports the rendered 100px viewport.
                  LayoutInspectorModifier(
                    name = "scrollingContainer",
                    properties = mapOf("clip" to "true"),
                    bounds = bounds(0, 0, 200, 100),
                  ),
                  // The outer rounded surface still carries the lookahead content height. Put it
                  // last to prove selection does not depend on modifier enumeration order.
                  LayoutInspectorModifier(
                    name = "graphicsLayer",
                    properties = mapOf("clip" to "true"),
                    bounds = bounds(0, 0, 200, 300),
                  ),
                ),
              tokens = ComposeSemanticsTokens(backgroundColor = "#FFFFFFFF", clipsContent = true),
            )
          ),
      )
    val model = FigmaSvgModel.from(layout = LayoutInspectorPayload(scrollSurface), density = 1f)
    val svg = FigmaLayeredSvg.render(model)
    assertTrue(
      "the surface must be clipped to the rendered 100px viewport, not the 300px content:\n$svg",
      svg.contains("""height="100""""),
    )
    assertTrue(
      "the lookahead content height must not reach the export:\n$svg",
      !svg.contains("""height="300""""),
    )
  }
}
