package ee.schimke.composeai.daemon

import ee.schimke.composeai.data.render.extensions.DataExtensionDescriptor
import ee.schimke.composeai.data.render.extensions.DataExtensionId
import ee.schimke.composeai.data.render.extensions.RecordingScriptEventDescriptor

/**
 * Rotary side-button (RSB) `record_preview` script events for Wear previews. One descriptor —
 * `input.rsb` — advertising `input.rotaryScroll` as `supported = true` on the Android host.
 *
 * Lives in `:daemon:android` because RSB dispatch is Wear/Android-only — the held-rule loop's
 * native `MotionEvent.SOURCE_ROTARY_ENCODER + ACTION_SCROLL` path doesn't have a desktop
 * equivalent (`ImageComposeScene` exposes `sendPointerEvent` but no rotary channel). Only
 * `RobolectricHost.recordingScriptEventDescriptors()` advertises this descriptor; desktop
 * daemons skip it.
 *
 * The companion touch / keyboard descriptors live in `:daemon:core` since both backends share
 * those contracts.
 */
object InputRsbRecordingScriptEvents {

  const val ROTARY_SCROLL_EVENT: String = "input.rotaryScroll"

  val descriptor: DataExtensionDescriptor =
    DataExtensionDescriptor(
      id = DataExtensionId("input.rsb"),
      displayName = "Rotary side-button input",
      recordingScriptEvents =
        listOf(
          RecordingScriptEventDescriptor(
            id = ROTARY_SCROLL_EVENT,
            displayName = "Rotary scroll",
            summary =
              "Synthesises a SOURCE_ROTARY_ENCODER MotionEvent and dispatches it to the focused " +
                "Compose view. `scrollDeltaY` payload carries the wheel delta (positive = " +
                "wheel-down). Wear-only — desktop daemons don't advertise this descriptor.",
            supported = true,
          )
        ),
    )

  /** Convenience for the host's `recordingScriptEventDescriptors()` override. */
  val descriptors: List<DataExtensionDescriptor> = listOf(descriptor)
}
