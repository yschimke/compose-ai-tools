package ee.schimke.composeai.data.remotecompose

import ee.schimke.composeai.daemon.protocol.RemoteComposeProfile
import ee.schimke.composeai.daemon.protocol.RemoteHostAction
import ee.schimke.composeai.daemon.protocol.RemoteNamedValue
import kotlinx.serialization.Serializable

/**
 * Stable identity of the `compose/remotecompose` data product. Lifted out of
 * `RemoteComposeDataProductRegistry` so MCP clients and other connectors can depend on the payload
 * schema without pulling in the daemon-side registry, Compose, or the alpha
 * `androidx.compose.remote.*` artifacts. Mirrors `Material3PermissionsProduct` /
 * `Material3KeyboardProduct`.
 */
object RemoteComposeProduct {
  const val KIND: String = "compose/remotecompose"
  const val SCHEMA_VERSION: Int = 1
}

/**
 * Wire-shape returned by `data/fetch?kind=compose/remotecompose`.
 *
 * Three facets feed the panel's per-card chip:
 *
 * * [namedValues] — the effective named-value map after the latest render. Reflects daemon-side
 *   seeds (`renderNow.overrides.remoteCompose.namedValues`) merged with any writes user code pushed
 *   back via `LocalRemoteComposeHost.current.setNamedValue(...)`. Values use the same typed sum
 *   (`RemoteNamedValue`) the override sends in.
 * * [hostActions] — insertion-ordered list of `HostAction` events the remote runtime fired during
 *   the captured frame (and during an interactive session if the connector is held). Capped to the
 *   most recent [HOST_ACTION_BUFFER_SIZE] entries so a runaway emitter doesn't grow the payload
 *   unboundedly.
 * * [profile] — the active platform profile (mirrors `RcPlatformProfiles`). Null when user code
 *   didn't bind one through `LocalRemoteComposeHost`.
 */
@Serializable
data class RemoteComposePayload(
  val namedValues: Map<String, RemoteNamedValue> = emptyMap(),
  val hostActions: List<RemoteHostAction> = emptyList(),
  val profile: RemoteComposeProfile? = null,
) {
  companion object {
    /**
     * Cap for the in-memory host-action ring buffer. Picked so a busy panel session can keep ~5
     * seconds of typical agent-driven events without unbounded growth; downstream consumers that
     * need older events should subscribe to `data/subscribe(kind=compose/remotecompose)` and
     * accumulate themselves.
     */
    const val HOST_ACTION_BUFFER_SIZE: Int = 256
  }
}
