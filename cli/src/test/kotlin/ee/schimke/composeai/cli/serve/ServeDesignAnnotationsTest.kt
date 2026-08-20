package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.data.layoutinspector.ComposeSemanticsInsets
import ee.schimke.composeai.data.layoutinspector.ComposeSemanticsNode
import ee.schimke.composeai.data.layoutinspector.ComposeSemanticsPayload
import ee.schimke.composeai.data.layoutinspector.ComposeSemanticsTokens
import ee.schimke.composeai.data.layoutinspector.LayoutInspectorBounds
import ee.schimke.composeai.data.layoutinspector.LayoutInspectorGradient
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
    children: List<ComposeSemanticsNode> = emptyList(),
  ) =
    ComposeSemanticsNode(
      nodeId = nodeId,
      boundsInRoot = bounds,
      role = role,
      tokens = tokens,
      children = children,
    )

  private fun annotationsOf(root: ComposeSemanticsNode) =
    ServeDesignAnnotations.annotations(ComposeSemanticsPayload(root))

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
    assertEquals("#FF6750A4@0 → #FF625B71@1", annotation.detail["backgroundGradient"])
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
    val annotation = themeOf(node(tokens = ComposeSemanticsTokens(backgroundColor = "#FF000000")))

    assertEquals(AnnotationBounds(x = 0, y = 0, width = 100, height = 50), annotation.bounds)
    assertEquals("placement", annotation.detail["box"])
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
}
