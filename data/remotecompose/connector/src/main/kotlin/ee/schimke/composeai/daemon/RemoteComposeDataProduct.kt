@file:Suppress("RestrictedApiAndroidX")

package ee.schimke.composeai.daemon

import androidx.compose.remote.creation.compose.action.Action
import androidx.compose.remote.creation.compose.action.hostAction
import androidx.compose.remote.creation.compose.state.rf
import androidx.compose.remote.creation.compose.state.rs
import androidx.compose.remote.creation.profile.Profile
import androidx.compose.remote.creation.profile.RcPlatformProfiles
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
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
import ee.schimke.composeai.data.remotecompose.RemoteComposeKnobDeclaration
import ee.schimke.composeai.data.remotecompose.RemoteComposePayload
import ee.schimke.composeai.data.remotecompose.RemoteComposeProduct
import ee.schimke.composeai.data.render.PreviewContext
import ee.schimke.composeai.data.render.extensions.DataExtension
import ee.schimke.composeai.data.render.extensions.DataExtensionCapability
import ee.schimke.composeai.data.render.extensions.DataExtensionConstraints
import ee.schimke.composeai.data.render.extensions.DataExtensionHookKind
import ee.schimke.composeai.data.render.extensions.DataExtensionId
import ee.schimke.composeai.data.render.extensions.DataExtensionPhase
import ee.schimke.composeai.data.render.extensions.PlannedDataExtension
import ee.schimke.composeai.data.render.extensions.compose.AroundComposableHook
import ee.schimke.composeai.data.render.extensions.compose.ExtensionComposeContext
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * Compose composition local exposing the live Remote Compose state to consumer screens. Wired by
 * [RemoteComposeOverrideExtension.AroundComposable] for every render so user code rendering a
 * `RemotePreview { ... }` block can:
 *
 * * read [RemoteComposeHost.profile] (the daemon-requested platform profile) and pass it to the
 *   `RemotePreview(profile = …)` call,
 * * read seeded named values via the typed `namedFloat` / `namedString` / `namedBoolean` /
 *   `namedInt` / `namedColor` helpers and bind them to a remote `RemoteFloat` / `RemoteString` /
 *   etc.,
 * * report `HostAction` events the remote runtime fires through `reportHostAction(...)`.
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
   * Declare [name] as an editable named-value knob with [default] as its author fallback, so a
   * consumer (the VS Code panel, the serve viewer) can render a control for it and write an edit
   * back through `renderNow.overrides.remoteCompose.namedValues`. The typed `namedFloat` /
   * `namedString` / … reads above already self-declare, so call this only for a value user code
   * binds *without* reading it through the host — e.g. a name seeded straight into the player's
   * `StateUpdater`. Recording is deduped by name and preserves declaration order.
   */
  fun declareKnob(name: String, default: RemoteNamedValue)

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
    declareInComposition(name, RemoteNamedValue.FloatValue(default))
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
    declareInComposition(name, RemoteNamedValue.BooleanValue(default))
    val current by RemoteComposeController.namedValues
    return (current[name] as? RemoteNamedValue.BooleanValue)?.value ?: default
  }

  @Composable
  override fun namedInt(name: String, default: Int): Int {
    declareInComposition(name, RemoteNamedValue.IntValue(default))
    val current by RemoteComposeController.namedValues
    return when (val v = current[name]) {
      is RemoteNamedValue.IntValue -> v.value
      is RemoteNamedValue.FloatValue -> v.value.toInt()
      else -> default
    }
  }

  @Composable
  override fun namedString(name: String, default: String): String {
    declareInComposition(name, RemoteNamedValue.StringValue(default))
    val current by RemoteComposeController.namedValues
    return (current[name] as? RemoteNamedValue.StringValue)?.value ?: default
  }

  @Composable
  override fun namedColor(name: String, default: String): String {
    declareInComposition(name, RemoteNamedValue.ColorValue(default))
    val current by RemoteComposeController.namedValues
    return (current[name] as? RemoteNamedValue.ColorValue)?.argb ?: default
  }

  /**
   * Record the read name as an editable knob from a `SideEffect`, not directly during composition:
   * mirrors `ControllerPreviewOverrideHost`'s `previewOverride*`. A `SideEffect` runs after the
   * `DisposableEffect` clear that [RemoteComposeOverrideExtension] performs at render start (Compose
   * runs every `RememberObserver` before any `SideEffect`), so each pass's declaration set is rebuilt
   * from scratch, and it never writes controller snapshot state mid-composition (which would risk a
   * recompose loop).
   */
  @Composable
  private fun declareInComposition(name: String, default: RemoteNamedValue) {
    SideEffect {
      RemoteComposeController.recordDeclaration(RemoteComposeKnobDeclaration(name, default))
    }
  }

  override fun setNamedValue(name: String, value: RemoteNamedValue) {
    RemoteComposeController.setNamedValue(name, value)
  }

  override fun declareKnob(name: String, default: RemoteNamedValue) {
    RemoteComposeController.recordDeclaration(RemoteComposeKnobDeclaration(name, default))
  }

  override fun reportHostAction(action: RemoteHostAction) {
    RemoteComposeController.recordHostAction(action)
  }
}

/**
 * Maps a protocol [RemoteComposeProfile] to the upstream
 * `androidx.compose.remote.creation.profile.Profile` constant carried by [RcPlatformProfiles].
 * Exposed so user code passing `LocalRemoteComposeHost.current.profile?.toRcPlatformProfile()` to
 * `RemotePreview(profile = …)` doesn't need to fork the enum mapping.
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
 * Build a host-action [Action] from the protocol payload. Mirrors the alpha API's factory —
 * `hostAction(payload: RemoteString, handlerId: RemoteFloat)` — wrapping the wire `(String, Float)`
 * pair via the same `.rs` / `.rf` helpers the sample uses. (The concrete `HostAction` type went
 * `internal` in compose-remote alpha13; `hostAction(...)` is its public replacement.) Useful for
 * user code that wants to materialise a daemon-supplied action descriptor into a live
 * `RemoteButton(onClick = …)`.
 */
fun RemoteHostAction.toHostAction(): Action = hostAction(payload.rs, handlerId.rf)

/**
 * `AroundComposable` extension that owns the Remote Compose surface. The extension is **always
 * active** — the planner emits an instance for every render so [LocalRemoteComposeHost] is in scope
 * regardless of whether the client sent an explicit `RemoteComposeOverride`. User code that
 * speculatively reads `LocalRemoteComposeHost.current.namedFloat("score", default = 0f)` always
 * gets a sensible answer.
 *
 * Lifecycle:
 *
 * * On enter — [RemoteComposeController.set] is called with the seed (clears the map / profile when
 *   null). `DisposableEffect(seed)` re-runs only when the override identity changes, so a
 *   subsequent `renderNow.overrides.remoteCompose` with the same shape doesn't churn.
 * * On dispose — clears only the seed (named values / profile / accepted-action filter) via
 *   [RemoteComposeController.set]`(null)`, **not** the recorded declarations or the sandbox bridge.
 *   On Android the Compose test rule disposes the activity *before* `JsonRpcServer` calls
 *   [RemoteComposeDataProductRegistry.onRender] → `declarationsFor`, so resetting the bridge here
 *   would wipe the knobs before the host snapshots them. Declarations drop at the *next* render's
 *   start via [RemoteComposeController.clearDeclarations] (after the host captured this render).
 *   Mirrors `PreviewOverridesOverrideExtension`'s `onDispose { set(null) }`.
 *
 * Runs in [DataExtensionPhase.OuterEnvironment] so the composition local is in place before the
 * user-environment phase reaches preview content — `RemotePreview` blocks composed by user code see
 * the host.
 */
class RemoteComposeOverrideExtension(private val seed: RemoteComposeOverride? = null) :
  AroundComposableHook {

  override val id: DataExtensionId = ID

  override val hooks: Set<DataExtensionHookKind> = setOf(DataExtensionHookKind.AroundComposable)

  override val constraints: DataExtensionConstraints =
    DataExtensionConstraints(
      phase = DataExtensionPhase.OuterEnvironment,
      provides = setOf(DataExtensionCapability(RemoteComposeDataProductRegistry.KIND)),
    )

  @Composable
  override fun Around(context: ExtensionComposeContext, content: @Composable () -> Unit) {
    // Stamp the active previewId before content composes so the sandbox-side declaration forwards
    // land in this preview's bridge scope, not a concurrently-rendering preview's (pooled sandboxes).
    // Plain call (not a SideEffect) so it runs during composition, ahead of any `named*` read in
    // `content()`. Mirrors PreviewOverridesOverrideExtension.
    RemoteComposeController.beginRender(context.previewId)
    DisposableEffect(seed) {
      RemoteComposeController.set(seed)
      // Clear declarations at render start (mirrors PreviewOverridesOverrideExtension): a held
      // session re-rendering with a shrunk knob set must not carry stale controls. A DisposableEffect
      // runs as a RememberObserver, which Compose invokes before any SideEffect, so this clear always
      // precedes the `named*` reads' SideEffect-recorded declarations for this pass.
      RemoteComposeController.clearDeclarations()
      // Dispose clears only the seed — NOT declarations or the bridge — so the host's post-dispose
      // onRender snapshot still sees this render's knobs (see the lifecycle KDoc above). Mirrors
      // PreviewOverridesOverrideExtension's `onDispose { set(null) }`.
      onDispose { RemoteComposeController.set(null) }
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
 * `PermissionsPreviewOverrideExtension`. The around-composable's [LocalRemoteComposeHost] needs to
 * be in place on every render so a screen that consults the host (or one whose embedded
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
 * interactive session — `addChangeListener` callbacks already wired through the controller wake any
 * active subscription.
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
    val declarations = declarationsFor(previewId)
    if (
      namedValues.isEmpty() && hostActions.isEmpty() && profile == null && declarations.isEmpty()
    ) {
      clear(previewId)
      return
    }
    capture(
      previewId,
      RemoteComposePayload(
        namedValues = namedValues,
        hostActions = hostActions,
        profile = profile,
        declarations = declarations,
      ),
    )
  }

  /**
   * The knobs declared by the render for [previewId]. The do-not-acquire [SandboxRemoteComposeBridge]
   * is shared across the sandbox boundary, so prefer it when reachable (its JSON snapshot is decoded
   * back into typed declarations) — on Android the in-classloader controller the host reads is a
   * different, empty instance. Falls back to the in-CL `RemoteComposeController` when the bridge
   * isn't on the classpath (the desktop daemon / connector unit tests, where the controller IS the
   * source of truth) or the bridge has nothing for this preview. Mirrors
   * `PreviewOverridesDataProductRegistry.declarationsFor`.
   */
  private fun declarationsFor(previewId: String): List<RemoteComposeKnobDeclaration> {
    val controllerDeclarations = RemoteComposeController.declarations()
    val bridge = SandboxRemoteComposeBridgeReader.tryLoad() ?: return controllerDeclarations
    val bridgeJson = bridge.snapshot(previewId)
    if (bridgeJson.isEmpty()) return controllerDeclarations
    return bridgeJson.mapNotNull { entry ->
      runCatching { json.decodeFromString(RemoteComposeKnobDeclaration.serializer(), entry) }
        .getOrNull()
    }
  }

  /**
   * Reflective lookup of `ee.schimke.composeai.daemon.bridge.SandboxRemoteComposeBridge`. Cached per
   * JVM. `null` means the bridge isn't on the classpath (connector-only unit tests; the desktop
   * daemon has no sandbox). Mirrors `PreviewOverridesDataProductRegistry`'s
   * `SandboxPreviewOverridesBridgeReader`.
   */
  private class SandboxRemoteComposeBridgeReader(
    private val snapshotMethod: java.lang.reflect.Method
  ) {
    fun snapshot(scope: String): List<String> =
      runCatching {
          @Suppress("UNCHECKED_CAST")
          (snapshotMethod.invoke(null, scope) as Array<String>).toList()
        }
        .getOrDefault(emptyList())

    companion object {
      private const val BRIDGE_FQN: String =
        "ee.schimke.composeai.daemon.bridge.SandboxRemoteComposeBridge"

      @Volatile private var resolved: SandboxRemoteComposeBridgeReader? = null
      @Volatile private var attempted: Boolean = false

      fun tryLoad(): SandboxRemoteComposeBridgeReader? {
        if (attempted) return resolved
        val reader =
          try {
            val cls =
              Class.forName(
                BRIDGE_FQN,
                true,
                SandboxRemoteComposeBridgeReader::class.java.classLoader,
              )
            SandboxRemoteComposeBridgeReader(
              snapshotMethod = cls.getMethod("snapshot", String::class.java)
            )
          } catch (_: ClassNotFoundException) {
            null
          } catch (_: NoSuchMethodException) {
            null
          }
        resolved = reader
        attempted = true
        return reader
      }
    }
  }

  companion object {
    const val KIND: String = RemoteComposeProduct.KIND
    const val SCHEMA_VERSION: Int = RemoteComposeProduct.SCHEMA_VERSION

    private val json = Json {
      encodeDefaults = true
      prettyPrint = false
      ignoreUnknownKeys = true
    }
  }
}
