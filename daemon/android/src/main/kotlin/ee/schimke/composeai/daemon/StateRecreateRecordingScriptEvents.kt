package ee.schimke.composeai.daemon

import ee.schimke.composeai.data.render.extensions.DataExtensionDescriptor
import ee.schimke.composeai.data.render.extensions.DataExtensionId
import ee.schimke.composeai.data.render.extensions.RecordingScriptEventDescriptor

/**
 * State-recreate `record_preview` script event. One descriptor — `state.recreate` — that snapshots
 * the current `SaveableStateRegistry`, tears down the held composition under a `key(...)`
 * boundary, and rebuilds with the snapshot restored. Same audit signal as an Android
 * `ActivityScenario.recreate()` (verifies state survives a teardown) but lives entirely at the
 * Compose level so it doesn't depend on `onSaveInstanceState`/onCreate plumbing.
 *
 * **Distinct from the other state-shaped events:**
 * - `lifecycle.event` (`pause`/`resume`/`stop`) — drives the activity lifecycle without
 *   destroying it. `rememberSaveable` AND `remember` both survive.
 * - `preview.reload` — full cold composition. Both `remember` and `rememberSaveable` reset.
 * - `state.recreate` — the middle ground. `rememberSaveable` survives via a snapshot/restore
 *   round-trip; `remember` resets.
 *
 * **Distinct from `state.save` / `state.restore` (still roadmap).** The named-checkpoint pair
 * lets agents save multiple bundles and restore any one. `state.recreate` is the simpler
 * single-event "round-trip" primitive — it's enough for the most common audit case ("does this
 * preview survive a recreate?") without the multi-bundle stash machinery.
 *
 * Why this lives in `:daemon:android` (mirroring [LifecycleRecordingScriptEvents] and
 * [PreviewReloadRecordingScriptEvents]): the `SaveableStateRegistry` bridge needs the held rule
 * machinery, which is Robolectric-only today.
 *
 * **Status:** the descriptor ships with `supported = true`. Hosts that wire the
 * `SaveableStateRegistry` bridge (today: only [RobolectricHost]) advertise it from
 * `recordingScriptEventDescriptors()`.
 */
object StateRecreateRecordingScriptEvents {

  /** Wire-name id. New constant — distinct from `state.save` / `state.restore` (still roadmap). */
  const val STATE_RECREATE_EVENT: String = "state.recreate"

  val descriptor: DataExtensionDescriptor =
    DataExtensionDescriptor(
      id = DataExtensionId("state.recreate"),
      displayName = "State recreate (Compose-level save+restore)",
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
          )
        ),
    )

  /** Convenience for the host's `recordingScriptEventDescriptors()` override. */
  val descriptors: List<DataExtensionDescriptor> = listOf(descriptor)
}
