package ee.schimke.composeai.data.layoutinspector

/**
 * Resolves a [SemanticsTarget] — a stable handle for a node — to a concrete node and a pixel point
 * an interactive session can dispatch at, so agents drive previews by ref / testTag / role+text
 * instead of guessing coordinates (issue #1784).
 *
 * Resolution runs against a [ComposeSemanticsPayload] tree whose [ComposeSemanticsNode.ref]s have
 * been assigned by [SemanticsRefs]; [resolve] re-assigns defensively so a caller can pass a raw
 * (unref'd) tree. The returned [SemanticsPoint] is the node's centre in the same root-pixel space
 * as [ComposeSemanticsNode.boundsInRoot].
 */
object SemanticsTargets {

  fun resolve(payload: ComposeSemanticsPayload, target: SemanticsTarget): TargetResolution =
    resolve(payload.root, target)

  fun resolve(root: ComposeSemanticsNode, target: SemanticsTarget): TargetResolution {
    val refRoot = SemanticsRefs.assign(root)
    val matches =
      when (target) {
        is SemanticsTarget.Ref -> refRoot.flatten().filter { it.ref == target.ref }
        is SemanticsTarget.Tag -> refRoot.flatten().filter { it.testTag == target.testTag }
        is SemanticsTarget.RoleText -> refRoot.flatten().filter { matchesRoleText(it, target) }
      }
    return when (matches.size) {
      0 -> TargetResolution.NotFound
      1 -> matches.single().let { node -> resolvedAt(node) }
      else -> TargetResolution.Ambiguous(matches)
    }
  }

  private fun resolvedAt(node: ComposeSemanticsNode): TargetResolution {
    val bounds = SemanticsBounds.parse(node.boundsInRoot)
    return if (bounds == null) TargetResolution.NotFound
    else TargetResolution.Resolved(node, SemanticsPoint(bounds.centerX, bounds.centerY))
  }

  private fun matchesRoleText(
    node: ComposeSemanticsNode,
    target: SemanticsTarget.RoleText,
  ): Boolean {
    val roleOk = target.role == null || node.role.equals(target.role, ignoreCase = true)
    val textOk =
      target.text == null ||
        sequiv(node.text, target.text) ||
        sequiv(node.label, target.text) ||
        sequiv(node.layoutText, target.text)
    return roleOk && textOk && (target.role != null || target.text != null)
  }

  /** Case-insensitive, whitespace-trimmed equality, ignoring null. */
  private fun sequiv(actual: String?, expected: String): Boolean =
    actual != null && actual.trim().equals(expected.trim(), ignoreCase = true)

  private fun ComposeSemanticsNode.flatten(): List<ComposeSemanticsNode> = buildList {
    add(this@flatten)
    children.forEach { addAll(it.flatten()) }
  }
}

/** A request to identify a node by stable handle rather than pixel coordinates (issue #1784). */
sealed interface SemanticsTarget {
  /** Match the unique node whose [ComposeSemanticsNode.ref] equals [ref]. */
  data class Ref(val ref: String) : SemanticsTarget

  /** Match nodes carrying this exact `testTag`. */
  data class Tag(val testTag: String) : SemanticsTarget

  /** Match nodes by role and/or accessible text (at least one must be non-null). */
  data class RoleText(val role: String? = null, val text: String? = null) : SemanticsTarget
}

sealed interface TargetResolution {
  /** Exactly one node matched; dispatch at [point] (centre of the node, root-pixel space). */
  data class Resolved(val node: ComposeSemanticsNode, val point: SemanticsPoint) : TargetResolution

  /** No node matched. */
  data object NotFound : TargetResolution

  /** More than one node matched; the caller should disambiguate among [candidates]. */
  data class Ambiguous(val candidates: List<ComposeSemanticsNode>) : TargetResolution
}

/** A point in the root-pixel coordinate space used by [ComposeSemanticsNode.boundsInRoot]. */
data class SemanticsPoint(val x: Int, val y: Int)

/** Bounds parsed from the `"left,top,right,bottom"` wire string. */
data class SemanticsBounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
  val centerX: Int
    get() = (left + right) / 2

  val centerY: Int
    get() = (top + bottom) / 2

  companion object {
    fun parse(wire: String): SemanticsBounds? {
      val parts = wire.split(',')
      if (parts.size != 4) return null
      val ints = parts.map { it.trim().toIntOrNull() ?: return null }
      return SemanticsBounds(ints[0], ints[1], ints[2], ints[3])
    }
  }
}
