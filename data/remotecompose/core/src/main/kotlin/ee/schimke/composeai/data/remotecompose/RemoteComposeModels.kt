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
  // v2 adds [RemoteComposePayload.declarations] — the auto-captured set of editable named-value
  // knobs a preview declared this render, so the viewer can render a control per knob instead of
  // relying on a hand-authored sidecar. Additive + defaulted, so a v1 reader that ignores the field
  // still decodes a v2 payload.
  const val SCHEMA_VERSION: Int = 2
}

/**
 * One editable Remote Compose named-value knob a preview declared during its render — the auto-
 * capture counterpart of a plain-Compose `compose/overrides`
 * [ee.schimke.composeai.data.overrides.PreviewOverrideDeclaration]. A knob is declared whenever
 * user code reads a named value through `LocalRemoteComposeHost` (the typed `namedFloat` /
 * `namedString` / … helpers self-declare) or calls
 * `LocalRemoteComposeHost.current.declareKnob(...)` explicitly.
 *
 * [name] is the bare binding name (the same key `renderNow.overrides.remoteCompose.namedValues` and
 * the serve `rc.<name>=…` param address); [default] is the author-supplied fallback, typed via the
 * shared [RemoteNamedValue] sum so its variant carries the knob's kind (float / dp / int / string /
 * bool / color). A consumer renders the matching control (slider, text field, colour swatch) and
 * writes an edit back through the `remoteCompose` override facet.
 */
@Serializable
data class RemoteComposeKnobDeclaration(val name: String, val default: RemoteNamedValue)

/**
 * Bundle-sidecar shape for a preview's declared Remote Compose knobs — the payload of the
 * `renders/<stem>.remotecompose.json` file the render step writes and the bundle packs under
 * `previews/<id>.remotecompose.json`. The counterpart of the plain-Compose
 * `ee.schimke.composeai.data.overrides.PreviewOverridesPayload`. A detached reader (the serve host,
 * a viewer) decodes this to render a control per knob; the render manifest never parses it (it's
 * copied verbatim). Distinct from [RemoteComposePayload], the live `data/fetch` shape — the sidecar
 * carries only the editable surface, not the effective values / host actions / profile.
 */
@Serializable
data class RemoteComposeDeclarationsPayload(
  val declarations: List<RemoteComposeKnobDeclaration> = emptyList()
)

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
 * * [declarations] — the editable named-value knobs the preview declared this render, in
 *   declaration order, deduped by name. Drives the viewer's per-knob controls (a slider / field /
 *   swatch per entry). Empty when the preview reads no named values through
 *   `LocalRemoteComposeHost` and never calls `declareKnob`. Distinct from [namedValues], which is
 *   the *effective* value map (seeds + write-backs) — [declarations] is the *editable surface*
 *   (name + author default + kind).
 */
@Serializable
data class RemoteComposePayload(
  val namedValues: Map<String, RemoteNamedValue> = emptyMap(),
  val hostActions: List<RemoteHostAction> = emptyList(),
  val profile: RemoteComposeProfile? = null,
  val declarations: List<RemoteComposeKnobDeclaration> = emptyList(),
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
