package ee.schimke.composeai.data.layoutinspector

import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FigmaLayeredSvgTest {

  private fun bounds(l: Int, t: Int, r: Int, b: Int) = LayoutInspectorBounds(l, t, r, b)

  /**
   * Parses [svg] with a namespace-aware XML parser and fails the test if it isn't well-formed. The
   * layered export is meant to round-trip through Figma / Illustrator, which read the `.svg` as XML
   * (strict), not through Chromium's lenient HTML parser — so an unescaped `&`/`<` anywhere (a
   * `<text>`, an attribute, or the `@font-face` `<style>` block) is a hard import failure, not a
   * cosmetic one. The document declares no DTD, so parsing pulls no external entities.
   */
  private fun assertWellFormedXml(svg: String) {
    val builder = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
    builder.newDocumentBuilder().parse(ByteArrayInputStream(svg.toByteArray(Charsets.UTF_8)))
  }

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
  fun vectorPathCarriesStrokeLinecap() {
    val node =
      LayoutInspectorNode(
        nodeId = "c",
        component = "Canvas",
        bounds = bounds(0, 0, 40, 40),
        size = LayoutInspectorSize(40, 40),
        vectorGraphic =
          LayoutInspectorVectorGraphic(
            viewportWidth = 40f,
            viewportHeight = 40f,
            paths =
              listOf(
                LayoutInspectorVectorPath(
                  pathData = "M4,20 L36,20",
                  strokeArgb = "#FF6750A4",
                  strokeWidth = 8f,
                  strokeCap = "round",
                )
              ),
          ),
      )
    val svg = render(node)
    assertTrue(svg.contains("""stroke-linecap="round""""))
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
  fun layerNameStripsMeasurePolicySuffixSoGroupsReadAsComposables() {
    // When source-info resolution fails the component falls back to the measure-policy class name
    // (`BoxMeasurePolicy`, `RootMeasurePolicy`); the layer id should read as the composable
    // instead.
    val svg =
      render(
        layoutNode(
          "RootMeasurePolicy",
          0,
          0,
          200,
          100,
          children =
            listOf(
              layoutNode("BoxMeasurePolicy", 0, 0, 100, 50),
              layoutNode("OutlinedTextFieldMeasurePolicy", 0, 50, 200, 100),
            ),
        )
      )
    assertTrue(svg, svg.contains("""<g id="Root""""))
    assertTrue(svg, svg.contains("""<g id="Box""""))
    assertTrue(svg, svg.contains("""<g id="OutlinedTextField""""))
    assertFalse(svg, svg.contains("MeasurePolicy"))
  }

  @Test
  fun collapsesPurePassthroughWrappersIntoTheirSingleChild() {
    // Compose stacks anonymous single-child layout nodes per widget (padding / min-size / clip
    // wrappers). Those draw nothing and only nest one child, so they collapse into the child that
    // actually paints — the export shouldn't emit a pile of empty <g> groups.
    val svg =
      render(
        layoutNode(
          "Screen",
          0,
          0,
          200,
          100,
          children =
            listOf(
              layoutNode(
                "WrapperA",
                0,
                0,
                100,
                40,
                children =
                  listOf(
                    layoutNode(
                      "WrapperB",
                      0,
                      0,
                      100,
                      40,
                      children =
                        listOf(
                          layoutNode(
                            "Button",
                            0,
                            0,
                            100,
                            40,
                            tokens = ComposeSemanticsTokens(backgroundColor = "#FF000000"),
                          )
                        ),
                    )
                  ),
              )
            ),
        )
      )
    // The two empty wrappers are gone; the painting Button and the root frame survive.
    assertFalse(svg, svg.contains("WrapperA"))
    assertFalse(svg, svg.contains("WrapperB"))
    assertTrue(svg, svg.contains("""<g id="Screen""""))
    assertTrue(svg, svg.contains("""<g id="Button""""))
    assertTrue(svg, svg.contains("<rect"))
  }

  @Test
  fun keepsGroupingLayersThatHoldMultipleChildren() {
    // A pass-through node with 2+ children genuinely groups siblings — it's real structure, not a
    // redundant nesting level, so it must be preserved even though it draws nothing itself.
    val svg =
      render(
        layoutNode(
          "Screen",
          0,
          0,
          200,
          100,
          children =
            listOf(
              layoutNode("Header", 0, 0, 200, 40),
              layoutNode(
                "Row",
                0,
                0,
                200,
                40,
                children =
                  listOf(layoutNode("Left", 0, 0, 100, 40), layoutNode("Right", 100, 0, 200, 40)),
              ),
            ),
        )
      )
    assertTrue(svg, svg.contains("""<g id="Left""""))
    assertTrue(svg, svg.contains("""<g id="Right""""))
  }

  @Test
  fun layerLabelPrefersInheritedDisplayNameOverOwnComponent() {
    // A node keeps its own identity in `component` (here a measure-policy fallback), but the layer
    // label reads the friendly inherited `displayName`.
    val svg =
      render(
        LayoutInspectorNode(
          nodeId = "root",
          component = "BoxMeasurePolicy",
          displayName = "Card",
          bounds = LayoutInspectorBounds(0, 0, 100, 60),
          size = LayoutInspectorSize(100, 60),
          tokens = ComposeSemanticsTokens(backgroundColor = "#FF000000"),
        )
      )
    assertTrue(svg, svg.contains("""<g id="Card""""))
    assertFalse(svg, svg.contains("""id="Box"""))
  }

  @Test
  fun inheritedDisplayNameIsNotUsedForOpaqueRasterMatching() {
    // Regression guard (#2469 follow-up): an IconButton's internal wrapper inherits the label
    // "IconButton" for display, but its own identity is a plain Box — it must NOT match the "Icon"
    // raster fragment and rasterise, which would drop the whole editable button subtree into a PNG.
    // Only the real Icon leaf, whose own `component` is "Icon", rasterises.
    val model =
      FigmaSvgModel.from(
        layout =
          LayoutInspectorPayload(
            LayoutInspectorNode(
              nodeId = "screen",
              component = "Screen",
              bounds = LayoutInspectorBounds(0, 0, 200, 100),
              size = LayoutInspectorSize(200, 100),
              children =
                listOf(
                  LayoutInspectorNode(
                    nodeId = "iconButton",
                    component = "BoxMeasurePolicy",
                    displayName = "IconButton",
                    bounds = LayoutInspectorBounds(0, 0, 48, 48),
                    size = LayoutInspectorSize(48, 48),
                    children =
                      listOf(
                        LayoutInspectorNode(
                          nodeId = "icon",
                          component = "Icon",
                          bounds = LayoutInspectorBounds(12, 12, 36, 36),
                          size = LayoutInspectorSize(24, 24),
                        ),
                        LayoutInspectorNode(
                          nodeId = "ripple",
                          component = "BoxMeasurePolicy",
                          displayName = "IconButton",
                          bounds = LayoutInspectorBounds(0, 0, 48, 48),
                          size = LayoutInspectorSize(48, 48),
                          tokens = ComposeSemanticsTokens(backgroundColor = "#22000000"),
                        ),
                      ),
                  )
                ),
            )
          ),
        rasterComponents = setOf("Icon"),
      )
    // Only the real Icon leaf became a raster; the IconButton wrapper stayed editable vector.
    assertEquals(listOf("icon"), model.rasterTargets.map { it.nodeId })
    val svg = FigmaLayeredSvg.render(model)
    assertTrue(svg, svg.contains("""<g id="IconButton""""))
  }

  @Test
  fun ownMeasurePolicyIdentityStillRastersUnderAnInheritedLabel() {
    // Counterpart to the IconButton guard: a control's own identity is its measure-policy class
    // (OutlinedTextFieldMeasurePolicy) even though its friendly label was inherited from the
    // enclosing Column. Raster matching keys off `component`, so the control still rasterises — the
    // token export can't vectorise its imperatively drawn chrome, and an inherited label must not
    // hide that own identity.
    val model =
      FigmaSvgModel.from(
        layout =
          LayoutInspectorPayload(
            LayoutInspectorNode(
              nodeId = "form",
              component = "Column",
              bounds = LayoutInspectorBounds(0, 0, 300, 200),
              size = LayoutInspectorSize(300, 200),
              children =
                listOf(
                  LayoutInspectorNode(
                    nodeId = "textField",
                    component = "OutlinedTextFieldMeasurePolicy",
                    displayName = "Column",
                    bounds = LayoutInspectorBounds(0, 0, 300, 56),
                    size = LayoutInspectorSize(300, 56),
                  )
                ),
            )
          ),
        rasterComponents = setOf("TextField"),
      )
    assertEquals(listOf("textField"), model.rasterTargets.map { it.nodeId })
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
  fun spToPxAppliesDensityAndFontScale() {
    // sp text sizes as sp × density × fontScale; fontScale defaults to 1.0 (an un-scaled capture).
    assertEquals(32.0, FigmaSvgModel.spToPx("16.0sp", 2f)!!, 0.001)
    assertEquals(64.0, FigmaSvgModel.spToPx("16.0sp", 2f, 2f)!!, 0.001)
    // An `em` line-height resolves against the (scaled) font px, so it grows with fontScale too.
    assertEquals(1.5 * 64.0, FigmaSvgModel.lineHeightToPx("1.5em", "16.0sp", 2f, 2f)!!, 0.001)
    // An `sp` line-height scales directly.
    assertEquals(48.0, FigmaSvgModel.lineHeightToPx("12.0sp", "16.0sp", 2f, 2f)!!, 0.001)
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
  fun aMeasuredSizeLargerThanBoundsGrowsTheFillClampedToTheParent() {
    // A Wear `Button`/`Card` places its background across content + its own horizontal padding, so
    // the fill node's `bounds` (the inner content rect) is narrower than its measured `size`. The
    // export grows the fill to the measured size — clamped to the parent's placed bounds — centered
    // on the bounds. Pill bounds 78×80 @ (44,28), size 134×104, parent box 134×104 @ (16,16) →
    // fill at 134×104 @ (16,16), not a narrow 78×80 at the inner placement.
    val pill =
      LayoutInspectorNode(
        nodeId = "pill",
        component = "RowMeasurePolicy",
        bounds = bounds(44, 28, 122, 108),
        size = LayoutInspectorSize(134, 104),
        tokens = ComposeSemanticsTokens(backgroundColor = "#FF332E3C", cornerRadius = "26.0dp"),
      )
    val box =
      LayoutInspectorNode(
        nodeId = "box",
        component = "BoxMeasurePolicy",
        bounds = bounds(16, 16, 150, 120),
        size = LayoutInspectorSize(134, 104),
        children = listOf(pill),
      )
    val svg = FigmaLayeredSvg.render(FigmaSvgModel.from(LayoutInspectorPayload(box)))
    assertTrue(svg, svg.contains("""x="16"""") && svg.contains("""width="134""""))
    assertTrue("grown to the measured height too", svg.contains("""height="104""""))
  }

  @Test
  fun aPollutedMeasuredSizeIsClampedToTheParentBounds() {
    // A loosely-constrained node can report a `size` far larger than its real drawn extent (the
    // whole sandbox). Clamp to the parent's placed bounds so the fill never paints beyond it.
    val fill =
      LayoutInspectorNode(
        nodeId = "fill",
        component = "RowMeasurePolicy",
        bounds = bounds(44, 28, 122, 108),
        size = LayoutInspectorSize(454, 454),
        tokens = ComposeSemanticsTokens(backgroundColor = "#FF332E3C"),
      )
    val box =
      LayoutInspectorNode(
        nodeId = "box",
        component = "BoxMeasurePolicy",
        bounds = bounds(16, 16, 150, 120),
        size = LayoutInspectorSize(134, 104),
        children = listOf(fill),
      )
    val svg = FigmaLayeredSvg.render(FigmaSvgModel.from(LayoutInspectorPayload(box)))
    assertTrue(svg, svg.contains("""width="134"""") && !svg.contains("""width="454""""))
  }

  @Test
  fun anOffCenterGrownFillIsClampedInsideTheParentNotCenteredOutOfIt() {
    // The grown width is clamped to the parent, but the shape is centered on its own bounds — so a
    // fill whose bounds sit off-center in its parent must still not slide past the parent edge.
    // Parent x 0..100; child bounds x 0..40 (hard against the left), measured size 100 wide → grown
    // to width 100, which centered on the child (centre x=20) would be x=-30..70. It must instead
    // be
    // clamped to x=0..100, flush inside the parent.
    val fill =
      LayoutInspectorNode(
        nodeId = "fill",
        component = "RowMeasurePolicy",
        bounds = bounds(0, 40, 40, 120),
        size = LayoutInspectorSize(100, 80),
        tokens = ComposeSemanticsTokens(backgroundColor = "#FF332E3C"),
      )
    val box =
      LayoutInspectorNode(
        nodeId = "box",
        component = "BoxMeasurePolicy",
        bounds = bounds(0, 40, 100, 120),
        size = LayoutInspectorSize(100, 80),
        children = listOf(fill),
      )
    val svg = FigmaLayeredSvg.render(FigmaSvgModel.from(LayoutInspectorPayload(box)))
    // Grown to the parent width, pinned to the parent's left edge — never x="-30".
    assertTrue(svg, svg.contains("""x="0"""") && svg.contains("""width="100""""))
    assertFalse("must not be shifted left of the parent", svg.contains("""x="-"""))
  }

  @Test
  fun aRoundClipMasksTheTreeToTheFramesInscribedCircleAndCapsTheExtent() {
    // A round Wear device screen is rendered through Roborazzi's device crop — the frame is masked
    // to
    // its inscribed circle. The export must mask to the same circle (or its square full-frame
    // background paints the corners the render leaves clear) AND cap the canvas to the frame, not
    // the
    // taller off-screen content that a scrolling list pushes below the visible screen.
    val root =
      LayoutInspectorNode(
        nodeId = "screen",
        component = "RootMeasurePolicy",
        bounds = bounds(0, 0, 384, 384),
        size = LayoutInspectorSize(384, 384),
        tokens = ComposeSemanticsTokens(backgroundColor = "#FF000000"),
        children =
          listOf(
            // A list item scrolled off the bottom of the 384 frame — must not inflate the canvas.
            layoutNode(
              "OffScreenCard",
              20,
              360,
              364,
              520,
              tokens = ComposeSemanticsTokens(backgroundColor = "#FF2E2E38"),
            )
          ),
      )
    val model = FigmaSvgModel.from(LayoutInspectorPayload(root), roundClip = true)
    // Circle centred on the frame with the inscribed radius, in root-pixel space.
    assertNotNull(model.roundClip)
    assertEquals(192, model.roundClip!!.cx)
    assertEquals(192, model.roundClip.cy)
    assertEquals(192, model.roundClip.r)
    // Extent is the 384 frame (+ padding on both sides), NOT the 520-tall off-screen content.
    assertEquals(384 + FigmaSvgModel.DEFAULT_PADDING * 2, model.width)
    assertEquals(384 + FigmaSvgModel.DEFAULT_PADDING * 2, model.height)
    val svg = FigmaLayeredSvg.render(model)
    assertTrue("emits a clipPath circle", svg.contains("""<clipPath id="deviceRound"><circle"""))
    assertTrue(
      "the tree group references the clip",
      svg.contains("""clip-path="url(#deviceRound)""""),
    )
  }

  @Test
  fun aCapsuleClipMasksATallWearFrameToAVerticalStadium() {
    // A Wear scroll-SVG export grows the round face into a TALL frame so the whole
    // TransformingLazyColumn composes in one pass. Masking that to the inscribed circle would clip
    // the list to a lens, so a capsule (vertical stadium) clip is used instead: a top half-circle
    // of radius width/2, straight sides, a bottom half-circle — emitted as <rect rx=width/2>.
    val root =
      LayoutInspectorNode(
        nodeId = "screen",
        component = "RootMeasurePolicy",
        // 384 wide, 900 tall — the grown scroll frame.
        bounds = bounds(0, 0, 384, 900),
        size = LayoutInspectorSize(384, 900),
        tokens = ComposeSemanticsTokens(backgroundColor = "#FF000000"),
        children =
          listOf(
            layoutNode(
              "Card",
              20,
              400,
              364,
              560,
              tokens = ComposeSemanticsTokens(backgroundColor = "#FF2E2E38"),
            )
          ),
      )
    val model = FigmaSvgModel.from(LayoutInspectorPayload(root), capsuleClip = true)
    // Capsule spanning the full tall frame, rx = width/2 so the caps are true half-circles.
    assertNotNull(model.capsuleClip)
    assertEquals(0, model.capsuleClip!!.x)
    assertEquals(0, model.capsuleClip.y)
    assertEquals(384, model.capsuleClip.width)
    assertEquals(900, model.capsuleClip.height)
    assertEquals(192, model.capsuleClip.rx)
    // The plain round clip must NOT also be set — they are mutually exclusive.
    assertNull("capsule and round clip are mutually exclusive", model.roundClip)
    // Extent is the tall frame (+ padding on both sides).
    assertEquals(384 + FigmaSvgModel.DEFAULT_PADDING * 2, model.width)
    assertEquals(900 + FigmaSvgModel.DEFAULT_PADDING * 2, model.height)
    val svg = FigmaLayeredSvg.render(model)
    assertTrue(
      "emits a rounded-rect clipPath",
      svg.contains("""<clipPath id="deviceRound"><rect""") && svg.contains("""rx="192""""),
    )
    assertTrue(
      "the tree group references the clip",
      svg.contains("""clip-path="url(#deviceRound)""""),
    )
    assertFalse(
      "no circle clip for a capsule frame",
      svg.contains("""<clipPath id="deviceRound"><circle"""),
    )
  }

  @Test
  fun aRoundClipOnATallFrameAutoSelectsTheCapsule() {
    // The Wear scroll-SVG export grows the square watch face into a TALL frame and re-renders
    // through the always-on figma-svg extension, which passes `roundClip = isRound`. A round frame
    // that's taller than it is wide is the grown scroll frame, so `roundClip` alone must resolve to
    // a capsule (not a lens-clipping circle) — no separate flag needs threading through the daemon.
    val root =
      layoutNode(
        "screen",
        0,
        0,
        384,
        1200,
        tokens = ComposeSemanticsTokens(backgroundColor = "#FF000000"),
      )
    val model = FigmaSvgModel.from(LayoutInspectorPayload(root), roundClip = true)
    assertNotNull("tall round frame → capsule", model.capsuleClip)
    assertNull("no circle on a tall round frame", model.roundClip)
    assertEquals(192, model.capsuleClip!!.rx)
    val svg = FigmaLayeredSvg.render(model)
    assertTrue("emits a rounded-rect clip", svg.contains("""<clipPath id="deviceRound"><rect"""))
    assertFalse("no circle clip", svg.contains("""<clipPath id="deviceRound"><circle"""))
  }

  @Test
  fun capsuleClipTakesPrecedenceOverRoundClipWhenBothRequested() {
    // A caller that flags a frame both round and tall-scroll gets the capsule — the tall-mode shape
    // wins so the grown list is never clipped to a lens.
    val root =
      layoutNode(
        "screen",
        0,
        0,
        384,
        900,
        tokens = ComposeSemanticsTokens(backgroundColor = "#FF000000"),
      )
    val model =
      FigmaSvgModel.from(LayoutInspectorPayload(root), roundClip = true, capsuleClip = true)
    assertNotNull("capsule wins", model.capsuleClip)
    assertNull("round clip suppressed", model.roundClip)
  }

  @Test
  fun curvedTextIsEmittedAsATextPathOnItsBaselineArc() {
    // A Wear `TimeText` clock is laid out along an arc — captured as a curved-text run and rendered
    // as an SVG `<textPath>` on the baseline circle so it stays editable (not dropped, not a
    // raster).
    // Top-centred arc: centre (192,192), radius 160, spanning ~28° around 270° (screen up).
    val node =
      LayoutInspectorNode(
        nodeId = "clock",
        component = "CurvedLayoutKt",
        bounds = bounds(0, 0, 384, 384),
        size = LayoutInspectorSize(384, 384),
        curvedTexts =
          listOf(
            LayoutInspectorCurvedText(
              text = "10:10",
              centerXPx = 192.0,
              centerYPx = 192.0,
              radiusPx = 160.0,
              startAngleRadians = 4.4652,
              sweepRadians = 0.4944,
              clockwise = true,
              fontSizePx = 30.0,
              fontWeight = 600,
              colorArgb = "#FFC6C6C7",
            )
          ),
      )
    val svg = FigmaLayeredSvg.render(FigmaSvgModel.from(LayoutInspectorPayload(node)))
    assertTrue("emits a baseline arc path", svg.contains("""<path id="curve-c0" d="M """))
    assertTrue(
      "draws an SVG arc (A) command",
      Regex("""d="M [\d.]+ [\d.]+ A 160""").containsMatchIn(svg),
    )
    assertTrue(
      "the text rides the path via <textPath>",
      svg.contains("""<textPath href="#curve-c0""""),
    )
    assertTrue("carries the clock string", svg.contains(">10:10</textPath>"))
    assertTrue("emits the captured weight", svg.contains("""font-weight="600""""))
    assertTrue("drops the ARGB alpha for the SVG fill", svg.contains("""fill="#C6C6C7""""))
  }

  @Test
  fun twoCurvedLayoutsWithTheSameNameGetDistinctPathIds() {
    // Duplicate SVG ids make a `<textPath href>` resolve to the first matching path, so two
    // same-named `CurvedLayout`s (a top TimeText + a bottom curved label) must not collide. A
    // document-wide sequence keeps every arc id unique.
    fun clock(text: String) =
      LayoutInspectorNode(
        nodeId = "n-$text",
        component = "CurvedLayoutKt",
        bounds = bounds(0, 0, 384, 384),
        size = LayoutInspectorSize(384, 384),
        curvedTexts =
          listOf(
            LayoutInspectorCurvedText(
              text = text,
              centerXPx = 192.0,
              centerYPx = 192.0,
              radiusPx = 160.0,
              startAngleRadians = 4.4652,
              sweepRadians = 0.4944,
              clockwise = true,
              fontSizePx = 30.0,
            )
          ),
      )
    val root =
      LayoutInspectorNode(
        nodeId = "root",
        component = "Root",
        bounds = bounds(0, 0, 384, 384),
        size = LayoutInspectorSize(384, 384),
        children = listOf(clock("10:10"), clock("Steps")),
      )
    val svg = FigmaLayeredSvg.render(FigmaSvgModel.from(LayoutInspectorPayload(root)))
    val ids = Regex("""<path id="(curve-c\d+)"""").findAll(svg).map { it.groupValues[1] }.toList()
    assertEquals("one path id per curved run", 2, ids.size)
    assertEquals("the two path ids are distinct", ids.size, ids.toSet().size)
  }

  @Test
  fun withoutRoundClipTheExportStaysSquareAndUncapped() {
    // The default (non-round) export must be unchanged: no clip, and the canvas still grows to the
    // full content extent so a normal sticker isn't wrongly clipped or cropped.
    val root =
      layoutNode(
        "screen",
        0,
        0,
        384,
        384,
        tokens = ComposeSemanticsTokens(backgroundColor = "#FF000000"),
      )
    val model = FigmaSvgModel.from(LayoutInspectorPayload(root))
    assertNull("no round clip by default", model.roundClip)
    val svg = FigmaLayeredSvg.render(model)
    assertFalse("no clipPath emitted", svg.contains("clipPath"))
    assertFalse("no clip-path attr on the tree", svg.contains("clip-path"))
  }

  @Test
  fun aPaintFillWhosePainterIsNotAColorPainterRastersFromTheFrameInHybridMode() {
    // Wear `SwitchButton`/list `Card` fill their container via `Modifier.paint(painter)` where the
    // painter is a component-private `Painter` (an animated colour painter, a `BackgroundPainter`),
    // NOT a plain `ColorPainter` — so the token resolver can't read a flat colour and the fill
    // would
    // vanish from a vector-only export. In hybrid mode (a frame exists to crop) the node's painted
    // region must instead be captured as an `<image>`, exactly like an opaque `Image`/`Icon`.
    val node =
      LayoutInspectorNode(
        nodeId = "pill",
        component = "RowMeasurePolicy",
        bounds = bounds(44, 32, 175, 104),
        size = LayoutInspectorSize(187, 104),
        modifiers =
          listOf(
            LayoutInspectorModifier(
              name = "paint",
              properties = mapOf("painter" to "SwitchButtonKt\$SwitchButton\$colorPainter\$1@1"),
              bounds = bounds(16, 16, 203, 120),
            )
          ),
        children =
          listOf(layoutNode("Label", 44, 50, 99, 86, tokens = null)), // a child that must be dropped
      )
    val svg =
      FigmaLayeredSvg.render(
        FigmaSvgModel.from(LayoutInspectorPayload(node), captureCanvasDraws = true)
      )
    assertTrue("unresolved paint fill must raster as an <image>", svg.contains("<image "))
    // Cropped to the paint modifier's drawn region (the full pill), not the inner content bounds.
    assertTrue(
      "image spans the painted region",
      svg.contains("""width="187"""") && svg.contains("""height="104""""),
    )
    assertFalse("the subtree is dropped, so no child group survives", svg.contains(">Label<"))
  }

  @Test
  fun aCoil3AsyncImageContentPainterRastersFromTheFrameInHybridMode() {
    // Coil 3's `AsyncImage` draws through its own `ContentPainterElement` (inspector name
    // `content`, carrying `request`/`imageLoader` but no `painter` property) on a `Layout` whose
    // measure policy is a lambda in coil's `internal/utils.kt` — so the node's component reads
    // `UtilsKt`, matching neither the Image/AsyncImage opaque names nor the `paint` fill modifier,
    // and the speaker photo silently vanished from the hybrid export (Confetti `speakerdetails`).
    // The content painter must raster exactly like an unresolved `Modifier.paint`.
    val node =
      LayoutInspectorNode(
        nodeId = "photo",
        component = "UtilsKt",
        bounds = bounds(223, 280, 853, 910),
        size = LayoutInspectorSize(630, 630),
        modifiers =
          listOf(
            LayoutInspectorModifier(
              name = "content",
              properties =
                mapOf(
                  "request" to "ImageRequest(context=..., data=https://example.com/avatar.png)",
                  "imageLoader" to "coil3.RealImageLoader@5af8a2df",
                  "contentScale" to "androidx.compose.ui.layout.ContentScale\$Companion\$Fit\$1@1",
                ),
              bounds = bounds(223, 280, 853, 910),
            )
          ),
      )
    val svg =
      FigmaLayeredSvg.render(
        FigmaSvgModel.from(LayoutInspectorPayload(node), captureCanvasDraws = true)
      )
    assertTrue("a Coil content painter must raster as an <image>", svg.contains("<image "))
    assertTrue(
      "image spans the photo box",
      svg.contains("""width="630"""") && svg.contains("""height="630""""),
    )
  }

  @Test
  fun aCoil2ContentPainterModifierClassNameFallbackRastersInHybridMode() {
    // Coil 2's `ContentPainterModifier` names itself via `debugInspectorInfo`, which is compiled
    // out of release artifacts — so the capture falls back to the element's class name. That
    // spelling must raster the same way as Coil 3's `content`.
    val node =
      LayoutInspectorNode(
        nodeId = "avatar",
        component = "UtilsKt",
        bounds = bounds(0, 0, 96, 96),
        size = LayoutInspectorSize(96, 96),
        modifiers =
          listOf(
            LayoutInspectorModifier(
              name = "ContentPainterModifier",
              properties = emptyMap(),
              bounds = bounds(0, 0, 96, 96),
            )
          ),
      )
    val svg =
      FigmaLayeredSvg.render(
        FigmaSvgModel.from(LayoutInspectorPayload(node), captureCanvasDraws = true)
      )
    assertTrue("a Coil 2 content painter must raster as an <image>", svg.contains("<image "))
  }

  @Test
  fun aCoilContentPainterStaysAGroupInVectorOnlyMode() {
    // With no frame to crop from (vector-only export) there are no pixels to recover — the node
    // must fall through to a plain group exactly as before, not emit an <image> with a dangling
    // href.
    val node =
      LayoutInspectorNode(
        nodeId = "photo",
        component = "UtilsKt",
        bounds = bounds(0, 0, 240, 240),
        size = LayoutInspectorSize(240, 240),
        modifiers =
          listOf(
            LayoutInspectorModifier(
              name = "content",
              properties = emptyMap(),
              bounds = bounds(0, 0, 240, 240),
            )
          ),
      )
    val svg = FigmaLayeredSvg.render(FigmaSvgModel.from(LayoutInspectorPayload(node)))
    assertFalse("vector-only export must not reference a raster", svg.contains("<image "))
  }

  @Test
  fun aColorPainterFillWithAColorFilterRastersBecauseTheTintCannotVectorise() {
    // `Modifier.paint(ColorPainter(...), colorFilter = tint(...))` stringifies its painter as
    // `ColorPainter(...)`, but the resolver leaves `backgroundColor` null because the re-tint can't
    // collapse to a flat token. That (visible) fill must still fall to the frame raster — the
    // `ColorPainter(` prefix alone must NOT mark it vectorisable when a filter is present.
    val node =
      LayoutInspectorNode(
        nodeId = "tinted",
        component = "BoxMeasurePolicy",
        bounds = bounds(0, 0, 100, 100),
        size = LayoutInspectorSize(100, 100),
        modifiers =
          listOf(
            LayoutInspectorModifier(
              name = "paint",
              properties =
                mapOf(
                  "painter" to "ColorPainter(color=Color(1.0, 0.0, 0.0, 1.0, sRGB IEC61966-2.1))",
                  "colorFilter" to "ColorFilter(...)",
                ),
              bounds = bounds(0, 0, 100, 100),
            )
          ),
      )
    val svg =
      FigmaLayeredSvg.render(
        FigmaSvgModel.from(LayoutInspectorPayload(node), captureCanvasDraws = true)
      )
    assertTrue(
      "a colour-filtered ColorPainter fill must raster from the frame",
      svg.contains("<image "),
    )
  }

  @Test
  fun aTransparentColorPainterFillWithoutAFilterDoesNotRaster() {
    // A `ColorPainter` that resolved to nothing because it's fully transparent (no filter) has no
    // visible pixels to recover — it must NOT raster, or an invisible fill would bake an opaque
    // crop.
    val node =
      LayoutInspectorNode(
        nodeId = "clear",
        component = "BoxMeasurePolicy",
        bounds = bounds(0, 0, 100, 100),
        size = LayoutInspectorSize(100, 100),
        modifiers =
          listOf(
            LayoutInspectorModifier(
              name = "paint",
              properties =
                mapOf(
                  "painter" to "ColorPainter(color=Color(0.0, 0.0, 0.0, 0.0, sRGB IEC61966-2.1))"
                ),
              bounds = bounds(0, 0, 100, 100),
            )
          ),
      )
    val svg =
      FigmaLayeredSvg.render(
        FigmaSvgModel.from(LayoutInspectorPayload(node), captureCanvasDraws = true)
      )
    assertFalse("a transparent unfiltered ColorPainter must not raster", svg.contains("<image "))
  }

  @Test
  fun aPlainColorPainterFillStaysVectorAndDoesNotRaster() {
    // The one painter we CAN vectorise — a solid `ColorPainter` — must still resolve to a flat fill
    // rect even in hybrid mode; it must NOT fall to the frame-raster path.
    val node =
      layoutNode(
        "Surface",
        16,
        16,
        150,
        120,
        tokens = ComposeSemanticsTokens(backgroundColor = "#FF332E3C"),
      )
    val svg =
      FigmaLayeredSvg.render(
        FigmaSvgModel.from(LayoutInspectorPayload(node), captureCanvasDraws = true)
      )
    assertTrue("a solid fill stays a vector rect", svg.contains("""fill="#332E3C""""))
    assertFalse("a resolvable fill must not raster", svg.contains("<image "))
  }

  @Test
  fun anUnvectorizablePaintFillStaysVectorOnlyOutsideHybridMode() {
    // Without a frame to crop from (vector-only export) there's nothing to raster, so an unresolved
    // paint fill can't be recovered — the node stays a (fill-less) group rather than emitting a
    // dangling `<image>` ref. The raster fallback is strictly a hybrid-mode affordance.
    val node =
      LayoutInspectorNode(
        nodeId = "pill",
        component = "RowMeasurePolicy",
        bounds = bounds(44, 32, 175, 104),
        size = LayoutInspectorSize(187, 104),
        modifiers =
          listOf(
            LayoutInspectorModifier(
              name = "paint",
              properties = mapOf("painter" to "some.pkg.BackgroundPainter@2"),
              bounds = bounds(16, 16, 203, 120),
            )
          ),
      )
    val svg = FigmaLayeredSvg.render(FigmaSvgModel.from(LayoutInspectorPayload(node)))
    assertFalse("vector-only export must not emit an <image>", svg.contains("<image "))
  }

  @Test
  fun aTouchTargetInflatedFillDoesNotGrowToItsMeasuredSize() {
    // Every M3 `Button`/`IconButton` fills via a `BackgroundElement` on a node that also carries
    // `Modifier.minimumInteractiveComponentSize()`. That modifier inflates the measured `size` up
    // to
    // the 48dp touch target while the background still paints at the smaller visual `bounds`, so
    // the
    // measured-`size` growth must be SUPPRESSED here — otherwise a 40dp pill balloons into its
    // invisible touch margin. Fill bounds 170×80 @ (0,8), size 170×96 (the touch target), parent
    // 170×96 @ (0,0): the fill must stay 80 tall at y=8, NOT grow to 96 at y=0. (The complement of
    // `aMeasuredSizeLargerThanBoundsGrowsTheFillClampedToTheParent`, whose node carries no such
    // modifier.)
    val fill =
      LayoutInspectorNode(
        nodeId = "fill",
        component = "BoxMeasurePolicy",
        bounds = bounds(0, 8, 170, 88),
        size = LayoutInspectorSize(170, 96),
        modifiers = listOf(LayoutInspectorModifier(name = "minimumInteractiveComponentSize")),
        tokens = ComposeSemanticsTokens(backgroundColor = "#FF6750A4"),
      )
    val root =
      LayoutInspectorNode(
        nodeId = "root",
        component = "RootMeasurePolicy",
        bounds = bounds(0, 0, 170, 96),
        size = LayoutInspectorSize(170, 96),
        children = listOf(fill),
      )
    val svg = FigmaLayeredSvg.render(FigmaSvgModel.from(LayoutInspectorPayload(root)))
    assertTrue("fill stays at its visual bounds height", svg.contains("""height="80""""))
    assertTrue("fill stays at its visual y", svg.contains("""y="8""""))
    assertFalse("fill must not grow to the touch-target height", svg.contains("""height="96""""))
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
  fun embeddedFontFaceFamilyIsXmlEscapedInTheStyleBlock() {
    // The `@font-face` family rides inside `<defs><style>`, whose content is XML character data —
    // so
    // a family carrying `&`/`<`/`>` must be entity-escaped there too, not just CSS-escaped, or the
    // exported `.svg` fails to parse on Figma/Illustrator import (Chromium's HTML parser hides it).
    val model = FigmaSvgModel.from(LayoutInspectorPayload(layoutNode("Screen", 0, 0, 100, 100)))
    val svg =
      FigmaLayeredSvg.render(
        model,
        FigmaLayeredSvg.Options(defaultFontFamily = "Roboto"),
        listOf(FigmaSvgFontFace("Ampersand & Co <b>", 400, italic = false, dataBase64 = "QUJD")),
      )
    val style = svg.substringAfter("<style>").substringBefore("</style>")
    assertTrue(
      "family is entity-escaped inside <style>",
      style.contains("font-family:'Ampersand &amp; Co &lt;b&gt;'"),
    )
    assertFalse("no raw < survives in the style block", style.contains('<'))
    assertFalse("no raw > survives in the style block", style.contains('>'))
    assertFalse(
      "no bare & survives in the style block",
      style.contains(Regex("&(?!amp;|lt;|gt;|quot;|apos;)")),
    )
    assertWellFormedXml(svg)
  }

  @Test
  fun hostileStringsInEveryTextSurfaceStillParseAsXml() {
    // Layer names, theme-token labels, straight `<text>`, and curved `<textPath>` runs are all
    // attacker-influenced — a composable name or a `Text("<b>&…")` can carry any character. This is
    // the whole-document regression guard: every escape site (`escape`/`escapeAttr`/the `<style>`
    // block) must combine into a document a strict XML parser accepts.
    val hostile = "a < b & \"c\" 'd' >e"
    val layout =
      layoutNode(
        hostile, // layer name → `id` attr + `<title>`
        0,
        0,
        200,
        200,
        tokens = ComposeSemanticsTokens(backgroundColor = "#FF6750A4"),
        children =
          listOf(
            LayoutInspectorNode(
              nodeId = "clock",
              component = hostile,
              bounds = bounds(0, 0, 200, 60),
              size = LayoutInspectorSize(200, 60),
              curvedTexts =
                listOf(
                  LayoutInspectorCurvedText(
                    text = hostile,
                    centerXPx = 100.0,
                    centerYPx = 100.0,
                    radiusPx = 80.0,
                    startAngleRadians = 4.4652,
                    sweepRadians = 0.5,
                    clockwise = true,
                    fontSizePx = 20.0,
                    fontWeight = 600,
                    colorArgb = "#FFC6C6C7",
                  )
                ),
            ),
            layoutNode("Text", 8, 80, 192, 120),
          ),
      )
    val semantics =
      ComposeSemanticsNode(
        nodeId = "root",
        boundsInRoot = "0,0,200,200",
        children =
          listOf(
            ComposeSemanticsNode(
              nodeId = "Text",
              boundsInRoot = "8,80,192,120",
              text = hostile,
              typography = ComposeSemanticsTypography(fontSize = "16.0sp"),
            )
          ),
      )
    val model =
      FigmaSvgModel.from(
        LayoutInspectorPayload(layout),
        ComposeSemanticsPayload(semantics),
        colorNames = mapOf("#FF6750A4" to hostile), // token name → `data-token` attr + `<title>`
      )
    val svg =
      FigmaLayeredSvg.render(
        model,
        FigmaLayeredSvg.Options(),
        listOf(FigmaSvgFontFace(hostile, 400, italic = false, dataBase64 = "QUJD")),
      )
    // Straight text content and the clock string are entity-escaped, not passed through raw …
    assertTrue(svg.contains("a &lt; b &amp; \"c\" 'd' &gt;e"))
    // … and the whole document — names, tokens, text, curved text, font-face — parses as XML.
    assertWellFormedXml(svg)
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
