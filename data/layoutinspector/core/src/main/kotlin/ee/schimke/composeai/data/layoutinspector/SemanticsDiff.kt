package ee.schimke.composeai.data.layoutinspector

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

object SemanticsDiffProduct {
  const val SCHEMA: String = "compose-semantics-diff/v1"
}

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
object SemanticsDiff {

  /** Semantic fields compared between two nodes sharing a ref, in stable report order. */
  private val COMPARED_FIELDS: List<Pair<String, (ComposeSemanticsNode) -> String?>> =
    listOf(
      "role" to { it.role },
      "testTag" to { it.testTag },
      "label" to { it.label },
      "text" to { it.text },
      "mergeMode" to { it.mergeMode },
      "clickable" to { it.clickable.toString() },
      "editableText" to { it.editableText },
      "inputText" to { it.inputText },
      "layoutTruncated" to { it.textOverflow?.truncated?.toString() },
      "layoutOverflow" to { it.textOverflow?.overflow },
      "layoutLineCount" to { it.textOverflow?.lineCount?.toString() },
      "layoutMaxLines" to { it.textOverflow?.maxLines?.toString() },
      "layoutDidOverflowWidth" to { it.textOverflow?.didOverflowWidth?.toString() },
      "layoutDidOverflowHeight" to { it.textOverflow?.didOverflowHeight?.toString() },
    )

  fun diff(base: ComposeSemanticsPayload, head: ComposeSemanticsPayload): SemanticsDelta =
    diff(base.root, head.root)

  fun diff(base: ComposeSemanticsNode, head: ComposeSemanticsNode): SemanticsDelta {
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

@Serializable data class SemanticsFieldChange(val field: String, val from: String?, val to: String?)

@Serializable
data class SemanticsNodeChange(
  val ref: String,
  /** testTag/role anchor of the node, for human-readable output. */
  val anchor: String? = null,
  val changes: List<SemanticsFieldChange>,
)

@Serializable
data class SemanticsNodeSummary(
  val ref: String,
  val role: String? = null,
  val testTag: String? = null,
  val text: String? = null,
  val label: String? = null,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class SemanticsDelta(
  // `@EncodeDefault` so the versioned schema rides every wire surface — including JSON encoders
  // configured with `encodeDefaults = false` (the daemon's `history/diff mode=SEMANTICS` result and
  // the MCP `diff_semantics` payload). Without it an empty-or-default delta would serialize without
  // its `schema`, defeating the "versioned JSON delta" contract (issue #1785).
  @EncodeDefault val schema: String = SemanticsDiffProduct.SCHEMA,
  val added: List<SemanticsNodeSummary> = emptyList(),
  val removed: List<SemanticsNodeSummary> = emptyList(),
  val changed: List<SemanticsNodeChange> = emptyList(),
) {
  val isEmpty: Boolean
    get() = added.isEmpty() && removed.isEmpty() && changed.isEmpty()
}
