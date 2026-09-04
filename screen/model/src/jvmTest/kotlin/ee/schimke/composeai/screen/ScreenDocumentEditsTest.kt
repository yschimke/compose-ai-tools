package ee.schimke.composeai.screen

import ee.schimke.composeai.discovery.ScreenDocument
import ee.schimke.composeai.discovery.ScreenNode
import ee.schimke.composeai.discovery.ScreenValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The builder's edits, as pure functions over an immutable [ScreenDocument]. */
class ScreenDocumentEditsTest {

  // scaffold(0) → content[ lazy-column(1) → content[ card(2) → content[ button(3) ], text(4) ] ]
  private val document =
    ScreenDocument(
      name = "S",
      root =
        ScreenNode(
          "scaffold",
          slots =
            mapOf(
              "content" to
                listOf(
                  ScreenNode(
                    "lazy-column",
                    slots =
                      mapOf(
                        "content" to
                          listOf(
                            ScreenNode(
                              "card",
                              slots = mapOf("content" to listOf(ScreenNode("button"))),
                            ),
                            ScreenNode("text"),
                          )
                      ),
                  )
                )
            ),
        ),
    )

  @Test
  fun `pre-order numbers the tree the way the UI selects it`() {
    assertEquals(
      listOf("scaffold", "lazy-column", "card", "button", "text"),
      document.flattenNodes().map { it.node.componentId },
    )
    assertEquals(listOf(null, 0, 1, 2, 1), document.flattenNodes().map { it.parentIndex })
    assertEquals(
      listOf(null, "content", "content", "content", "content"),
      document.flattenNodes().map { it.slot },
    )
  }

  @Test
  fun `adding appends into the named slot of the selected node`() {
    val edited = document.addNode(2, "content", ScreenNode("text"))
    assertEquals(
      listOf("scaffold", "lazy-column", "card", "button", "text", "text"),
      edited.flattenNodes().map { it.node.componentId },
    )
    // …into the card, after the button that was already there. Pre-order puts it at 4, *inside*
    // the card — index 5 is the pre-existing sibling text under the lazy column, which is exactly
    // the renumbering a caller must not cache an index across.
    assertEquals(2, edited.flattenNodes().first { it.index == 4 }.parentIndex)
    assertEquals(1, edited.flattenNodes().first { it.index == 5 }.parentIndex)
  }

  @Test
  fun `removing takes the whole subtree and renumbers what follows`() {
    // Removing the card must take the button with it — this is the case a naive index walk gets
    // wrong, because the subtree's indices are consumed before the node is tested.
    val edited = document.removeNode(2)
    assertEquals(
      listOf("scaffold", "lazy-column", "text"),
      edited.flattenNodes().map { it.node.componentId },
    )
  }

  @Test
  fun `removing a leaf leaves its siblings, and an empty slot is dropped`() {
    assertEquals(
      listOf("scaffold", "lazy-column", "card", "text"),
      document.removeNode(3).flattenNodes().map { it.node.componentId },
    )
    // The card's `content` slot is now empty and is not carried as an empty list.
    assertEquals(emptyMap<String, Any>(), document.removeNode(3).nodeAt(2)?.slots)
  }

  @Test
  fun `the root is never removed`() {
    assertEquals(document, document.removeNode(0))
  }

  @Test
  fun `an argument is set on one instance and cleared by null`() {
    val set = document.setArgument(4, "text", ScreenValue.Text("Hello"))
    assertEquals(mapOf("text" to ScreenValue.Text("Hello")), set.nodeAt(4)?.arguments)
    // …and the sibling is untouched, which is the whole point of per-instance editing.
    assertEquals(emptyMap<String, ScreenValue>(), set.nodeAt(3)?.arguments)
    assertEquals(
      emptyMap<String, ScreenValue>(),
      set.setArgument(4, "text", null).nodeAt(4)?.arguments,
    )
  }

  @Test
  fun `an edit at an index that does not exist leaves the document alone`() {
    assertEquals(document, document.mapNodeAt(99) { it.copy(componentId = "nope") })
    assertEquals(document, document.removeNode(99))
    assertNull(document.nodeAt(99))
  }
}
