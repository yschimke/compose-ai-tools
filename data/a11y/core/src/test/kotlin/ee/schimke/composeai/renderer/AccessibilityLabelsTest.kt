package ee.schimke.composeai.renderer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Pure-JVM coverage of [AccessibilityLabels.rollUpMergedLabels] — the pass that gives a focus stop
 * the announcement its merged descendants carry (issue #4253). ATF itself needs a real `View`
 * graph, so the shapes it produces are written out here as the flat node list the walk emits.
 */
class AccessibilityLabelsTest {

  private fun node(
    label: String,
    role: String? = null,
    states: List<String> = emptyList(),
    merged: Boolean = true,
    bounds: String = "0,0,10,10",
  ) =
    AccessibilityNode(
      label = label,
      role = role,
      states = states,
      merged = merged,
      boundsInScreen = bounds,
    )

  @Test
  fun `blank stop takes the label of the descendants it merges`() {
    // A Wear `Button(icon = …, label = { Text("Filled") })`: the click lands on the merging
    // surface, the copy on an unmerged Text child.
    val rolled =
      AccessibilityLabels.rollUpMergedLabels(
        listOf(
          node("", states = listOf("clickable"), bounds = "16,16,209,120"),
          node("Filled", role = "TextView", merged = false, bounds = "104,50,181,86"),
        )
      )

    assertEquals(listOf("Filled", "Filled"), rolled.map { it.label })
    // Only the label moves — the stop keeps its own role, states and bounds.
    assertEquals(listOf("clickable"), rolled[0].states)
    assertEquals(null, rolled[0].role)
    assertEquals("16,16,209,120", rolled[0].boundsInScreen)
  }

  @Test
  fun `a stop that already announces itself is untouched`() {
    val nodes =
      listOf(
        node("Add to cart", role = "Button", states = listOf("clickable")),
        node("Add to cart", role = "TextView", merged = false),
      )

    assertSame(nodes, AccessibilityLabels.rollUpMergedLabels(nodes))
  }

  @Test
  fun `descendant copy is joined in traversal order`() {
    // A clickable row folding a title + subtitle into one announcement.
    val rolled =
      AccessibilityLabels.rollUpMergedLabels(
        listOf(
          node("", states = listOf("clickable")),
          node("Jetnews", merged = false),
          node("3 min read", merged = false),
        )
      )

    assertEquals("Jetnews 3 min read", rolled[0].label)
  }

  @Test
  fun `roll-up stops at the next focus stop`() {
    // Two sibling icon buttons: the second one's copy must not leak into the first.
    val rolled =
      AccessibilityLabels.rollUpMergedLabels(
        listOf(
          node("", states = listOf("clickable")),
          node("Open navigation drawer", merged = false),
          node("", states = listOf("clickable")),
          node("Search", merged = false),
        )
      )

    assertEquals(
      listOf("Open navigation drawer", "Open navigation drawer", "Search", "Search"),
      rolled.map { it.label },
    )
  }

  @Test
  fun `a stop with nothing under it stays unlabelled`() {
    // An unlabelled clickable really is unlabelled — that is a finding worth seeing.
    val rolled =
      AccessibilityLabels.rollUpMergedLabels(
        listOf(node("", role = "Button", states = listOf("clickable")), node("Next"))
      )

    assertEquals(listOf("", "Next"), rolled.map { it.label })
  }

  @Test
  fun `blank descendants contribute nothing`() {
    val rolled =
      AccessibilityLabels.rollUpMergedLabels(
        listOf(
          node("", states = listOf("clickable")),
          node("   ", merged = false),
          node("Play", merged = false),
        )
      )

    assertEquals("Play", rolled[0].label)
  }

  @Test
  fun `running twice changes nothing`() {
    val once =
      AccessibilityLabels.rollUpMergedLabels(
        listOf(node("", states = listOf("clickable")), node("Filled", merged = false))
      )

    assertEquals(once, AccessibilityLabels.rollUpMergedLabels(once))
  }

  @Test
  fun `an unmerged run before any stop is left alone`() {
    val nodes = listOf(node("Heading", merged = false), node("", states = listOf("clickable")))

    assertEquals(
      listOf("Heading", ""),
      AccessibilityLabels.rollUpMergedLabels(nodes).map { it.label },
    )
  }
}
