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

  // --- nodeAt: pixel → strongest stable handle (issue #2047, record-live bridge) ---

  @Test
  fun nodeAtPrefersTestTag() {
    val root = node(children = listOf(node(bounds = "100,40,140,80", testTag = "submit")))
    assertEquals(SemanticsTarget.Tag("submit"), SemanticsTargets.nodeAt(root, 120, 60))
  }

  @Test
  fun nodeAtFallsBackToTextThenRef() {
    val textRoot = node(children = listOf(node(bounds = "0,0,50,20", text = "Save")))
    assertEquals(SemanticsTarget.RoleText(text = "Save"), SemanticsTargets.nodeAt(textRoot, 25, 10))

    // A node with no testTag/text/label but a real position still gets a replayable ref handle.
    val refRoot = node(children = listOf(node(bounds = "0,0,50,20", role = "Button")))
    val hit = SemanticsTargets.nodeAt(refRoot, 25, 10)
    assertTrue("expected a ref handle, got $hit", hit is SemanticsTarget.Ref)
  }

  @Test
  fun nodeAtPicksDeepestSmallestNodeUnderPoint() {
    // A small tagged child sits inside a larger tagged container; a click inside the child should
    // resolve to the child (smallest area), matching what a real pointer lands on.
    val root =
      node(
        bounds = "0,0,200,200",
        testTag = "screen",
        children =
          listOf(
            node(
              bounds = "0,0,200,100",
              testTag = "card",
              children = listOf(node(bounds = "10,10,40,40", testTag = "icon")),
            )
          ),
      )
    assertEquals(SemanticsTarget.Tag("icon"), SemanticsTargets.nodeAt(root, 25, 25))
    // A point inside the card but outside the icon resolves to the card.
    assertEquals(SemanticsTarget.Tag("card"), SemanticsTargets.nodeAt(root, 150, 50))
  }

  @Test
  fun nodeAtReturnsNullWhenNoTargetableNodeContainsThePoint() {
    val root = node(bounds = "0,0,200,200", children = listOf(node(bounds = "0,0,40,40")))
    // (180,180) is inside the bare root but no targetable node covers it.
    assertEquals(null, SemanticsTargets.nodeAt(root, 180, 180))
  }
}
