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
}
