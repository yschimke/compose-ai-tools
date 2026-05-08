package ee.schimke.composeai.daemon

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onRoot
import ee.schimke.composeai.daemon.protocol.UiAutomatorNearMatchNode
import ee.schimke.composeai.daemon.protocol.UiAutomatorUnsupportedReason
import ee.schimke.composeai.daemon.protocol.UiAutomatorUnsupportedReasonCode
import ee.schimke.composeai.renderer.uiautomator.SelectorJson
import ee.schimke.composeai.renderer.uiautomator.UiAutomator
import ee.schimke.composeai.renderer.uiautomator.UiAutomatorDataProducts
import ee.schimke.composeai.renderer.uiautomator.decodeSelectorJson
import kotlinx.serialization.json.Json

/**
 * Sandbox-side helper that turns a missed `uia.*` dispatch into a structured
 * [UiAutomatorUnsupportedReason] (#874 item #2). Walks the same `SemanticsOwner` tree the
 * matcher uses and emits a near-match candidate alongside the matched-count so agents can
 * iterate on selectors without re-rendering and reading the screenshot.
 *
 * # Heuristic
 *
 * 1. Resolve the selector against the merged-or-unmerged tree (mirroring the dispatch path).
 *    `0` matches → `NO_MATCH`; `1` match where the action wasn't exposed → `ACTION_NOT_EXPOSED`;
 *    `>=2` matches → `MULTIPLE_MATCHES`.
 * 2. For `NO_MATCH`, score every actionable node against the selector's text axes. The score
 *    rewards case-insensitive equality (5pt), substring match (3pt), and selector-state
 *    overlap (2pt per state predicate). The highest-scoring node becomes [nearMatch].
 *    Falls back to "the first actionable node we found" when no string axis matches at all —
 *    that's the answer to "you matched 0; here's an actionable node you might have meant".
 * 3. For `ACTION_NOT_EXPOSED`, [nearMatch] is the matched node — agents see exactly which
 *    actions it exposes vs the one they tried.
 *
 * The scoring is deliberately simple: a Levenshtein / longest-common-subsequence pass would
 * give marginally better results on typoed selectors but doubles the per-walk cost on real
 * Material screens (hundreds of nodes). Case-insensitive equality + substring covers the
 * common "Submit vs SUBMIT" / "row-2 vs row 2" failure modes m13v's #874 review called out.
 */
internal object UiAutomatorEvidence {

  private val WireJson = Json {
    encodeDefaults = false
    ignoreUnknownKeys = true
    prettyPrint = false
  }

  fun compute(
    rule: AndroidComposeTestRule<*, androidx.activity.ComponentActivity>,
    actionKind: String,
    selectorJson: String,
    useUnmergedTree: Boolean,
  ): UiAutomatorUnsupportedReason {
    if (actionKind !in WiredActions) {
      return UiAutomatorUnsupportedReason(
        code = UiAutomatorUnsupportedReasonCode.UNKNOWN_ACTION_KIND,
        actionKind = actionKind,
        selectorJson = selectorJson,
        useUnmergedTree = useUnmergedTree,
      )
    }
    val selector = decodeSelectorJson(selectorJson)
    val parsedSelector =
      runCatching { WireJson.decodeFromString(SelectorJson.serializer(), selectorJson) }
        .getOrNull()
    val matches = UiAutomator.findObjects(rule, selector, useUnmergedTree = useUnmergedTree)
    if (matches.size >= 2) {
      val firstMatched = matches.first().node
      return UiAutomatorUnsupportedReason(
        code = UiAutomatorUnsupportedReasonCode.MULTIPLE_MATCHES,
        actionKind = actionKind,
        selectorJson = selectorJson,
        useUnmergedTree = useUnmergedTree,
        matchCount = matches.size,
        nearMatch = nearMatchNode(firstMatched),
      )
    }
    if (matches.size == 1) {
      return UiAutomatorUnsupportedReason(
        code = UiAutomatorUnsupportedReasonCode.ACTION_NOT_EXPOSED,
        actionKind = actionKind,
        selectorJson = selectorJson,
        useUnmergedTree = useUnmergedTree,
        matchCount = 1,
        nearMatch = nearMatchNode(matches.first().node),
      )
    }
    val rootNode = rule.onRoot(useUnmergedTree = useUnmergedTree).fetchSemanticsNode()
    val candidates = collectActionable(rootNode)
    val best = candidates.maxByOrNull { score(parsedSelector, it) }
    return UiAutomatorUnsupportedReason(
      code = UiAutomatorUnsupportedReasonCode.NO_MATCH,
      actionKind = actionKind,
      selectorJson = selectorJson,
      useUnmergedTree = useUnmergedTree,
      matchCount = 0,
      nearMatch = best?.let { nearMatchNode(it) },
    )
  }

  private fun collectActionable(root: SemanticsNode): List<SemanticsNode> {
    val out = mutableListOf<SemanticsNode>()
    fun walk(node: SemanticsNode) {
      if (actionsFor(node).isNotEmpty()) out += node
      for (child in node.children) walk(child)
    }
    walk(root)
    return out
  }

  private fun score(selector: SelectorJson?, node: SemanticsNode): Int {
    if (selector == null) return 0
    var s = 0
    val text = textOf(node)?.lowercase()
    val desc = descOf(node)?.lowercase()
    val testTag =
      node.config.getOrNull(SemanticsProperties.TestTag)?.takeIf { it.isNotBlank() }?.lowercase()
    s += stringAxisScore(selector.text, text) + stringAxisScore(selector.text, desc)
    s += stringAxisScore(selector.desc, desc) + stringAxisScore(selector.desc, text)
    s += stringAxisScore(selector.res, testTag)
    if (selector.clickable == true && node.config.getOrNull(SemanticsActions.OnClick) != null) {
      s += 2
    }
    if (
      selector.longClickable == true &&
        node.config.getOrNull(SemanticsActions.OnLongClick) != null
    ) {
      s += 2
    }
    if (selector.scrollable == true && node.config.getOrNull(SemanticsActions.ScrollBy) != null) {
      s += 2
    }
    return s
  }

  private fun stringAxisScore(needle: String?, haystack: String?): Int {
    if (needle == null || haystack == null) return 0
    val n = needle.lowercase()
    if (n == haystack) return 5
    if (haystack.contains(n)) return 3
    if (n.contains(haystack) && haystack.length >= 2) return 2
    return 0
  }

  private fun textOf(node: SemanticsNode): String? {
    node.config.getOrNull(SemanticsProperties.EditableText)?.let { return it.text }
    return node.config
      .getOrNull(SemanticsProperties.Text)
      ?.joinToString(separator = " ") { it.text }
  }

  private fun descOf(node: SemanticsNode): String? =
    node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ")

  private fun nearMatchNode(node: SemanticsNode): UiAutomatorNearMatchNode {
    val testTag =
      node.config.getOrNull(SemanticsProperties.TestTag)?.takeIf { it.isNotBlank() }
    val role = node.config.getOrNull(SemanticsProperties.Role)?.toString()
    val bounds = node.boundsInRoot
    val boundsString =
      "${bounds.left.toInt()},${bounds.top.toInt()},${bounds.right.toInt()},${bounds.bottom.toInt()}"
    return UiAutomatorNearMatchNode(
      text = textOf(node),
      contentDescription = descOf(node),
      testTag = testTag,
      role = role,
      actions = actionsFor(node),
      boundsInScreen = boundsString,
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

  /** Action kinds the wired `UiObject` methods cover — must stay in sync with `RobolectricHost.performUiAutomatorAction`. */
  private val WiredActions: Set<String> =
    setOf(
      "click",
      "longClick",
      "scrollForward",
      "scrollBackward",
      "requestFocus",
      "expand",
      "collapse",
      "dismiss",
      "inputText",
    )
}
