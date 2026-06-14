package ee.schimke.composeai.renderer

/**
 * Assigns a stable [AccessibilityNode.ref] to every node in an `a11y/hierarchy` payload (issue
 * #1784) — the accessibility analogue of `SemanticsRefs` for the Compose semantics tree.
 *
 * The `a11y/hierarchy` payload is a **flat** list (ATF flattens the View tree into the nodes
 * TalkBack would stop on), so the scheme is simpler than the path-based `SemanticsRefs`: each node
 * anchors on the most stable identity it carries —
 * 1. its `role` if set (`Button`, `Image`, … — what TalkBack announces), else
 * 2. a generic `node` token —
 *
 * disambiguated by occurrence index among nodes that share the same anchor (`role:Button[0]`,
 * `role:Button[1]`). The visible `label` and `states` are deliberately **not** part of the ref so a
 * copy edit (button text changing, a state-description string updating) shows up as a field change
 * on the same ref rather than a remove + add. There is no `testTag` on the ATF surface (it's a
 * View-tree projection, not the Compose semantics tree), so `role` is the strongest anchor here.
 *
 * Assignment is deterministic and idempotent: running it twice yields identical refs, so callers can
 * re-assign defensively without surprise.
 */
object AccessibilityRefs {
  const val ROOT_PREFIX: String = "a"

  /** Stamp a `ref` onto every node, preserving order. Nodes that already carry a `ref` are reassigned. */
  fun assign(nodes: List<AccessibilityNode>): List<AccessibilityNode> {
    val seen = HashMap<String, Int>()
    return nodes.map { node ->
      val a = anchor(node)
      val index = (seen[a] ?: 0).also { seen[a] = it + 1 }
      node.copy(ref = "$ROOT_PREFIX/$a[$index]")
    }
  }

  /** The anchor token for a node, before sibling disambiguation. */
  fun anchor(node: AccessibilityNode): String =
    node.role?.trim()?.takeIf { it.isNotEmpty() }?.let { "role:${sanitize(it)}" } ?: GENERIC_ANCHOR

  private const val GENERIC_ANCHOR = "node"

  /** Strip characters that would collide with the ref grammar (`/`, `[`, `]`, whitespace). */
  private fun sanitize(value: String): String = value.replace(SANITIZE_REGEX, "_")

  private val SANITIZE_REGEX = Regex("""[\s/\[\]]+""")
}
