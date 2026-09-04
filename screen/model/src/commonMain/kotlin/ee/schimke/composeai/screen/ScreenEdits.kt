package ee.schimke.composeai.screen

/**
 * The edits a builder makes to a [Screen] — add, select, retitle, move, delete.
 *
 * Pure functions over an immutable document, deliberately: a builder's undo history is then just
 * the list of documents it has produced, and every operation is testable without a composition. The
 * UI holds one `Screen` in state and replaces it; nothing else has to be reasoned about.
 *
 * Every index here is the **pre-order index** [flatten] assigns, which is the same index the knob
 * seeds are keyed by. That is what keeps a selection, a knob value and a rendered node talking
 * about the same thing — and it is also why an edit that changes the tree's shape renumbers
 * everything after it, which callers must expect rather than caching an index across edits.
 */

/** [Screen] with [child] appended to the node at [parentIndex], or to the roots when null. */
public fun Screen.addNode(parentIndex: Int?, child: ScreenNode): Screen =
  if (parentIndex == null) copy(roots = roots + child)
  else mapNode(parentIndex) { it.copy(children = it.children + child) }

/** [Screen] with [transform] applied to the node at [index]; unchanged if there is none. */
public fun Screen.mapNode(index: Int, transform: (ScreenNode) -> ScreenNode): Screen {
  var seen = -1
  fun walk(node: ScreenNode): ScreenNode {
    seen++
    val here = seen
    val children = node.children.map { walk(it) }
    val rebuilt = if (children == node.children) node else node.copy(children = children)
    return if (here == index) transform(rebuilt) else rebuilt
  }
  return copy(roots = roots.map { walk(it) })
}

/** [Screen] with the node at [index] — and its whole subtree — removed. */
public fun Screen.removeNode(index: Int): Screen {
  var seen = -1
  fun prune(nodes: List<ScreenNode>): List<ScreenNode> = buildList {
    nodes.forEach { node ->
      seen++
      val here = seen
      val children = prune(node.children)
      // The subtree's indices are consumed by the recursion above whether or not it survives, so a
      // removal does not shift the numbering of the nodes it walks past — only of the ones after.
      if (here != index)
        add(if (children == node.children) node else node.copy(children = children))
    }
  }
  return copy(roots = prune(roots))
}

/** [Screen] with [key] set to [value] on the node at [index]; a blank [value] clears the knob. */
public fun Screen.setKnob(index: Int, key: String, value: String): Screen =
  mapNode(index) { node ->
    val knobs = if (value.isEmpty()) node.knobs - key else node.knobs + (key to value)
    node.copy(knobs = knobs)
  }

/**
 * The node at [index], or null.
 *
 * Returns the node rather than throwing because a builder's selection outlives the edit that
 * invalidates it: deleting a node leaves a selection pointing past the end, and a null is something
 * the UI can render as "nothing selected" rather than a crash the user caused by deleting a row.
 */
public fun Screen.nodeAt(index: Int): ScreenNode? = flatten().getOrNull(index)?.node
