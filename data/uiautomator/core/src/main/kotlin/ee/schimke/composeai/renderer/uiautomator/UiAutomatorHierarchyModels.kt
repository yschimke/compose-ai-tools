package ee.schimke.composeai.renderer.uiautomator

import ee.schimke.composeai.data.render.extensions.DataProductKey
import kotlinx.serialization.Serializable

/**
 * One Compose semantics node surfaced by the `uia/hierarchy` data product (#874). Shape is
 * deliberately small: agents need just enough metadata to formulate a [Selector] that uniquely
 * targets the node — text, contentDescription, testTag, role, the supported `uia.*` actions —
 * not the full SemanticsNode graph.
 *
 * Emitted by [`UiAutomatorHierarchyExtractor`][ee.schimke.composeai.renderer.uiautomator.UiAutomatorHierarchyExtractor]
 * (lives in `:data-uiautomator-hierarchy-android`). The extractor's default filter keeps only
 * nodes that expose at least one of [UiAutomatorDataProducts.SUPPORTED_ACTIONS]; that drops the
 * ~80% of layout-wrapper / pure-text nodes a real Material screen produces while still emitting
 * everything a `uia.click` / `uia.scrollForward` / `uia.inputText` could plausibly target.
 */
@Serializable
data class UiAutomatorHierarchyNode(
  /**
   * `EditableText` first (input fields), falling back to joined `Text` — same shape
   * `SemanticsBacking` exposes to selector matching, so an emitted node's `text` is exactly
   * what a `By.text(...)` would match against.
   */
  val text: String? = null,
  /** Joined `ContentDescription`. */
  val contentDescription: String? = null,
  /** `Modifier.testTag(...)` — the closest stable per-node id Compose offers. */
  val testTag: String? = null,
  /**
   * TestTags of ancestors, root-most → nearest, omitting blanks. Preserved on the emitted node
   * so `hasParent({testTag: ...})` / `hasAncestor` selector chains stay resolvable from the
   * hierarchy snapshot alone, even though intermediate non-actionable parents are filtered out.
   */
  val testTagAncestors: List<String> = emptyList(),
  /**
   * `SemanticsProperties.Role` stringified (`Button`, `Checkbox`, `Switch`, `RadioButton`,
   * `Tab`, `Image`, `DropdownList`). `null` when the node carries no role.
   */
  val role: String? = null,
  /**
   * Sorted subset of [UiAutomatorDataProducts.SUPPORTED_ACTIONS] this node exposes. Stable order
   * keeps wire diffs deterministic across runs.
   */
  val actions: List<String> = emptyList(),
  /** `left,top,right,bottom` in source-bitmap pixels — same shape `AccessibilityNode` uses. */
  val boundsInScreen: String,
  /**
   * `true` when the snapshot was taken against the merged semantics tree (the on-device
   * UIAutomator default; what `Button { Text("Submit") }` collapses into one node), `false`
   * when the unmerged tree was requested. Recorded on the emitted node so a downstream client
   * can disambiguate two payloads pointing at the same preview.
   */
  val merged: Boolean = true,
)

/** Per-preview `uia/hierarchy` payload — the node list emitted by the producer. */
@Serializable data class UiAutomatorHierarchyPayload(val nodes: List<UiAutomatorHierarchyNode>)

object UiAutomatorDataProducts {
  const val SCHEMA_VERSION: Int = 1
  const val KIND_HIERARCHY: String = "uia/hierarchy"

  /** Action names emitted on [UiAutomatorHierarchyNode.actions]. */
  const val ACTION_CLICK: String = "click"
  const val ACTION_LONG_CLICK: String = "longClick"
  const val ACTION_SCROLL: String = "scroll"
  const val ACTION_SET_TEXT: String = "setText"
  const val ACTION_REQUEST_FOCUS: String = "requestFocus"
  const val ACTION_EXPAND: String = "expand"
  const val ACTION_COLLAPSE: String = "collapse"
  const val ACTION_DISMISS: String = "dismiss"
  const val ACTION_SET_PROGRESS: String = "setProgress"

  /**
   * Action set that makes a node a viable `uia.*` dispatch target. Matches the `when` arms in
   * `RobolectricHost.performUiAutomatorAction` so the default-filtered hierarchy never hides a
   * node the dispatch path could actually drive.
   */
  val SUPPORTED_ACTIONS: Set<String> =
    setOf(
      ACTION_CLICK,
      ACTION_LONG_CLICK,
      ACTION_SCROLL,
      ACTION_SET_TEXT,
      ACTION_REQUEST_FOCUS,
      ACTION_EXPAND,
      ACTION_COLLAPSE,
      ACTION_DISMISS,
      ACTION_SET_PROGRESS,
    )

  val Hierarchy: DataProductKey<UiAutomatorHierarchyPayload> =
    DataProductKey(KIND_HIERARCHY, SCHEMA_VERSION, UiAutomatorHierarchyPayload::class.java)
}
