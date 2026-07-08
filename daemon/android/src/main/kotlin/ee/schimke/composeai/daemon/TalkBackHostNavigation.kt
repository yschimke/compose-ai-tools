package ee.schimke.composeai.daemon

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import ee.schimke.composeai.renderer.AccessibilityNode
import ee.schimke.composeai.renderer.AccessibilityRefs
import ee.schimke.composeai.renderer.TalkBackTraversal

/**
 * Host-side TalkBack linear-navigation core (issue #1956). Given the merged Compose semantics nodes
 * of the current frame and the session's focus cursor, computes the next / previous focus stop in
 * traversal order using the shared, unit-tested [TalkBackTraversal].
 *
 * Kept separate from [RobolectricHost] so the focus-stop extraction (which merged nodes are stops,
 * reading-order sort, role-anchored refs) is testable against a real Compose semantics tree via a
 * lightweight compose rule, without standing up the full held-session sandbox. [RobolectricHost]
 * supplies `rule.onAllNodes(...).fetchSemanticsNodes(...)`, applies the returned [Move] to the
 * cursor, and requests focus on [Move.focusTarget].
 */
internal object TalkBackHostNavigation {

  /**
   * Result of a navigation step. [matched] is `true` when the cursor moved; `false` at a list
   * boundary (TalkBack's "end of screen" — no wrap) or when there are no focus stops. [cursor] is
   * the new cursor value to store, and [focusTarget] is the semantics node to request focus on
   * (best-effort; `null` when no move or the stop has no node).
   */
  data class Move(val matched: Boolean, val cursor: String?, val focusTarget: SemanticsNode?)

  /**
   * Compute the [direction] (`"next"` / `"previous"`) move from [currentCursor] over [semantics]
   * (the merged tree's nodes for the frame). Focus stops are the merged nodes that carry a label or
   * a click action — the screen-reader-focusable ones, mirroring the desktop extractor's keep
   * filter — taken in reading order (top-to-bottom, then left-to-right) and stamped with the same
   * role-anchored refs the a11y hierarchy uses.
   */
  fun move(semantics: List<SemanticsNode>, direction: String, currentCursor: String?): Move {
    val stops =
      semantics
        .sortedWith(compareBy({ it.boundsInRoot.top }, { it.boundsInRoot.left }))
        .map { it to it.toFocusStop() }
        .filter { (sn, an) -> an.label.isNotEmpty() || sn.isStateOnlyFocusStop() }
    if (stops.isEmpty()) return Move(matched = false, cursor = currentCursor, focusTarget = null)
    val nodes = AccessibilityRefs.assign(stops.map { it.second })
    val target =
      if (direction == "previous") TalkBackTraversal.previous(nodes, currentCursor)
      else TalkBackTraversal.next(nodes, currentCursor)
    if (target == null) return Move(matched = false, cursor = currentCursor, focusTarget = null)
    val idx = nodes.indexOfFirst { it.ref == target.ref }
    return Move(matched = true, cursor = target.ref, focusTarget = stops.getOrNull(idx)?.first)
  }

  /**
   * Whether a node with no label is still a TalkBack focus stop because it carries actionable /
   * stateful semantics — an empty edit field (`SetText` / `EditableText`), an unlabeled scrollable
   * (`ScrollBy` / a scroll-axis range), a clickable, a toggle, or a slider. Mirrors the desktop
   * accessibility extractor's state-based keep filter so the cursor order matches the a11y
   * hierarchy (a bare `Role` with nothing else is *not* a stop — e.g. an undescribed Image —
   * matching the hierarchy, which only surfaces a roled node when it's also actionable / labelled).
   */
  private fun SemanticsNode.isStateOnlyFocusStop(): Boolean {
    val cfg = config
    return cfg.contains(SemanticsActions.OnClick) ||
      cfg.contains(SemanticsActions.OnLongClick) ||
      cfg.contains(SemanticsActions.SetText) ||
      cfg.getOrNull(SemanticsProperties.EditableText) != null ||
      cfg.contains(SemanticsActions.ScrollBy) ||
      cfg.getOrNull(SemanticsProperties.HorizontalScrollAxisRange) != null ||
      cfg.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null ||
      cfg.getOrNull(SemanticsProperties.ToggleableState) != null ||
      cfg.getOrNull(SemanticsProperties.ProgressBarRangeInfo) != null
  }

  /** Project a merged [SemanticsNode] to the a11y-core node model used by [TalkBackTraversal]. */
  private fun SemanticsNode.toFocusStop(): AccessibilityNode {
    val cd = config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ")
    val text = config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }
    val r = boundsInRoot
    return AccessibilityNode(
      label = (cd ?: text ?: "").trim(),
      role = config.getOrNull(SemanticsProperties.Role)?.toString(),
      merged = true,
      boundsInScreen = "${r.left.toInt()},${r.top.toInt()},${r.right.toInt()},${r.bottom.toInt()}",
    )
  }
}
