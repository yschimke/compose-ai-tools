package ee.schimke.composeai.daemon

import ee.schimke.composeai.data.render.extensions.DataExtensionDescriptor
import ee.schimke.composeai.data.render.extensions.DataExtensionId
import ee.schimke.composeai.data.render.extensions.RecordingScriptDataExtensions
import ee.schimke.composeai.data.render.extensions.RecordingScriptEventDescriptor

/**
 * Preview-reload `record_preview` script event. One descriptor — `preview.reload` — that forces a
 * fresh composition by tearing down the held content under its current `key(...)` boundary and
 * rebuilding from scratch. Used by audits that want to verify a screen recovers cleanly from a
 * recompose-from-zero (`remember`, `rememberSaveable`, `LaunchedEffect`-keyed work all reset).
 *
 * Why this lives in `:daemon:android` (mirroring [LifecycleRecordingScriptEvents]) and not in a
 * separate connector module: there's no data-product side, just a single dispatch arm in
 * [RobolectricHost.SandboxRunner] that increments a `mutableIntStateOf` counter the wrapping
 * `key(...)` block reads.
 *
 * **Distinct from `lifecycle.event`.** Lifecycle drives `ActivityScenario.moveToState(...)` which
 * preserves `rememberSaveable` state across `pause` / `resume`. `preview.reload` invalidates the
 * Compose slot table entirely — `rememberSaveable` state is lost too because the `key(...)`
 * boundary changes its call-site path. Use `lifecycle.event` to verify state survives a
 * configuration change; use `preview.reload` to verify the screen handles a cold composition.
 *
 * **Status:** the descriptor ships with `supported = true`. Hosts that wire reload (today: only
 * [RobolectricHost]) advertise it from `recordingScriptEventDescriptors()`. The renderer-agnostic
 * `RecordingScriptDataExtensions.PREVIEW_RELOAD_EVENT` constant is reused for the wire-name id;
 * `RecordingScriptDataExtensions.roadmapDescriptors` no longer carries the `preview` extension
 * since it's now wired here.
 */
object PreviewReloadRecordingScriptEvents {

  /** Wire-name id matching `RecordingScriptDataExtensions.PREVIEW_RELOAD_EVENT`. */
  const val PREVIEW_RELOAD_EVENT: String = RecordingScriptDataExtensions.PREVIEW_RELOAD_EVENT

  val descriptor: DataExtensionDescriptor =
    DataExtensionDescriptor(
      id = DataExtensionId("preview"),
      displayName = "Preview script controls",
      recordingScriptEvents =
        listOf(
          RecordingScriptEventDescriptor(
            id = PREVIEW_RELOAD_EVENT,
            displayName = "Reload preview",
            summary =
              "Forces a fresh composition: tears down the current slot table under its " +
                "`key(...)` boundary and rebuilds against the same composable. `remember` and " +
                "`rememberSaveable` state both reset. Use `lifecycle.event` (pause/resume) for " +
                "config-change-style audits where rememberSaveable state should survive.",
            supported = true,
          )
        ),
    )

  /** Convenience for the host's `recordingScriptEventDescriptors()` override. */
  val descriptors: List<DataExtensionDescriptor> = listOf(descriptor)
}
