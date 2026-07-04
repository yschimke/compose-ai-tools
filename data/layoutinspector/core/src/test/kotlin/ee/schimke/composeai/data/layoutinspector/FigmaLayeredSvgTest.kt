package ee.schimke.composeai.data.layoutinspector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FigmaLayeredSvgTest {

  private fun bounds(l: Int, t: Int, r: Int, b: Int) = LayoutInspectorBounds(l, t, r, b)

  private fun layoutNode(
    component: String,
    l: Int,
    t: Int,
    r: Int,
    b: Int,
    tokens: ComposeSemanticsTokens? = null,
    children: List<LayoutInspectorNode> = emptyList(),
  ) =
    LayoutInspectorNode(
      nodeId = component,
      component = component,
      bounds = bounds(l, t, r, b),
      size = LayoutInspectorSize(r - l, b - t),
      tokens = tokens,
      children = children,
    )

  private fun render(
    layout: LayoutInspectorNode,
    semantics: ComposeSemanticsNode? = null,
    colorNames: Map<String, String> = emptyMap(),
    density: Float = 1f,
  ): String {
    val model =
      FigmaSvgModel.from(
        layout = LayoutInspectorPayload(layout),
        semantics = semantics?.let { ComposeSemanticsPayload(it) },
        colorNames = colorNames,
        density = density,
      )
    return FigmaLayeredSvg.render(model)
  }

  @Test
  fun everyLayoutNodeBecomesANamedGroup() {
    val svg =
      render(
        layoutNode(
          "Screen",
          0,
          0,
          400,
          800,
          children =
            listOf(layoutNode("Header", 0, 0, 400, 100), layoutNode("Body", 0, 100, 400, 800)),
        )
      )
    assertTrue(svg.contains("""<g id="Screen""""))
    assertTrue(svg.contains("""<g id="Header""""))
    assertTrue(svg.contains("""<g id="Body""""))
    assertTrue(svg.startsWith("<svg"))
    assertTrue(svg.trimEnd().endsWith("</svg>"))
  }

  @Test
  fun nestingIsPreservedAsNestedGroups() {
    val svg =
      render(
        layoutNode("Card", 0, 0, 200, 200, children = listOf(layoutNode("Inner", 10, 10, 190, 190)))
      )
    val cardIdx = svg.indexOf("""<g id="Card"""")
    val innerIdx = svg.indexOf("""<g id="Inner"""")
    // Inner opens after Card and before Card closes → it is a descendant group.
    assertTrue(cardIdx in 0 until innerIdx)
    // The Inner group's closing tag precedes Card's, confirming containment.
    val innerClose = svg.indexOf("</g>", innerIdx)
    val cardClose = svg.lastIndexOf("</g>")
    assertTrue(innerClose in (innerIdx + 1) until cardClose)
  }

  @Test
  fun backgroundTokenBecomesFilledRect() {
    val svg =
      render(
        layoutNode(
          "Surface",
          0,
          0,
          100,
          100,
          tokens = ComposeSemanticsTokens(backgroundColor = "#FF6750A4"),
        )
      )
    assertTrue(svg.contains("<rect"))
    assertTrue(svg.contains("""fill="#6750A4""""))
  }

  @Test
  fun opaqueFillHasNoOpacityAttributeButTranslucentDoes() {
    val opaque =
      render(
        layoutNode(
          "A",
          0,
          0,
          10,
          10,
          tokens = ComposeSemanticsTokens(backgroundColor = "#FF112233"),
        )
      )
    assertFalse(opaque.contains("fill-opacity"))
    val translucent =
      render(
        layoutNode(
          "A",
          0,
          0,
          10,
          10,
          tokens = ComposeSemanticsTokens(backgroundColor = "#80112233"),
        )
      )
    assertTrue(translucent.contains("fill-opacity"))
  }

  @Test
  fun borderTokenBecomesStroke() {
    val svg =
      render(
        layoutNode(
          "Bordered",
          0,
          0,
          100,
          100,
          tokens = ComposeSemanticsTokens(borderColor = "#FF000000"),
        )
      )
    assertTrue(svg.contains("""stroke="#000000""""))
  }

  @Test
  fun uniformCornerRadiusUsesRxRy() {
    val svg =
      render(
        layoutNode(
          "Rounded",
          0,
          0,
          100,
          100,
          tokens = ComposeSemanticsTokens(backgroundColor = "#FFFFFFFF", cornerRadius = "12.0dp"),
        )
      )
    assertTrue(svg.contains("""rx="12""""))
    assertTrue(svg.contains("""ry="12""""))
  }

  @Test
  fun cornerRadiusScalesWithDensity() {
    val svg =
      render(
        layoutNode(
          "Rounded",
          0,
          0,
          100,
          100,
          tokens = ComposeSemanticsTokens(backgroundColor = "#FFFFFFFF", cornerRadius = "8.0dp"),
        ),
        density = 2f,
      )
    assertTrue(svg.contains("""rx="16""""))
  }

  @Test
  fun nonUniformCornersBecomeAPath() {
    val svg =
      render(
        layoutNode(
          "TopRounded",
          0,
          0,
          100,
          100,
          tokens =
            ComposeSemanticsTokens(
              backgroundColor = "#FFFFFFFF",
              cornerRadius = "12.0dp,12.0dp,0.0dp,0.0dp",
            ),
        )
      )
    assertTrue(svg.contains("<path"))
    assertTrue(svg.contains(" A")) // arc commands for the rounded corners
  }

  @Test
  fun circleShapeIsDrawnWithHalfMinSideRadius() {
    val svg =
      render(
        layoutNode(
          "Avatar",
          0,
          0,
          48,
          48,
          tokens = ComposeSemanticsTokens(backgroundColor = "#FFCCCCCC", shape = "circle"),
        )
      )
    assertTrue(svg.contains("""rx="24""""))
  }

  @Test
  fun textNodeIsMatchedByBoundsAndEmittedAsEditableText() {
    val layout =
      layoutNode("Screen", 0, 0, 200, 100, children = listOf(layoutNode("Text", 8, 8, 192, 40)))
    val semantics =
      ComposeSemanticsNode(
        nodeId = "root",
        boundsInRoot = "0,0,200,100",
        children =
          listOf(
            ComposeSemanticsNode(
              nodeId = "t",
              boundsInRoot = "8,8,192,40",
              text = "Hello",
              typography =
                ComposeSemanticsTypography(
                  fontSize = "16.0sp",
                  fontWeight = 500,
                  fontStyle = "normal",
                ),
              textColor = ComposeSemanticsTextColor(foreground = "#FF202020"),
            )
          ),
      )
    val svg = render(layout, semantics = semantics)
    assertTrue(svg.contains(">Hello</text>"))
    assertTrue(svg.contains("""font-size="16""""))
    assertTrue(svg.contains("""font-weight="500""""))
    assertTrue(svg.contains("""fill="#202020""""))
  }

  @Test
  fun textAttachesDespiteOffByOneBoundsSkew() {
    // Regression: semantics bounds truncate to Int while layout bounds round, so the same node can
    // differ by up to 1px per edge. Exact-key matching dropped the text; tolerant matching keeps
    // it.
    val layout =
      layoutNode("Screen", 0, 0, 200, 100, children = listOf(layoutNode("Text", 8, 9, 192, 41)))
    val semantics =
      ComposeSemanticsNode(
        nodeId = "root",
        boundsInRoot = "0,0,200,100",
        children =
          listOf(
            ComposeSemanticsNode(
              nodeId = "t",
              boundsInRoot = "8,8,192,40", // skewed by 1px on two edges vs the layout node
              text = "Hello",
              typography = ComposeSemanticsTypography(fontSize = "16.0sp"),
            )
          ),
      )
    val svg = render(layout, semantics = semantics)
    assertTrue("text must still attach despite off-by-one bounds", svg.contains(">Hello</text>"))
  }

  @Test
  fun textIsNotAttachedToAWildlyDifferentNode() {
    // A text node far from any layout node must not be force-attached (tolerance is tight).
    val layout =
      layoutNode("Screen", 0, 0, 200, 100, children = listOf(layoutNode("Box", 8, 8, 192, 40)))
    val semantics =
      ComposeSemanticsNode(
        nodeId = "root",
        boundsInRoot = "0,0,200,100",
        children =
          listOf(
            ComposeSemanticsNode(nodeId = "t", boundsInRoot = "500,500,600,540", text = "Elsewhere")
          ),
      )
    val svg = render(layout, semantics = semantics)
    assertFalse("far-away text must not be attached", svg.contains("Elsewhere"))
  }

  @Test
  fun namedThemeColorIsAnnotatedOnTheLayer() {
    val svg =
      render(
        layoutNode(
          "Surface",
          0,
          0,
          100,
          100,
          tokens = ComposeSemanticsTokens(backgroundColor = "#FF6750A4"),
        ),
        colorNames = mapOf("#FF6750A4" to "primary"),
      )
    assertTrue(svg.contains("""data-token="primary""""))
    assertTrue(svg.contains("<title>"))
    assertTrue(svg.contains("primary"))
  }

  @Test
  fun namedColorLookupIsCaseInsensitive() {
    val svg =
      render(
        layoutNode(
          "Surface",
          0,
          0,
          10,
          10,
          tokens = ComposeSemanticsTokens(backgroundColor = "#ff6750a4"),
        ),
        colorNames = mapOf("#FF6750A4" to "primary"),
      )
    assertTrue(svg.contains("""data-token="primary""""))
  }

  @Test
  fun layerNameIsXmlAttributeEscaped() {
    val svg = render(layoutNode("""Weird"<Name>&""", 0, 0, 10, 10))
    assertTrue(svg.contains("&quot;"))
    assertTrue(svg.contains("&lt;"))
    assertFalse(svg.contains("""id="Weird"<Name>&""""))
  }

  @Test
  fun pxCornerRadiusThatCannotBeReadAsDpFallsBackToSharpRect() {
    val svg =
      render(
        layoutNode(
          "Weird",
          0,
          0,
          100,
          100,
          tokens = ComposeSemanticsTokens(backgroundColor = "#FFFFFFFF", cornerRadius = "12px"),
        )
      )
    assertFalse(svg.contains("rx="))
    assertFalse(svg.contains("<path"))
    assertTrue(svg.contains("<rect"))
  }

  @Test
  fun viewBoxIsUnionOfDrawingLayersPlusPadding() {
    // Grouping-only root (no tokens/text) must not constrain the canvas; the filled child does.
    val svg =
      render(
        layoutNode(
          "Root",
          0,
          0,
          1000,
          1000,
          children =
            listOf(
              layoutNode(
                "Box",
                10,
                20,
                110,
                220,
                tokens = ComposeSemanticsTokens(backgroundColor = "#FF000000"),
              )
            ),
        )
      )
    // extent x:[10..110] y:[20..220]; +16 padding each side → 132 x 232
    assertTrue(svg, svg.contains("""viewBox="0 0 132 232""""))
  }

  @Test
  fun colorParsingHelpers() {
    val opaque = FigmaSvgModel.argbToColor("#FF6750A4", emptyMap())
    assertEquals("#6750A4", opaque?.hex)
    assertEquals(1.0, opaque?.opacity!!, 0.0001)
    val translucent = FigmaSvgModel.argbToColor("#806750A4", emptyMap())
    assertEquals(128 / 255.0, translucent?.opacity!!, 0.0001)
    assertNull(FigmaSvgModel.argbToColor("not-a-color", emptyMap()))
    assertNull(FigmaSvgModel.argbToColor("#GGGGGG", emptyMap()))
  }

  @Test
  fun outputIsDeterministic() {
    val layout =
      layoutNode(
        "Screen",
        0,
        0,
        200,
        200,
        tokens = ComposeSemanticsTokens(backgroundColor = "#FF6750A4", cornerRadius = "8.0dp"),
        children = listOf(layoutNode("Child", 10, 10, 190, 190)),
      )
    assertEquals(render(layout), render(layout))
  }
}
