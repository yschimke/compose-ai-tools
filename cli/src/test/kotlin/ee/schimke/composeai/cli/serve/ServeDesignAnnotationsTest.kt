package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.data.layoutinspector.ComposeSemanticsInsets
import ee.schimke.composeai.data.layoutinspector.ComposeSemanticsNode
import ee.schimke.composeai.data.layoutinspector.ComposeSemanticsPayload
import ee.schimke.composeai.data.layoutinspector.ComposeSemanticsTokens
import ee.schimke.composeai.data.layoutinspector.ComposeSemanticsTypography
import ee.schimke.composeai.data.layoutinspector.LayoutInspectorBounds
import ee.schimke.composeai.data.layoutinspector.LayoutInspectorGradient
import ee.schimke.composeai.data.layoutinspector.LayoutInspectorNode
import ee.schimke.composeai.data.layoutinspector.LayoutInspectorPayload
import ee.schimke.composeai.data.layoutinspector.LayoutInspectorSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The viewer's derived inspection layers (issue #4328).
 *
 * The theme layer used to quote eight of the ~18 tokens a capture resolves and there was no
 * code-side layout layer at all, so the redline values a layout diff is argued in — the box's own
 * size, its padding, the arrangement gap — had no surface. These tests pin what each layer says
 * about a node, since a wrong-but-plausible label looks exactly like a right one on screen.
 */
class ServeDesignAnnotationsTest {

  private fun node(
    nodeId: String = "1",
    bounds: String = "0,0,100,50",
    tokens: ComposeSemanticsTokens? = null,
    role: String? = null,
    typography: ComposeSemanticsTypography? = null,
    placed: Boolean = true,
    children: List<ComposeSemanticsNode> = emptyList(),
  ) =
    ComposeSemanticsNode(
      nodeId = nodeId,
      boundsInRoot = bounds,
      role = role,
      typography = typography,
      tokens = tokens,
      placed = placed,
      children = children,
    )

  private fun annotationsOf(root: ComposeSemanticsNode) =
    ServeDesignAnnotations.annotations(ComposeSemanticsPayload(root))

  /** A `LayoutNode` as `layout/inspector` reports it: no semantics needed, and none implied. */
  private fun layoutNode(
    nodeId: String = "1",
    component: String = "Box",
    displayName: String? = null,
    bounds: LayoutInspectorBounds = LayoutInspectorBounds(0, 0, 100, 50),
    tokens: ComposeSemanticsTokens? = null,
    placed: Boolean = true,
    children: List<LayoutInspectorNode> = emptyList(),
  ) =
    LayoutInspectorNode(
      nodeId = nodeId,
      component = component,
      displayName = displayName,
      bounds = bounds,
      size = LayoutInspectorSize(bounds.right - bounds.left, bounds.bottom - bounds.top),
      placed = placed,
      tokens = tokens,
      children = children,
    )

  /** The projection as the render host drives it: a semantics tree AND a layout tree. */
  private fun annotationsOf(semantics: ComposeSemanticsNode, layout: LayoutInspectorNode) =
    ServeDesignAnnotations.annotations(
      ComposeSemanticsPayload(semantics),
      layout = LayoutInspectorPayload(layout),
    )

  private fun themeOf(root: ComposeSemanticsNode) =
    annotationsOf(root).single { it.kind == AnnotationKind.THEME }

  private fun layoutOf(root: ComposeSemanticsNode) =
    annotationsOf(root).filter { it.kind == AnnotationKind.LAYOUT }

  @Test
  fun `theme label carries the shadow, alpha and clip the old subset dropped`() {
    val annotation =
      themeOf(
        node(
          role = "Button",
          tokens =
            ComposeSemanticsTokens(
              backgroundColor = "#FF6750A4",
              cornerRadius = "20.0dp",
              elevation = "6.0dp",
              opacity = 0.5,
              clipsContent = true,
            ),
        )
      )

    assertEquals(
      "fill #FF6750A4 · radius 20.0dp · elevation 6.0dp · alpha 0.5 · clip",
      annotation.label,
    )
    assertEquals("Button", annotation.role)
    assertEquals("6.0dp", annotation.detail["elevation"])
    assertEquals("0.5", annotation.detail["opacity"])
    assertEquals("true", annotation.detail["clipsContent"])
  }

  @Test
  fun `a fully opaque node says nothing about its alpha`() {
    // `alpha 1` on every second box is noise: it is the value a reader already assumes.
    val annotation =
      themeOf(node(tokens = ComposeSemanticsTokens(backgroundColor = "#FF000000", opacity = 1.0)))

    assertEquals("fill #FF000000", annotation.label)
    assertEquals("1", annotation.detail["opacity"])
  }

  @Test
  fun `a gradient is named by its stops rather than the bare word`() {
    val annotation =
      themeOf(
        node(
          tokens =
            ComposeSemanticsTokens(
              backgroundGradient =
                LayoutInspectorGradient(
                  colors = listOf("#FF6750A4", "#FF625B71"),
                  stops = listOf(0f, 1f),
                )
            )
        )
      )

    assertEquals("fill gradient #FF6750A4→#FF625B71", annotation.label)
    assertEquals(
      "#FF6750A4@0 → #FF625B71@1 (horizontal)",
      annotation.detail["backgroundGradient"],
    )
  }

  @Test
  fun `theme anchors to the box the paint actually landed in`() {
    // `padding(4.dp).clip(…).background(…)` paints inside the placement box; outlining the
    // placement box reports a radius against geometry that was never painted.
    val annotation =
      themeOf(
        node(
          bounds = "0,0,100,50",
          tokens =
            ComposeSemanticsTokens(
              backgroundColor = "#FF000000",
              paintBox = LayoutInspectorBounds(left = 4, top = 4, right = 96, bottom = 46),
            ),
        )
      )

    assertEquals(AnnotationBounds(x = 4, y = 4, width = 92, height = 42), annotation.bounds)
    assertEquals("paint", annotation.detail["box"])
  }

  @Test
  fun `theme falls back to the placement box when no paint box was captured`() {
    // And says nothing about which box: `box placement` on every ordinary container is a row that
    // carries no information. The key marks the exception, not the rule.
    val annotation = themeOf(node(tokens = ComposeSemanticsTokens(backgroundColor = "#FF000000")))

    assertEquals(AnnotationBounds(x = 0, y = 0, width = 100, height = 50), annotation.bounds)
    assertNull(annotation.detail["box"])
  }

  @Test
  fun `a paint box identical to the placement box is not called out either`() {
    val annotation =
      themeOf(
        node(
          bounds = "0,0,100,50",
          tokens =
            ComposeSemanticsTokens(
              backgroundColor = "#FF000000",
              paintBox = LayoutInspectorBounds(left = 0, top = 0, right = 100, bottom = 50),
            ),
        )
      )

    assertEquals(AnnotationBounds(x = 0, y = 0, width = 100, height = 50), annotation.bounds)
    assertNull(annotation.detail["box"])
  }

  @Test
  fun `a node declaring no container tokens contributes no theme box`() {
    assertTrue(annotationsOf(node()).none { it.kind == AnnotationKind.THEME })
  }

  @Test
  fun `layout quotes the box size and the tokens that shaped it`() {
    val annotation =
      layoutOf(
          node(
            bounds = "10,20,130,68",
            tokens =
              ComposeSemanticsTokens(
                padding = ComposeSemanticsInsets(start = "16.0dp", top = "16.0dp"),
                gap = "8.0dp",
                minWidth = "48.0dp",
                minHeight = "48.0dp",
              ),
          )
        )
        .single()

    assertEquals(
      "120×48px · pad 16.0dp 0.0dp 0.0dp 16.0dp · gap 8.0dp · min 48.0dp×48.0dp",
      annotation.label,
    )
    assertEquals("120px", annotation.detail["width"])
    assertEquals("48px", annotation.detail["height"])
    assertEquals("10px", annotation.detail["x"])
    assertEquals("top 16.0dp, start 16.0dp", annotation.detail["padding"])
  }

  @Test
  fun `a uniform padding reads as one number and a symmetric one as two`() {
    val uniform = ComposeSemanticsInsets("8.0dp", "8.0dp", "8.0dp", "8.0dp")
    val symmetric =
      ComposeSemanticsInsets(start = "16.0dp", top = "8.0dp", end = "16.0dp", bottom = "8.0dp")

    assertEquals(
      "100×50px · pad 8.0dp",
      layoutOf(node(tokens = ComposeSemanticsTokens(padding = uniform))).single().label,
    )
    assertEquals(
      "100×50px · pad 8.0dp/16.0dp",
      layoutOf(node(tokens = ComposeSemanticsTokens(padding = symmetric))).single().label,
    )
  }

  @Test
  fun `every nested slot box gets a layout row`() {
    // The value of a redline is the nesting: a component 4px wider than the kit is only
    // diagnosable when the slot boxes inside it are on screen too.
    val boxes =
      layoutOf(
        node(
          bounds = "0,0,200,100",
          children =
            listOf(
              node(nodeId = "2", bounds = "8,8,100,60"),
              node(nodeId = "3", bounds = "108,8,192,60"),
            ),
        )
      )

    assertEquals(listOf("200×100px", "92×52px", "84×52px"), boxes.map { it.label })
  }

  @Test
  fun `a wrapper that reproduces its parent's box exactly is not drawn twice`() {
    val boxes =
      layoutOf(
        node(
          bounds = "0,0,200,100",
          children = listOf(node(nodeId = "2", bounds = "0,0,200,100")),
        )
      )

    assertEquals(1, boxes.size)
  }

  @Test
  fun `a wrapper on its parent's box still counts when it declares layout tokens`() {
    // Same pixels, different fact: the inner node is where the 16dp inset comes from.
    val boxes =
      layoutOf(
        node(
          bounds = "0,0,200,100",
          children =
            listOf(
              node(
                nodeId = "2",
                bounds = "0,0,200,100",
                tokens = ComposeSemanticsTokens(padding = ComposeSemanticsInsets(start = "16.0dp")),
              )
            ),
        )
      )

    assertEquals(2, boxes.size)
    assertTrue(boxes[1].label.contains("pad"))
  }

  @Test
  fun `a node with no drawable box contributes nothing at all`() {
    assertTrue(annotationsOf(node(bounds = "10,10,10,10")).isEmpty())
    assertTrue(annotationsOf(node(bounds = "nonsense")).isEmpty())
  }

  @Test
  fun `pixel corners are reported when a dp radius cannot express them`() {
    val annotation =
      themeOf(
        node(
          tokens = ComposeSemanticsTokens(backgroundColor = "#FF000000", cornerRadiusPx = "20.0px")
        )
      )

    assertTrue(annotation.label.contains("radius 20.0px"))
    assertEquals("20.0px", annotation.detail["cornerRadiusPx"])
    assertNull(annotation.detail["cornerRadius"])
  }

  @Test
  fun `the layout tree supplies containers the semantics tree cannot see`() {
    // The case the layer exists for: `Column(Modifier.padding(16.dp), Arrangement.spacedBy(8.dp))`
    // declares no semantics at all, so walking the semantics tree finds only the text inside it and
    // the very padding and gap this layer advertises are silently absent.
    val semantics = node(bounds = "16,16,184,64")
    val layout =
      layoutNode(
        component = "Column",
        bounds = LayoutInspectorBounds(0, 0, 200, 100),
        tokens =
          ComposeSemanticsTokens(
            padding = ComposeSemanticsInsets("16.0dp", "16.0dp", "16.0dp", "16.0dp"),
            gap = "8.0dp",
          ),
        children =
          listOf(layoutNode(nodeId = "2", bounds = LayoutInspectorBounds(16, 16, 184, 64))),
      )

    val boxes = annotationsOf(semantics, layout).filter { it.kind == AnnotationKind.LAYOUT }

    assertEquals(listOf("200×100px · pad 16.0dp · gap 8.0dp", "168×48px"), boxes.map { it.label })
    assertEquals("Column", boxes[0].role)
  }

  @Test
  fun `container layers come from ONE tree, never both`() {
    // Both trees carry the same mirrored tokens, so projecting both would draw every container
    // twice — two boxes and two legend rows for one rectangle.
    val tokens = ComposeSemanticsTokens(backgroundColor = "#FF6750A4")
    val all =
      annotationsOf(
        node(bounds = "0,0,100,50", tokens = tokens),
        layoutNode(bounds = LayoutInspectorBounds(0, 0, 100, 50), tokens = tokens),
      )

    assertEquals(1, all.count { it.kind == AnnotationKind.THEME })
    assertEquals(1, all.count { it.kind == AnnotationKind.LAYOUT })
  }

  @Test
  fun `a capture with no layout tree still gets both container layers`() {
    // A daemon too old to know `layout/inspector` must not lose the layers entirely: the semantics
    // tree mirrors the same tokens, so it falls back to fewer boxes rather than none.
    val all = annotationsOf(node(tokens = ComposeSemanticsTokens(backgroundColor = "#FF6750A4")))

    assertEquals(1, all.count { it.kind == AnnotationKind.THEME })
    assertEquals(1, all.count { it.kind == AnnotationKind.LAYOUT })
  }

  @Test
  fun `an unplaced semantics subtree describes nowhere on the frame`() {
    // Wear's `AlertDialogContent` subcomposes a full trial copy of the dialog to decide whether
    // its content has to scroll. That copy is measured and never placed, so every node in it
    // reports the ORIGIN — and the typography layer drew a second title stacked in the frame's
    // top-left corner (yschimke/wear-m3-catalog#77).
    val title = ComposeSemanticsTypography(fontSize = "16.0sp", fontFamily = "Roboto Flex")
    val boxes =
      annotationsOf(
          node(
            bounds = "0,0,384,384",
            children =
              listOf(
                node(nodeId = "2", bounds = "68,101,316,175", typography = title),
                node(
                  nodeId = "3",
                  bounds = "0,0,384,354",
                  placed = false,
                  children = listOf(node(nodeId = "4", bounds = "0,0,248,74", typography = title)),
                ),
              ),
          )
        )
        .filter { it.kind == AnnotationKind.TYPOGRAPHY }

    assertEquals(listOf(AnnotationBounds(68, 101, 248, 74)), boxes.map { it.bounds })
  }

  @Test
  fun `an unplaced layout node describes nowhere on the frame`() {
    // Measured but never positioned: its bounds point at a rectangle the render does not contain.
    val boxes =
      annotationsOf(
          node(),
          layoutNode(
            bounds = LayoutInspectorBounds(0, 0, 200, 100),
            children =
              listOf(
                layoutNode(
                  nodeId = "2",
                  bounds = LayoutInspectorBounds(0, 0, 40, 40),
                  placed = false,
                )
              ),
          ),
        )
        .filter { it.kind == AnnotationKind.LAYOUT }

    assertEquals(listOf("200×100px"), boxes.map { it.label })
  }

  @Test
  fun `an unplaced layout node takes its whole subtree with it`() {
    // Suppressing only the unplaced node's own box left any descendant still flagged placed free
    // to draw one — and nothing under a node that was never positioned is on the frame, whatever
    // its own flag says.
    val boxes =
      annotationsOf(
          node(),
          layoutNode(
            bounds = LayoutInspectorBounds(0, 0, 200, 100),
            children =
              listOf(
                layoutNode(
                  nodeId = "2",
                  bounds = LayoutInspectorBounds(0, 0, 40, 40),
                  placed = false,
                  children =
                    listOf(layoutNode(nodeId = "3", bounds = LayoutInspectorBounds(0, 0, 20, 20))),
                )
              ),
          ),
        )
        .filter { it.kind == AnnotationKind.LAYOUT }

    assertEquals(listOf("200×100px"), boxes.map { it.label })
  }

  @Test
  fun `a layout node is named by its friendly label before its own class`() {
    val boxes =
      annotationsOf(
          node(),
          layoutNode(component = "Box", displayName = "IconButton"),
        )
        .filter { it.kind == AnnotationKind.LAYOUT }

    assertEquals("IconButton", boxes.single().role)
  }

  @Test
  fun `two gradients that differ only in direction do not project identically`() {
    fun detailFor(endX: Float, endY: Float) =
      themeOf(
          node(
            tokens =
              ComposeSemanticsTokens(
                backgroundGradient =
                  LayoutInspectorGradient(
                    colors = listOf("#FF000000", "#FFFFFFFF"),
                    endX = endX,
                    endY = endY,
                  )
              )
          )
        )
        .detail["backgroundGradient"]

    assertEquals("#FF000000 → #FFFFFFFF (horizontal)", detailFor(endX = 1f, endY = 0f))
    assertEquals("#FF000000 → #FFFFFFFF (vertical)", detailFor(endX = 0f, endY = 1f))
    assertEquals("#FF000000 → #FFFFFFFF (0,0 → 1,1)", detailFor(endX = 1f, endY = 1f))
  }
}
