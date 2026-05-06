package ee.schimke.composeai.daemon

import ee.schimke.composeai.data.render.extensions.DataExtensionDescriptor
import ee.schimke.composeai.data.render.extensions.DataExtensionId
import ee.schimke.composeai.data.render.extensions.RecordingScriptEventDescriptor

/**
 * UIAutomator-shaped `record_preview` script events. Each `uia.<actionKind>` id targets a node by
 * a multi-axis [`SelectorJson`](https://github.com/yschimke/compose-ai-tools) (text, desc, clazz,
 * res, state predicates, tree predicates) — the agent supplies the predicate via the script
 * event's `selector` field — and dispatches the corresponding action. The handler routes through
 * `interactive.dispatchUiAutomator(actionKind, selectorJson, useUnmergedTree, inputText)`; the
 * sandbox-side `when` arm in [`RobolectricHost.performUiAutomatorAction`] does the matcher
 * resolution + invocation via `:data-uiautomator-core`'s `UiAutomator.findObject` and the matched
 * `UiObject`'s action method.
 *
 * Why this lives in `:data-uiautomator-connector` and not in `RecordingScriptDataExtensions` in
 * `:data-render-core`: UIAutomator dispatch is Android-only (Robolectric `SemanticsOwner` /
 * `View` traversal); desktop daemons don't advertise this descriptor. The wiring point is
 * `:daemon:android`'s [DaemonMain], which adds an `Extension(id="uiautomator", …)` carrying
 * [descriptor].
 *
 * **Single descriptor, all supported.** The 9 `uia.*` ids each correspond to a `UiObject` action
 * method backed by a `SemanticsActions` lambda or `View.performAccessibilityAction` route. To wire
 * a new action, add a `supportedEvent` entry below, a `when` arm in
 * `RobolectricHost.performUiAutomatorAction`, and a registry entry in `AndroidRecordingSession`'s
 * `uiautomator` handler block.
 */
object UiAutomatorRecordingScriptEvents {

  const val EXTENSION_ID: String = "uiautomator"

  /** Namespaced ids — `uia.<actionKind>` mirrors `:data-uiautomator-core`'s `UiObject` methods. */
  const val UIA_CLICK: String = "uia.click"
  const val UIA_LONG_CLICK: String = "uia.longClick"
  const val UIA_SCROLL_FORWARD: String = "uia.scrollForward"
  const val UIA_SCROLL_BACKWARD: String = "uia.scrollBackward"
  const val UIA_REQUEST_FOCUS: String = "uia.requestFocus"
  const val UIA_EXPAND: String = "uia.expand"
  const val UIA_COLLAPSE: String = "uia.collapse"
  const val UIA_DISMISS: String = "uia.dismiss"
  const val UIA_INPUT_TEXT: String = "uia.inputText"

  /** Wired event ids registered in `AndroidRecordingSession`'s handler block. */
  val WIRED_EVENTS: List<String> =
    listOf(
      UIA_CLICK,
      UIA_LONG_CLICK,
      UIA_SCROLL_FORWARD,
      UIA_SCROLL_BACKWARD,
      UIA_REQUEST_FOCUS,
      UIA_EXPAND,
      UIA_COLLAPSE,
      UIA_DISMISS,
      UIA_INPUT_TEXT,
    )

  val descriptor: DataExtensionDescriptor =
    DataExtensionDescriptor(
      id = DataExtensionId(EXTENSION_ID),
      displayName = "UIAutomator script actions",
      recordingScriptEvents =
        listOf(
          supportedEvent(
            UIA_CLICK,
            "Click (selector)",
            "Resolves a node by the event's `selector` JsonObject (text/desc/clazz/res/state/" +
              "tree predicates, mirrors androidx.test.uiautomator.BySelector) and invokes " +
              "`SemanticsActions.OnClick`. Default `useUnmergedTree=false` matches a " +
              "`Button { Text(\"Submit\") }` as one node.",
          ),
          supportedEvent(
            UIA_LONG_CLICK,
            "Long click (selector)",
            "Resolves a node by `selector` and invokes `SemanticsActions.OnLongClick`.",
          ),
          supportedEvent(
            UIA_SCROLL_FORWARD,
            "Scroll forward (selector)",
            "Resolves a node by `selector` and invokes `SemanticsActions.ScrollBy(0, +height)` " +
              "— one viewport-height forward scroll.",
          ),
          supportedEvent(
            UIA_SCROLL_BACKWARD,
            "Scroll backward (selector)",
            "Resolves a node by `selector` and invokes `SemanticsActions.ScrollBy(0, -height)`.",
          ),
          supportedEvent(
            UIA_REQUEST_FOCUS,
            "Request focus (selector)",
            "Resolves a node by `selector` and invokes `SemanticsActions.RequestFocus`.",
          ),
          supportedEvent(
            UIA_EXPAND,
            "Expand (selector)",
            "Resolves a node by `selector` and invokes `SemanticsActions.Expand`.",
          ),
          supportedEvent(
            UIA_COLLAPSE,
            "Collapse (selector)",
            "Resolves a node by `selector` and invokes `SemanticsActions.Collapse`.",
          ),
          supportedEvent(
            UIA_DISMISS,
            "Dismiss (selector)",
            "Resolves a node by `selector` and invokes `SemanticsActions.Dismiss`.",
          ),
          supportedEvent(
            UIA_INPUT_TEXT,
            "Input text (selector)",
            "Resolves a node by `selector` and invokes `SemanticsActions.SetText(inputText)` — " +
              "BasicTextField / EditText / any node with a SetText semantic. Reads the typed " +
              "value from the event's `inputText` field.",
          ),
        ),
    )

  /** Convenience for the host wiring point — the daemon's extension registry takes a list. */
  val descriptors: List<DataExtensionDescriptor> = listOf(descriptor)

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
