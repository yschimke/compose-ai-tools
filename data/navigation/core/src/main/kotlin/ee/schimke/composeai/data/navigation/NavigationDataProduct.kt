package ee.schimke.composeai.data.navigation

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Wire payload for the `data/navigation` data product. Captures the held activity's launch `Intent`
 * (action / data URI / categories / simple-typed extras) and the registered back-pressed-callback
 * state — what an agent needs to verify deep-link routing landed and that the screen wired up an
 * `OnBackPressedCallback` for the predictive-back gesture.
 *
 * **Robolectric caveat.** `ActivityScenarioRule<ComponentActivity>` launches the activity with the
 * default `MAIN`/`LAUNCHER` intent under Robolectric, so production renders typically see
 * `intent.action = "android.intent.action.MAIN"` and an empty extras bag — the producer ships the
 * surface anyway, so non-Robolectric backends (or tests that override the launch intent) can
 * populate it meaningfully without adding a new wire field later.
 */
@Serializable
data class NavigationPayload(
  val intent: NavigationIntent? = null,
  val onBackPressed: NavigationBackPressedState,
)

@Serializable
data class NavigationIntent(
  /** Intent action — e.g. `"android.intent.action.VIEW"` for a deep-link Intent. */
  val action: String? = null,
  /** Intent data URI as a string — `null` when the intent has no data. */
  val dataUri: String? = null,
  /** Explicit MIME type set on the intent, when present. */
  val type: String? = null,
  /** ComponentName.flattenToShortString — `pkg/.Activity` form when set explicitly. */
  val component: String? = null,
  /** Restricted-package, when `Intent.setPackage` was called. */
  val packageName: String? = null,
  /** Bitmask of `Intent.FLAG_*` flags. */
  val flags: Int = 0,
  /** Categories added via `Intent.addCategory`. */
  val categories: List<String> = emptyList(),
  /**
   * Extras keyed by string. Only simple types (String, Boolean, Int, Long, Float, Double) make it
   * across the wire — Parcelables, byte arrays, and nested Bundles are dropped because the JSON
   * payload would have to inline a Parcelable serialiser, and there's no path to round-trip them
   * back into an Intent on the agent side. Agents that need to verify a Parcelable extra can use
   * its `toString()` via a follow-up renderer-side custom check.
   */
  val extras: Map<String, JsonElement> = emptyMap(),
)

@Serializable
data class NavigationBackPressedState(
  /**
   * Mirror of `androidx.activity.OnBackPressedDispatcher.hasEnabledCallbacks`. `true` means a
   * `BackHandler { … }` (Compose) or `OnBackPressedCallback(enabled = true)` (View) has registered
   * with the activity's dispatcher. When `false`, a back press would fall through to the activity's
   * default behaviour (finish / pop the task).
   */
  val hasEnabledCallbacks: Boolean
)

/**
 * Shared constants for the `data/navigation` kind. The Android producer (`:daemon:android`) writes
 * `<rootDir>/<previewId>/navigation.json`; the registry (`:data-navigation-connector`) reads from
 * the same location.
 */
object NavigationDataProduct {
  const val KIND: String = "data/navigation"
  const val SCHEMA_VERSION: Int = 1
  const val FILE: String = "navigation.json"
}
