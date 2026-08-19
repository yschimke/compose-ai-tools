package ee.schimke.composeai.renderer

/**
 * Rolls a focus stop's announcement up from the descendants it merges (issue #4253).
 *
 * ATF describes each `ViewHierarchyElement` on its own terms: a merged focus stop reports the
 * `contentDescription` / `text` **it** carries, not the copy sitting on the children it folds into
 * one TalkBack announcement. For the shapes where the clickable surface and the copy are different
 * elements — a Wear `Button(icon = …, label = { Text("Filled") })`, an `IconButton` whose
 * `contentDescription` lives on the inner `Icon` — that leaves the stop with an empty `label` and
 * the copy stranded on an unmerged child that no screen reader stops on. Every consumer then reads
 * the stop as unlabelled: the inspection layer prints `(unlabelled)`, the legend falls back to the
 * role, and [TalkBackUtterance] speaks the state with no name in front of it.
 *
 * TalkBack announces "Filled, double-tap to activate" for that button, so that is what the node
 * should carry. The CMP side already resolves this at the source
 * (`DesktopAccessibilityNodeExtractor.effectiveLabel` walks the merged subtree); ATF gives us no
 * such subtree handle after the walk has flattened it, so the equivalent runs here as a pure pass
 * over the emitted list.
 *
 * **The grouping rule is emission order**, the same one `AccessibilityOverlay.groupNodes` and the
 * VS Code bundle presenter already use: nodes come out in pre-order, so the unmerged nodes
 * immediately following a merged one are exactly the descendants it folds in.
 */
object AccessibilityLabels {

  /**
   * Returns [nodes] with every blank-labelled focus stop given the announcement its merged
   * descendants supply, joined by `" "` in traversal order. Nodes that already carry a label are
   * untouched, and a stop whose descendants supply nothing stays blank — an unlabelled clickable
   * really is unlabelled, and that is a finding worth seeing, not one worth papering over.
   *
   * Pure and idempotent: running it twice yields the same list.
   */
  fun rollUpMergedLabels(nodes: List<AccessibilityNode>): List<AccessibilityNode> {
    if (nodes.none { it.merged && it.label.isBlank() }) return nodes
    return nodes.mapIndexed { index, node ->
      if (!node.merged || node.label.isNotBlank()) node
      else {
        val rolled = mergedDescendantLabel(nodes, index)
        if (rolled.isEmpty()) node else node.copy(label = rolled)
      }
    }
  }

  /**
   * The announcement the merged node at [index] gets from the run of unmerged nodes that follows it
   * — its descendants, in traversal order, blanks dropped.
   */
  fun mergedDescendantLabel(nodes: List<AccessibilityNode>, index: Int): String {
    val parts = mutableListOf<String>()
    var i = index + 1
    while (i < nodes.size && !nodes[i].merged) {
      nodes[i].label.trim().takeIf { it.isNotEmpty() }?.let { parts += it }
      i++
    }
    return parts.joinToString(" ")
  }
}
