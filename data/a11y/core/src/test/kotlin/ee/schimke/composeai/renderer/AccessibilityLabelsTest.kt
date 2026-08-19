package ee.schimke.composeai.renderer

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-JVM coverage of [AccessibilityLabels.announcement] — what a screen reader says for an
 * element, given the copy it carries and the copy of the descendants it folds in (issue #4253).
 *
 * ATF's own `ViewHierarchyElement` needs a real `View` graph to construct, so the trees here are
 * hand-built through [AccessibilityLabels.Element]. That the shapes are the ones ATF really
 * produces is measured separately, against a rendered Wear button, by `:renderer-android`'s
 * `WearButtonA11yHierarchyProbeTest`.
 */
class AccessibilityLabelsTest {

  private class Node(
    override val ownLabel: String = "",
    override val mergesDescendants: Boolean = false,
    override val children: List<AccessibilityLabels.Element> = emptyList(),
  ) : AccessibilityLabels.Element

  @Test
  fun `a merging stop announces the copy of the child holding it`() {
    // A Wear `Button(icon = …, label = { Text("Filled") })`: the click and the focus stop are on
    // the merging surface, the word on a child. The shape issue #4253 reported.
    val button =
      Node(
        mergesDescendants = true,
        children = listOf(Node(/* the decorative icon */ ), Node(ownLabel = "Filled")),
      )

    assertEquals("Filled", AccessibilityLabels.announcement(button))
  }

  @Test
  fun `an element that carries its own copy keeps it`() {
    val button =
      Node(
        ownLabel = "Add to cart",
        mergesDescendants = true,
        children = listOf(Node(ownLabel = "Add to cart")),
      )

    assertEquals("Add to cart", AccessibilityLabels.announcement(button))
  }

  @Test
  fun `descendant copy is joined in traversal order`() {
    val row =
      Node(
        mergesDescendants = true,
        children =
          listOf(
            Node(children = listOf(Node(ownLabel = "Jetnews"))),
            Node(ownLabel = "3 min read"),
          ),
      )

    assertEquals("Jetnews 3 min read", AccessibilityLabels.announcement(row))
  }

  @Test
  fun `a nested stop's copy stays its own, and the walk carries on past it`() {
    // A row folding in a title, a button of its own, then a subtitle. The button announces "Go";
    // the subtitle after it is still the row's. Ending the walk at the nested stop would drop the
    // subtitle, descending into it would swallow "Go" into the row.
    val nested = Node(mergesDescendants = true, children = listOf(Node(ownLabel = "Go")))
    val row =
      Node(
        mergesDescendants = true,
        children = listOf(Node(ownLabel = "Title"), nested, Node(ownLabel = "Subtitle")),
      )

    assertEquals("Title Subtitle", AccessibilityLabels.announcement(row))
    assertEquals("Go", AccessibilityLabels.announcement(nested))
  }

  @Test
  fun `a nested stop is pruned by ancestry, not by where it is drawn`() {
    // The same shape with the nested stop covering the whole row — the case bounds cannot answer,
    // because the subtitle's rectangle falls inside the nested action's. Ancestry has no such
    // ambiguity: the subtitle is not underneath it.
    val fullBleed = Node(mergesDescendants = true, children = listOf(Node(ownLabel = "Dismiss")))
    val row =
      Node(
        mergesDescendants = true,
        children = listOf(Node(ownLabel = "Title"), fullBleed, Node(ownLabel = "Subtitle")),
      )

    assertEquals("Title Subtitle", AccessibilityLabels.announcement(row))
  }

  @Test
  fun `an element that merges nothing borrows nothing`() {
    // Not a merging element: its children are their own focus stops and it announces nothing of
    // theirs, however much copy sits underneath.
    val column = Node(children = listOf(Node(ownLabel = "First"), Node(ownLabel = "Second")))

    assertEquals("", AccessibilityLabels.announcement(column))
  }

  @Test
  fun `a stop with nothing under it stays unlabelled`() {
    // An unlabelled clickable really is unlabelled — that is a finding worth seeing.
    val iconButton = Node(mergesDescendants = true, children = listOf(Node()))

    assertEquals("", AccessibilityLabels.announcement(iconButton))
  }

  @Test
  fun `blank copy contributes nothing`() {
    val row =
      Node(
        ownLabel = "   ",
        mergesDescendants = true,
        children = listOf(Node(ownLabel = " "), Node(ownLabel = " Play ")),
      )

    assertEquals("Play", AccessibilityLabels.announcement(row))
  }

  @Test
  fun `copy nested several levels deep still rolls up`() {
    val card =
      Node(
        mergesDescendants = true,
        children =
          listOf(Node(children = listOf(Node(children = listOf(Node(ownLabel = "Buy now")))))),
      )

    assertEquals("Buy now", AccessibilityLabels.announcement(card))
  }
}
