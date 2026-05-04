package ee.schimke.composeai.daemon

import ee.schimke.composeai.data.render.extensions.DataExtensionDescriptor
import ee.schimke.composeai.data.render.extensions.DataExtensionId
import ee.schimke.composeai.data.render.extensions.RecordingScriptEventDescriptor

/**
 * Touch / pointer-input `record_preview` script events. One descriptor — `input.touch` —
 * advertising the four pointer-shaped event ids both backends dispatch:
 *
 * - `input.click` — Press → render-tick → Release at the same image-natural pixel position.
 *   `Modifier.clickable {}` and primary-button-filtered tap-gesture detectors fire.
 * - `input.pointerDown` — `PointerEventType.Press` only.
 * - `input.pointerMove` — `PointerEventType.Move` with the primary button held.
 * - `input.pointerUp` — `PointerEventType.Release`.
 *
 * Lives in `:daemon:core` because both `DesktopHost` and `RobolectricHost` dispatch this
 * extension. Each host advertises the same descriptor from `recordingScriptEventDescriptors()`;
 * the dispatch end is host-specific (Skiko `sendPointerEvent` on desktop, the held-rule's
 * pointer pipeline on Android), but the descriptor advertisement is uniform.
 *
 * **Why split from `input.keyboard` and `input.rsb`.** Per-axis splits let each host advertise
 * the exact subset it dispatches: desktop carries `input.touch` + `input.keyboard` (latter as
 * roadmap); RobolectricHost carries `input.touch` + `input.keyboard` + `input.rsb`. One blanket
 * `input` extension would force per-host descriptor variants with different `supported` flags
 * — the per-axis split keeps the descriptors uniform across hosts and the per-host advertisement
 * trivially additive.
 */
object InputTouchRecordingScriptEvents {

  const val CLICK_EVENT: String = "input.click"
  const val POINTER_DOWN_EVENT: String = "input.pointerDown"
  const val POINTER_MOVE_EVENT: String = "input.pointerMove"
  const val POINTER_UP_EVENT: String = "input.pointerUp"

  /** All wired touch event ids. */
  val WIRED_EVENTS: Set<String> =
    setOf(CLICK_EVENT, POINTER_DOWN_EVENT, POINTER_MOVE_EVENT, POINTER_UP_EVENT)

  val descriptor: DataExtensionDescriptor =
    DataExtensionDescriptor(
      id = DataExtensionId("input.touch"),
      displayName = "Touch / pointer input",
      recordingScriptEvents =
        listOf(
          RecordingScriptEventDescriptor(
            id = CLICK_EVENT,
            displayName = "Click",
            summary =
              "Press → render-tick → Release at the same image-natural pixel position. " +
                "`Modifier.clickable {}` and primary-button-filtered tap-gesture detectors fire.",
            supported = true,
          ),
          RecordingScriptEventDescriptor(
            id = POINTER_DOWN_EVENT,
            displayName = "Pointer down",
            summary = "PointerEventType.Press at the supplied pixelX/pixelY.",
            supported = true,
          ),
          RecordingScriptEventDescriptor(
            id = POINTER_MOVE_EVENT,
            displayName = "Pointer move",
            summary = "PointerEventType.Move with the primary button held.",
            supported = true,
          ),
          RecordingScriptEventDescriptor(
            id = POINTER_UP_EVENT,
            displayName = "Pointer up",
            summary = "PointerEventType.Release at the supplied pixelX/pixelY.",
            supported = true,
          ),
        ),
    )

  /** Convenience for the host's `recordingScriptEventDescriptors()` override. */
  val descriptors: List<DataExtensionDescriptor> = listOf(descriptor)
}
