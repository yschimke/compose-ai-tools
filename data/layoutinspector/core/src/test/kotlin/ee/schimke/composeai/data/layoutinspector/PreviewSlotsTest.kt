package ee.schimke.composeai.data.layoutinspector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PreviewSlotsTest {

  private fun node(
    tag: String? = null,
    bounds: String = "0,0,0,0",
    children: List<ComposeSemanticsNode> = emptyList(),
  ) = ComposeSemanticsNode(nodeId = "0", boundsInRoot = bounds, testTag = tag, children = children)

  @Test
  fun `extracts named, bounded slots from dp-slot testTags, depth-first`() {
    val tree =
      ComposeSemanticsPayload(
        node(
          children =
            listOf(
              node(tag = "dp-slot:leadingIcon", bounds = "8,8,40,40"),
              node(
                tag = "notASlot",
                bounds = "0,40,200,80",
                children = listOf(node(tag = "dp-slot:headline", bounds = "48,44,192,64")),
              ),
              node(tag = "dp-slot:supporting", bounds = "48,68,192,88"),
            )
        )
      )

    val slots = PreviewSlots.extractSlots(tree)
    assertEquals(listOf("leadingIcon", "headline", "supporting"), slots.map { it.name })
    val icon = slots.first()
    assertEquals(SlotBounds(8, 8, 40, 40), icon.bounds)
    assertEquals(32, icon.width)
    assertEquals(32, icon.height)
  }

  @Test
  fun `skips blank names and malformed bounds, never throws`() {
    val tree =
      ComposeSemanticsPayload(
        node(
          children =
            listOf(
              node(tag = "dp-slot:", bounds = "0,0,10,10"), // blank name → skipped
              node(tag = "dp-slot:bad", bounds = "0,0,oops"), // malformed bounds → skipped
              node(tag = "dp-slot:ok", bounds = "1,2,3,4"),
            )
        )
      )
    assertEquals(listOf("ok"), PreviewSlots.extractSlots(tree).map { it.name })
  }

  @Test
  fun `is empty for a tree with no slot markers`() {
    assertEquals(
      emptyList<PreviewSlot>(),
      PreviewSlots.extractSlots(ComposeSemanticsPayload(node())),
    )
  }

  @Test
  fun `parses and rejects bounds wire forms`() {
    assertEquals(SlotBounds(0, 0, 200, 100), SlotBounds.parse("0,0,200,100"))
    assertNull(SlotBounds.parse("1,2,3"))
    assertNull(SlotBounds.parse("a,b,c,d"))
  }
}
