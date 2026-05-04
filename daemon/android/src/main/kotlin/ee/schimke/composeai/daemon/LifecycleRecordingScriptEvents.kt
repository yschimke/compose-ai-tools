package ee.schimke.composeai.daemon

import ee.schimke.composeai.data.render.extensions.DataExtensionDescriptor
import ee.schimke.composeai.data.render.extensions.DataExtensionId
import ee.schimke.composeai.data.render.extensions.RecordingScriptEventDescriptor

/**
 * Lifecycle-driven `record_preview` script events — see
 * [`compose-preview-review/design/AGENT_AUDITS.md`](../../../../../../skills/compose-preview-review/design/AGENT_AUDITS.md)
 * § "State restoration and lifecycle audit".
 *
 * One descriptor — `lifecycle.event` — that drives `ActivityScenario.moveToState(...)` on the
 * held rule's activity. The wire payload's `lifecycleEvent` field selects the target state:
 * `"pause"` → STARTED, `"resume"` → RESUMED, `"stop"` → CREATED. `"destroy"` is intentionally
 * not part of v1 — moving to DESTROYED mid-recording would tear down the scenario and break
 * subsequent renders.
 *
 * Why this lives in `:daemon:android` and not in a `:data-lifecycle-connector` module like the
 * a11y descriptors do: lifecycle has no data products (no per-render JSON / overlay PNG), so
 * there's no separate Android-only data module to colocate with. The dispatch end is in
 * [RobolectricHost.SandboxRunner.performLifecycleTransition]; the descriptor end lives next to
 * it.
 *
 * **Status:** the single descriptor ships with `supported = true`. Hosts that can dispatch
 * lifecycle (today: only [RobolectricHost]) advertise it from `recordingScriptEventDescriptors()`;
 * hosts without an Android lifecycle owner (DesktopHost) skip it entirely. The roadmap entries
 * (`state.save` / `state.restore` / `preview.reload`) stay in
 * `RecordingScriptDataExtensions.roadmapDescriptors` until their dispatch lands.
 */
object LifecycleRecordingScriptEvents {

  /** Wire-name id matching `RecordingScriptDataExtensions.LIFECYCLE_EVENT`. */
  const val LIFECYCLE_EVENT: String = "lifecycle.event"

  /** Recognised values for [RecordingScriptEvent.lifecycleEvent]. `"destroy"` deliberately not in v1. */
  val SUPPORTED_LIFECYCLE_EVENTS: Set<String> = setOf("pause", "resume", "stop")

  val descriptor: DataExtensionDescriptor =
    DataExtensionDescriptor(
      id = DataExtensionId("lifecycle"),
      displayName = "Lifecycle script controls",
      recordingScriptEvents =
        listOf(
          RecordingScriptEventDescriptor(
            id = LIFECYCLE_EVENT,
            displayName = "Lifecycle event",
            summary =
              "Drives the held activity through the named lifecycle transition via " +
                "ActivityScenario.moveToState. `lifecycleEvent` payload selects the target: " +
                "'pause' → STARTED, 'resume' → RESUMED, 'stop' → CREATED. " +
                "'destroy' is rejected (would break subsequent renders).",
            supported = true,
          )
        ),
    )

  /** Convenience for the host's `recordingScriptEventDescriptors()` override. */
  val descriptors: List<DataExtensionDescriptor> = listOf(descriptor)
}
