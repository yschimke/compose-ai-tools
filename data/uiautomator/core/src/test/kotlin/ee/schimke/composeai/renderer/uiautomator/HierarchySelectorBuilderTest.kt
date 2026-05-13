package ee.schimke.composeai.renderer.uiautomator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HierarchySelectorBuilderTest {

  private fun node(
    text: String? = null,
    contentDescription: String? = null,
    testTag: String? = null,
    testTagAncestors: List<String> = emptyList(),
    role: String? = null,
    actions: List<String> = emptyList(),
    bounds: String = "0,0,100,100",
  ): UiAutomatorHierarchyNode =
    UiAutomatorHierarchyNode(
      text = text,
      contentDescription = contentDescription,
      testTag = testTag,
      testTagAncestors = testTagAncestors,
      role = role,
      actions = actions,
      boundsInScreen = bounds,
    )

  @Test
  fun `unique testTag wins`() {
    val target = node(testTag = "submit", text = "Submit")
    val nodes = listOf(target, node(testTag = "cancel", text = "Cancel"))
    assertEquals(
      "By.testTag(\"submit\")",
      HierarchySelectorBuilder.buildSelectorSnippet(target, nodes),
    )
  }

  @Test
  fun `falls through to text when testTag is blank`() {
    val target = node(text = "Submit")
    val nodes = listOf(target, node(text = "Cancel"))
    assertEquals(
      "By.text(\"Submit\")",
      HierarchySelectorBuilder.buildSelectorSnippet(target, nodes),
    )
  }

  @Test
  fun `falls through to contentDescription when text is missing too`() {
    val target = node(contentDescription = "Submit form")
    val nodes = listOf(target, node(contentDescription = "Cancel form"))
    assertEquals(
      "By.desc(\"Submit form\")",
      HierarchySelectorBuilder.buildSelectorSnippet(target, nodes),
    )
  }

  @Test
  fun `non-unique text chains to nearest disambiguating ancestor`() {
    val a =
      node(
        text = "Item",
        testTagAncestors = listOf("screen", "list-A"),
      )
    val b =
      node(
        text = "Item",
        testTagAncestors = listOf("screen", "list-B"),
      )
    val snippet = HierarchySelectorBuilder.buildSelectorSnippet(a, listOf(a, b))
    assertEquals("By.text(\"Item\").hasParent(By.testTag(\"list-A\"))", snippet)
  }

  @Test
  fun `nearest ancestor is preferred over root-most`() {
    // The "screen" ancestor doesn't disambiguate (both rows share it).
    // The "list-A" ancestor does. Builder should pick the nearest match.
    val a = node(text = "Item", testTagAncestors = listOf("screen", "list-A"))
    val b = node(text = "Item", testTagAncestors = listOf("screen", "list-B"))
    val snippet = HierarchySelectorBuilder.buildSelectorSnippet(a, listOf(a, b))
    assertEquals("By.text(\"Item\").hasParent(By.testTag(\"list-A\"))", snippet)
  }

  @Test
  fun `blank ancestors are skipped during walk`() {
    val a =
      node(
        text = "Item",
        testTagAncestors = listOf("screen", "", "list-A"),
      )
    val b =
      node(
        text = "Item",
        testTagAncestors = listOf("screen", "", "list-B"),
      )
    val snippet = HierarchySelectorBuilder.buildSelectorSnippet(a, listOf(a, b))
    assertEquals("By.text(\"Item\").hasParent(By.testTag(\"list-A\"))", snippet)
  }

  @Test
  fun `returns null when every axis is blank`() {
    val target = node()
    assertNull(HierarchySelectorBuilder.buildSelectorSnippet(target, listOf(target)))
  }

  @Test
  fun `returns bare anchor when no ancestor disambiguates`() {
    val a = node(text = "Item")
    val b = node(text = "Item")
    val snippet = HierarchySelectorBuilder.buildSelectorSnippet(a, listOf(a, b))
    // Bare anchor — caller can refine, but we don't fabricate a fake chain.
    assertEquals("By.text(\"Item\")", snippet)
  }

  @Test
  fun `quote escapes special characters`() {
    val target = node(text = "Line 1\nLine 2 with \"quotes\"")
    val snippet = HierarchySelectorBuilder.buildSelectorSnippet(target, listOf(target))
    assertEquals(
      "By.text(\"Line 1\\nLine 2 with \\\"quotes\\\"\")",
      snippet,
    )
  }

  @Test
  fun `buildSelector returns structured Selector mirroring snippet`() {
    val target = node(testTag = "submit")
    val sel = HierarchySelectorBuilder.buildSelector(target, listOf(target))
    assertEquals(Selector(res = TextMatch.Exact("submit")), sel)
  }

  @Test
  fun `buildSelector ancestor chain matches snippet shape`() {
    val a = node(text = "Item", testTagAncestors = listOf("list-A"))
    val b = node(text = "Item", testTagAncestors = listOf("list-B"))
    val sel = HierarchySelectorBuilder.buildSelector(a, listOf(a, b))
    assertEquals(
      Selector(
        text = TextMatch.Exact("Item"),
        children = listOf(Selector(res = TextMatch.Exact("list-A"))),
      ),
      sel,
    )
  }
}
