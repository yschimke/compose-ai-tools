package ee.schimke.composeai.data.permissions

import kotlinx.serialization.Serializable

/**
 * Stable identity of the `compose/permissions` data product. Lifted out of
 * `PermissionsDataProductRegistry` so MCP clients and other connectors can depend on the payload
 * schema without pulling in the daemon-side registry, Compose, or Robolectric. Mirrors
 * `Material3KeyboardProduct` / `Material3AmbientProduct`.
 */
object Material3PermissionsProduct {
  const val KIND: String = "compose/permissions"
  const val SCHEMA_VERSION: Int = 1
}

/**
 * Wire-shape returned by `data/fetch?kind=compose/permissions`.
 *
 * Two facets feed the panel's per-card chip:
 *
 * * [grants] reflects the effective grant state the around-composable applied for this render — the
 *   union of `renderNow.overrides.permissions.grants` (explicit overrides) and any in-session
 *   updates the `PermissionsController` accepted while a live preview is held. Permission names
 *   match the Android `Manifest.permission.*` constant strings (e.g.
 *   `"android.permission.CAMERA"`).
 * * [queried] lists the permissions the screen asked about during the captured frame — either via
 *   `ContextCompat.checkSelfPermission(...)` (intercepted by the connector's shadow on
 *   `ContextWrapper.checkPermission`) or via the explicit `LocalPermissionsHost.check(...)` opt-in
 *   helper. Order is insertion order so the panel can display queries in roughly the sequence the
 *   composition issued them. Permissions that were not yet queried at capture time do not appear.
 */
@Serializable
data class PermissionsPayload(
  val grants: Map<String, PermissionGrantWire>,
  val queried: List<String>,
)

/**
 * Wire spelling for a permission's grant state. Matches the lower-case form `KeyboardOverride` /
 * `AmbientStateOverride` use so JSON payloads stay consistent across data products.
 */
@Serializable
enum class PermissionGrantWire {
  @kotlinx.serialization.SerialName("granted") GRANTED,
  @kotlinx.serialization.SerialName("denied") DENIED,
}
