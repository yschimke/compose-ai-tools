package ee.schimke.composeai.renderer

/**
 * Rolls a focus stop's announcement up from the descendants it merges (issue #4253).
 *
 * ATF describes each `ViewHierarchyElement` on its own terms: a merged focus stop reports the
 * `contentDescription` / `text` **it** carries, not the copy sitting on the children it folds into
 * one TalkBack announcement. For the shapes where the clickable surface and the copy are different
 * elements — a Wear `Button(icon = …, label = { Text("Filled") })`, an `IconButton` whose
 * `contentDescription` lives on the inner `Icon` — that leaves the stop with an empty `label` and
 * the copy stranded on a child that no screen reader stops on. Every consumer then reads the stop
 * as unlabelled: the inspection layer prints `(unlabelled)`, the legend falls back to the role, and
 * [TalkBackUtterance] speaks the state with no name in front of it. What ATF reports for that
 * button is measured in `:renderer-android`'s `WearButtonA11yHierarchyProbeTest`.
 *
 * TalkBack announces "Filled, double-tap to activate" there, so that is what the node should carry.
 * This is the same walk `DesktopAccessibilityNodeExtractor.effectiveLabel` does on the CMP side,
 * and it reads the hierarchy the same way: **real ancestry**, taken while the walk still holds it.
 * A nested focus stop owns its own announcement, so its subtree is pruned — but the walk carries on
 * with the descendants after it, which is what lets a row that folds in a title, a button of its
 * own, then a subtitle announce "Title Subtitle".
 *
 * Structural questions have to be answered here, at extraction, because the `a11y/hierarchy` wire
 * format is flat: it has no parent link, so a consumer reading it back can only approximate the
 * subtree from emission order and bounds (`cli/serve-web`'s `mergedDescendantLabel` does exactly
 * that, for hierarchies baked before this walk started rolling labels up). Approximating is what
 * this avoids.
 */
object AccessibilityLabels {

  /**
   * The slice of a hierarchy element the roll-up reads. Kept small so the rule can be exercised
   * against hand-built trees — ATF's own `ViewHierarchyElement` needs a real `View` graph to
   * construct, which is why the projection used to be tested against flat node lists that could
   * only prove its arithmetic, never its reading of the tree.
   *
   * [isFocusStop] and [mergesDescendants] are **not** the same question, and the roll-up asks each
   * of them exactly once. A scrollable container is a stop a screen reader lands on, but it does
   * not fold its rows into one announcement — each row stays independently reachable
   * ([#1565](https://github.com/yschimke/compose-ai-tools/issues/1565)). Collapsing the two would
   * let a clickable card that happens to contain a carousel announce every row in it.
   */
  interface Element {
    /** The copy this element carries itself: `contentDescription` else `text`. */
    val ownLabel: String

    /**
     * `true` when a screen reader stops on this element in its own right — ATF's
     * `isScreenReaderFocusable`. What an ancestor's announcement must stop at, whether or not this
     * element goes on to fold anything of its own.
     */
    val isFocusStop: Boolean

    /**
     * `true` when this element folds its descendants into a single announcement — ATF's
     * `isScreenReaderFocusable` on a **non-scrollable** element
     * ([AccessibilityChecker.mergesDescendants]), the analogue of Compose's
     * `isMergingSemanticsOfDescendants`. What decides whether this element has an announcement to
     * roll up at all.
     */
    val mergesDescendants: Boolean

    val children: List<Element>
  }

  /**
   * What a screen reader announces for [element]: the copy it carries itself, or — for a merging
   * element that carries none — the copy of the descendants it folds in, joined by `" "` in
   * traversal order.
   *
   * Empty when nothing supplies a name. An unlabelled clickable really is unlabelled, and that is a
   * finding worth seeing, not one worth papering over.
   */
  fun announcement(element: Element): String {
    val own = element.ownLabel.trim()
    if (own.isNotEmpty()) return own
    if (!element.mergesDescendants) return ""
    val parts = mutableListOf<String>()
    fun collect(node: Element, isRoot: Boolean) {
      // A nested stop is reached on its own, so neither it nor its subtree is part of this
      // announcement — but what follows it still is. The test is "is it a stop", not "does it
      // merge": a scrollable list is the case where those differ, and absorbing its rows here would
      // announce the whole list as this element's name.
      if (!isRoot && node.isFocusStop) return
      node.ownLabel.trim().takeIf { it.isNotEmpty() }?.let { parts += it }
      for (child in node.children) collect(child, isRoot = false)
    }
    collect(element, isRoot = true)
    return parts.joinToString(" ")
  }
}
