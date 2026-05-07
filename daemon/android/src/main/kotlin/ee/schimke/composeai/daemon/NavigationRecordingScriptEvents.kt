package ee.schimke.composeai.daemon

import ee.schimke.composeai.data.render.extensions.DataExtensionDescriptor
import ee.schimke.composeai.data.render.extensions.DataExtensionId
import ee.schimke.composeai.data.render.extensions.RecordingScriptEventDescriptor

/**
 * Navigation-driven `record_preview` script events — deep-link routing and predictive-back gesture
 * audits, dispatched against the held [`androidx.activity.ComponentActivity`]'s
 * `onBackPressedDispatcher` (back / predictive-back) and `startActivity` (deep-link).
 *
 * Six event ids:
 *
 * - `navigation.deepLink` — fires `Intent(ACTION_VIEW, Uri.parse(deepLinkUri))` at the held
 *   activity, exercising the consumer's intent-filter / `NavController` deep-link routing. The URI
 *   travels on the wire as `RecordingScriptEvent.deepLinkUri`.
 * - `navigation.back` — calls `OnBackPressedDispatcher.onBackPressed()` (no payload). Mirrors the
 *   instant back press that pre-API-34 devices deliver.
 * - `navigation.predictiveBackStarted` — drives `dispatchOnBackStarted(BackEventCompat)`. The wire
 *   `backProgress` (0.0–1.0) and `backEdge` (`"left"` / `"right"`) populate the synthesised
 *   `BackEventCompat`.
 * - `navigation.predictiveBackProgressed` — drives `dispatchOnBackProgressed(BackEventCompat)`.
 *   Same payload shape as `predictiveBackStarted`; emit one per animation frame to drive
 *   progress-bound animations.
 * - `navigation.predictiveBackCommitted` — calls `onBackPressed()` to commit the gesture (no
 *   payload). Use after a `predictiveBackStarted` + N `predictiveBackProgressed` to land the back
 *   stack pop.
 * - `navigation.predictiveBackCancelled` — drives `dispatchOnBackCancelled()` (no payload). Use
 *   to verify the screen restores cleanly when the gesture is abandoned.
 *
 * **Why per-id discrimination instead of one `navigation.event` with an `actionKind` payload.**
 * Per-id ids let the MCP validator reject unknown actions at the kind level (no special payload
 * check needed), make the surface self-documenting in `list_data_products`, and align with
 * `lifecycle.*` / `state.*` / `preview.*` / `a11y.action.*` shapes — every other extension uses
 * kind-level discrimination.
 *
 * Why this lives in `:daemon:android` (mirroring [LifecycleRecordingScriptEvents] /
 * [PreviewReloadRecordingScriptEvents]) and not in a separate `:data-navigation-connector`
 * module: navigation has no data products (no per-render JSON / overlay PNG), just dispatch arms
 * in [RobolectricHost.SandboxRunner.performNavigationAction]. The connector pattern's value is
 * shipping a separate matcher / overlay artefact across the daemon bridge — there's nothing of
 * that shape here.
 */
object NavigationRecordingScriptEvents {

  const val EXTENSION_ID: String = "navigation"

  /** `navigation.deepLink` → `Intent(ACTION_VIEW, Uri.parse(event.deepLinkUri))`. */
  const val NAV_DEEP_LINK_EVENT: String = "navigation.deepLink"

  /** `navigation.back` → `OnBackPressedDispatcher.onBackPressed()`. */
  const val NAV_BACK_EVENT: String = "navigation.back"

  /**
   * `navigation.predictiveBackStarted` → `dispatchOnBackStarted(BackEventCompat)` with the wire
   * `backProgress` / `backEdge` payload.
   */
  const val NAV_PREDICTIVE_BACK_STARTED_EVENT: String = "navigation.predictiveBackStarted"

  /**
   * `navigation.predictiveBackProgressed` → `dispatchOnBackProgressed(BackEventCompat)`. Emit one
   * per animation frame to drive progress-bound animations.
   */
  const val NAV_PREDICTIVE_BACK_PROGRESSED_EVENT: String = "navigation.predictiveBackProgressed"

  /** `navigation.predictiveBackCommitted` → `OnBackPressedDispatcher.onBackPressed()`. */
  const val NAV_PREDICTIVE_BACK_COMMITTED_EVENT: String = "navigation.predictiveBackCommitted"

  /** `navigation.predictiveBackCancelled` → `dispatchOnBackCancelled()`. */
  const val NAV_PREDICTIVE_BACK_CANCELLED_EVENT: String = "navigation.predictiveBackCancelled"

  /** Wired event ids registered in `AndroidRecordingSession`'s handler block. */
  val WIRED_EVENTS: List<String> =
    listOf(
      NAV_DEEP_LINK_EVENT,
      NAV_BACK_EVENT,
      NAV_PREDICTIVE_BACK_STARTED_EVENT,
      NAV_PREDICTIVE_BACK_PROGRESSED_EVENT,
      NAV_PREDICTIVE_BACK_COMMITTED_EVENT,
      NAV_PREDICTIVE_BACK_CANCELLED_EVENT,
    )

  val descriptor: DataExtensionDescriptor =
    DataExtensionDescriptor(
      id = DataExtensionId(EXTENSION_ID),
      displayName = "Navigation script controls",
      recordingScriptEvents =
        listOf(
          RecordingScriptEventDescriptor(
            id = NAV_DEEP_LINK_EVENT,
            displayName = "Deep-link",
            summary =
              "Fires Intent(ACTION_VIEW, Uri.parse(event.deepLinkUri)) at the held activity. " +
                "The intent is constrained to the activity's own package so unrelated resolvers " +
                "don't intercept it. Use to audit the consumer's intent-filter / " +
                "`NavController.navigate(deepLink)` routing without a real device.",
            supported = true,
          ),
          RecordingScriptEventDescriptor(
            id = NAV_BACK_EVENT,
            displayName = "Back press",
            summary =
              "Calls OnBackPressedDispatcher.onBackPressed() on the held activity — the same " +
                "path a pre-API-34 hardware back button takes. Use to verify a back-stack pop, " +
                "an `OnBackPressedCallback` registration, or a `popBackStack()` from a Compose " +
                "screen.",
            supported = true,
          ),
          RecordingScriptEventDescriptor(
            id = NAV_PREDICTIVE_BACK_STARTED_EVENT,
            displayName = "Predictive back — start",
            summary =
              "Drives OnBackPressedDispatcher.dispatchOnBackStarted(BackEventCompat) with the " +
                "wire `backProgress` (0.0–1.0) and `backEdge` (`left`/`right`) payload. Use as " +
                "the first event in a predictive-back gesture sequence.",
            supported = true,
          ),
          RecordingScriptEventDescriptor(
            id = NAV_PREDICTIVE_BACK_PROGRESSED_EVENT,
            displayName = "Predictive back — progress",
            summary =
              "Drives dispatchOnBackProgressed(BackEventCompat) — emit one per animation frame " +
                "between `predictiveBackStarted` and a terminal `predictiveBackCommitted` / " +
                "`predictiveBackCancelled` so progress-bound animations record their full curve.",
            supported = true,
          ),
          RecordingScriptEventDescriptor(
            id = NAV_PREDICTIVE_BACK_COMMITTED_EVENT,
            displayName = "Predictive back — commit",
            summary =
              "Lands the predictive-back gesture by calling onBackPressed() — the same call the " +
                "instant `navigation.back` event uses, but emitted after a started/progressed " +
                "sequence so observers see the gesture commit rather than a cold back press.",
            supported = true,
          ),
          RecordingScriptEventDescriptor(
            id = NAV_PREDICTIVE_BACK_CANCELLED_EVENT,
            displayName = "Predictive back — cancel",
            summary =
              "Drives dispatchOnBackCancelled() to abandon the in-flight gesture. Use to verify " +
                "the screen restores cleanly when the user lets go before committing.",
            supported = true,
          ),
        ),
    )

  /** Convenience for the host's `recordingScriptEventDescriptors()` override. */
  val descriptors: List<DataExtensionDescriptor> = listOf(descriptor)
}
