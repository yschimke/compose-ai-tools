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
 * **Reconstructing "which nodes did this stop fold in" from a flat list** takes both signals the
 * wire carries, and neither alone is enough:
 * - *Emission order* — nodes come out in pre-order, so a stop's descendants are the nodes that
 *   follow it, up to the point the walk leaves its subtree. This is the rule
 *   `AccessibilityOverlay.groupNodes` and the VS Code bundle presenter already group on.
 * - *Bounds containment* — which says where that subtree ends, and which of the nodes inside it
 *   belong to a **nested** focus stop instead. A row that folds in a title, then a button of its
 *   own, then a subtitle announces "Title Subtitle": the button owns its own announcement, but the
 *   subtitle after it is still the row's. Stopping at the nested stop (order alone) would drop the
 *   subtitle; ignoring it would swallow the button's copy into the row.
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
   * The announcement the merged node at [index] gets from the descendants it folds in — the
   * following nodes that sit inside its bounds, minus those a nested focus stop announces itself,
   * in traversal order with blanks dropped.
   *
   * A node whose bounds don't parse is skipped rather than treated as the end of the subtree: it
   * describes nothing anyone can point at, and ending the scan there would silently truncate the
   * announcement. A **parent** whose bounds don't parse has no subtree to test against, so it falls
   * back to the plain following run.
   */
  fun mergedDescendantLabel(nodes: List<AccessibilityNode>, index: Int): String {
    val parent = parseBounds(nodes[index].boundsInScreen) ?: return followingRunLabel(nodes, index)
    val parts = mutableListOf<String>()
    // The nested focus stop currently being skipped over, if any — what sits inside it is part of
    // its announcement, not of ours.
    var nested: Bounds? = null
    for (i in index + 1 until nodes.size) {
      val node = nodes[i]
      val bounds = parseBounds(node.boundsInScreen) ?: continue
      // Out of the parent's box: the walk has left its subtree, and what follows belongs to
      // something else.
      if (!parent.contains(bounds)) break
      if (nested != null && nested.contains(bounds)) continue
      nested = null
      if (node.merged) {
        nested = bounds
        continue
      }
      node.label.trim().takeIf { it.isNotEmpty() }?.let { parts += it }
    }
    return parts.joinToString(" ")
  }

  /**
   * The plain following run of non-stops — the fallback for a parent whose bounds don't parse, and
   * so has no box to test its descendants against.
   */
  private fun followingRunLabel(nodes: List<AccessibilityNode>, index: Int): String {
    val parts = mutableListOf<String>()
    for (i in index + 1 until nodes.size) {
      if (nodes[i].merged) break
      nodes[i].label.trim().takeIf { it.isNotEmpty() }?.let { parts += it }
    }
    return parts.joinToString(" ")
  }

  private data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    fun contains(other: Bounds): Boolean =
      left <= other.left && top <= other.top && right >= other.right && bottom >= other.bottom
  }

  private fun parseBounds(wire: String): Bounds? {
    val parts = wire.split(',')
    if (parts.size != 4) return null
    val values = parts.map { it.trim().toIntOrNull() ?: return null }
    return Bounds(values[0], values[1], values[2], values[3])
  }
}
