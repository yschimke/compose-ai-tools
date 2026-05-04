package ee.schimke.composeai.daemon

import ee.schimke.composeai.data.render.extensions.DataExtensionDescriptor
import ee.schimke.composeai.data.render.extensions.DataExtensionId
import ee.schimke.composeai.data.render.extensions.RecordingScriptEventDescriptor

/**
 * Accessibility-driven `record_preview` script events — see
 * [`compose-preview-review/design/AGENT_AUDITS.md`](../../../../../../skills/compose-preview-review/design/AGENT_AUDITS.md)
 * § "Accessibility-driven interaction audit" (planned).
 *
 * Each id maps to one [`AccessibilityNodeInfo`](https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo)
 * action constant. An `a11y.action.<name>` event in a recording script means "find the node
 * identified by `params` (today: `nodeId` from the most recent `a11y/hierarchy` payload — content-
 * description / semantics-tag matching is a follow-up) and dispatch the corresponding accessibility
 * action through `AccessibilityNodeInfo.performAction(...)`."
 *
 * Why these belong on a separate descriptor and not on `RecordingScriptDataExtensions` in
 * `:data-render-core`: a11y dispatch is Android-only (TalkBack-equivalent semantics drive these
 * actions) and lives in the a11y module pair. Desktop daemons don't advertise this descriptor at
 * all. The wiring point is `:daemon:android`'s [DaemonMain], which concatenates this list onto the
 * base recording-script descriptors when `a11y` is enabled — same gate as the a11y preview-
 * extension publishers.
 *
 * **Status:** [supportedDescriptors] today carries `a11y.action.click` only — wired end-to-end
 * through `AndroidInteractiveSession.dispatchSemanticsAction` →
 * `RobolectricHost.performSemanticsActionByContentDescription` →
 * `SemanticsActions.OnClick.action.invoke()`. [roadmapDescriptors] carries the other 18 actions
 * with `supported = false`; the MCP layer rejects them up front, and each lands one-by-one as
 * its sandbox-side `when` arm + handler factory ships.
 */
object AccessibilityRecordingScriptEvents {

  /** Namespaced ids match `AccessibilityAction`'s short names. */
  const val ACTION_CLICK: String = "a11y.action.click"
  const val ACTION_LONG_CLICK: String = "a11y.action.longClick"
  const val ACTION_FOCUS: String = "a11y.action.focus"
  const val ACTION_CLEAR_FOCUS: String = "a11y.action.clearFocus"
  const val ACTION_ACCESSIBILITY_FOCUS: String = "a11y.action.accessibilityFocus"
  const val ACTION_CLEAR_ACCESSIBILITY_FOCUS: String = "a11y.action.clearAccessibilityFocus"
  const val ACTION_SELECT: String = "a11y.action.select"
  const val ACTION_CLEAR_SELECTION: String = "a11y.action.clearSelection"
  const val ACTION_SCROLL_FORWARD: String = "a11y.action.scrollForward"
  const val ACTION_SCROLL_BACKWARD: String = "a11y.action.scrollBackward"
  const val ACTION_SCROLL_UP: String = "a11y.action.scrollUp"
  const val ACTION_SCROLL_DOWN: String = "a11y.action.scrollDown"
  const val ACTION_SCROLL_LEFT: String = "a11y.action.scrollLeft"
  const val ACTION_SCROLL_RIGHT: String = "a11y.action.scrollRight"
  const val ACTION_EXPAND: String = "a11y.action.expand"
  const val ACTION_COLLAPSE: String = "a11y.action.collapse"
  const val ACTION_DISMISS: String = "a11y.action.dismiss"
  const val ACTION_NEXT_AT_GRANULARITY: String = "a11y.action.nextAtGranularity"
  const val ACTION_PREVIOUS_AT_GRANULARITY: String = "a11y.action.previousAtGranularity"

  /**
   * Currently-wired a11y actions. Each `recording.scriptEvents[*].id` here corresponds to a
   * `when` arm in `RobolectricHost.performSemanticsActionByContentDescription` plus a registry
   * entry in `AndroidRecordingSession.A11Y_SEMANTIC_ACTIONS`. The handler resolves a node by
   * `hasContentDescription(...)` and invokes the matching `SemanticsActions` constant — same
   * path `AccessibilityNodeInfo.performAction(...)` walks via TalkBack.
   *
   * The 6 scroll variants all ride on `SemanticsActions.ScrollBy(dx, dy)`: `scrollForward` /
   * `scrollDown` push y forward by one viewport-height, `scrollBackward` / `scrollUp` push y
   * backward, `scrollLeft` / `scrollRight` push x. Horizontal scrollers should use the explicit
   * left/right ids; the forward/backward pair is vertical-axis-first. Future iteration could
   * read the node's scroll-axis ranges and pick the dominant axis automatically.
   */
  val supportedDescriptors: List<DataExtensionDescriptor> =
    listOf(
      DataExtensionDescriptor(
        id = DataExtensionId("a11y"),
        displayName = "Accessibility script actions",
        recordingScriptEvents =
          listOf(
            supportedEvent(
              ACTION_CLICK,
              "Click",
              "Performs ACTION_CLICK on the targeted accessibility node via SemanticsActions.OnClick.",
            ),
            supportedEvent(
              ACTION_LONG_CLICK,
              "Long click",
              "Performs ACTION_LONG_CLICK on the targeted accessibility node via SemanticsActions.OnLongClick.",
            ),
            supportedEvent(
              ACTION_FOCUS,
              "Focus",
              "Performs ACTION_FOCUS on the targeted node via SemanticsActions.RequestFocus.",
            ),
            supportedEvent(
              ACTION_EXPAND,
              "Expand",
              "Performs ACTION_EXPAND on the targeted node via SemanticsActions.Expand.",
            ),
            supportedEvent(
              ACTION_COLLAPSE,
              "Collapse",
              "Performs ACTION_COLLAPSE on the targeted node via SemanticsActions.Collapse.",
            ),
            supportedEvent(
              ACTION_DISMISS,
              "Dismiss",
              "Performs ACTION_DISMISS on the targeted node via SemanticsActions.Dismiss.",
            ),
            supportedEvent(
              ACTION_SCROLL_FORWARD,
              "Scroll forward",
              "Scrolls the targeted scrollable forward by one viewport-height via " +
                "SemanticsActions.ScrollBy(0, +height). Vertical-axis primary; for horizontal " +
                "scrollers use scrollLeft/scrollRight directly.",
            ),
            supportedEvent(
              ACTION_SCROLL_BACKWARD,
              "Scroll backward",
              "Scrolls the targeted scrollable backward by one viewport-height via " +
                "SemanticsActions.ScrollBy(0, -height). Vertical-axis primary.",
            ),
            supportedEvent(
              ACTION_SCROLL_UP,
              "Scroll up",
              "Scrolls the targeted scrollable up by one viewport-height via SemanticsActions.ScrollBy(0, -height).",
            ),
            supportedEvent(
              ACTION_SCROLL_DOWN,
              "Scroll down",
              "Scrolls the targeted scrollable down by one viewport-height via SemanticsActions.ScrollBy(0, +height).",
            ),
            supportedEvent(
              ACTION_SCROLL_LEFT,
              "Scroll left",
              "Scrolls the targeted scrollable left by one viewport-width via SemanticsActions.ScrollBy(-width, 0).",
            ),
            supportedEvent(
              ACTION_SCROLL_RIGHT,
              "Scroll right",
              "Scrolls the targeted scrollable right by one viewport-width via SemanticsActions.ScrollBy(+width, 0).",
            ),
          ),
      )
    )

  /**
   * Roadmap a11y action descriptors — `supported = false`. These don't have a clean
   * `SemanticsActions` equivalent in Compose:
   *
   * - `clearFocus` — Compose doesn't expose a `ClearFocus` semantic (focus is owned by the
   *   FocusManager; releasing focus is internal).
   * - `accessibilityFocus` / `clearAccessibilityFocus` — TalkBack-internal screen-reader focus,
   *   not a user-invokable action on Compose nodes.
   * - `select` / `clearSelection` — Compose's `Selected` semantic is state, not action; toggling
   *   typically happens through `OnClick`. No `SetSelected` semantic action exists.
   * - `nextAtGranularity` / `previousAtGranularity` — text-cursor navigation needs a granularity
   *   argument and ties into `SetSelection`; sketching the right wire shape is its own design
   *   pass.
   *
   * `record_preview` rejects these up front via `validateRecordingScriptKinds`. Each lands when
   * Compose grows the matching semantic action OR we wire a direct `AccessibilityNodeInfo`
   * fallback path for the ones it never will.
   */
  val roadmapDescriptors: List<DataExtensionDescriptor> =
    listOf(
      DataExtensionDescriptor(
        id = DataExtensionId("a11y.roadmap"),
        displayName = "Accessibility script actions (roadmap)",
        recordingScriptEvents =
          listOf(
            event(
              ACTION_CLEAR_FOCUS,
              "Clear focus",
              "Releases focus from the targeted node. No SemanticsActions equivalent today; " +
                "tracked as roadmap.",
            ),
            event(
              ACTION_ACCESSIBILITY_FOCUS,
              "Accessibility focus",
              "TalkBack-internal screen-reader focus action; not a user-invokable Compose " +
                "semantic. Tracked as roadmap.",
            ),
            event(
              ACTION_CLEAR_ACCESSIBILITY_FOCUS,
              "Clear accessibility focus",
              "TalkBack-internal counterpart to accessibilityFocus. Tracked as roadmap.",
            ),
            event(
              ACTION_SELECT,
              "Select",
              "Marks the targeted node as selected. Compose's `Selected` semantic is state, " +
                "not an action; tracked as roadmap until a `SetSelected` semantic ships.",
            ),
            event(
              ACTION_CLEAR_SELECTION,
              "Clear selection",
              "Counterpart to select; tracked as roadmap.",
            ),
            event(
              ACTION_NEXT_AT_GRANULARITY,
              "Next at movement granularity",
              "Text-cursor navigation by character/word/paragraph. Needs a granularity " +
                "argument; wire shape TBD. Tracked as roadmap.",
            ),
            event(
              ACTION_PREVIOUS_AT_GRANULARITY,
              "Previous at movement granularity",
              "Counterpart to nextAtGranularity; tracked as roadmap.",
            ),
          ),
      )
    )

  /**
   * Combined list ([supportedDescriptors] + [roadmapDescriptors]) for callers that want to
   * advertise the full a11y action surface in one go. New code should prefer the split lists so
   * supported / roadmap halves can flow through the host-derived dataExtensions composition
   * without re-flagging supported flags.
   */
  val descriptors: List<DataExtensionDescriptor> = supportedDescriptors + roadmapDescriptors

  /** Roadmap descriptor — `supported = false`. */
  private fun event(
    id: String,
    displayName: String,
    summary: String,
  ): RecordingScriptEventDescriptor =
    RecordingScriptEventDescriptor(
      id = id,
      displayName = displayName,
      summary = summary,
      supported = false,
    )

  /** Wired descriptor — `supported = true`; matches an entry in `AndroidRecordingSession.A11Y_SEMANTIC_ACTIONS`. */
  private fun supportedEvent(
    id: String,
    displayName: String,
    summary: String,
  ): RecordingScriptEventDescriptor =
    RecordingScriptEventDescriptor(
      id = id,
      displayName = displayName,
      summary = summary,
      supported = true,
    )
}
