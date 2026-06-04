package ee.schimke.composeai.data.layoutinspector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SemanticsWireframeSvgTest {

  private fun node(
    id: String,
    bounds: String,
    label: String? = null,
    text: String? = null,
    role: String? = null,
    testTag: String? = null,
    clickable: Boolean = false,
    mergeMode: String? = null,
    children: List<ComposeSemanticsNode> = emptyList(),
  ) =
    ComposeSemanticsNode(
      nodeId = id,
      boundsInRoot = bounds,
      label = label,
      text = text,
      role = role,
      testTag = testTag,
      clickable = clickable,
      mergeMode = mergeMode,
      children = children,
    )

  @Test
  fun emitsOneRectPerParseableNodePlusGround() {
    val payload =
      ComposeSemanticsPayload(
        root =
          node(
            "root",
            "0,0,400,800",
            children = listOf(node("a", "0,0,400,100"), node("b", "0,100,400,200")),
          )
      )
    val svg = SemanticsWireframeSvg.render(payload)
    // 3 node rects + 1 white ground rect.
    assertEquals(4, Regex("<rect ").findAll(svg).count())
    assertTrue(svg.startsWith("<svg"))
    assertTrue(svg.trimEnd().endsWith("</svg>"))
  }

  @Test
  fun viewBoxIsUnionOfBoundsPlusPadding() {
    // Root reports a degenerate (0,0,0,0); the extent must still come from the children's union.
    val payload =
      ComposeSemanticsPayload(
        root =
          node(
            "root",
            "0,0,0,0",
            children = listOf(node("a", "10,20,110,220"), node("b", "60,120,260,320")),
          )
      )
    val svg = SemanticsWireframeSvg.render(payload, SemanticsWireframeSvg.Options(padding = 16))
    // union x:[0..260] (root box counts: 0..0, child b right=260) y:[0..320]
    // width = (260-0)+32 = 292 ; height = (320-0)+32 = 352
    assertTrue(svg.lineSequence().first(), svg.contains("""viewBox="0 0 292 352""""))
    assertTrue(svg.contains("""width="292""""))
    assertTrue(svg.contains("""height="352""""))
  }

  @Test
  fun clickableNodeGetsAccentFillAndThickerStroke() {
    val payload =
      ComposeSemanticsPayload(root = node("btn", "0,0,200,60", label = "Save", clickable = true))
    val svg = SemanticsWireframeSvg.render(payload)
    assertTrue(svg.contains("""fill="#1976D2""""))
    assertTrue(svg.contains("""stroke="#1976D2""""))
    assertTrue(svg.contains("""stroke-width="2""""))
  }

  @Test
  fun clearAndSetNodeIsDashed() {
    val payload = ComposeSemanticsPayload(root = node("x", "0,0,200,60", mergeMode = "clearAndSet"))
    val svg = SemanticsWireframeSvg.render(payload)
    assertTrue(svg.contains("stroke-dasharray"))
  }

  @Test
  fun mergeDescendantsIsNotDashed() {
    val payload =
      ComposeSemanticsPayload(root = node("x", "0,0,200,60", mergeMode = "mergeDescendants"))
    val svg = SemanticsWireframeSvg.render(payload)
    assertFalse(svg.contains("stroke-dasharray"))
  }

  @Test
  fun labelFallsBackThroughRoleTestTagText() {
    val payload =
      ComposeSemanticsPayload(
        root =
          node("root", "0,0,400,400", children = listOf(node("r", "0,0,400,80", role = "Button")))
      )
    val svg = SemanticsWireframeSvg.render(payload)
    assertTrue(svg.contains(">Button</text>"))
  }

  @Test
  fun labelIsXmlEscaped() {
    val payload = ComposeSemanticsPayload(root = node("x", "0,0,4000,80", text = "a < b & \"c\""))
    val svg = SemanticsWireframeSvg.render(payload)
    assertTrue(svg.contains("a &lt; b &amp; &quot;c&quot;"))
    assertFalse(svg.contains("a < b & \"c\""))
  }

  @Test
  fun labelTruncatedToBoxWidth() {
    // Narrow box: only a few glyphs fit, so a long label must end with the ellipsis.
    val payload =
      ComposeSemanticsPayload(root = node("x", "0,0,40,40", text = "A very long label indeed"))
    val svg = SemanticsWireframeSvg.render(payload)
    val textContent = Regex("<text[^>]*>([^<]*)</text>").find(svg)?.groupValues?.get(1)
    assertTrue("got: $textContent", textContent != null && textContent.endsWith("…"))
    assertFalse(svg.contains("A very long label indeed"))
  }

  @Test
  fun labelsCanBeDisabled() {
    val payload = ComposeSemanticsPayload(root = node("x", "0,0,200,60", label = "Hello"))
    val svg =
      SemanticsWireframeSvg.render(payload, SemanticsWireframeSvg.Options(showLabels = false))
    assertFalse(svg.contains("<text"))
  }

  @Test
  fun unparseableBoundsAreSkippedButSubtreeKept() {
    val payload =
      ComposeSemanticsPayload(
        root = node("root", "garbage", children = listOf(node("a", "0,0,100,100")))
      )
    val svg = SemanticsWireframeSvg.render(payload)
    // ground + the one valid child = 2 rects (root skipped).
    assertEquals(2, Regex("<rect ").findAll(svg).count())
  }

  @Test
  fun emptyTreeProducesMinimalValidSvg() {
    val payload = ComposeSemanticsPayload(root = node("root", "nope"))
    val svg = SemanticsWireframeSvg.render(payload)
    assertTrue(svg.startsWith("<svg"))
    assertTrue(svg.trimEnd().endsWith("</svg>"))
    // Only the ground rect.
    assertEquals(1, Regex("<rect ").findAll(svg).count())
  }

  @Test
  fun outputIsDeterministic() {
    val payload =
      ComposeSemanticsPayload(
        root =
          node(
            "root",
            "0,0,400,800",
            children = listOf(node("a", "0,0,400,100", label = "A", clickable = true)),
          )
      )
    assertEquals(SemanticsWireframeSvg.render(payload), SemanticsWireframeSvg.render(payload))
  }
}
