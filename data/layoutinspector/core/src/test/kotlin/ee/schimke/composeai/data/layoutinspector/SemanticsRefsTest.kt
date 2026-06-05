package ee.schimke.composeai.data.layoutinspector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SemanticsRefsTest {

  private fun node(
    role: String? = null,
    testTag: String? = null,
    text: String? = null,
    children: List<ComposeSemanticsNode> = emptyList(),
  ) =
    ComposeSemanticsNode(
      nodeId = "0",
      boundsInRoot = "0,0,10,10",
      role = role,
      testTag = testTag,
      text = text,
      children = children,
    )

  @Test
  fun rootGetsRootRef() {
    assertEquals("r", SemanticsRefs.assign(node()).ref)
  }

  @Test
  fun testTagIsTheStrongestAnchor() {
    val root = node(children = listOf(node(testTag = "submit")))
    val child = SemanticsRefs.assign(root).children.single()
    assertEquals("r/tag:submit", child.ref)
  }

  @Test
  fun roleAnchorsWhenNoTestTag() {
    val root = node(children = listOf(node(role = "Button")))
    assertEquals("r/role:Button", SemanticsRefs.assign(root).children.single().ref)
  }

  @Test
  fun genericAnchorWhenNoTagOrRole() {
    val root = node(children = listOf(node(text = "hi")))
    assertEquals("r/node", SemanticsRefs.assign(root).children.single().ref)
  }

  @Test
  fun siblingsSharingAnAnchorAreIndexed() {
    val root = node(children = listOf(node(role = "Button"), node(role = "Button")))
    val refs = SemanticsRefs.assign(root).children.map { it.ref }
    assertEquals(listOf("r/role:Button[0]", "r/role:Button[1]"), refs)
  }

  @Test
  fun loneAnchorIsNotIndexed() {
    val root = node(children = listOf(node(role = "Button"), node(role = "Text")))
    val refs = SemanticsRefs.assign(root).children.map { it.ref }
    assertEquals(listOf("r/role:Button", "r/role:Text"), refs)
  }

  @Test
  fun refIsContentIndependent() {
    val before = SemanticsRefs.assign(node(children = listOf(node(testTag = "t", text = "Hello"))))
    val after = SemanticsRefs.assign(node(children = listOf(node(testTag = "t", text = "Goodbye"))))
    assertEquals(before.children.single().ref, after.children.single().ref)
  }

  @Test
  fun changingTestTagMovesTheRef() {
    val before = SemanticsRefs.assign(node(children = listOf(node(testTag = "a"))))
    val after = SemanticsRefs.assign(node(children = listOf(node(testTag = "b"))))
    assertNotEquals(before.children.single().ref, after.children.single().ref)
  }

  @Test
  fun assignmentIsIdempotent() {
    val once = SemanticsRefs.assign(node(children = listOf(node(role = "Button"))))
    val twice = SemanticsRefs.assign(once)
    assertEquals(once, twice)
  }

  @Test
  fun pathSeparatorsInTagsAreSanitised() {
    val root = node(children = listOf(node(testTag = "nav/home tab")))
    assertEquals("r/tag:nav_home_tab", SemanticsRefs.assign(root).children.single().ref)
  }
}
