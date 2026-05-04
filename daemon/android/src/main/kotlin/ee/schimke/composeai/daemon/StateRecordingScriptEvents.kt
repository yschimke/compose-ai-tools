package ee.schimke.composeai.daemon

import ee.schimke.composeai.data.render.extensions.DataExtensionDescriptor
import ee.schimke.composeai.data.render.extensions.DataExtensionId
import ee.schimke.composeai.data.render.extensions.RecordingScriptDataExtensions
import ee.schimke.composeai.data.render.extensions.RecordingScriptEventDescriptor

/**
 * State-related `record_preview` script events. One descriptor — `state` — owning three events
 * that all ride on the held rule's `SaveableStateRegistry` bridge:
 *
 * - **`state.recreate`** — single-event round-trip. Snapshot current saveable state, tear down
 *   under a `key(...)` boundary, rebuild with the snapshot restored. `rememberSaveable`
 *   survives, `remember` resets. The pragmatic "did this preview survive a recreate?"
 *   primitive.
 * - **`state.save`** — named-checkpoint capture. Takes a `checkpointId` (agent-supplied string)
 *   and stores the current saveable bundle under that key without rebuilding the composition.
 *   Multiple checkpoints can coexist; a later `state.restore` picks one by id.
 * - **`state.restore`** — named-checkpoint restore. Takes a `checkpointId`, looks up the bundle
 *   stashed by an earlier `state.save`, and rebuilds the composition with that bundle restored.
 *
 * **Distinct from the other state-shaped events:**
 * - `lifecycle.event` (`pause`/`resume`/`stop`) — drives the activity lifecycle without
 *   destroying it. `rememberSaveable` AND `remember` both survive.
 * - `preview.reload` — full cold composition. Both `remember` and `rememberSaveable` reset.
 *
 * **`state.save` / `state.restore` vs `state.recreate`.** The pair lets agents capture multiple
 * named checkpoints and restore an arbitrary one — useful when the audit needs to compare
 * before/after states or when a single recreate isn't enough to exercise the path. Use
 * `state.recreate` when one round-trip is all you need; use the pair when checkpoint identity
 * matters.
 *
 * Why this lives in `:daemon:android` (mirroring [LifecycleRecordingScriptEvents] and
 * [PreviewReloadRecordingScriptEvents]): the `SaveableStateRegistry` bridge needs the held rule
 * machinery, which is Robolectric-only today.
 *
 * **Status:** all three events ship with `supported = true`. Hosts that wire the bridge (today:
 * only [RobolectricHost]) advertise the descriptor from `recordingScriptEventDescriptors()`.
 */
object StateRecordingScriptEvents {

  /** Wire-name id matching the recreate primitive. */
  const val STATE_RECREATE_EVENT: String = "state.recreate"

  /** Wire-name id matching `RecordingScriptDataExtensions.STATE_SAVE_EVENT`. */
  const val STATE_SAVE_EVENT: String = RecordingScriptDataExtensions.STATE_SAVE_EVENT

  /** Wire-name id matching `RecordingScriptDataExtensions.STATE_RESTORE_EVENT`. */
  const val STATE_RESTORE_EVENT: String = RecordingScriptDataExtensions.STATE_RESTORE_EVENT

  val descriptor: DataExtensionDescriptor =
    DataExtensionDescriptor(
      id = DataExtensionId("state"),
      displayName = "State recording (Compose-level save+restore)",
      recordingScriptEvents =
        listOf(
          RecordingScriptEventDescriptor(
            id = STATE_RECREATE_EVENT,
            displayName = "Recreate composition",
            summary =
              "Snapshots the current `SaveableStateRegistry` via `performSave()`, tears down " +
                "the held composition under a `key(...)` boundary, and rebuilds with the " +
                "snapshot restored. `rememberSaveable` survives; `remember` resets. Same audit " +
                "signal as `ActivityScenario.recreate()` but Compose-level.",
            supported = true,
          ),
          RecordingScriptEventDescriptor(
            id = STATE_SAVE_EVENT,
            displayName = "Save state checkpoint",
            summary =
              "Snapshots the current `SaveableStateRegistry` and stores it under the agent's " +
                "`checkpointId`. Doesn't rebuild the composition — pair with a later " +
                "`state.restore` with the same id to apply the saved bundle.",
            supported = true,
          ),
          RecordingScriptEventDescriptor(
            id = STATE_RESTORE_EVENT,
            displayName = "Restore state checkpoint",
            summary =
              "Looks up the bundle stashed by an earlier `state.save` with matching " +
                "`checkpointId` and rebuilds the held composition with it restored. Reports " +
                "unsupported when no checkpoint with that id exists.",
            supported = true,
          ),
        ),
    )

  /** Convenience for the host's `recordingScriptEventDescriptors()` override. */
  val descriptors: List<DataExtensionDescriptor> = listOf(descriptor)
}
