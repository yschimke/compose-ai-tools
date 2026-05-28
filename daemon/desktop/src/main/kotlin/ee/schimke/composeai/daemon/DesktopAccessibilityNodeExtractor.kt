package ee.schimke.composeai.daemon

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsConfiguration
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import ee.schimke.composeai.cli.AccessibilityNode

/**
 * Desktop (Compose Multiplatform) port of `:data-a11y-core`'s `AccessibilityChecker.extractNodes` —
 * it walks a [SemanticsNode] tree instead of ATF's `ViewHierarchyElement` graph and produces the
 * same [AccessibilityNode] wire shape the Android side emits, so [DesktopAccessibilityOverlay] can
 * group + draw nodes identically on both backends.
 *
 * ATF (the Android Accessibility Test Framework) is Android-only — there's no equivalent on the JVM
 * Compose runtime — so the desktop a11y path produces no findings. The "what a screen reader sees"
 * overlay is built purely from Compose semantics: every labelled / actionable node gets a pastel
 * fill + legend row, and there is never a finding badge.
 *
 * **Field mapping (kept byte-identical to the Android producer where the data exists):**
 * - `label` — `ContentDescription` (joined by `" "`) else `Text` (joined by `" "`), trimmed. Same
 *   precedence as [ComposeSemanticsDataProducer]'s `label()`.
 * - `role` — `SemanticsProperties.Role.toString()` run through [simplifyRole].
 * - `states` — projected by [buildStates] in the same order as Android's `buildStates`, with
 *   byte-identical chip strings.
 * - `merged` — `true` when the node merges its descendants' semantics, or when no merging ancestor
 *   exists ([isMergedSemanticsRoot]).
 * - `boundsInScreen` — `boundsInRoot` in source-bitmap px (the scene is built at `spec.density`, so
 *   root-px == bitmap-px); the overlay applies its own upscale, exactly like Android.
 *
 * Traversal is depth-first pre-order over the **unmerged** tree (parent before descendants), so
 * `DesktopAccessibilityOverlay.groupNodes` pairs merged parents with their following unmerged
 * descendants the same way it does for the Android `allViews` order.
 */
object DesktopAccessibilityNodeExtractor {

  /**
   * Walks [root] (the scene's `unmergedRootSemanticsNode`) depth-first and returns the
   * accessibility-relevant nodes in pre-order. The root itself is included in the walk — it's
   * usually dropped by the keep filter (no label / state) but a labelled root surfaces correctly.
   */
  fun extractNodes(root: SemanticsNode): List<AccessibilityNode> {
    val out = mutableListOf<AccessibilityNode>()
    visit(root, out)
    return out
  }

  private fun visit(node: SemanticsNode, out: MutableList<AccessibilityNode>) {
    val cfg = node.config
    val bounds = node.boundsInRoot
    // Skip zero-area nodes (off-screen / not-yet-placed) — drawing a fill on them is noise and
    // mirrors the Android producer's `width <= 0 || height <= 0` guard.
    val left = bounds.left.toInt()
    val top = bounds.top.toInt()
    val right = bounds.right.toInt()
    val bottom = bounds.bottom.toInt()
    val nonZeroArea = right > left && bottom > top

    // A merging node (the Compose analogue of an ATF screen-reader focus stop) announces its
    // descendants' text rolled up — e.g. a `Button { Text("Go") }` keeps the role + click action on
    // the merging surface while the "Go" label lives on a merged child. Roll that label up here so
    // the merged parent carries the announcement, matching the Android producer's merged-view read.
    val label = effectiveLabel(node)
    val role = simplifyRole(cfg.getOrNull(SemanticsProperties.Role)?.toString())
    val clickable = cfg.getOrNull(SemanticsActions.OnClick) != null
    val states = buildStates(cfg)
    val merged = isMergedSemanticsRoot(node)

    // Drop merged descendants of a focusable ancestor that already carries its own (rolled-up)
    // label — a screen reader reads only the ancestor's announcement, so the inner rows would
    // clutter the legend with content that isn't actually spoken. Mirrors `extractNodes`'
    // `screenReaderFocusableAncestor(...).contentDescription` check, generalised to "ancestor has
    // a label" (contentDescription OR text, including text rolled up from descendants).
    val shadowedByAncestor =
      !merged &&
        screenReaderFocusableAncestor(node)?.let { effectiveLabel(it).isNotEmpty() } == true

    // Keep a node when it carries weight in the legend: a label, an actionable state, or a known
    // role on a clickable element. Clickable-but-empty containers drop out.
    val keep = label.isNotEmpty() || states.isNotEmpty() || (role != null && clickable)

    if (nonZeroArea && keep && !shadowedByAncestor) {
      out +=
        AccessibilityNode(
          label = label,
          role = role,
          states = states,
          merged = merged,
          boundsInScreen = "$left,$top,$right,$bottom",
        )
    }

    // Pre-order: emit (above) before recursing so the overlay's parent-before-children grouping
    // holds. Walk the unmerged children so merged descendants surface as their own (dashed) rows.
    for (child in node.children) {
      visit(child, out)
    }
  }

  /**
   * Pure projection from a node's [SemanticsConfiguration] to the legend chips, in the same order
   * and with the same byte-identical strings as Android's `AccessibilityChecker.buildStates`:
   * structural state first (`checked` / `unchecked`), then behaviour (`clickable`,
   * `long-clickable`, `scrollable`, `editable`), then `disabled`, then the verbatim
   * `stateDescription`.
   *
   * `hint:` (Android `getHintText()`) and `heading` have no clean Compose-semantics equivalent
   * here, so they're omitted — matching the Android producer's "no heading chip" stance.
   */
  internal fun buildStates(cfg: SemanticsConfiguration): List<String> = buildList {
    cfg.getOrNull(SemanticsProperties.ToggleableState)?.let { toggle ->
      add(if (toggle == ToggleableState.On) "checked" else "unchecked")
    }
    if (cfg.getOrNull(SemanticsActions.OnClick) != null) add("clickable")
    if (cfg.getOrNull(SemanticsActions.OnLongClick) != null) add("long-clickable")
    val scrollable =
      cfg.getOrNull(SemanticsActions.ScrollBy) != null ||
        cfg.getOrNull(SemanticsProperties.HorizontalScrollAxisRange) != null ||
        cfg.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null
    if (scrollable) add("scrollable")
    val editable =
      cfg.getOrNull(SemanticsActions.SetText) != null ||
        cfg.getOrNull(SemanticsProperties.EditableText) != null
    if (editable) add("editable")
    if (cfg.contains(SemanticsProperties.Disabled)) add("disabled")
    cfg
      .getOrNull(SemanticsProperties.StateDescription)
      ?.trim()
      ?.takeIf { it.isNotEmpty() }
      ?.let { add(it) }
  }

  /**
   * `true` when [node] is its own focus stop — either it merges its descendants' semantics
   * (`isMergingSemanticsOfDescendants`, the Compose analogue of ATF `isScreenReaderFocusable`), or
   * it has no merging ancestor (a standalone node). `false` for the inner `Text` of a `Button`
   * whose semantics merge into the button.
   */
  internal fun isMergedSemanticsRoot(node: SemanticsNode): Boolean {
    if (node.config.isMergingSemanticsOfDescendants) return true
    return screenReaderFocusableAncestor(node) == null
  }

  /**
   * Closest ancestor of [node] that merges its descendants' semantics, or `null` when [node] is
   * itself a merging root or has no merging ancestor. Used to find "the parent that owns this
   * node's announcement" when filtering shadowed children.
   */
  internal fun screenReaderFocusableAncestor(node: SemanticsNode): SemanticsNode? {
    var p = node.parent
    while (p != null) {
      if (p.config.isMergingSemanticsOfDescendants) return p
      p = p.parent
    }
    return null
  }

  /**
   * The label [node] announces: its own content description / text, or — for a merging node with no
   * label of its own — its descendants' text rolled up ([mergedDescendantLabel]). This is the label
   * the overlay legend shows and the value the shadowing filter checks on a merging ancestor.
   */
  private fun effectiveLabel(node: SemanticsNode): String {
    val own = node.config.label()
    if (own.isNotEmpty()) return own
    return if (node.config.isMergingSemanticsOfDescendants) mergedDescendantLabel(node) else ""
  }

  /**
   * Rolls up the label a merging [node] announces, from its merged descendants' content
   * descriptions / text in pre-order. Stops descending into a child that itself merges (it owns its
   * own announcement), mirroring how a screen reader reads one focus stop. Used when the merging
   * node carries no label of its own (the common `Button { Text(...) }` shape, where the role +
   * click sit on the merging surface and the text is on a child).
   */
  private fun mergedDescendantLabel(node: SemanticsNode): String {
    val parts = mutableListOf<String>()
    fun collect(n: SemanticsNode, isRoot: Boolean) {
      if (!isRoot && n.config.isMergingSemanticsOfDescendants) return
      n.config.label().takeIf { it.isNotEmpty() }?.let { parts.add(it) }
      for (child in n.children) collect(child, isRoot = false)
    }
    collect(node, isRoot = true)
    return parts.joinToString(" ").trim()
  }

  /**
   * `ContentDescription` (joined) else `Text` (joined), trimmed. Reuses
   * [ComposeSemanticsDataProducer]'s precedence so the desktop legend label matches the layout
   * inspector's `label`.
   */
  private fun SemanticsConfiguration.label(): String {
    getOrNull(SemanticsProperties.ContentDescription)
      ?.joinToString(" ")
      ?.trim()
      ?.takeIf { it.isNotEmpty() }
      ?.let {
        return it
      }
    return getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }?.trim().orEmpty()
  }

  /**
   * Drop the package off a role string and the generic `View` / `ViewGroup` so the legend doesn't
   * fill up with noise. Compose's `Role.toString()` already returns short names (`Button`,
   * `Checkbox`, …); the `substringAfterLast('.')` is defensive for any FQN-shaped value.
   */
  private fun simplifyRole(raw: String?): String? {
    if (raw.isNullOrEmpty()) return null
    val short = raw.substringAfterLast('.')
    if (short.isEmpty() || short == "View" || short == "ViewGroup") return null
    return short
  }
}
