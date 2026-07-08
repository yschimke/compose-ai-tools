package ee.schimke.composeai.renderer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Coverage for [TalkBackTraversal] (issue #1956 Phase 3): focus-stop filtering, deterministic
 * next/previous moves in traversal order, cold-start entry, and non-wrapping boundaries.
 */
class TalkBackTraversalTest {

  private fun node(ref: String, merged: Boolean = true): AccessibilityNode =
    AccessibilityNode(label = ref, ref = ref, merged = merged, boundsInScreen = "0,0,10,10")

  // A header (stop), a clickable card (stop) with two merged-away text children (not stops),
  // and a button (stop). Focus stops in order: header, card, button.
  private val tree =
    listOf(
      node("a/role:Header[0]"),
      node("a/role:ViewGroup[0]"),
      node("a/role:TextView[0]", merged = false),
      node("a/role:TextView[1]", merged = false),
      node("a/role:Button[0]"),
    )

  @Test
  fun focusStopsAreTheMergedNodesInOrder() {
    assertEquals(
      listOf("a/role:Header[0]", "a/role:ViewGroup[0]", "a/role:Button[0]"),
      TalkBackTraversal.focusStops(tree).map { it.ref },
    )
  }

  @Test
  fun nextFromNoFocusEntersAtFirstStop() {
    assertEquals("a/role:Header[0]", TalkBackTraversal.next(tree, null)?.ref)
  }

  @Test
  fun previousFromNoFocusEntersAtLastStop() {
    assertEquals("a/role:Button[0]", TalkBackTraversal.previous(tree, null)?.ref)
  }

  @Test
  fun nextWalksForwardSkippingUnmergedChildren() {
    assertEquals("a/role:ViewGroup[0]", TalkBackTraversal.next(tree, "a/role:Header[0]")?.ref)
    assertEquals("a/role:Button[0]", TalkBackTraversal.next(tree, "a/role:ViewGroup[0]")?.ref)
  }

  @Test
  fun previousWalksBackward() {
    assertEquals("a/role:ViewGroup[0]", TalkBackTraversal.previous(tree, "a/role:Button[0]")?.ref)
    assertEquals("a/role:Header[0]", TalkBackTraversal.previous(tree, "a/role:ViewGroup[0]")?.ref)
  }

  @Test
  fun nextPastLastStopDoesNotWrap() {
    assertNull(TalkBackTraversal.next(tree, "a/role:Button[0]"))
  }

  @Test
  fun previousBeforeFirstStopDoesNotWrap() {
    assertNull(TalkBackTraversal.previous(tree, "a/role:Header[0]"))
  }

  @Test
  fun unresolvedRefReEntersAtBoundary() {
    // The focused node was removed / re-keyed: navigation re-enters rather than dead-ending.
    assertEquals("a/role:Header[0]", TalkBackTraversal.next(tree, "a/gone[9]")?.ref)
    assertEquals("a/role:Button[0]", TalkBackTraversal.previous(tree, "a/gone[9]")?.ref)
  }

  @Test
  fun currentResolvesFocusedStopAndIgnoresUnmergedRefs() {
    assertEquals("a/role:ViewGroup[0]", TalkBackTraversal.current(tree, "a/role:ViewGroup[0]")?.ref)
    // An unmerged child ref is not a focus stop, so it never resolves as "current".
    assertNull(TalkBackTraversal.current(tree, "a/role:TextView[0]"))
    assertNull(TalkBackTraversal.current(tree, null))
  }

  @Test
  fun emptyAndStoplessTreesYieldNothing() {
    assertNull(TalkBackTraversal.next(emptyList(), null))
    val noStops = listOf(node("x", merged = false))
    assertNull(TalkBackTraversal.first(noStops))
    assertNull(TalkBackTraversal.next(noStops, null))
  }
}
