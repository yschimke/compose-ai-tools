package ee.schimke.composeai.screen

import ee.schimke.composeai.discovery.ScreenDocument
import ee.schimke.composeai.discovery.ScreenNode
import ee.schimke.composeai.discovery.ScreenValue

/**
 * The edits a builder makes to a [ScreenDocument] — add into a slot, remove, set an argument.
 *
 * Pure functions over an immutable document: a builder's undo history is then the list of documents
 * it has produced, and every operation is testable without a composition.
 *
 * Nodes are addressed by **pre-order index**, walking each node's slots in declaration order. That
 * ordering is the contract between selecting a node, editing it and rendering it, so it is derived
 * here once rather than by each of them. An edit that changes the tree's shape renumbers everything
 * after it, which callers must expect rather than caching an index across edits.
 */

/** A node with its pre-order [index], its parent's, and the slot of its parent it sits in. */
public data class IndexedNode(
  val index: Int,
  val node: ScreenNode,
  val parentIndex: Int?,
  val slot: String?,
)

/** Every node of the document in pre-order, root first. */
public fun ScreenDocument.flattenNodes(): List<IndexedNode> {
  val out = ArrayList<IndexedNode>()
  fun walk(node: ScreenNode, parent: Int?, slot: String?) {
    val index = out.size
    out.add(IndexedNode(index, node, parent, slot))
    node.slots.forEach { (slotName, children) -> children.forEach { walk(it, index, slotName) } }
  }
  walk(root, null, null)
  return out
}

/** The node at [index], or null — a selection outlives the edit that invalidates it. */
public fun ScreenDocument.nodeAt(index: Int): ScreenNode? = flattenNodes().getOrNull(index)?.node

/** [transform] applied to the node at [index]; unchanged when there is none. */
public fun ScreenDocument.mapNodeAt(
  index: Int,
  transform: (ScreenNode) -> ScreenNode,
): ScreenDocument {
  var seen = -1
  fun walk(node: ScreenNode): ScreenNode {
    seen++
    val here = seen
    val slots = node.slots.mapValues { (_, children) -> children.map { walk(it) } }
    val rebuilt = if (slots == node.slots) node else node.copy(slots = slots)
    return if (here == index) transform(rebuilt) else rebuilt
  }
  return copy(root = walk(root))
}

/** [child] appended to [slot] of the node at [parentIndex]. */
public fun ScreenDocument.addNode(
  parentIndex: Int,
  slot: String,
  child: ScreenNode,
): ScreenDocument =
  mapNodeAt(parentIndex) { node ->
    node.copy(slots = node.slots + (slot to ((node.slots[slot] ?: emptyList()) + child)))
  }

/**
 * The node at [index] and its subtree removed.
 *
 * The root is never removed — a document with no root is not a document, and the builder's "clear"
 * is a new document rather than an empty one.
 */
public fun ScreenDocument.removeNode(index: Int): ScreenDocument {
  if (index == 0) return this
  var seen = -1
  // Children are walked **before** the node is tested, so the subtree's indices are consumed
  // whether or not it survives — which is what keeps this numbering identical to `flattenNodes`.
  fun walk(node: ScreenNode): ScreenNode? {
    seen++
    val here = seen
    val slots =
      node.slots
        .mapValues { (_, children) -> children.mapNotNull { walk(it) } }
        .filterValues { it.isNotEmpty() }
    if (here == index) return null
    return if (slots == node.slots) node else node.copy(slots = slots)
  }
  return copy(root = walk(root) ?: root)
}

/** [value] set on [name] at [index]; a null [value] clears the argument. */
public fun ScreenDocument.setArgument(
  index: Int,
  name: String,
  value: ScreenValue?,
): ScreenDocument =
  mapNodeAt(index) { node ->
    node.copy(
      arguments = if (value == null) node.arguments - name else node.arguments + (name to value)
    )
  }
