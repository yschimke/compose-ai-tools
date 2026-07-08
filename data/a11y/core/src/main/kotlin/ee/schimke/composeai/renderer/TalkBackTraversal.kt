package ee.schimke.composeai.renderer

/**
 * Computes TalkBack linear-navigation focus moves over an extracted accessibility hierarchy —
 * issue #1956, Phase 3.
 *
 * TalkBack's swipe-right / swipe-left gestures walk **focus stops** in traversal order. A focus
 * stop is a node that is its own screen-reader target — exactly the nodes the extractors mark
 * [AccessibilityNode.merged] = `true` (a merged container is one stop; its unmerged descendant text
 * runs are *not* separate stops). The node list the extractors produce is already in pre-order
 * traversal ([AccessibilityChecker] walks ATF's `allViews`; the desktop extractor walks the
 * semantics tree depth-first), so the focus-stop sequence is just `nodes.filter { it.merged }` in
 * order.
 *
 * This object is the deterministic, pure core behind the `a11y.action.next` /
 * `a11y.action.previous` / `a11y.action.activate` recording-script events and behind the live focus
 * overlay's auto-advance: given the current focus (by [AccessibilityNode.ref]) it returns the next
 * / previous stop. Keeping it pure means the same traversal math drives the scripted host dispatch
 * and the overlay caption, so they never disagree about "which node is focused now".
 *
 * **Boundary + entry semantics** (matching how TalkBack behaves from a cold start):
 * - [next] with no current focus enters at the **first** stop; [previous] with no current focus
 *   enters at the **last** stop.
 * - [next] past the last stop and [previous] before the first stop return `null` (no move) —
 *   TalkBack announces "end of screen" rather than wrapping, and a non-wrapping linear walk is the
 *   deterministic thing to script.
 * - A `currentRef` that no longer resolves to a stop (the focused node was removed / re-keyed) is
 *   treated as "no current focus", so navigation re-enters cleanly instead of dead-ending.
 */
object TalkBackTraversal {

  /** The focus stops in TalkBack traversal order — the merged nodes, in extraction order. */
  fun focusStops(nodes: List<AccessibilityNode>): List<AccessibilityNode> = nodes.filter {
    it.merged
  }

  /** The first focus stop, or `null` when nothing is focusable. */
  fun first(nodes: List<AccessibilityNode>): AccessibilityNode? = focusStops(nodes).firstOrNull()

  /** The last focus stop, or `null` when nothing is focusable. */
  fun last(nodes: List<AccessibilityNode>): AccessibilityNode? = focusStops(nodes).lastOrNull()

  /**
   * The stop currently focused, identified by its [AccessibilityNode.ref]. `null` when [currentRef]
   * is null or no stop carries it.
   */
  fun current(nodes: List<AccessibilityNode>, currentRef: String?): AccessibilityNode? {
    if (currentRef == null) return null
    return focusStops(nodes).firstOrNull { it.ref == currentRef }
  }

  /**
   * The next focus stop after the one identified by [currentRef]. Enters at the first stop when
   * [currentRef] is null / unresolved; returns `null` when already at (or past) the last stop.
   */
  fun next(nodes: List<AccessibilityNode>, currentRef: String?): AccessibilityNode? {
    val stops = focusStops(nodes)
    if (stops.isEmpty()) return null
    val idx = indexOfRef(stops, currentRef)
    if (idx < 0) return stops.first()
    return stops.getOrNull(idx + 1)
  }

  /**
   * The previous focus stop before the one identified by [currentRef]. Enters at the last stop when
   * [currentRef] is null / unresolved; returns `null` when already at (or before) the first stop.
   */
  fun previous(nodes: List<AccessibilityNode>, currentRef: String?): AccessibilityNode? {
    val stops = focusStops(nodes)
    if (stops.isEmpty()) return null
    val idx = indexOfRef(stops, currentRef)
    if (idx < 0) return stops.last()
    return stops.getOrNull(idx - 1)
  }

  private fun indexOfRef(stops: List<AccessibilityNode>, ref: String?): Int {
    if (ref == null) return -1
    return stops.indexOfFirst { it.ref == ref }
  }
}
