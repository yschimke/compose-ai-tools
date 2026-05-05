package ee.schimke.composeai.daemon

import ee.schimke.composeai.data.render.extensions.DataExtensionDescriptor
import ee.schimke.composeai.data.render.extensions.DataExtensionId
import ee.schimke.composeai.data.render.extensions.RecordingScriptEventDescriptor

/**
 * Keyboard `record_preview` script events. One descriptor — `input.keyboard` — advertising
 * `input.keyDown` and `input.keyUp` as **roadmap** (`supported = false`) on every host today.
 * Desktop's `ImageComposeScene.sendKeyEvent` and Android's held-rule `KeyEvent.dispatchKeyEvent`
 * are both wirable but neither is implemented yet — the existing input handlers register these as
 * unsupported so the wire shape is documented while the dispatch side remains a follow-up.
 *
 * Lives in `:daemon:core` because the descriptor is renderer-agnostic — both backends will
 * eventually advertise it from `recordingScriptEventDescriptors()` with `supported = true` once
 * dispatch lands.
 */
object InputKeyboardRecordingScriptEvents {

  const val KEY_DOWN_EVENT: String = "input.keyDown"
  const val KEY_UP_EVENT: String = "input.keyUp"

  val descriptor: DataExtensionDescriptor =
    DataExtensionDescriptor(
      id = DataExtensionId("input.keyboard"),
      displayName = "Keyboard input",
      recordingScriptEvents =
        listOf(
          RecordingScriptEventDescriptor(
            id = KEY_DOWN_EVENT,
            displayName = "Key down",
            summary =
              "Reserved for keyboard dispatch. Wire shape carries `keyCode`; daemon-side " +
                "dispatch (Skiko `sendKeyEvent` on desktop, held-rule key event on Android) is " +
                "a follow-up — both backends register this as unsupported today.",
            supported = false,
          ),
          RecordingScriptEventDescriptor(
            id = KEY_UP_EVENT,
            displayName = "Key up",
            summary = "Counterpart to keyDown; same dispatch follow-up.",
            supported = false,
          ),
        ),
    )

  /** Convenience for the host's `recordingScriptEventDescriptors()` override. */
  val descriptors: List<DataExtensionDescriptor> = listOf(descriptor)
}
