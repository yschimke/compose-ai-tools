package ee.schimke.composeai.data.layoutinspector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A `Modifier.clip(shape)` container masks its children to its own box + corner shape. A child
 * placed beyond the clip — Jetsnack Search/Categories' minimum-size image under
 * `.clip(CategoryShape)` — must be clipped to the rounded box (an SVG `<clipPath>`) and must not
 * grow the exported canvas past the clipped viewport the render shows (issue #2852).
 */
class FigmaSvgChildClipTest {

  private fun bounds(l: Int, t: Int, r: Int, b: Int) = LayoutInspectorBounds(l, t, r, b)

  /**
   * A rounded clip container at 0..100 with a filled child that runs [childRight]px wide (past the
   * 100px box when [childRight] > 100). [clips] toggles the container's `Modifier.clip` so the same
   * geometry can be measured with and without the mask.
   */
  private fun clipContainer(clips: Boolean, childRight: Int) =
    LayoutInspectorNode(
      nodeId = "card",
      component = "Box",
      bounds = bounds(0, 0, 100, 100),
      size = LayoutInspectorSize(100, 100),
      tokens =
        ComposeSemanticsTokens(
          backgroundColor = "#FFEEEEEE",
          cornerRadius = "12.0dp",
          clipsContent = clips.takeIf { it },
        ),
      children =
        listOf(
          LayoutInspectorNode(
            nodeId = "image",
            component = "Image",
            // Starts inside the card, runs off its right edge.
            bounds = bounds(40, 20, childRight, 80),
            size = LayoutInspectorSize(childRight - 40, 60),
            tokens = ComposeSemanticsTokens(backgroundColor = "#FF3366CC"),
          )
        ),
    )

  private fun model(clips: Boolean, childRight: Int) =
    FigmaSvgModel.from(
      layout = LayoutInspectorPayload(clipContainer(clips, childRight)),
      density = 1f,
    )

  private fun extentWidth(m: FigmaSvgModel) = m.width - m.padding * 2

  @Test
  fun overflowingChildUnderClipDoesNotGrowTheCanvas() {
    // The image runs to x=180, 80px past the 100px card. Under the clip the canvas is the 100px
    // card, not the 180px overflow bbox.
    val clipped = model(clips = true, childRight = 180)
    assertEquals("clip clamps the canvas to the card box", 100, extentWidth(clipped))
  }

  @Test
  fun overflowingChildWithoutClipStillGrowsTheCanvas() {
    // Control: without the clip the exporter keeps the pre-existing behaviour — the overflow bbox
    // grows the canvas (issue #2937 keeps deliberately-retained draws on-canvas).
    val unclipped = model(clips = false, childRight = 180)
    assertTrue(
      "no clip → canvas grows to the overflow (${extentWidth(unclipped)})",
      extentWidth(unclipped) > 100,
    )
  }

  @Test
  fun overflowingChildUnderClipEmitsAClipPath() {
    val svg = FigmaLayeredSvg.render(model(clips = true, childRight = 180))
    assertTrue("a clipPath must be defined:\n$svg", svg.contains("<clipPath id=\"clip-"))
    assertTrue("the group must reference it:\n$svg", svg.contains("clip-path=\"url(#clip-"))
    // A rounded clip carries the card's corner radius on its mask rect.
    assertTrue(
      "the clip mask must be rounded:\n$svg",
      Regex("<clipPath[^>]*>\\s*<rect[^>]*rx=").containsMatchIn(svg),
    )
  }

  @Test
  fun containedChildUnderClipEmitsNoClipPath() {
    // A clip whose child sits fully inside its box removes nothing, so no clip-path is emitted and
    // the common case stays unchanged.
    val svg = FigmaLayeredSvg.render(model(clips = true, childRight = 90))
    assertFalse("no overflow → no clipPath:\n$svg", svg.contains("<clipPath id=\"clip-"))
  }
}
