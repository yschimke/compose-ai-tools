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

  /**
   * The narrowing must not *widen* a fill. `size(100.dp).clip(…).requiredSize(50.dp, 200.dp)
   * .background(…)` overflows its fixed clip vertically while staying narrower than it horizontally
   * — the same "shorter but wider" shape the lookahead chain presents, but here the node really
   * does paint only 50 wide. Clipping the node's *painted* extent (its own box unioned with its
   * paint modifiers' boxes) rather than substituting the clip box whole keeps the fill off the 25px
   * margins on each side that Compose leaves blank.
   */
  @Test
  fun aFixedClipAroundARequiredSizeOverflowDoesNotWidenTheFill() {
    val overflowing =
      LayoutInspectorNode(
        nodeId = "screen",
        component = "Box",
        bounds = bounds(0, 0, 200, 400),
        size = LayoutInspectorSize(200, 400),
        children =
          listOf(
            LayoutInspectorNode(
              nodeId = "tall",
              component = "Box",
              // `requiredSize(50, 200)`: narrower than the clip, and twice as tall.
              bounds = bounds(25, 0, 75, 200),
              size = LayoutInspectorSize(50, 200),
              modifiers =
                listOf(
                  LayoutInspectorModifier(
                    name = "graphicsLayer",
                    properties = mapOf("clip" to "true"),
                    bounds = bounds(0, 0, 100, 100),
                  ),
                  // The fill is on the innermost coordinator, so the painted extent is the node's
                  // own 50-wide box.
                  LayoutInspectorModifier(name = "background", bounds = bounds(25, 0, 75, 200)),
                ),
              tokens = ComposeSemanticsTokens(backgroundColor = "#FFFFFFFF", clipsContent = true),
              children =
                listOf(
                  // A child drawing across the FULL 100-wide viewport — past the parent's own
                  // 50-wide paint, but well inside the mask. The render shows all 70px of it.
                  LayoutInspectorNode(
                    nodeId = "wide",
                    component = "Box",
                    bounds = bounds(25, 10, 95, 160),
                    size = LayoutInspectorSize(70, 150),
                    tokens = ComposeSemanticsTokens(backgroundColor = "#FF0000FF"),
                  )
                ),
            )
          ),
      )
    val svg =
      FigmaLayeredSvg.render(
        FigmaSvgModel.from(layout = LayoutInspectorPayload(overflowing), density = 1f)
      )
    assertTrue(
      "the fill must be the painted 50×100 box — clipped in height, never widened to the clip:" +
        "\n$svg",
      svg.contains("""<rect x="25" y="0" width="50" height="100" fill="#FFFFFF""""),
    )
    // The mask keeps the clipping coordinator's own rect: it is what the subtree is cut to, and
    // shrinking it to the parent's narrower paint would trim a child the render draws in full.
    assertTrue(
      "the mask must stay the 100×100 clip box:\n$svg",
      svg.contains("""<clipPath id="clip-0"><rect x="0" y="0" width="100" height="100"/>"""),
    )
    assertTrue(
      "the child spanning the viewport must keep its full 70px width:\n$svg",
      svg.contains("""width="70""""),
    )
  }
}
