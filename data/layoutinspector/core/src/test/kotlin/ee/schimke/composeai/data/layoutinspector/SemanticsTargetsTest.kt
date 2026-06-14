package ee.schimke.composeai.data.layoutinspector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SemanticsTargetsTest {

  private fun node(
    bounds: String = "0,0,10,10",
    role: String? = null,
    testTag: String? = null,
    text: String? = null,
    label: String? = null,
    children: List<ComposeSemanticsNode> = emptyList(),
  ) =
    ComposeSemanticsNode(
      nodeId = "0",
      boundsInRoot = bounds,
      role = role,
      testTag = testTag,
      text = text,
      label = label,
      children = children,
    )

  @Test
  fun resolvesTagToCentrePoint() {
    val root = node(children = listOf(node(bounds = "100,40,140,80", testTag = "submit")))
    val res = SemanticsTargets.resolve(root, SemanticsTarget.Tag("submit"))
    assertTrue(res is TargetResolution.Resolved)
    val resolved = res as TargetResolution.Resolved
    assertEquals(SemanticsPoint(120, 60), resolved.point)
    assertEquals("submit", resolved.node.testTag)
  }

  @Test
  fun resolvesByRef() {
    val root = node(children = listOf(node(role = "Button")))
    val res = SemanticsTargets.resolve(root, SemanticsTarget.Ref("r/role:Button"))
    assertTrue(res is TargetResolution.Resolved)
  }

  @Test
  fun missingTargetIsNotFound() {
    val root = node(children = listOf(node(testTag = "submit")))
    assertEquals(
      TargetResolution.NotFound,
      SemanticsTargets.resolve(root, SemanticsTarget.Tag("cancel")),
    )
  }

  @Test
  fun duplicateTagsAreAmbiguous() {
    val root = node(children = listOf(node(testTag = "row"), node(testTag = "row")))
    val res = SemanticsTargets.resolve(root, SemanticsTarget.Tag("row"))
    assertTrue(res is TargetResolution.Ambiguous)
    assertEquals(2, (res as TargetResolution.Ambiguous).candidates.size)
  }

  @Test
  fun roleAndTextNarrowsToOne() {
    val root =
      node(
        children =
          listOf(node(role = "Button", text = "Save"), node(role = "Button", text = "Cancel"))
      )
    val res =
      SemanticsTargets.resolve(root, SemanticsTarget.RoleText(role = "Button", text = "cancel"))
    assertTrue(res is TargetResolution.Resolved)
    assertEquals("Cancel", (res as TargetResolution.Resolved).node.text)
  }

  @Test
  fun textMatchesAccessibleLabel() {
    val root = node(children = listOf(node(role = "Button", label = "Add to cart")))
    val res = SemanticsTargets.resolve(root, SemanticsTarget.RoleText(text = "add to cart"))
    assertTrue(res is TargetResolution.Resolved)
  }

  @Test
  fun targetableNodesListsHandleBearingNodesWithRefs() {
    val root =
      node(
        children =
          listOf(
            node(testTag = "submit", role = "Button", text = "Submit"),
            node(role = "Button", text = "Cancel"),
            node(), // bare container — no testTag/role/text/label, not clickable
          )
      )
    val candidates = SemanticsTargets.targetableNodes(root)
    // The bare container drops out; the two buttons stay, each carrying an assigned ref.
    assertEquals(2, candidates.size)
    assertTrue(candidates.all { it.ref != null })
    // testTag-bearing nodes sort first so the agent sees the strongest handle up top.
    assertEquals("submit", candidates.first().testTag)
  }

  @Test
  fun targetableNodesRespectsLimit() {
    val root = node(children = (1..10).map { node(role = "Button", text = "B$it") })
    assertEquals(3, SemanticsTargets.targetableNodes(root, limit = 3).size)
  }
}
