package ee.schimke.composeai.renderer.uiautomator

/**
 * Pure selector-builder for the `uia/hierarchy` data product (#1059). Given one node from a
 * hierarchy snapshot plus the full node list, return a [Selector] / source-snippet that uniquely
 * targets the node — the same logic the VS Code extension's `uiaSelector.ts` mirrors for its
 * "Copy as selector" row action, factored out here so MCP / record-script paths can ship the same
 * snippets without re-implementing the rules.
 *
 * # Priority
 *
 * 1. `testTag` when present and unique across [nodes].
 * 2. `text` when present and unique.
 * 3. `contentDescription` when present and unique.
 * 4. Best non-unique anchor (`testTag` ▶ `text` ▶ `contentDescription`) plus the nearest
 *    [UiAutomatorHierarchyNode.testTagAncestors] entry that disambiguates — chained as
 *    `hasParent(By.testTag(...))`. We walk ancestors nearest → root-most because the narrowest
 *    scope is the smallest selector that still locates the node.
 *
 * Returns `null` when every axis is blank — the node has nothing distinguishing to anchor against.
 * Callers should fall back to a UI hint rather than emitting a useless `By` chain. Mirrors the TS
 * implementation's null return so the two surfaces stay in lockstep.
 */
public object HierarchySelectorBuilder {

  /**
   * Build a runnable [Selector] for [node] from the [nodes] population it lives in. Used by paths
   * that want the structured selector (record-script events, MCP). Roughly the same as
   * [buildSelectorSnippet] but returns the structured form rather than a Kotlin-source string.
   */
  public fun buildSelector(
    node: UiAutomatorHierarchyNode,
    nodes: List<UiAutomatorHierarchyNode>,
  ): Selector? {
    val tag = node.testTag.nonBlank()
    val text = node.text.nonBlank()
    val desc = node.contentDescription.nonBlank()

    if (tag != null && nodes.count { it.testTag.nonBlank() == tag } == 1) {
      return Selector(res = TextMatch.Exact(tag))
    }
    if (text != null && nodes.count { it.text.nonBlank() == text } == 1) {
      return Selector(text = TextMatch.Exact(text))
    }
    if (desc != null && nodes.count { it.contentDescription.nonBlank() == desc } == 1) {
      return Selector(desc = TextMatch.Exact(desc))
    }

    val anchor = pickAnchor(node) ?: return null
    val anchorSel = anchor.toSelector()
    // Walk ancestors nearest → root-most. testTagAncestors is stored root-most → nearest, so
    // iterate in reverse to find the smallest scope that disambiguates.
    for (i in node.testTagAncestors.indices.reversed()) {
      val parentTag = node.testTagAncestors[i].nonBlank() ?: continue
      val matchCount =
        nodes.count { other ->
          anchor.matches(other) &&
            other.testTagAncestors.any { it.nonBlank() == parentTag }
        }
      if (matchCount == 1) {
        return anchorSel.copy(children = listOf(Selector(res = TextMatch.Exact(parentTag))))
      }
    }
    // No ancestor disambiguates either — return the anchor alone so callers have somewhere to
    // start, matching the TS fallback. The caller can refine further when the snippet is
    // visibly ambiguous.
    return anchorSel
  }

  /**
   * Build a Kotlin-source selector snippet for [node]. Mirror of `buildSelectorSnippet` in
   * `vscode-extension/src/webview/preview/uiaSelector.ts`. Stable, copy-paste-friendly format —
   * `By.testTag("...")` / `By.text("...")` / `By.desc("...")`, optionally chained with
   * `.hasParent(By.testTag("..."))`.
   */
  public fun buildSelectorSnippet(
    node: UiAutomatorHierarchyNode,
    nodes: List<UiAutomatorHierarchyNode>,
  ): String? {
    val tag = node.testTag.nonBlank()
    val text = node.text.nonBlank()
    val desc = node.contentDescription.nonBlank()

    if (tag != null && nodes.count { it.testTag.nonBlank() == tag } == 1) {
      return "By.testTag(${quote(tag)})"
    }
    if (text != null && nodes.count { it.text.nonBlank() == text } == 1) {
      return "By.text(${quote(text)})"
    }
    if (desc != null && nodes.count { it.contentDescription.nonBlank() == desc } == 1) {
      return "By.desc(${quote(desc)})"
    }

    val anchor = pickAnchor(node) ?: return null
    for (i in node.testTagAncestors.indices.reversed()) {
      val parentTag = node.testTagAncestors[i].nonBlank() ?: continue
      val matchCount =
        nodes.count { other ->
          anchor.matches(other) &&
            other.testTagAncestors.any { it.nonBlank() == parentTag }
        }
      if (matchCount == 1) {
        return "${anchor.render()}.hasParent(By.testTag(${quote(parentTag)}))"
      }
    }
    return anchor.render()
  }

  private fun pickAnchor(node: UiAutomatorHierarchyNode): Anchor? {
    node.testTag.nonBlank()?.let {
      return Anchor.Tag(it)
    }
    node.text.nonBlank()?.let {
      return Anchor.Text(it)
    }
    node.contentDescription.nonBlank()?.let {
      return Anchor.Desc(it)
    }
    return null
  }

  private sealed class Anchor {
    abstract val value: String

    abstract fun render(): String

    abstract fun matches(node: UiAutomatorHierarchyNode): Boolean

    abstract fun toSelector(): Selector

    data class Tag(override val value: String) : Anchor() {
      override fun render(): String = "By.testTag(${quote(value)})"

      override fun matches(node: UiAutomatorHierarchyNode): Boolean =
        node.testTag.nonBlank() == value

      override fun toSelector(): Selector = Selector(res = TextMatch.Exact(value))
    }

    data class Text(override val value: String) : Anchor() {
      override fun render(): String = "By.text(${quote(value)})"

      override fun matches(node: UiAutomatorHierarchyNode): Boolean =
        node.text.nonBlank() == value

      override fun toSelector(): Selector = Selector(text = TextMatch.Exact(value))
    }

    data class Desc(override val value: String) : Anchor() {
      override fun render(): String = "By.desc(${quote(value)})"

      override fun matches(node: UiAutomatorHierarchyNode): Boolean =
        node.contentDescription.nonBlank() == value

      override fun toSelector(): Selector = Selector(desc = TextMatch.Exact(value))
    }
  }
}

private fun String?.nonBlank(): String? = this?.takeIf { it.isNotEmpty() }

private fun quote(s: String): String {
  val escaped =
    s.replace("\\", "\\\\")
      .replace("\"", "\\\"")
      .replace("\n", "\\n")
      .replace("\r", "\\r")
      .replace("\t", "\\t")
  return "\"$escaped\""
}
