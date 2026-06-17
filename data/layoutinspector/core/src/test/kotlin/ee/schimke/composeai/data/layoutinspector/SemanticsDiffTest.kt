package ee.schimke.composeai.data.layoutinspector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SemanticsDiffTest {

  private fun node(
    role: String? = null,
    testTag: String? = null,
    text: String? = null,
    label: String? = null,
    clickable: Boolean = false,
    layoutTruncated: Boolean? = null,
    children: List<ComposeSemanticsNode> = emptyList(),
  ) =
    ComposeSemanticsNode(
      nodeId = "0",
      boundsInRoot = "0,0,10,10",
      role = role,
      testTag = testTag,
      text = text,
      label = label,
      clickable = clickable,
      textOverflow = layoutTruncated?.let { ComposeSemanticsTextOverflow(truncated = it) },
      children = children,
    )

  @Test
  fun identicalTreesProduceEmptyDelta() {
    val tree = node(children = listOf(node(testTag = "submit", text = "Go")))
    assertTrue(SemanticsDiff.diff(tree, tree).isEmpty)
  }

  @Test
  fun textChangeReportsFieldChangeOnSameRef() {
    val base = node(children = listOf(node(testTag = "label", text = "Hello")))
    val head = node(children = listOf(node(testTag = "label", text = "Helloo")))
    val delta = SemanticsDiff.diff(base, head)

    assertTrue(delta.added.isEmpty())
    assertTrue(delta.removed.isEmpty())
    assertEquals(1, delta.changed.size)
    val change = delta.changed.single()
    assertEquals("r/tag:label", change.ref)
    assertEquals(1, change.changes.size)
    assertEquals(SemanticsFieldChange("text", "Hello", "Helloo"), change.changes.single())
  }

  @Test
  fun addedNodeIsReported() {
    val base = node(children = listOf(node(testTag = "a")))
    val head = node(children = listOf(node(testTag = "a"), node(testTag = "b")))
    val delta = SemanticsDiff.diff(base, head)
    assertTrue(delta.changed.isEmpty())
    assertEquals(listOf("r/tag:b"), delta.added.map { it.ref })
  }

  @Test
  fun removedNodeIsReported() {
    val base = node(children = listOf(node(testTag = "a"), node(testTag = "b")))
    val head = node(children = listOf(node(testTag = "a")))
    val delta = SemanticsDiff.diff(base, head)
    assertEquals(listOf("r/tag:b"), delta.removed.map { it.ref })
  }

  @Test
  fun lostContentDescriptionIsAFieldChange() {
    val base = node(children = listOf(node(role = "Button", label = "Submit")))
    val head = node(children = listOf(node(role = "Button", label = null)))
    val change = SemanticsDiff.diff(base, head).changed.single()
    assertEquals(SemanticsFieldChange("label", "Submit", null), change.changes.single())
  }

  @Test
  fun overflowFlagFlipIsReported() {
    val base = node(children = listOf(node(testTag = "t", layoutTruncated = false)))
    val head = node(children = listOf(node(testTag = "t", layoutTruncated = true)))
    val change = SemanticsDiff.diff(base, head).changed.single()
    assertEquals("layoutTruncated", change.changes.single().field)
  }

  @Test
  fun changeCarriesAnchorForHumanOutput() {
    val base = node(children = listOf(node(testTag = "submit", text = "Go")))
    val head = node(children = listOf(node(testTag = "submit", text = "Send")))
    assertEquals("tag:submit", SemanticsDiff.diff(base, head).changed.single().anchor)
  }

  @Test
  fun worksOnRawUnrefdTrees() {
    // Nodes built without running SemanticsRefs (ref == null) still diff correctly.
    val base = node(children = listOf(node(testTag = "x", text = "1")))
    val head = node(children = listOf(node(testTag = "x", text = "2")))
    assertFalse(SemanticsDiff.diff(base, head).isEmpty)
  }
}
