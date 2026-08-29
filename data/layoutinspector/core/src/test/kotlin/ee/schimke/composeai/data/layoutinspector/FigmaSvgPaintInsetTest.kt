package ee.schimke.composeai.data.layoutinspector

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A node whose paint modifiers sit after a `Modifier.padding`
 * (`padding(4.dp).clip(…).border(…).background(…)` — Jetsnack's `JetsnackGradientTintedIconButton`)
 * draws its shape in the padded box, which is the node's placed `bounds`. Its measured `size` still
 * spans the padding, so the measured-size growth heuristic would inflate the ring back out to the
 * padded root — the 85×85-vs-63×63 defect. A captured leading [ComposeSemanticsTokens.paintInset]
 * suppresses that growth so the ring stays on the inner control (issue #2852).
 */
class FigmaSvgPaintInsetTest {

  private fun bounds(l: Int, t: Int, r: Int, b: Int) = LayoutInspectorBounds(l, t, r, b)

  /**
   * A gradient-ringed control placed at the inner 63×63 box but measured at the padded 85×85,
   * wrapped in an 85×85 parent so the growth heuristic has a parent to clamp against (as it does in
   * a real render). The [paintInset] models the `padding(11.dp)` that leads the control's
   * `clip/border/background` chain.
   */
  private fun ringUnderParent(paintInset: ComposeSemanticsInsets?) =
    LayoutInspectorNode(
      nodeId = "parent-1",
      component = "Box",
      bounds = bounds(0, 0, 85, 85),
      size = LayoutInspectorSize(85, 85),
      tokens = ComposeSemanticsTokens(backgroundColor = "#FF000000"),
      children =
        listOf(
          LayoutInspectorNode(
            nodeId = "ring-1",
            component = "Box",
            bounds = bounds(11, 11, 74, 74),
            size = LayoutInspectorSize(85, 85),
            tokens =
              ComposeSemanticsTokens(
                backgroundColor = "#FF102030",
                borderColor = "#FF00FFFF",
                borderWidth = "2.0dp",
                shape = "circle",
                padding = paintInset,
                paintInset = paintInset,
              ),
          )
        ),
    )

  private fun ring(paintInset: ComposeSemanticsInsets?): FigmaSvgLayer {
    val root =
      FigmaSvgModel.from(layout = LayoutInspectorPayload(ringUnderParent(paintInset)), density = 1f)
        .root
    // Only the ring carries a border → a `stroke`; the parent has a fill only.
    return firstStroked(root) ?: error("ring layer not found")
  }

  private fun firstStroked(layer: FigmaSvgLayer): FigmaSvgLayer? =
    if (layer.stroke != null) layer else layer.children.firstNotNullOfOrNull { firstStroked(it) }

  @Test
  fun leadingPaddingKeepsTheRingOnTheInnerControl() {
    val inset =
      ComposeSemanticsInsets(start = "11.0dp", top = "11.0dp", end = "11.0dp", bottom = "11.0dp")
    val r = ring(inset)
    // Stays on the placed 63×63 control; the measured 85px padded box does not grow it.
    assertEquals(63, r.right - r.left)
    assertEquals(63, r.bottom - r.top)
    assertEquals(11, r.left)
    assertEquals(11, r.top)
  }

  @Test
  fun withoutLeadingPaddingTheMeasuredSizeStillGrowsTheShape() {
    // Control: the same geometry with no leading padding grows to the measured 85px box — the
    // pre-existing growth behaviour this fix must not disturb for ordinary (non-padded) fills.
    val r = ring(paintInset = null)
    assertEquals(85, r.right - r.left)
    assertEquals(85, r.bottom - r.top)
  }

  /**
   * The gradient counterpart of [ringUnderParent]: Pocket Casts' `GradientRowButton` is
   * `background(brush, RoundedCornerShape(12.dp)).clickable().padding(16.dp)`, so the brush covers
   * the whole node and the padding insets only the label. The button is placed at the padded 966×56
   * content rect but measured at the full 1050×140 box, and its brush resolves no flat
   * [ComposeSemanticsTokens.backgroundColor] — only [ComposeSemanticsTokens.backgroundGradient]
   * (issue #2852). The padding trails the paint, so no [ComposeSemanticsTokens.paintInset] is
   * captured and the growth must run (issue #3569).
   */
  private fun gradientButton(paintInset: ComposeSemanticsInsets?): FigmaSvgLayer {
    val payload =
      LayoutInspectorPayload(
        LayoutInspectorNode(
          nodeId = "root-1",
          component = "Box",
          bounds = bounds(0, 0, 1050, 140),
          size = LayoutInspectorSize(1050, 140),
          tokens = ComposeSemanticsTokens(backgroundColor = "#FFFFF0EB"),
          children =
            listOf(
              LayoutInspectorNode(
                nodeId = "button-1",
                component = "Box",
                bounds = bounds(42, 42, 1008, 98),
                size = LayoutInspectorSize(1050, 140),
                tokens =
                  ComposeSemanticsTokens(
                    backgroundGradient =
                      LayoutInspectorGradient(colors = listOf("#FFFFD846", "#FFFEB525")),
                    cornerRadius = "12.0dp",
                    padding = paintInset,
                    paintInset = paintInset,
                  ),
              )
            ),
        )
      )
    val root = FigmaSvgModel.from(layout = payload, density = 1f).root
    return firstGradient(root) ?: error("gradient layer not found")
  }

  private fun firstGradient(layer: FigmaSvgLayer): FigmaSvgLayer? =
    if (layer.fillGradient != null) layer
    else layer.children.firstNotNullOfOrNull { firstGradient(it) }

  @Test
  fun aBrushFillGrowsToTheMeasuredBoxLikeAFlatOne() {
    // Before the fix the `expand` gate read the flat `fill`/`stroke` only, so the gradient stayed
    // on the placed 966×56 content rect — a pill floating inside the button the PNG paints edge to
    // edge.
    val b = gradientButton(paintInset = null)
    assertEquals(0, b.left)
    assertEquals(0, b.top)
    assertEquals(1050, b.right - b.left)
    assertEquals(140, b.bottom - b.top)
  }

  @Test
  fun aBrushFillBehindALeadingPaddingStaysOnItsPlacedBounds() {
    // The mirror of [leadingPaddingKeepsTheRingOnTheInnerControl]: when the padding really does
    // lead the paint, a brush must be suppressed exactly like a flat fill.
    val inset =
      ComposeSemanticsInsets(start = "16.0dp", top = "16.0dp", end = "16.0dp", bottom = "16.0dp")
    val b = gradientButton(inset)
    assertEquals(42, b.left)
    assertEquals(42, b.top)
    assertEquals(966, b.right - b.left)
    assertEquals(56, b.bottom - b.top)
  }

  @Test
  fun aZeroLeadingPaddingDoesNotSuppressGrowth() {
    // `padding(0.dp)` resolves to an all-"0.0dp" inset that changes no geometry; it must not be
    // treated as a paint-insetting padding, or a node whose chain merely contains `padding(0.dp)`
    // would lose the growth heuristic it still needs.
    val zero =
      ComposeSemanticsInsets(start = "0.0dp", top = "0.0dp", end = "0.0dp", bottom = "0.0dp")
    val r = ring(zero)
    assertEquals(85, r.right - r.left)
    assertEquals(85, r.bottom - r.top)
  }
}
