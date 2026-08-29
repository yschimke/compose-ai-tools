package ee.schimke.composeai.data.layoutinspector

import ee.schimke.composeai.daemon.protocol.SemanticsDelta
import ee.schimke.composeai.daemon.protocol.SemanticsFieldChange
import ee.schimke.composeai.daemon.protocol.SemanticsNodeChange
import ee.schimke.composeai.daemon.protocol.SemanticsNodeSummary

/**
 * A structural, content-aware diff of two [ComposeSemanticsPayload] trees (issue #1785).
 *
 * This is the Compose analogue of Playwright's `toMatchAriaSnapshot` text diff: a cheap,
 * deterministic regression signal that says *what changed semantically* ("Button 'Submit' lost its
 * label", "text 'Hello' → 'Helloo'") without reading pixels. Nodes are matched by their stable
 * [ComposeSemanticsNode.ref] (assigned by [SemanticsRefs]), so a copy edit reports as a field
 * change on the same ref rather than a remove + add.
 *
 * Positional fields ([ComposeSemanticsNode.boundsInRoot]) and the volatile per-render
 * [ComposeSemanticsNode.nodeId] are deliberately ignored — bounds churn is the pixel diff's job.
 */
public object SemanticsDiff {

  /** Semantic fields compared between two nodes sharing a ref, in stable report order. */
  private val COMPARED_FIELDS: List<Pair<String, (ComposeSemanticsNode) -> String?>> =
    listOf(
      "role" to { it.role },
      "testTag" to { it.testTag },
      "label" to { it.label },
      "text" to { it.text },
      "mergeMode" to { it.mergeMode },
      "clickable" to { it.clickable.toString() },
      // Deliberately COMPARED rather than pruned, unlike every drawing/targeting consumer of this
      // tree. Those answer "what is on the frame?", so a subtree a `SubcomposeLayout` measured and
      // never placed is noise to them. A diff answers "what changed?", and a node leaving the frame
      // is the most interesting thing that can happen to it — pruning would report that as no
      // change at all when `placed` is the only field that moved. Reporting it as a field change
      // keeps the ref in the map and names what happened. See `ComposeSemanticsNode.placed`.
      "placed" to { it.placed.toString() },
      "editableText" to { it.editableText },
      "inputText" to { it.inputText },
      "layoutTruncated" to { it.textOverflow?.truncated?.toString() },
      "layoutOverflow" to { it.textOverflow?.overflow },
      "layoutLineCount" to { it.textOverflow?.lineCount?.toString() },
      "layoutMaxLines" to { it.textOverflow?.maxLines?.toString() },
      "layoutDidOverflowWidth" to { it.textOverflow?.didOverflowWidth?.toString() },
      "layoutDidOverflowHeight" to { it.textOverflow?.didOverflowHeight?.toString() },
    )

  public fun diff(base: ComposeSemanticsPayload, head: ComposeSemanticsPayload): SemanticsDelta =
    diff(base.root, head.root)

  public fun diff(base: ComposeSemanticsNode, head: ComposeSemanticsNode): SemanticsDelta {
    val baseByRef = SemanticsRefs.assign(base).byRef()
    val headByRef = SemanticsRefs.assign(head).byRef()

    val removed =
      baseByRef.keys.filter { it !in headByRef }.sorted().map { baseByRef.getValue(it).summary() }
    val added =
      headByRef.keys.filter { it !in baseByRef }.sorted().map { headByRef.getValue(it).summary() }
    val changed =
      baseByRef.keys
        .filter { it in headByRef }
        .sorted()
        .mapNotNull { ref -> nodeChange(ref, baseByRef.getValue(ref), headByRef.getValue(ref)) }

    return SemanticsDelta(added = added, removed = removed, changed = changed)
  }

  private fun nodeChange(
    ref: String,
    base: ComposeSemanticsNode,
    head: ComposeSemanticsNode,
  ): SemanticsNodeChange? {
    val changes = COMPARED_FIELDS.mapNotNull { (field, extract) ->
      val from = extract(base)
      val to = extract(head)
      if (from != to) SemanticsFieldChange(field, from, to) else null
    }
    return if (changes.isEmpty()) null
    else SemanticsNodeChange(ref = ref, anchor = SemanticsRefs.anchor(head), changes = changes)
  }

  private fun ComposeSemanticsNode.byRef(): Map<String, ComposeSemanticsNode> {
    val out = LinkedHashMap<String, ComposeSemanticsNode>()
    fun walk(node: ComposeSemanticsNode) {
      node.ref?.let { out[it] = node }
      node.children.forEach(::walk)
    }
    walk(this)
    return out
  }

  private fun ComposeSemanticsNode.summary(): SemanticsNodeSummary =
    SemanticsNodeSummary(
      ref = ref ?: SemanticsRefs.ROOT_REF,
      role = role,
      testTag = testTag,
      text = text,
      label = label,
    )
}
