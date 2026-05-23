package ee.schimke.composeai.daemon

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import ee.schimke.composeai.daemon.protocol.DataFetchResult
import ee.schimke.composeai.daemon.protocol.DataProductAttachment
import ee.schimke.composeai.daemon.protocol.DataProductCapability
import ee.schimke.composeai.daemon.protocol.DataProductTransport
import ee.schimke.composeai.daemon.protocol.PermissionGrantStateOverride
import ee.schimke.composeai.daemon.protocol.PermissionsOverride
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.data.permissions.Material3PermissionsProduct
import ee.schimke.composeai.data.permissions.PermissionGrantWire
import ee.schimke.composeai.data.permissions.PermissionsPayload
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
 * Compose composition local exposing the live permission state to consumer screens. Two
 * affordances:
 *
 * * [PermissionsHost.check] — returns the current grant for [permission] and records the query so
 *   the `compose/permissions` data product can surface what the screen asked about. Reads from
 *   the snapshot-state-backed [PermissionsController.grants], so a screen calling `check(...)`
 *   inside a `@Composable` recomposes when the panel pushes a fresh
 *   `renderNow.overrides.permissions`.
 * * [PermissionsHost.grants] — direct read of the current grant map for screens that want to
 *   iterate all granted/denied permissions without naming each up front.
 *
 * The `ContextCompat.checkSelfPermission(...)` platform path also works (the controller seeds
 * Robolectric's `ShadowApplication`) — `LocalPermissionsHost` exists for screens that want
 * tracking + automatic recomposition on flip without going through the platform call.
 */
val LocalPermissionsHost = compositionLocalOf<PermissionsHost> { ControllerPermissionsHost }

/**
 * Façade over [PermissionsController] for consumer code. Sealed so future facets (rationale flags,
 * per-permission timestamps) can land without breaking the call shape.
 */
interface PermissionsHost {
  /**
   * Read [permission]'s current grant and record the query. Returns `null` when no override
   * applies for [permission] — caller decides the default (Compose code typically treats this as
   * `denied` to surface the "request permission" UI).
   */
  @Composable fun check(permission: String): PermissionGrantStateOverride?
}

private object ControllerPermissionsHost : PermissionsHost {
  @Composable
  override fun check(permission: String): PermissionGrantStateOverride? {
    PermissionsController.recordQuery(permission)
    // Read the snapshot-state map so the composition recomposes when the controller flips.
    val current by PermissionsController.grants
    return current[permission]
  }
}

/**
 * `AroundComposable` extension that owns the runtime-permissions surface. The extension is
 * **always active** — the planner emits an instance for every render so [LocalPermissionsHost] is
 * in scope and the controller's `recordQuery` path is wired regardless of whether the client sent
 * an explicit `PermissionsOverride`.
 *
 * Lifecycle:
 *
 * * On enter — [PermissionsController.set] is called with the seed (clears the map when null).
 *   `DisposableEffect(seed)` re-runs only when the override identity changes, so a subsequent
 *   `renderNow.overrides.permissions` with the same shape doesn't churn the controller.
 * * On dispose — clears the override (matches `KeyboardOverrideExtension`'s on-dispose semantics).
 *
 * Runs in [DataExtensionPhase.OuterEnvironment] so the shadow `LocalPermissionsHost` is in place
 * before the user-environment phase reaches preview content — text fields, buttons, and any
 * `LaunchedEffect`-driven permission check composed in user code see the controller's view.
 */
class PermissionsOverrideExtension(private val seed: PermissionsOverride? = null) :
  AroundComposableExtension(
    id = ID,
    constraints =
      DataExtensionConstraints(
        phase = DataExtensionPhase.OuterEnvironment,
        provides = setOf(DataExtensionCapability(PermissionsDataProductRegistry.KIND)),
      ),
  ) {
  @Composable
  override fun AroundComposable(content: @Composable () -> Unit) {
    DisposableEffect(seed) {
      PermissionsController.set(seed)
      onDispose { PermissionsController.set(null) }
    }
    CompositionLocalProvider(LocalPermissionsHost provides ControllerPermissionsHost) { content() }
  }

  companion object {
    val ID: DataExtensionId = DataExtensionId(PermissionsDataProductRegistry.KIND)
  }
}

/**
 * Planner that maps `renderNow.overrides.permissions` to a [PermissionsOverrideExtension].
 * **Always** returns a non-null extension — like `KeyboardPreviewOverrideExtension`. The
 * around-composable's `LocalPermissionsHost` needs to be in place on every render so a screen
 * that consults the host (or one whose `checkSelfPermission` call lands in the shadow tracker)
 * reaches the controller's tracking path.
 */
class PermissionsPreviewOverrideExtension : DataExtension<PreviewOverrides> {
  override val id: DataExtensionId = PermissionsOverrideExtension.ID

  override fun plan(request: PreviewOverrides): PlannedDataExtension =
    PermissionsOverrideExtension(seed = request.permissions)
}

/**
 * Daemon-side registry adapter for `compose/permissions`.
 *
 * The registry tracks two facets per preview id:
 *
 * * The effective grant map applied by the last `renderNow.overrides.permissions`.
 * * The set of permissions the screen queried during the latest render (insertion order
 *   preserved).
 *
 * A `data/fetch` after a permission-aware render returns the combined payload; before any render
 * or after [clear], it returns [DataProductRegistry.Outcome.NotAvailable]. Clients update the
 * state by sending a fresh `renderNow.overrides.permissions`; the panel's "what's queried" chip
 * subscribes to refresh on every render.
 */
class PermissionsDataProductRegistry : DataProductRegistry {
  private val latestPayloads = ConcurrentHashMap<String, PermissionsPayload>()

  override val capabilities: List<DataProductCapability> =
    listOf(
      DataProductCapability(
        kind = KIND,
        schemaVersion = SCHEMA_VERSION,
        transport = DataProductTransport.INLINE,
        attachable = true,
        fetchable = true,
        // Flipping a grant via a fresh `renderNow.overrides.permissions` triggers a re-render
        // anyway, and the `LocalPermissionsHost`-based screens recompose live during a held
        // session — no need to ask the dispatcher to queue an extra render.
        requiresRerender = false,
      )
    )

  fun capture(previewId: String?, payload: PermissionsPayload) {
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
        payload = json.encodeToJsonElement(PermissionsPayload.serializer(), payload),
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
        payload = json.encodeToJsonElement(PermissionsPayload.serializer(), payload),
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
    // Capture even when no explicit override was sent — the screen may have queried permissions
    // through the controller without a seed, and the panel still wants to surface that. An empty
    // payload (no grants, no queries) is filtered out so we don't masquerade `NotAvailable`.
    val grants = overrides?.permissions?.grants.orEmpty() + PermissionsController.grants.value
    val queried = PermissionsController.queried.value
    if (grants.isEmpty() && queried.isEmpty()) {
      clear(previewId)
      return
    }
    capture(previewId, payloadFor(grants, queried))
  }

  private fun payloadFor(
    grants: Map<String, PermissionGrantStateOverride>,
    queried: List<String>,
  ): PermissionsPayload =
    PermissionsPayload(
      grants =
        grants.mapValues { (_, v) ->
          when (v) {
            PermissionGrantStateOverride.GRANTED -> PermissionGrantWire.GRANTED
            PermissionGrantStateOverride.DENIED -> PermissionGrantWire.DENIED
          }
        },
      queried = queried,
    )

  companion object {
    const val KIND: String = Material3PermissionsProduct.KIND
    const val SCHEMA_VERSION: Int = Material3PermissionsProduct.SCHEMA_VERSION

    private val json = Json {
      encodeDefaults = true
      prettyPrint = false
    }
  }
}
