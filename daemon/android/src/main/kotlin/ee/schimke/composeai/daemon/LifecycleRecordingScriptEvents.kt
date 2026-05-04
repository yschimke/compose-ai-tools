package ee.schimke.composeai.daemon

import ee.schimke.composeai.data.render.extensions.DataExtensionDescriptor
import ee.schimke.composeai.data.render.extensions.DataExtensionId
import ee.schimke.composeai.data.render.extensions.RecordingScriptEventDescriptor

/**
 * Lifecycle-driven `record_preview` script events — see
 * [`compose-preview-review/design/AGENT_AUDITS.md`](../../../../../../skills/compose-preview-review/design/AGENT_AUDITS.md)
 * § "State restoration and lifecycle audit".
 *
 * Three event ids — `lifecycle.pause`, `lifecycle.resume`, `lifecycle.stop` — each driving
 * `ActivityScenario.moveToState(...)` on the held rule's activity:
 *
 * - `lifecycle.pause` → `Lifecycle.State.STARTED` (activity goes through onPause)
 * - `lifecycle.resume` → `Lifecycle.State.RESUMED` (activity goes through onResume)
 * - `lifecycle.stop` → `Lifecycle.State.CREATED` (activity goes through onPause + onStop)
 *
 * **Why three ids instead of one `lifecycle.event` with a `lifecycleEvent` payload.** Per-state
 * ids let the MCP validator reject unknown transitions at the kind level (no special payload
 * check needed), make the surface self-documenting in `list_data_products`, and align with
 * `a11y.action.*` / `state.*` / `preview.*` shapes — every other extension uses kind-level
 * discrimination.
 *
 * `"destroy"` is intentionally NOT one of the ids — moving to DESTROYED mid-recording would tear
 * down the scenario and break subsequent renders. If we wire it in a future PR it would land as
 * `lifecycle.destroy`.
 *
 * Why this lives in `:daemon:android` and not in a `:data-lifecycle-connector` module like the
 * a11y descriptors do: lifecycle has no data products (no per-render JSON / overlay PNG), so
 * there's no separate Android-only data module to colocate with. The dispatch end is in
 * [RobolectricHost.SandboxRunner.performLifecycleTransition]; the descriptor end lives next to
 * it.
 */
object LifecycleRecordingScriptEvents {

  /** `lifecycle.pause` → `Lifecycle.State.STARTED`. Drives `onPause`. */
  const val LIFECYCLE_PAUSE_EVENT: String = "lifecycle.pause"

  /** `lifecycle.resume` → `Lifecycle.State.RESUMED`. Drives `onResume`. */
  const val LIFECYCLE_RESUME_EVENT: String = "lifecycle.resume"

  /** `lifecycle.stop` → `Lifecycle.State.CREATED`. Drives `onPause` + `onStop`. */
  const val LIFECYCLE_STOP_EVENT: String = "lifecycle.stop"

  /** All wired lifecycle event ids. */
  val WIRED_EVENTS: Set<String> =
    setOf(LIFECYCLE_PAUSE_EVENT, LIFECYCLE_RESUME_EVENT, LIFECYCLE_STOP_EVENT)

  val descriptor: DataExtensionDescriptor =
    DataExtensionDescriptor(
      id = DataExtensionId("lifecycle"),
      displayName = "Lifecycle script controls",
      recordingScriptEvents =
        listOf(
          RecordingScriptEventDescriptor(
            id = LIFECYCLE_PAUSE_EVENT,
            displayName = "Pause",
            summary =
              "Moves the held activity to Lifecycle.State.STARTED via " +
                "ActivityScenario.moveToState. Drives `onPause` on the way.",
            supported = true,
          ),
          RecordingScriptEventDescriptor(
            id = LIFECYCLE_RESUME_EVENT,
            displayName = "Resume",
            summary =
              "Moves the held activity to Lifecycle.State.RESUMED via " +
                "ActivityScenario.moveToState. Drives `onResume` on the way.",
            supported = true,
          ),
          RecordingScriptEventDescriptor(
            id = LIFECYCLE_STOP_EVENT,
            displayName = "Stop",
            summary =
              "Moves the held activity to Lifecycle.State.CREATED via " +
                "ActivityScenario.moveToState. Drives `onPause` + `onStop` on the way.",
            supported = true,
          ),
        ),
    )

  /** Convenience for the host's `recordingScriptEventDescriptors()` override. */
  val descriptors: List<DataExtensionDescriptor> = listOf(descriptor)
}
