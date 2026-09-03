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
public object RemoteComposeProduct {
  public const val KIND: String = "compose/remotecompose"
  // v2 adds [RemoteComposePayload.declarations] — the auto-captured set of editable named-value
  // knobs a preview declared this render, so the viewer can render a control per knob instead of
  // relying on a hand-authored sidecar. Additive + defaulted, so a v1 reader that ignores the field
  // still decodes a v2 payload.
  public const val SCHEMA_VERSION: Int = 2
}

/**
 * Stable identity of the `compose/remotecompose-doc` data product — the **document** a preview
 * drew, distinct from the editable-knob state of [RemoteComposeProduct]. Where
 * `compose/remotecompose` carries the named-value / host-action / knob surface, this carries the
 * serialized Remote Compose document (`.rc`) the live render just captured (via
 * `IrSidecarChannel`), so a client can take the bytes the composition produced instead of a
 * pre-packed replay. The playground's remote-compose mode fetches this after a render to publish
 * the document as a `/d/<id>` permalink.
 *
 * Kept in this alpha-free core module (beside [RemoteComposeProduct]) so a client can depend on the
 * payload schema without the daemon-side registry or the `androidx.compose.remote.*` artifacts.
 */
public object RemoteComposeDocumentProduct {
  public const val KIND: String = "compose/remotecompose-doc"
  public const val SCHEMA_VERSION: Int = 1
}

/**
 * Wire-shape returned by `data/fetch?kind=compose/remotecompose-doc`: the serialized Remote Compose
 * document (`.rc`) captured during the latest render, Base64-encoded (the `data/fetch` transport is
 * JSON, so the raw bytes ride as text). [documentBase64] decodes to the exact `.rc` byte stream the
 * vendored player consumes — the same bytes a bundle's `ir/<id>.rc` sidecar would carry.
 */
public @Serializable data class RemoteComposeDocumentPayload(val documentBase64: String)

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
public data class RemoteComposeKnobDeclaration(val name: String, val default: RemoteNamedValue)

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
public data class RemoteComposeDeclarationsPayload(
  val declarations: List<RemoteComposeKnobDeclaration> = emptyList(),
  /**
   * The `rcPlayer` wire id of the player that actually drew this capture — `"cmp-android"` for the
   * embedded player, `"java"` for the view-backed one — or null when the capture predates this
   * field.
   *
   * Recorded rather than derived, because it cannot be derived. `RemoteOverridablePreview` selects
   * the player as `player == EMBEDDED && isEmbeddedPlayerAvailable`, and that second term is a
   * property of the capturing app's own classpath at render time: a consumer shipping the connector
   * without the optional embedded-player runtime draws through the view player while its
   * `@PreviewWrapper` says nothing at all. A reader inferring from the wrapper would then answer
   * `?rcPlayer=cmp-android` with view-player pixels under a confident 200
   * (compose-preview-server#233 answers *unknown* instead, which is safe but costs the clean
   * default link — this is what lets it stop being unknown).
   *
   * Null and absent mean the same thing: not recorded. Never "the default one".
   */
  val capturePlayer: String? = null,
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
public data class RemoteComposePayload(
  val namedValues: Map<String, RemoteNamedValue> = emptyMap(),
  val hostActions: List<RemoteHostAction> = emptyList(),
  val profile: RemoteComposeProfile? = null,
  val declarations: List<RemoteComposeKnobDeclaration> = emptyList(),
) {
  public companion object {
    /**
     * Cap for the in-memory host-action ring buffer. Picked so a busy panel session can keep ~5
     * seconds of typical agent-driven events without unbounded growth; downstream consumers that
     * need older events should subscribe to `data/subscribe(kind=compose/remotecompose)` and
     * accumulate themselves.
     */
    public const val HOST_ACTION_BUFFER_SIZE: Int = 256
  }
}
