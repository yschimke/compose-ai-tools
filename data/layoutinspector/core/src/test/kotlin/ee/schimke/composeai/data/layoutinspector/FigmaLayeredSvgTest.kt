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
  fun rootSvgRequestsGeometricPrecisionSoTextMatchesTheRender() {
    // The default `text-rendering:auto` grid-fits glyphs to pixel boundaries in the browser, which
    // leaves a constant edge diff against the Skiko render on text-heavy previews. Pin the
    // `geometricPrecision` request on the root so every `<text>` rasterises at its exact metrics.
    val svg = render(layoutNode("Screen", 0, 0, 400, 800))
    assertTrue(svg, svg.contains("""text-rendering="geometricPrecision""""))
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
  fun aDefaultMinSizeGrowsTheDrawnShapeCenteredOnTheBounds() {
    // An M3 Badge with a single digit is *placed* in a narrow box (bounds 20×42) but its captured
    // `defaultMinSize` (42dp × 42dp here at density 1) is the box it draws its background circle
    // in,
    // centered. The export grows the fill to `max(bounds, minSize)` centered on the bounds — a
    // 42×42
    // circle at [42,42,84,84] — not a squashed 20×42 capsule at the placement bounds.
    val badge =
      layoutNode(
        "Badge",
        53,
        42,
        73,
        84,
        tokens =
          ComposeSemanticsTokens(
            backgroundColor = "#FFB3261E",
            shape = "circle",
            minWidth = "42.0dp",
            minHeight = "42.0dp",
          ),
      )
    val svg = FigmaLayeredSvg.render(FigmaSvgModel.from(LayoutInspectorPayload(badge)))
    // 42-wide circle centered on the bounds' centre (x=63) → x=42, width 42, max-radius (r=21).
    assertTrue(svg, svg.contains("""x="42"""") && svg.contains("""width="42""""))
    assertTrue("drawn as a full circle (r = size/2)", svg.contains("""rx="21""""))
  }

  @Test
  fun aMinSizeWithinTheBoundsDoesNotGrowTheShape() {
    // A button / chip also carries a `defaultMinSize`, but its content already exceeds it, so the
    // min ≤ its placement bounds and the fill stays exactly at the bounds — no ballooning.
    val chip =
      layoutNode(
        "Chip",
        42,
        63,
        231,
        147,
        tokens =
          ComposeSemanticsTokens(
            backgroundColor = "#FFE8DEF8",
            shape = "circle",
            minHeight = "32.0dp",
          ),
      )
    val svg = FigmaLayeredSvg.render(FigmaSvgModel.from(LayoutInspectorPayload(chip)))
    // bounds 189×84; minHeight 32 < 84 → no growth. Stays 189-wide at x=42, 84 tall.
    assertTrue(svg, svg.contains("""x="42"""") && svg.contains("""width="189""""))
    assertTrue("keeps its bounds height", svg.contains("""height="84""""))
  }

  @Test
  fun aFullyTransparentBorderEmitsNoStroke() {
    // A `Switch` on-track carries `borderColor` at alpha 0 — an invisible outline. It must not emit
    // a stroke (nor inset the fill for a stroke that never paints).
    val svg =
      render(
        layoutNode(
          "OnTrack",
          0,
          0,
          100,
          50,
          tokens =
            ComposeSemanticsTokens(
              backgroundColor = "#FF6750A4",
              borderColor = "#00000000",
              borderWidth = "2.0dp",
            ),
        ),
        density = 2f,
      )
    assertTrue("no stroke for a transparent border", !svg.contains("stroke="))
    // The fill keeps its full bounds (no stroke-inset): a 100-wide rect at x=0.
    assertTrue(svg.contains("""width="100""""))
  }

  @Test
  fun borderWidthTokenSetsTheStrokeWidthScaledByDensity() {
    // An off-state `Switch` track is a 2dp outline, not a 1dp hairline. The captured `borderWidth`
    // sets the stroke width (dp × density) instead of the hardcoded 1dp fallback.
    val svg =
      render(
        layoutNode(
          "Track",
          0,
          0,
          100,
          50,
          tokens = ComposeSemanticsTokens(borderColor = "#FF79747E", borderWidth = "2.0dp"),
        ),
        density = 2f,
      )
    assertTrue("stroke width is 2dp × density 2 = 4", svg.contains("""stroke-width="4""""))
  }

  @Test
  fun aBorderWithoutACapturedWidthFallsBackToADensityHairline() {
    val svg =
      render(
        layoutNode(
          "Hairline",
          0,
          0,
          100,
          50,
          tokens = ComposeSemanticsTokens(borderColor = "#FF79747E"),
        ),
        density = 2f,
      )
    // No borderWidth → 1dp hairline scaled by density (2).
    assertTrue("hairline falls back to 1dp × density", svg.contains("""stroke-width="2""""))
  }

  @Test
  fun shadowElevationBecomesADropShadowFilterScaledByDensity() {
    // A `Surface`/`Card`/`FAB` reports `elevation` (dp); the export emits a `feDropShadow` def and
    // filters the elevated group so it casts its Material drop shadow. Elevation → px scales by
    // density: 6dp at density 2 → a 12px filter id.
    val svg =
      render(
        layoutNode(
          "Fab",
          0,
          0,
          100,
          100,
          tokens = ComposeSemanticsTokens(backgroundColor = "#FF6750A4", elevation = "6.0dp"),
        ),
        density = 2f,
      )
    assertTrue("a drop-shadow filter is defined", svg.contains("<feDropShadow"))
    assertTrue("the filter id scales with density (6dp × 2)", svg.contains("""id="shadow-12""""))
    assertTrue(
      "the elevated group references the filter",
      svg.contains("""filter="url(#shadow-12)""""),
    )
  }

  @Test
  fun noElevationEmitsNoShadowFilter() {
    val svg =
      render(
        layoutNode(
          "Flat",
          0,
          0,
          100,
          100,
          tokens = ComposeSemanticsTokens(backgroundColor = "#FF6750A4"),
        )
      )
    assertTrue("no shadow filter without elevation", !svg.contains("feDropShadow"))
    assertTrue(!svg.contains("filter=\"url(#shadow"))
  }

  @Test
  fun rawPixelCornerRadiusRendersRoundedRectWithoutDensityScaling() {
    // `RoundedCornerShape(20f)` rides on `cornerRadiusPx` (not the dp `cornerRadius`) and is
    // already
    // in layer-space pixels — so it renders at 20, not 20*density, even at density 2.
    val svg =
      render(
        layoutNode(
          "PxRounded",
          0,
          0,
          100,
          100,
          tokens = ComposeSemanticsTokens(backgroundColor = "#FFFFFFFF", cornerRadiusPx = "20.0px"),
        ),
        density = 2f,
      )
    assertTrue(svg.contains("""rx="20""""))
    assertTrue(svg.contains("""ry="20""""))
  }

  @Test
  fun nonUniformPxCornersBecomeAPath() {
    val svg =
      render(
        layoutNode(
          "TopPxRounded",
          0,
          0,
          100,
          100,
          tokens =
            ComposeSemanticsTokens(
              backgroundColor = "#FFFFFFFF",
              cornerRadiusPx = "20.0px,20.0px,0.0px,0.0px",
            ),
        )
      )
    assertTrue(svg.contains("<path"))
    assertTrue(svg.contains(" A")) // arc commands for the rounded corners
  }

  @Test
  fun dpCornerRadiusWinsOverPxWhenBothPresent() {
    // Defensive: a shape resolvable to dp keeps the dp path (density-scaled), never the px
    // fallback.
    val svg =
      render(
        layoutNode(
          "BothCorners",
          0,
          0,
          100,
          100,
          tokens =
            ComposeSemanticsTokens(
              backgroundColor = "#FFFFFFFF",
              cornerRadius = "8.0dp",
              cornerRadiusPx = "20.0px",
            ),
        ),
        density = 2f,
      )
    assertTrue(svg.contains("""rx="16"""")) // 8dp * 2 density, not the 20px fallback
    assertFalse(svg.contains("""rx="20""""))
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
  fun cutCornerShapeChamfersInsteadOfRounding() {
    // A CutCornerShape reports its corner size on `cornerRadius` plus `shape="cut"`; the export
    // must
    // bevel (straight line segments) rather than round (arcs) or drop to a rounded/sharp rect.
    val svg =
      render(
        layoutNode(
          "Cut",
          0,
          0,
          100,
          100,
          tokens =
            ComposeSemanticsTokens(
              backgroundColor = "#FFFFFFFF",
              cornerRadius = "12.0dp",
              shape = "cut",
            ),
        )
      )
    assertTrue(svg.contains("<path"))
    assertTrue("chamfers are straight line segments", svg.contains(" L"))
    assertFalse("no arc commands for a cut corner", svg.contains(" A"))
    assertFalse("a cut corner is never a rounded <rect rx>", svg.contains("rx="))
  }

  @Test
  fun rawPixelCutCornerChamfers() {
    // A CutCornerShape(<px>f) rides on `cornerRadiusPx` + `shape="cut"` — same chamfer, no density.
    val svg =
      render(
        layoutNode(
          "CutPx",
          0,
          0,
          100,
          100,
          tokens =
            ComposeSemanticsTokens(
              backgroundColor = "#FFFFFFFF",
              cornerRadiusPx = "20.0px",
              shape = "cut",
            ),
        ),
        density = 2f,
      )
    assertTrue(svg.contains("<path"))
    assertTrue(svg.contains(" L"))
    assertFalse(svg.contains(" A"))
    // Raw px: the chamfer point is at x=20 (top-left ends where the top edge starts), not 40.
    assertTrue("px chamfer uses raw pixels, no density scaling", svg.contains("M20"))
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
  fun capturedLetterSpacingIsEmittedSoGlyphAdvancesMatchTheRender() {
    // A tracked run (Material label/body text carries 0.1–0.5sp) must emit SVG `letter-spacing`,
    // else
    // the browser lays it out with the font's natural advances and the line drifts across its
    // width.
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
              // 0.5sp at density 2 → 1.0px.
              typography = ComposeSemanticsTypography(fontSize = "16.0sp", letterSpacing = "0.5sp"),
            )
          ),
      )
    val svg = render(layout, semantics = semantics, density = 2f)
    assertTrue("expected letter-spacing in $svg", svg.contains("""letter-spacing="1""""))
  }

  @Test
  fun zeroLetterSpacingEmitsNoAttribute() {
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
              typography = ComposeSemanticsTypography(fontSize = "16.0sp", letterSpacing = "0.0sp"),
            )
          ),
      )
    assertFalse(render(layout, semantics = semantics, density = 2f).contains("letter-spacing"))
  }

  @Test
  fun embedFamilyNormalisesFileDerivedFacesToGoogleFamilies() {
    // A FontListFontFamily reports its resolved face by file stem ("Roboto-Medium",
    // "NotoSerif-Regular", "DroidSansMono"); the embed resolver keys on the spaced Google family
    // with
    // weight/italic supplied separately, so the style suffix drops and CamelCase words space out.
    assertEquals("Roboto", FigmaLayeredSvg.embedFamily("Roboto-Medium", "Roboto"))
    assertEquals("Roboto", FigmaLayeredSvg.embedFamily("Roboto-Regular", "Roboto"))
    assertEquals("Noto Serif", FigmaLayeredSvg.embedFamily("NotoSerif-Regular", "Roboto"))
    assertEquals("Droid Sans Mono", FigmaLayeredSvg.embedFamily("DroidSansMono", "Roboto"))
    // Generics keep their existing mappings.
    assertEquals("Roboto", FigmaLayeredSvg.embedFamily("sans-serif", "Roboto"))
    assertEquals("Noto Serif", FigmaLayeredSvg.embedFamily("serif", "Roboto"))
    assertNull(FigmaLayeredSvg.embedFamily("cursive", "Roboto"))
  }

  private fun textBaselineY(svg: String): Double =
    Regex("""<text [^>]*\by="([0-9.]+)"""").find(svg)!!.groupValues[1].toDouble()

  private fun textNodeWith(lineHeight: String?): String {
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
              typography = ComposeSemanticsTypography(fontSize = "16.0sp", lineHeight = lineHeight),
            )
          ),
      )
    return render(layout, semantics = semantics)
  }

  @Test
  fun textBaselineSitsBelowBareAscentAndWithinItsBox() {
    // The baseline must land inside the text box (top=8, bottom=40) and *below* the old flat
    // `top + 0.8·fontSize` heuristic (8 + 12.8 = 20.8) — Compose draws the baseline lower because
    // the
    // line-height leading is split above the first line. With fontSize 16 + lineHeight 24 the model
    // is 8 + halfLeading((24 - 16·1.17)/2 = 2.64) + ascent(16·0.93 = 14.88) ≈ 25.5.
    val y = textBaselineY(textNodeWith(lineHeight = "24.0sp"))
    assertTrue("baseline $y must be below the bare-0.8em heuristic (20.8)", y > 20.8)
    assertTrue("baseline $y must stay within the box bottom (40)", y < 40.0)
    assertTrue("baseline $y must be near the leading+ascent model (~25.5)", y in 24.5..26.5)
  }

  @Test
  fun largerLineHeightLowersTheBaseline() {
    // More leading pushes the first line's baseline down (the extra space is split above it).
    val tight = textBaselineY(textNodeWith(lineHeight = "18.0sp"))
    val loose = textBaselineY(textNodeWith(lineHeight = "28.0sp"))
    assertTrue(
      "looser line height ($loose) must lower the baseline vs tight ($tight)",
      loose > tight,
    )
  }

  @Test
  fun lineHeightToPxParsesSpAndEm() {
    assertEquals(24.0, FigmaSvgModel.lineHeightToPx("24.0sp", "16.0sp", 1f)!!, 1e-9)
    assertEquals(48.0, FigmaSvgModel.lineHeightToPx("24.0sp", "16.0sp", 2f)!!, 1e-9)
    // em is relative to the resolved font size: 1.5em × 16sp = 24px at density 1.
    assertEquals(24.0, FigmaSvgModel.lineHeightToPx("1.5em", "16.0sp", 1f)!!, 1e-9)
    assertNull(FigmaSvgModel.lineHeightToPx("1.5em", null, 1f))
    assertNull(FigmaSvgModel.lineHeightToPx("weird", "16.0sp", 1f))
  }

  @Test
  fun resolveFamilyMapsSansGenericToDefaultButKeepsRealFaces() {
    assertEquals("Roboto", FigmaLayeredSvg.resolveFamily(null, "Roboto"))
    assertEquals("Roboto", FigmaLayeredSvg.resolveFamily("sans-serif", "Roboto"))
    assertEquals("Roboto", FigmaLayeredSvg.resolveFamily("SANS-SERIF", "Roboto"))
    assertEquals("Roboto", FigmaLayeredSvg.resolveFamily("system-ui", "Roboto"))
    assertEquals("Lobster", FigmaLayeredSvg.resolveFamily("Lobster", "Roboto"))
  }

  @Test
  fun resolveFamilyKeepsMeaningfulGenericsSoSerifStaysSerif() {
    // The sans default must NOT swallow serif/monospace — that's what erased specimen identity.
    assertEquals("serif", FigmaLayeredSvg.resolveFamily("serif", "Roboto"))
    assertEquals("monospace", FigmaLayeredSvg.resolveFamily("monospace", "Roboto"))
    assertEquals("serif", FigmaLayeredSvg.resolveFamily("SERIF", "Roboto"))
    assertEquals("cursive", FigmaLayeredSvg.resolveFamily("cursive", "Roboto"))
    assertEquals("fantasy", FigmaLayeredSvg.resolveFamily("fantasy", "Roboto"))
  }

  @Test
  fun concreteTextFamilyCarriesAStyleGenericFallbackSoItNeverRendersAsSerif() {
    // A concrete face with no embedded @font-face fell back to the viewer's default *serif* in
    // Chromium/Figma (the visible bug). It must now carry a style-correct generic fallback.
    assertEquals(
      "Roboto-Regular, sans-serif",
      FigmaLayeredSvg.withGenericFallback("Roboto-Regular"),
    )
    assertEquals(
      "NotoSerif-Regular, serif",
      FigmaLayeredSvg.withGenericFallback("NotoSerif-Regular"),
    )
    assertEquals("DroidSansMono, monospace", FigmaLayeredSvg.withGenericFallback("DroidSansMono"))
    assertEquals("'Noto Serif', serif", FigmaLayeredSvg.withGenericFallback("Noto Serif"))
    // A bare generic is already a fallback — left unchanged, no double list.
    assertEquals("sans-serif", FigmaLayeredSvg.withGenericFallback("sans-serif"))
    assertEquals("serif", FigmaLayeredSvg.withGenericFallback("serif"))
    assertEquals("monospace", FigmaLayeredSvg.withGenericFallback("monospace"))
  }

  @Test
  fun textEmitsTheGenericFallbackInline() {
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
              text = "Hi",
              typography =
                ComposeSemanticsTypography(fontSize = "16.0sp", fontFamily = "Roboto-Regular"),
            )
          ),
      )
    val svg = render(layout, semantics = semantics)
    assertTrue(
      "concrete text family must carry a generic fallback",
      svg.contains("""font-family="Roboto-Regular, sans-serif""""),
    )
  }

  @Test
  fun wrappedTextEmitsOnePositionedTspanPerLineInsteadOfOneBaseline() {
    val layout =
      layoutNode("Screen", 0, 0, 200, 100, children = listOf(layoutNode("Text", 10, 10, 190, 90)))
    val semantics =
      ComposeSemanticsNode(
        nodeId = "root",
        boundsInRoot = "0,0,200,100",
        children =
          listOf(
            ComposeSemanticsNode(
              nodeId = "t",
              boundsInRoot = "10,10,190,90",
              text = "Outlined card",
              typography =
                ComposeSemanticsTypography(fontSize = "16.0sp", fontFamily = "Roboto-Regular"),
              textOverflow =
                ComposeSemanticsTextOverflow(
                  lineCount = 2,
                  lines =
                    listOf(
                      ComposeSemanticsTextLine(text = "Outlined", left = 0, baseline = 20),
                      ComposeSemanticsTextLine(text = "card", left = 0, baseline = 44),
                    ),
                ),
            )
          ),
      )
    val svg = render(layout, semantics = semantics)
    // Two positioned tspans at layer origin (10,10) + each line's offset — not one collapsed line.
    assertTrue("line 1 tspan", svg.contains("""<tspan x="10" y="30">Outlined</tspan>"""))
    assertTrue("line 2 tspan", svg.contains("""<tspan x="10" y="54">card</tspan>"""))
    // The single-baseline form must not also be emitted for this node.
    assertFalse("no collapsed single line", svg.contains(""">Outlined card</text>"""))
  }

  @Test
  fun singleLineTextKeepsThePlainBaselineForm() {
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
              text = "Hi",
              typography = ComposeSemanticsTypography(fontSize = "16.0sp"),
            )
          ),
      )
    val svg = render(layout, semantics = semantics)
    assertFalse("no tspan for single-line text", svg.contains("<tspan"))
    assertTrue("plain baseline text", svg.contains(">Hi</text>"))
  }

  @Test
  fun embedFamilyMapsGenericsToConcreteEmbeddableFaces() {
    // sans generics ride the default embedded face; serif/monospace get a real same-style face.
    assertEquals("Roboto", FigmaLayeredSvg.embedFamily(null, "Roboto"))
    assertEquals("Roboto", FigmaLayeredSvg.embedFamily("sans-serif", "Roboto"))
    assertEquals("Roboto", FigmaLayeredSvg.embedFamily("system-ui", "Roboto"))
    assertEquals("Noto Serif", FigmaLayeredSvg.embedFamily("serif", "Roboto"))
    assertEquals("Roboto Mono", FigmaLayeredSvg.embedFamily("monospace", "Roboto"))
    assertEquals("Roboto Mono", FigmaLayeredSvg.embedFamily("MONOSPACE", "Roboto"))
    // No concrete stand-in → null so the producer skips embedding and the text keeps the generic.
    assertNull(FigmaLayeredSvg.embedFamily("cursive", "Roboto"))
    assertNull(FigmaLayeredSvg.embedFamily("fantasy", "Roboto"))
    // A real captured face is unchanged.
    assertEquals("Lobster", FigmaLayeredSvg.embedFamily("Lobster", "Roboto"))
  }

  @Test
  fun embeddedFontFacesEmitAtFontFaceAndNameTheText() {
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
              text = "Hi",
              typography = ComposeSemanticsTypography(fontSize = "16.0sp"), // generic family
            )
          ),
      )
    val model =
      FigmaSvgModel.from(LayoutInspectorPayload(layout), ComposeSemanticsPayload(semantics))
    val svg =
      FigmaLayeredSvg.render(
        model,
        FigmaLayeredSvg.Options(defaultFontFamily = "Roboto"),
        listOf(FigmaSvgFontFace("Roboto", 400, italic = false, dataBase64 = "QUJD")),
      )
    assertTrue("must emit an @font-face", svg.contains("@font-face"))
    assertTrue("face names the family", svg.contains("font-family:'Roboto'"))
    assertTrue("face embeds the woff2 data URI", svg.contains("data:font/woff2;base64,QUJD"))
    assertTrue("face declares the format", svg.contains("format('woff2')"))
    // The generic-family text now names the embedded face rather than inheriting bare sans-serif.
    assertTrue("text uses the embedded family", svg.contains("""font-family="Roboto""""))
    assertFalse(
      "root no longer defaults to sans-serif",
      svg.contains("""font-family="sans-serif""""),
    )
  }

  @Test
  fun noFontFacesKeepsTheVectorOnlySansSerifDefault() {
    val svg = render(layoutNode("Screen", 0, 0, 100, 100))
    assertFalse(svg.contains("@font-face"))
    assertTrue(svg.contains("""font-family="sans-serif""""))
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

  @Test
  fun opaqueComponentBecomesAnImagePlaceholderAndRasterTarget() {
    // A screen that is mostly vector (a Surface) with one opaque component (an Image) that must be
    // rendered rather than vectorised — the hybrid the design workflow wants.
    val layout =
      LayoutInspectorPayload(
        layoutNode(
          "Screen",
          0,
          0,
          200,
          200,
          tokens = ComposeSemanticsTokens(backgroundColor = "#FFFFFBFE"),
          children = listOf(layoutNode("Image", 20, 20, 180, 120)),
        )
      )
    val model =
      FigmaSvgModel.from(layout, rasterComponents = FigmaSvgModel.DEFAULT_RASTER_COMPONENTS)
    val svg = FigmaLayeredSvg.render(model)
    // The Image node is an <image> placeholder, not a vector shape.
    assertTrue(svg.contains("<image "))
    assertTrue(svg.contains("""href="figma-raster/"""))
    // The vector part (the Surface fill) is still present.
    assertTrue(svg.contains("""fill="#FFFBFE""""))
    // The pipeline is told exactly what to capture.
    assertEquals(1, model.rasterTargets.size)
    val target = model.rasterTargets.single()
    assertEquals("Image", target.nodeId)
    assertEquals(20, target.left)
    assertEquals(180, target.right)
  }

  @Test
  fun opaqueNodeDropsItsSubtree() {
    // An Icon with vector-looking children still rasterises as one image — its subtree is replaced.
    val layout =
      LayoutInspectorPayload(
        layoutNode(
          "IconButton",
          0,
          0,
          48,
          48,
          children =
            listOf(
              layoutNode(
                "Icon",
                8,
                8,
                40,
                40,
                children = listOf(layoutNode("InnerVector", 8, 8, 40, 40)),
              )
            ),
        )
      )
    val svg =
      FigmaLayeredSvg.render(
        FigmaSvgModel.from(layout, rasterComponents = FigmaSvgModel.DEFAULT_RASTER_COMPONENTS)
      )
    assertTrue(svg.contains("<image "))
    assertFalse("opaque subtree must not emit its inner nodes", svg.contains("InnerVector"))
  }

  @Test
  fun hybridIsOptInSoDefaultExportIsVectorOnly() {
    // The pure model default stays vector-only: `from(...)` only emits <image> refs when a caller
    // opts in with `rasterComponents`. The daemon producer opts in (and crops the PNGs those refs
    // point at) only when it has the captured frame to crop from — a model-only caller with no
    // frame must never emit an <image> ref to a PNG nobody wrote.
    val layout = LayoutInspectorPayload(layoutNode("Image", 0, 0, 100, 100))
    val model = FigmaSvgModel.from(layout)
    assertFalse(FigmaLayeredSvg.render(model).contains("<image "))
    assertTrue(model.rasterTargets.isEmpty())
  }

  @Test
  fun rasterComponentSetIsConfigurable() {
    val layout = LayoutInspectorPayload(layoutNode("SparklineChartXyz", 0, 0, 100, 40))
    // A custom set that only rasterises "Gauge" → this Chart stays vector (no <image>).
    val vectorOnly = FigmaSvgModel.from(layout, rasterComponents = setOf("Gauge"))
    assertTrue(FigmaLayeredSvg.render(vectorOnly).let { !it.contains("<image ") })
    assertTrue(vectorOnly.rasterTargets.isEmpty())
    // The default preset includes "Chart" → rasterised when opted in.
    val hybrid =
      FigmaSvgModel.from(layout, rasterComponents = FigmaSvgModel.DEFAULT_RASTER_COMPONENTS)
    assertTrue(FigmaLayeredSvg.render(hybrid).contains("<image "))
    assertEquals(1, hybrid.rasterTargets.size)
  }

  @Test
  fun defaultRasterHrefSanitizesNodeId() {
    assertEquals("figma-raster/node_12_.png", FigmaSvgModel.defaultRasterHref("node:12/"))
  }
}
