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
   * `a11y.action.click` is the only a11y action wired today. `RobolectricHost`'s sandbox-side
   * loop resolves the matching node by `hasContentDescription(...)` and invokes
   * `SemanticsActions.OnClick.action.invoke()` — same semantic path
   * `AccessibilityNodeInfo.performAction(ACTION_CLICK)` walks. The descriptor advertises
   * `supported = true` so `record_preview` accepts it; the rest of the action ids appear in
   * [roadmapDescriptors] until their handler ships.
   */
  val supportedDescriptors: List<DataExtensionDescriptor> =
    listOf(
      DataExtensionDescriptor(
        id = DataExtensionId("a11y"),
        displayName = "Accessibility script actions",
        recordingScriptEvents =
          listOf(
            RecordingScriptEventDescriptor(
              id = ACTION_CLICK,
              displayName = "Click",
              summary =
                "Performs ACTION_CLICK on the accessibility node identified by " +
                  "`nodeContentDescription`. Same path a screen reader walks via " +
                  "AccessibilityNodeInfo.performAction(ACTION_CLICK).",
              supported = true,
            )
          ),
      )
    )

  /**
   * Roadmap a11y action descriptors — `supported = false`. Each one ships individually as a
   * `RobolectricHost.performSemanticsActionByContentDescription` arm + a registry entry in
   * `AndroidRecordingSession`. When that lands, move the matching `RecordingScriptEventDescriptor`
   * into the `supportedDescriptors` `a11y` extension above.
   */
  val roadmapDescriptors: List<DataExtensionDescriptor> =
    listOf(
      DataExtensionDescriptor(
        id = DataExtensionId("a11y.roadmap"),
        displayName = "Accessibility script actions (roadmap)",
        recordingScriptEvents =
          listOf(
            event(
              ACTION_LONG_CLICK,
              "Long click",
              "Performs ACTION_LONG_CLICK on the targeted accessibility node.",
            ),
            event(ACTION_FOCUS, "Focus", "Performs ACTION_FOCUS on the targeted node."),
            event(
              ACTION_CLEAR_FOCUS,
              "Clear focus",
              "Performs ACTION_CLEAR_FOCUS on the targeted node.",
            ),
            event(
              ACTION_ACCESSIBILITY_FOCUS,
              "Accessibility focus",
              "Performs ACTION_ACCESSIBILITY_FOCUS — the action a screen reader emits when the " +
                "user swipes onto a node.",
            ),
            event(
              ACTION_CLEAR_ACCESSIBILITY_FOCUS,
              "Clear accessibility focus",
              "Performs ACTION_CLEAR_ACCESSIBILITY_FOCUS, the screen-reader counterpart to " +
                "swiping off a node.",
            ),
            event(ACTION_SELECT, "Select", "Performs ACTION_SELECT on the targeted node."),
            event(
              ACTION_CLEAR_SELECTION,
              "Clear selection",
              "Performs ACTION_CLEAR_SELECTION on the targeted node.",
            ),
            event(
              ACTION_SCROLL_FORWARD,
              "Scroll forward",
              "Performs ACTION_SCROLL_FORWARD on the targeted scrollable node.",
            ),
            event(
              ACTION_SCROLL_BACKWARD,
              "Scroll backward",
              "Performs ACTION_SCROLL_BACKWARD on the targeted scrollable node.",
            ),
            event(
              ACTION_SCROLL_UP,
              "Scroll up",
              "Performs ACTION_SCROLL_UP on the targeted scrollable node.",
            ),
            event(
              ACTION_SCROLL_DOWN,
              "Scroll down",
              "Performs ACTION_SCROLL_DOWN on the targeted scrollable node.",
            ),
            event(
              ACTION_SCROLL_LEFT,
              "Scroll left",
              "Performs ACTION_SCROLL_LEFT on the targeted scrollable node.",
            ),
            event(
              ACTION_SCROLL_RIGHT,
              "Scroll right",
              "Performs ACTION_SCROLL_RIGHT on the targeted scrollable node.",
            ),
            event(ACTION_EXPAND, "Expand", "Performs ACTION_EXPAND on the targeted node."),
            event(ACTION_COLLAPSE, "Collapse", "Performs ACTION_COLLAPSE on the targeted node."),
            event(ACTION_DISMISS, "Dismiss", "Performs ACTION_DISMISS on the targeted node."),
            event(
              ACTION_NEXT_AT_GRANULARITY,
              "Next at movement granularity",
              "Performs ACTION_NEXT_AT_MOVEMENT_GRANULARITY on the targeted text node.",
            ),
            event(
              ACTION_PREVIOUS_AT_GRANULARITY,
              "Previous at movement granularity",
              "Performs ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY on the targeted text node.",
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

  private fun event(
    id: String,
    displayName: String,
    summary: String,
  ): RecordingScriptEventDescriptor =
    RecordingScriptEventDescriptor(
      id = id,
      displayName = displayName,
      summary = summary,
      // Dispatch lands with the recording-script handler registry refactor; until then the
      // descriptor advertises the surface area as roadmap and `record_preview` rejects up front.
      supported = false,
    )
}
