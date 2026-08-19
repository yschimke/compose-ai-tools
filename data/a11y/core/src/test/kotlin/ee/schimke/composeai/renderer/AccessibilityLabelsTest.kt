package ee.schimke.composeai.renderer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Pure-JVM coverage of [AccessibilityLabels.rollUpMergedLabels] — the pass that gives a focus stop
 * the announcement its merged descendants carry (issue #4253). ATF itself needs a real `View`
 * graph, so the shapes it produces are written out here as the flat node list the walk emits,
 * bounds and all: which nodes a stop folded in is reconstructed from emission order **and** those
 * bounds.
 */
class AccessibilityLabelsTest {

  private fun node(
    label: String,
    bounds: String,
    role: String? = null,
    states: List<String> = emptyList(),
    merged: Boolean = true,
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
    // surface, the copy on an unmerged Text child. The hierarchy issue #4253 reported, verbatim.
    val rolled =
      AccessibilityLabels.rollUpMergedLabels(
        listOf(
          node("", "16,16,209,120", states = listOf("clickable")),
          node("Filled", "104,50,181,86", role = "TextView", merged = false),
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
        node("Add to cart", "0,0,200,80", role = "Button", states = listOf("clickable")),
        node("Add to cart", "20,20,180,60", role = "TextView", merged = false),
      )

    assertSame(nodes, AccessibilityLabels.rollUpMergedLabels(nodes))
  }

  @Test
  fun `descendant copy is joined in traversal order`() {
    // A clickable row folding a title + subtitle into one announcement.
    val rolled =
      AccessibilityLabels.rollUpMergedLabels(
        listOf(
          node("", "0,0,400,100", states = listOf("clickable")),
          node("Jetnews", "8,8,200,40", merged = false),
          node("3 min read", "8,50,200,90", merged = false),
        )
      )

    assertEquals("Jetnews 3 min read", rolled[0].label)
  }

  @Test
  fun `roll-up carries on past a nested focus stop`() {
    // A row that folds in a title, then a button of its own, then a subtitle: the button owns its
    // announcement ("Go"), but the subtitle after it is still the row's. Stopping at the nested
    // stop would drop the subtitle; ignoring it would swallow "Go" into the row.
    val rolled =
      AccessibilityLabels.rollUpMergedLabels(
        listOf(
          node("", "0,0,400,100", states = listOf("clickable")),
          node("Title", "8,8,200,40", merged = false),
          node("", "300,10,390,90", states = listOf("clickable")),
          node("Go", "310,20,380,80", role = "TextView", merged = false),
          node("Subtitle", "8,50,200,90", merged = false),
        )
      )

    assertEquals("Title Subtitle", rolled[0].label)
    assertEquals("Go", rolled[2].label)
  }

  @Test
  fun `roll-up stops when the walk leaves the stop's box`() {
    // Two sibling icon buttons: the second one's copy must not leak into the first.
    val rolled =
      AccessibilityLabels.rollUpMergedLabels(
        listOf(
          node("", "0,0,48,48", states = listOf("clickable")),
          node("Open navigation drawer", "8,8,40,40", merged = false),
          node("", "60,0,108,48", states = listOf("clickable")),
          node("Search", "68,8,100,40", merged = false),
        )
      )

    assertEquals(
      listOf("Open navigation drawer", "Open navigation drawer", "Search", "Search"),
      rolled.map { it.label },
    )
  }

  @Test
  fun `copy outside the stop's box is not its own`() {
    // The unmerged node after the stop sits beside it, not inside it — a sibling text run the walk
    // reached after leaving the button, and not something the button announces.
    val rolled =
      AccessibilityLabels.rollUpMergedLabels(
        listOf(
          node("", "0,0,48,48", role = "Button", states = listOf("clickable")),
          node("Caption", "0,60,200,90", merged = false),
        )
      )

    assertEquals(listOf("", "Caption"), rolled.map { it.label })
  }

  @Test
  fun `a stop with nothing under it stays unlabelled`() {
    // An unlabelled clickable really is unlabelled — that is a finding worth seeing.
    val rolled =
      AccessibilityLabels.rollUpMergedLabels(
        listOf(
          node("", "0,0,48,48", role = "Button", states = listOf("clickable")),
          node("Next", "0,60,120,90"),
        )
      )

    assertEquals(listOf("", "Next"), rolled.map { it.label })
  }

  @Test
  fun `blank descendants contribute nothing`() {
    val rolled =
      AccessibilityLabels.rollUpMergedLabels(
        listOf(
          node("", "0,0,200,80", states = listOf("clickable")),
          node("   ", "8,8,60,40", merged = false),
          node("Play", "70,8,190,40", merged = false),
        )
      )

    assertEquals("Play", rolled[0].label)
  }

  @Test
  fun `a descendant with unreadable bounds does not truncate the announcement`() {
    val rolled =
      AccessibilityLabels.rollUpMergedLabels(
        listOf(
          node("", "0,0,400,100", states = listOf("clickable")),
          node("Title", "not,a,box", merged = false),
          node("Subtitle", "8,50,200,90", merged = false),
        )
      )

    assertEquals("Subtitle", rolled[0].label)
  }

  @Test
  fun `a stop with unreadable bounds falls back to the following run`() {
    val rolled =
      AccessibilityLabels.rollUpMergedLabels(
        listOf(
          node("", "", states = listOf("clickable")),
          node("Filled", "104,50,181,86", merged = false),
          node("Elsewhere", "0,200,100,260"),
        )
      )

    assertEquals(listOf("Filled", "Filled", "Elsewhere"), rolled.map { it.label })
  }

  @Test
  fun `running twice changes nothing`() {
    val once =
      AccessibilityLabels.rollUpMergedLabels(
        listOf(
          node("", "16,16,209,120", states = listOf("clickable")),
          node("Filled", "104,50,181,86", merged = false),
        )
      )

    assertEquals(once, AccessibilityLabels.rollUpMergedLabels(once))
  }

  @Test
  fun `an unmerged run before any stop is left alone`() {
    val nodes =
      listOf(
        node("Heading", "0,0,200,40", merged = false),
        node("", "0,50,48,98", role = "Button", states = listOf("clickable")),
      )

    assertEquals(
      listOf("Heading", ""),
      AccessibilityLabels.rollUpMergedLabels(nodes).map { it.label },
    )
  }
}
