package ee.schimke.composeai.daemon

import ee.schimke.composeai.data.render.extensions.DataExtensionDescriptor
import ee.schimke.composeai.data.render.extensions.DataExtensionId
import ee.schimke.composeai.data.render.extensions.RecordingScriptEventDescriptor

/**
 * Keyboard `record_preview` script events. One descriptor — `input.keyboard` — advertising
 * `input.keyDown` and `input.keyUp`. Each host calls [descriptor] with its own `supported` flag:
 * hosts that have wired the dispatch (Desktop via `ImageComposeScene.sendKeyEvent`, Android via the
 * held-rule `performKeyInput`) pass `supported = true`; backends still on the no-op path pass
 * `supported = false` so the wire shape is documented while the dispatch side remains a follow-up.
 *
 * Lives in `:daemon:core` because the descriptor is renderer-agnostic — both backends advertise it
 * from `recordingScriptEventDescriptors()`. Issue #1203 closed the desktop/Android no-op gaps.
 */
object InputKeyboardRecordingScriptEvents {

  const val KEY_DOWN_EVENT: String = "input.keyDown"
  const val KEY_UP_EVENT: String = "input.keyUp"

  /**
   * Build the descriptor with [supported] set on each script event. Hosts call this with `supported
   * = true` once they've wired real dispatch; the pre-#1203 default (`supported = false`) remains
   * available for backends that haven't yet.
   */
  fun descriptor(supported: Boolean): DataExtensionDescriptor =
    DataExtensionDescriptor(
      id = DataExtensionId("input.keyboard"),
      displayName = "Keyboard input",
      recordingScriptEvents =
        listOf(
          RecordingScriptEventDescriptor(
            id = KEY_DOWN_EVENT,
            displayName = "Key down",
            summary =
              "Synthesise a keyDown via the wire's Android `KEYCODE_*` int (decimal string). " +
                "Desktop translates through `DesktopKeyDispatch` to a Compose `Key`; Android " +
                "dispatches via the held-rule `performKeyInput` block.",
            supported = supported,
          ),
          RecordingScriptEventDescriptor(
            id = KEY_UP_EVENT,
            displayName = "Key up",
            summary = "Counterpart to keyDown; same wire shape, mirrors dispatch.",
            supported = supported,
          ),
        ),
    )

  /** Convenience for hosts whose dispatch path is wired. */
  val supportedDescriptor: DataExtensionDescriptor = descriptor(supported = true)

  /** Legacy const-style alias retained for backends that haven't wired key dispatch yet. */
  val descriptor: DataExtensionDescriptor = descriptor(supported = false)

  /** Convenience for the host's `recordingScriptEventDescriptors()` override. */
  val descriptors: List<DataExtensionDescriptor> = listOf(descriptor)
}
