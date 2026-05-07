package ee.schimke.composeai.daemon.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Typed evidence for an unsupported `uia.*` script-event dispatch (#874 item #2). Replaces the
 * free-form `RecordingScriptEvidence.message` for the UIAutomator path with a structured shape
 * agents can iterate on without re-rendering and reading the screenshot.
 *
 * Always carried alongside [RecordingScriptEvidence.message]; the message stays human-readable for
 * trace logs, while [code] / [matchCount] / [nearMatch] / [nearMatchActions] give coding agents
 * enough signal to fix the next selector. Example: a `By.text("Submit")` against a Material
 * `Button` whose merged label is `"SUBMIT"` arrives as `{code: NO_MATCH, matchCount: 0, nearMatch:
 * {text: "SUBMIT", actions: ["click"], …}}`.
 *
 * Wire-stable — fields are additive only. Pre-1.0; agents that didn't migrate continue to read
 * `message` and ignore `unsupportedReason`. The MCP `record_preview` schema surfaces this on the
 * response side.
 */
@Serializable
data class UiAutomatorUnsupportedReason(
  /** Coarse cause — see [UiAutomatorUnsupportedReasonCode]. */
  val code: UiAutomatorUnsupportedReasonCode,
  /** Action kind the dispatch attempted (`"click"`, `"longClick"`, `"inputText"`, …). */
  val actionKind: String,
  /**
   * The selector JSON the agent supplied (round-trippable through `decodeSelectorJson`). `null` for
   * [UiAutomatorUnsupportedReasonCode.MISSING_SELECTOR] where there was no selector to echo.
   */
  val selectorJson: String? = null,
  /** Mirror of `useUnmergedTree` so agents can see which tree the dispatch walked. */
  val useUnmergedTree: Boolean = false,
  /**
   * How many nodes matched the selector. `0` for `NO_MATCH`, `1` for `ACTION_NOT_EXPOSED`, `>=2`
   * for `MULTIPLE_MATCHES`. Agents differentiate "selector too narrow" vs "selector too broad"
   * directly off this field.
   */
  val matchCount: Int = 0,
  /**
   * Best near-match heuristic: the actionable Compose semantics node closest to the selector shape,
   * by case-insensitive equality / substring on the selector's text axes. `null` when no actionable
   * node exists in the tree (a preview with no clickables / scrollables).
   *
   * For `ACTION_NOT_EXPOSED`, this is the matched node — agents see exactly which actions it
   * exposes vs the one they tried.
   */
  val nearMatch: UiAutomatorNearMatchNode? = null,
)

/**
 * Distinguishes the coarse causes a `uia.*` dispatch can fail for, so agents can branch on the
 * right next step (refine selector vs widen selector vs supply missing payload). New codes land
 * additively; clients that don't recognise a new code fall back to the human-readable
 * [RecordingScriptEvidence.message].
 */
@Serializable
enum class UiAutomatorUnsupportedReasonCode {
  /** Agent omitted the `selector` object entirely. */
  @SerialName("missingSelector") MISSING_SELECTOR,
  /** Agent omitted the `inputText` payload required by `uia.inputText`. */
  @SerialName("missingInputText") MISSING_INPUT_TEXT,
  /**
   * Selector resolved to zero nodes. [UiAutomatorUnsupportedReason.nearMatch] is the closest
   * candidate.
   */
  @SerialName("noMatch") NO_MATCH,
  /** Selector resolved to a single node, but that node didn't expose the requested action. */
  @SerialName("actionNotExposed") ACTION_NOT_EXPOSED,
  /** Selector resolved to two or more nodes — agents must narrow the predicate. */
  @SerialName("multipleMatches") MULTIPLE_MATCHES,
  /** Backend doesn't ship a UIAutomator dispatch path (e.g. desktop sessions today). */
  @SerialName("noHostSupport") NO_HOST_SUPPORT,
  /** Action kind isn't one of the wired `UiObject` methods. */
  @SerialName("unknownActionKind") UNKNOWN_ACTION_KIND,
}

/**
 * Slim projection of one Compose semantics node, included on a [UiAutomatorUnsupportedReason] to
 * give the agent just enough shape to formulate the next selector. Mirrors the actionable subset of
 * `UiAutomatorHierarchyNode` from `:data-uiautomator-core` (text, contentDescription, testTag,
 * role, exposed actions, bounds) — the two surfaces deliberately overlap so a `uia/hierarchy`
 * payload and an `unsupportedReason.nearMatch` field carry the same selector vocabulary.
 *
 * Lives in `:daemon:core` (not `:data-uiautomator-core`) because [RecordingScriptEvidence] lives
 * here and `:daemon:core` doesn't depend on `:data-uiautomator-core`. The shape is plain
 * JSON-serializable, no Compose dep.
 */
@Serializable
data class UiAutomatorNearMatchNode(
  val text: String? = null,
  val contentDescription: String? = null,
  val testTag: String? = null,
  val role: String? = null,
  /**
   * Action names this node exposes; same vocabulary as `UiAutomatorDataProducts.SUPPORTED_ACTIONS`.
   */
  val actions: List<String> = emptyList(),
  /** `left,top,right,bottom` in source-bitmap pixels — same shape `AccessibilityNode` uses. */
  val boundsInScreen: String,
)
