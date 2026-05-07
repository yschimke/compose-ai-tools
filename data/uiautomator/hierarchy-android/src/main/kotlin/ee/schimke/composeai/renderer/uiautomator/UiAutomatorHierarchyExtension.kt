package ee.schimke.composeai.renderer.uiautomator

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import ee.schimke.composeai.data.render.extensions.DataExtensionConstraints
import ee.schimke.composeai.data.render.extensions.DataExtensionHookKind
import ee.schimke.composeai.data.render.extensions.DataExtensionId
import ee.schimke.composeai.data.render.extensions.DataExtensionPhase
import ee.schimke.composeai.data.render.extensions.DataExtensionTarget
import ee.schimke.composeai.data.render.extensions.DataProductKey
import ee.schimke.composeai.data.render.extensions.ExtensionContextKey
import ee.schimke.composeai.data.render.extensions.ExtensionPostCaptureContext
import ee.schimke.composeai.data.render.extensions.PostCaptureProcessor

/**
 * Pure extractor — walks a Compose [SemanticsNode] tree and returns the typed
 * [UiAutomatorHierarchyPayload]. The default emit set is the **actionable subset**: nodes whose
 * `SemanticsConfiguration` exposes at least one `uia.*` action (Click, LongClick, Scroll,
 * SetText, RequestFocus, Expand, Collapse, Dismiss, SetProgress). Per the #874 review, this
 * drops ~80% of the SemanticsOwner walk on real screens — Material containers, layout
 * wrappers, decorative `Text` nodes — while preserving every node a `uia.click` / `uia.scrollForward` /
 * `uia.inputText` could plausibly target.
 *
 * The walk descends through filtered-out parents. A clickable buried under three layout
 * wrappers is still emitted (only the wrappers are dropped); the wrapper's `testTag`, when it
 * has one, is preserved on the descendant via [UiAutomatorHierarchyNode.testTagAncestors] so
 * `hasParent({testTag: ...})` selector chains stay resolvable from the snapshot alone.
 *
 * # Flags
 *
 *  - [includeNonActionable] — when `true`, also emits any node carrying a selector-targetable
 *    property (`text` / `contentDescription` / `testTag` / `role`). Off by default; use it when
 *    debugging a `uia.click` whose selector targets static text or a description rather than
 *    the action-bearing node itself.
 *  - [merged] — descriptive only. Records on the emitted node whether the snapshot came from
 *    the merged or unmerged tree. Choosing which tree to walk is upstream of this extractor:
 *    the host calls `rule.onRoot(useUnmergedTree=...)` and hands the resulting [SemanticsNode]
 *    in. We don't emit both trees — the agent picks one per request, same shape `record_preview`
 *    already exposes for `uia.*` dispatch.
 *
 * Pure data — no platform side-effects. Unit-testable with a `ComposeContentTestRule`.
 */
object UiAutomatorHierarchyExtractor {
  fun extract(
    root: SemanticsNode,
    includeNonActionable: Boolean = false,
    merged: Boolean = true,
  ): UiAutomatorHierarchyPayload {
    val out = mutableListOf<UiAutomatorHierarchyNode>()
    walk(root, ancestorTags = emptyList(), out, includeNonActionable, merged)
    return UiAutomatorHierarchyPayload(out)
  }

  private fun walk(
    node: SemanticsNode,
    ancestorTags: List<String>,
    out: MutableList<UiAutomatorHierarchyNode>,
    includeNonActionable: Boolean,
    merged: Boolean,
  ) {
    val emitted = nodeFor(node, ancestorTags, merged)
    if (shouldEmit(emitted, includeNonActionable)) {
      out += emitted
    }
    val ownTag = node.config.getOrNull(SemanticsProperties.TestTag)
    val nextAncestors =
      if (ownTag.isNullOrBlank()) ancestorTags else (ancestorTags + ownTag)
    for (child in node.children) {
      walk(child, nextAncestors, out, includeNonActionable, merged)
    }
  }

  private fun shouldEmit(node: UiAutomatorHierarchyNode, includeNonActionable: Boolean): Boolean {
    if (node.actions.isNotEmpty()) return true
    if (!includeNonActionable) return false
    // Even in debug mode, drop nodes with no selector-targetable property at all — a pure
    // layout Box with empty bounds carries no signal for an agent.
    return node.text != null ||
      node.contentDescription != null ||
      node.testTag != null ||
      node.role != null
  }

  private fun nodeFor(
    node: SemanticsNode,
    ancestorTags: List<String>,
    merged: Boolean,
  ): UiAutomatorHierarchyNode {
    val config = node.config
    val text =
      config.getOrNull(SemanticsProperties.EditableText)?.text
        ?: config.getOrNull(SemanticsProperties.Text)?.joinToString(separator = " ") { it.text }
    val description = config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ")
    val testTag = config.getOrNull(SemanticsProperties.TestTag)?.takeIf { it.isNotBlank() }
    val role = config.getOrNull(SemanticsProperties.Role)?.toString()
    val actions = actionsFor(node)
    val bounds = node.boundsInRoot
    val boundsString =
      "${bounds.left.toInt()},${bounds.top.toInt()},${bounds.right.toInt()},${bounds.bottom.toInt()}"
    return UiAutomatorHierarchyNode(
      text = text,
      contentDescription = description,
      testTag = testTag,
      testTagAncestors = ancestorTags,
      role = role,
      actions = actions,
      boundsInScreen = boundsString,
      merged = merged,
    )
  }

  private fun actionsFor(node: SemanticsNode): List<String> {
    val config = node.config
    val out = mutableListOf<String>()
    if (config.getOrNull(SemanticsActions.OnClick) != null) {
      out += UiAutomatorDataProducts.ACTION_CLICK
    }
    if (config.getOrNull(SemanticsActions.OnLongClick) != null) {
      out += UiAutomatorDataProducts.ACTION_LONG_CLICK
    }
    if (config.getOrNull(SemanticsActions.ScrollBy) != null) {
      out += UiAutomatorDataProducts.ACTION_SCROLL
    }
    if (config.getOrNull(SemanticsActions.SetText) != null) {
      out += UiAutomatorDataProducts.ACTION_SET_TEXT
    }
    if (config.getOrNull(SemanticsActions.RequestFocus) != null) {
      out += UiAutomatorDataProducts.ACTION_REQUEST_FOCUS
    }
    if (config.getOrNull(SemanticsActions.Expand) != null) {
      out += UiAutomatorDataProducts.ACTION_EXPAND
    }
    if (config.getOrNull(SemanticsActions.Collapse) != null) {
      out += UiAutomatorDataProducts.ACTION_COLLAPSE
    }
    if (config.getOrNull(SemanticsActions.Dismiss) != null) {
      out += UiAutomatorDataProducts.ACTION_DISMISS
    }
    if (config.getOrNull(SemanticsActions.SetProgress) != null) {
      out += UiAutomatorDataProducts.ACTION_SET_PROGRESS
    }
    return out
  }
}

/**
 * Default binding for [UiAutomatorHierarchyExtractor]: invokes it after capture and emits a
 * typed [UiAutomatorHierarchyPayload] into the product store. Reads the captured
 * [SemanticsNode] root from [UiAutomatorHierarchyContextKeys.SemanticsRoot] (host populates it
 * from `rule.onRoot(useUnmergedTree=...)` or its on-device equivalent) and an
 * [UiAutomatorHierarchyOptions] block from [UiAutomatorHierarchyContextKeys.Options] (defaults
 * to filtered + merged when absent).
 *
 * `targets = {Android}` — a future Compose Multiplatform Desktop hierarchy producer would
 * target `{Desktop}` and emit the same product key; the planner's target filter selects
 * exactly one provider per platform.
 */
class UiAutomatorHierarchyExtension : PostCaptureProcessor {
  override val id: DataExtensionId = DataExtensionId(EXTENSION_ID)
  override val hooks: Set<DataExtensionHookKind> = setOf(DataExtensionHookKind.AfterCapture)
  override val constraints: DataExtensionConstraints =
    DataExtensionConstraints(phase = DataExtensionPhase.Capture)
  override val outputs: Set<DataProductKey<*>> = setOf(UiAutomatorDataProducts.Hierarchy)
  override val targets: Set<DataExtensionTarget> = setOf(DataExtensionTarget.Android)

  override fun process(context: ExtensionPostCaptureContext) {
    val root = context.require(UiAutomatorHierarchyContextKeys.SemanticsRoot)
    val options =
      context.get(UiAutomatorHierarchyContextKeys.Options) ?: UiAutomatorHierarchyOptions()
    val payload =
      UiAutomatorHierarchyExtractor.extract(
        root = root,
        includeNonActionable = options.includeNonActionable,
        merged = options.merged,
      )
    context.products.put(UiAutomatorDataProducts.Hierarchy, payload)
  }

  companion object {
    const val EXTENSION_ID: String = "uia-hierarchy"
  }
}

/**
 * Producer-side options. The host decides which tree to fetch (merged vs unmerged) before
 * handing us the [SemanticsNode] root, so [merged] here is descriptive — it ends up on every
 * emitted [UiAutomatorHierarchyNode] so a downstream consumer can disambiguate. The
 * [includeNonActionable] flag flips the extractor between the agent-targeting view (default)
 * and the debug walk.
 */
data class UiAutomatorHierarchyOptions(
  val includeNonActionable: Boolean = false,
  val merged: Boolean = true,
)

/**
 * Typed keys this extension reads from [ExtensionPostCaptureContext.data]. The Android-platform
 * binding lives here (where Compose [SemanticsNode] is in scope); a future
 * `:data-uiautomator-hierarchy-desktop` would declare its own `SemanticsRoot` key with the
 * Desktop equivalent (`ImageComposeScene` already exposes the same tree).
 */
object UiAutomatorHierarchyContextKeys {
  val SemanticsRoot: ExtensionContextKey<SemanticsNode> =
    ExtensionContextKey(name = "uia-hierarchy.semanticsRoot", type = SemanticsNode::class.java)

  val Options: ExtensionContextKey<UiAutomatorHierarchyOptions> =
    ExtensionContextKey(
      name = "uia-hierarchy.options",
      type = UiAutomatorHierarchyOptions::class.java,
    )
}
