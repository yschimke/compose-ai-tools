package ee.schimke.composeai.data.layoutinspector

/**
 * Assigns a stable [ComposeSemanticsNode.ref] to every node in a [ComposeSemanticsPayload] tree.
 *
 * The ref is the handle agents target for interaction (issue #1784) and the key the semantics
 * differ matches on (issue #1785). It must therefore stay stable across renders that change only
 * *content* (text, labels, colors) — just structural changes (a node added / removed / reparented,
 * or its `testTag` / `role` changing) should move a ref.
 *
 * Scheme: a `/`-joined path from the root. Each segment anchors on the most stable identity the
 * node carries:
 * 1. its `testTag` if set (developer-controlled, the strongest anchor),
 * 2. else its `role`,
 * 3. else a generic `node` token,
 *
 * disambiguated by occurrence index among siblings that share the same anchor (`role:Button[0]`,
 * `role:Button[1]`). Text and label are deliberately **not** part of the ref so a copy edit shows
 * up as a field change on the same ref rather than a remove + add.
 *
 * Assignment is deterministic and idempotent: running it twice yields identical refs, so callers
 * can re-assign defensively (the differ does) without surprise.
 */
object SemanticsRefs {
  const val ROOT_REF: String = "r"

  fun assign(payload: ComposeSemanticsPayload): ComposeSemanticsPayload =
    ComposeSemanticsPayload(root = assign(payload.root))

  fun assign(root: ComposeSemanticsNode): ComposeSemanticsNode = assignNode(root, ROOT_REF)

  /** The anchor token for a node, before sibling disambiguation. */
  fun anchor(node: ComposeSemanticsNode): String =
    node.testTag?.trim()?.takeIf { it.isNotEmpty() }?.let { "tag:${sanitize(it)}" }
      ?: node.role?.trim()?.takeIf { it.isNotEmpty() }?.let { "role:${sanitize(it)}" }
      ?: GENERIC_ANCHOR

  private fun assignNode(node: ComposeSemanticsNode, ref: String): ComposeSemanticsNode {
    val children = node.children
    val totals = HashMap<String, Int>()
    for (child in children) totals[anchor(child)] = (totals[anchor(child)] ?: 0) + 1

    val seen = HashMap<String, Int>()
    val newChildren = children.map { child ->
      val a = anchor(child)
      val index = (seen[a] ?: 0).also { seen[a] = it + 1 }
      val segment = if ((totals[a] ?: 0) > 1) "$a[$index]" else a
      assignNode(child, "$ref/$segment")
    }
    return node.copy(ref = ref, children = newChildren)
  }

  private const val GENERIC_ANCHOR = "node"

  /** Strip characters that would collide with the ref path grammar (`/`, `[`, `]`, whitespace). */
  private fun sanitize(value: String): String = value.replace(SANITIZE_REGEX, "_")

  private val SANITIZE_REGEX = Regex("""[\s/\[\]]+""")
}
