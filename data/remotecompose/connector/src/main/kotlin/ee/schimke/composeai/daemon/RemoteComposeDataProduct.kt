@file:Suppress("RestrictedApiAndroidX")

package ee.schimke.composeai.daemon

import androidx.compose.remote.creation.compose.action.HostAction
import androidx.compose.remote.creation.compose.state.rf
import androidx.compose.remote.creation.compose.state.rs
import androidx.compose.remote.creation.profile.Profile
import androidx.compose.remote.creation.profile.RcPlatformProfiles
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import ee.schimke.composeai.daemon.protocol.DataFetchResult
import ee.schimke.composeai.daemon.protocol.DataProductAttachment
import ee.schimke.composeai.daemon.protocol.DataProductCapability
import ee.schimke.composeai.daemon.protocol.DataProductTransport
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.RemoteComposeOverride
import ee.schimke.composeai.daemon.protocol.RemoteComposeProfile
import ee.schimke.composeai.daemon.protocol.RemoteHostAction
import ee.schimke.composeai.daemon.protocol.RemoteNamedValue
import ee.schimke.composeai.data.remotecompose.RemoteComposePayload
import ee.schimke.composeai.data.remotecompose.RemoteComposeProduct
import ee.schimke.composeai.data.render.PreviewContext
import ee.schimke.composeai.data.render.extensions.DataExtension
import ee.schimke.composeai.data.render.extensions.DataExtensionCapability
import ee.schimke.composeai.data.render.extensions.DataExtensionConstraints
import ee.schimke.composeai.data.render.extensions.DataExtensionId
import ee.schimke.composeai.data.render.extensions.DataExtensionPhase
import ee.schimke.composeai.data.render.extensions.PlannedDataExtension
import ee.schimke.composeai.data.render.extensions.compose.AroundComposableExtension
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * Compose composition local exposing the live Remote Compose state to consumer screens. Wired by
 * [RemoteComposeOverrideExtension.AroundComposable] for every render so user code rendering a
 * `RemotePreview { ... }` block can:
 *
 *  * read [RemoteComposeHost.profile] (the daemon-requested platform profile) and pass it to the
 *    `RemotePreview(profile = …)` call,
 *  * read seeded named values via the typed `namedFloat` / `namedString` / `namedBoolean` /
 *    `namedInt` / `namedColor` helpers and bind them to a remote `RemoteFloat` / `RemoteString` /
 *    etc.,
 *  * report `HostAction` events the remote runtime fires through `reportHostAction(...)`.
 *
 * The composition local is always provided — even if no `renderNow.overrides.remoteCompose` was
 * sent — so user code can speculatively consult the host without crashing. When no override is
 * active, all reads fall back to the user-supplied defaults and the host action sink silently
 * accumulates events for the next `data/fetch`.
 */
val LocalRemoteComposeHost = compositionLocalOf<RemoteComposeHost> { ControllerRemoteComposeHost }

/**
 * Façade over [RemoteComposeController] for consumer code. Sealed (via the `compose` package
 * visibility on the controller) so future facets (e.g. per-name change subscriptions, scoped host-
 * action sinks for nested `RemotePreview` blocks) can land without breaking the call shape.
 */
interface RemoteComposeHost {
  /** Active platform profile the daemon requested, or `null` if no override is set. */
  val profile: RemoteComposeProfile?

  /**
   * Read [name]'s current daemon-seeded float, or [default] when no value is bound (or when the
   * bound value isn't a [RemoteNamedValue.FloatValue] / [RemoteNamedValue.DpValue]). Records the
   * binding so a fresh override carrying this name triggers a recomposition.
   */
  @Composable fun namedFloat(name: String, default: Float): Float

  /** Same as [namedFloat] for booleans. */
  @Composable fun namedBoolean(name: String, default: Boolean): Boolean

  /** Same as [namedFloat] for ints. */
  @Composable fun namedInt(name: String, default: Int): Int

  /** Same as [namedFloat] for strings. */
  @Composable fun namedString(name: String, default: String): String

  /** Same as [namedFloat] for colors. Returns the `#AARRGGBB` form so user code can parse. */
  @Composable fun namedColor(name: String, default: String): String

  /**
   * Push a value computed by the remote runtime back into the controller so the next
   * `data/fetch?kind=compose/remotecompose` returns it. Use from inside a `RemotePreview` block
   * after the remote computation lands a new value the host should observe.
   */
  fun setNamedValue(name: String, value: RemoteNamedValue)

  /**
   * Report a `HostAction` emission. User code typically wires this in two places: (1) at preview
   * construction time, capturing the [HostAction] payload/handlerId it built so the daemon knows
   * what actions the document carries, and (2) at runtime, calling this from the click callback
   * that wraps the remote `onClick = hostAction` binding. The connector filters by the override's
   * accepted-actions list before recording.
   */
  fun reportHostAction(action: RemoteHostAction)
}

private object ControllerRemoteComposeHost : RemoteComposeHost {
  override val profile: RemoteComposeProfile?
    get() = RemoteComposeController.profile.value

  @Composable
  override fun namedFloat(name: String, default: Float): Float {
    val current by RemoteComposeController.namedValues
    return when (val v = current[name]) {
      is RemoteNamedValue.FloatValue -> v.value
      is RemoteNamedValue.DpValue -> v.value
      is RemoteNamedValue.IntValue -> v.value.toFloat()
      else -> default
    }
  }

  @Composable
  override fun namedBoolean(name: String, default: Boolean): Boolean {
    val current by RemoteComposeController.namedValues
    return (current[name] as? RemoteNamedValue.BooleanValue)?.value ?: default
  }

  @Composable
  override fun namedInt(name: String, default: Int): Int {
    val current by RemoteComposeController.namedValues
    return when (val v = current[name]) {
      is RemoteNamedValue.IntValue -> v.value
      is RemoteNamedValue.FloatValue -> v.value.toInt()
      else -> default
    }
  }

  @Composable
  override fun namedString(name: String, default: String): String {
    val current by RemoteComposeController.namedValues
    return (current[name] as? RemoteNamedValue.StringValue)?.value ?: default
  }

  @Composable
  override fun namedColor(name: String, default: String): String {
    val current by RemoteComposeController.namedValues
    return (current[name] as? RemoteNamedValue.ColorValue)?.argb ?: default
  }

  override fun setNamedValue(name: String, value: RemoteNamedValue) {
    RemoteComposeController.setNamedValue(name, value)
  }

  override fun reportHostAction(action: RemoteHostAction) {
    RemoteComposeController.recordHostAction(action)
  }
}

/**
 * Maps a protocol [RemoteComposeProfile] to the upstream
 * `androidx.compose.remote.creation.profile.Profile` constant carried by
 * [RcPlatformProfiles]. Exposed so user code passing
 * `LocalRemoteComposeHost.current.profile?.toRcPlatformProfile()` to `RemotePreview(profile = …)`
 * doesn't need to fork the enum mapping.
 *
 * `RcPlatformProfiles` itself is a holder class with `static final Profile` fields — the
 * `RemotePreview` API takes a `Profile`, not the holder. Match the sample's call site:
 * `RemotePreview(profile = RcPlatformProfiles.ANDROIDX)` is implicitly passing
 * `RcPlatformProfiles.ANDROIDX` (a `Profile`), so this returns the same.
 */
fun RemoteComposeProfile.toRcPlatformProfile(): Profile =
  when (this) {
    RemoteComposeProfile.ANDROIDX -> RcPlatformProfiles.ANDROIDX
    RemoteComposeProfile.ANDROIDX7 -> RcPlatformProfiles.ANDROIDX7
    RemoteComposeProfile.ANDROIDX8 -> RcPlatformProfiles.ANDROIDX8
    RemoteComposeProfile.ANDROIDX9 -> RcPlatformProfiles.ANDROIDX9
    RemoteComposeProfile.WIDGETS_V6 -> RcPlatformProfiles.WIDGETS_V6
    RemoteComposeProfile.WIDGETS_V7 -> RcPlatformProfiles.WIDGETS_V7
    RemoteComposeProfile.WEAR_WIDGETS -> RcPlatformProfiles.WEAR_WIDGETS
  }

/**
 * Build a `HostAction` from the protocol payload. Mirrors the alpha API's constructor —
 * `HostAction(payload: RemoteString, handlerId: RemoteFloat)` — wrapping the wire `(String,
 * Float)` pair via the same `.rs` / `.rf` helpers the sample uses. Useful for user code that
 * wants to materialise a daemon-supplied action descriptor into a live `RemoteButton(onClick =
 * …)`.
 */
fun RemoteHostAction.toHostAction(): HostAction = HostAction(payload.rs, handlerId.rf)

/**
 * `AroundComposable` extension that owns the Remote Compose surface. The extension is **always
 * active** — the planner emits an instance for every render so [LocalRemoteComposeHost] is in
 * scope regardless of whether the client sent an explicit `RemoteComposeOverride`. User code that
 * speculatively reads `LocalRemoteComposeHost.current.namedFloat("score", default = 0f)` always
 * gets a sensible answer.
 *
 * Lifecycle:
 *
 * * On enter — [RemoteComposeController.set] is called with the seed (clears the map / profile
 *   when null). `DisposableEffect(seed)` re-runs only when the override identity changes, so a
 *   subsequent `renderNow.overrides.remoteCompose` with the same shape doesn't churn.
 * * On dispose — clears the override and any captured host-action buffer (`resetForNewSession`).
 *   Matches `KeyboardOverrideExtension` / `PermissionsOverrideExtension` semantics.
 *
 * Runs in [DataExtensionPhase.OuterEnvironment] so the composition local is in place before the
 * user-environment phase reaches preview content — `RemotePreview` blocks composed by user code
 * see the host.
 */
class RemoteComposeOverrideExtension(private val seed: RemoteComposeOverride? = null) :
  AroundComposableExtension(
    id = ID,
    constraints =
      DataExtensionConstraints(
        phase = DataExtensionPhase.OuterEnvironment,
        provides = setOf(DataExtensionCapability(RemoteComposeDataProductRegistry.KIND)),
      ),
  ) {
  @Composable
  override fun AroundComposable(content: @Composable () -> Unit) {
    DisposableEffect(seed) {
      RemoteComposeController.set(seed)
      onDispose { RemoteComposeController.resetForNewSession() }
    }
    CompositionLocalProvider(LocalRemoteComposeHost provides ControllerRemoteComposeHost) {
      content()
    }
  }

  companion object {
    val ID: DataExtensionId = DataExtensionId(RemoteComposeDataProductRegistry.KIND)
  }
}

/**
 * Planner that maps `renderNow.overrides.remoteCompose` to a [RemoteComposeOverrideExtension].
 * **Always** returns a non-null extension — like `KeyboardPreviewOverrideExtension` /
 * `PermissionsPreviewOverrideExtension`. The around-composable's [LocalRemoteComposeHost] needs
 * to be in place on every render so a screen that consults the host (or one whose embedded
 * `RemotePreview` block reads the profile / named values) reaches the controller's tracking path.
 */
class RemoteComposePreviewOverrideExtension : DataExtension<PreviewOverrides> {
  override val id: DataExtensionId = RemoteComposeOverrideExtension.ID

  override fun plan(request: PreviewOverrides): PlannedDataExtension =
    RemoteComposeOverrideExtension(seed = request.remoteCompose)
}

/**
 * Daemon-side registry adapter for `compose/remotecompose`.
 *
 * The registry tracks three facets per preview id:
 *
 * * The effective named-value map applied / written during the latest render.
 * * The captured host-action ring buffer (insertion order preserved, capped at
 *   [RemoteComposePayload.HOST_ACTION_BUFFER_SIZE]).
 * * The active platform profile.
 *
 * A `data/fetch` after a Remote Compose-aware render returns the combined payload; before any
 * render or after [clear], it returns [DataProductRegistry.Outcome.NotAvailable]. Clients update
 * the state by sending a fresh `renderNow.overrides.remoteCompose` (replaces named values +
 * profile, preserves host-action buffer) or by waiting for user code to call
 * [RemoteComposeHost.setNamedValue] / [RemoteComposeHost.reportHostAction] inside a held
 * interactive session — `addChangeListener` callbacks already wired through the controller wake
 * any active subscription.
 */
class RemoteComposeDataProductRegistry : DataProductRegistry {
  private val latestPayloads = ConcurrentHashMap<String, RemoteComposePayload>()

  override val capabilities: List<DataProductCapability> =
    listOf(
      DataProductCapability(
        kind = KIND,
        schemaVersion = SCHEMA_VERSION,
        transport = DataProductTransport.INLINE,
        attachable = true,
        fetchable = true,
        // Pushing a fresh `renderNow.overrides.remoteCompose` triggers a re-render anyway, and
        // user code calling `setNamedValue` / `reportHostAction` runs inside a held session
        // (where the panel observes via `data/subscribe` rather than re-asking the dispatcher to
        // queue an extra render).
        requiresRerender = false,
      )
    )

  fun capture(previewId: String?, payload: RemoteComposePayload) {
    if (previewId == null) return
    latestPayloads[previewId] = payload
  }

  fun clear(previewId: String?) {
    if (previewId == null) return
    latestPayloads.remove(previewId)
  }

  override fun fetch(
    previewId: String,
    kind: String,
    params: JsonElement?,
    inline: Boolean,
  ): DataProductRegistry.Outcome {
    if (kind != KIND) return DataProductRegistry.Outcome.Unknown
    val payload = latestPayloads[previewId] ?: return DataProductRegistry.Outcome.NotAvailable
    return DataProductRegistry.Outcome.Ok(
      DataFetchResult(
        kind = KIND,
        schemaVersion = SCHEMA_VERSION,
        payload = json.encodeToJsonElement(RemoteComposePayload.serializer(), payload),
      )
    )
  }

  override fun attachmentsFor(previewId: String, kinds: Set<String>): List<DataProductAttachment> {
    if (KIND !in kinds) return emptyList()
    val payload = latestPayloads[previewId] ?: return emptyList()
    return listOf(
      DataProductAttachment(
        kind = KIND,
        schemaVersion = SCHEMA_VERSION,
        payload = json.encodeToJsonElement(RemoteComposePayload.serializer(), payload),
      )
    )
  }

  override fun onRender(previewId: String, result: RenderResult) {
    onRender(previewId, result, overrides = null, previewContext = result.previewContext)
  }

  override fun onRender(
    previewId: String,
    result: RenderResult,
    overrides: PreviewOverrides?,
    previewContext: PreviewContext?,
  ) {
    val namedValues = RemoteComposeController.namedValues.value
    val hostActions = RemoteComposeController.hostActions.value
    val profile = RemoteComposeController.profile.value
    if (namedValues.isEmpty() && hostActions.isEmpty() && profile == null) {
      clear(previewId)
      return
    }
    capture(
      previewId,
      RemoteComposePayload(
        namedValues = namedValues,
        hostActions = hostActions,
        profile = profile,
      ),
    )
  }

  companion object {
    const val KIND: String = RemoteComposeProduct.KIND
    const val SCHEMA_VERSION: Int = RemoteComposeProduct.SCHEMA_VERSION

    private val json = Json {
      encodeDefaults = true
      prettyPrint = false
    }
  }
}
