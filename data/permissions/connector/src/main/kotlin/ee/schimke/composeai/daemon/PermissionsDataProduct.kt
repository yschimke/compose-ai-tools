package ee.schimke.composeai.daemon

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
 * `AroundComposable` extension that owns the runtime-permissions surface. The extension is
 * **always active** — the planner emits an instance for every render so the controller-driven
 * Robolectric grant state is seeded and the shadow tracker's `recordQuery` path is wired
 * regardless of whether the client sent an explicit `PermissionsOverride`.
 *
 * **No custom Compose API.** Consumer screens drive the standard Android permission APIs —
 * `ContextCompat.checkSelfPermission(...)`, `Activity.checkSelfPermission(...)`,
 * `PackageManager.checkPermission(...)`, accompanist's `rememberPermissionState`, and the
 * AndroidX `ActivityResultContracts.RequestPermission` launcher — and the connector hooks them
 * transparently:
 *
 * * **Apply overrides** — [PermissionsController.set] pushes the grant map into Robolectric's
 *   `ShadowApplication.grantPermissions/denyPermissions`, so the platform `checkPermission`
 *   path returns the requested value without the screen reaching for a connector-specific
 *   composition local.
 * * **Track queries** — [ShadowContextWrapperPermissionTracker] intercepts every
 *   `ContextWrapper.checkPermission(...)` call (the union of all the public check APIs above)
 *   and records the queried permission in the controller for the `compose/permissions`
 *   data-product payload.
 * * **Live updates** — a follow-up `renderNow.overrides.permissions` re-renders the held
 *   preview with the new grants; the screen reads `ContextCompat.checkSelfPermission(...)` on
 *   recomposition and observes the new value through the standard platform call.
 *
 * Lifecycle:
 *
 * * On construction (planner phase, before composition starts) — [PermissionsController.set]
 *   is called with the seed (clears the map when null). The seed must land **before** the
 *   first composition pass so that consumer code reading `Context.checkSelfPermission(...)` on
 *   the very first composition observes the override; a previous shape applied the seed inside
 *   a `DisposableEffect(seed)` whose block runs *after* composition, leaving the screen on the
 *   pre-seed branch for one full render. See `PermissionsOverrideIntegrationTest` in
 *   `:daemon:android` for the regression that pins this.
 * * On dispose (composition leaves the tree) — clears the override (matches
 *   `KeyboardOverrideExtension`'s on-dispose semantics).
 *
 * Runs in [DataExtensionPhase.OuterEnvironment] so the controller's Robolectric grant state is
 * primed before the user-environment phase reaches preview content — any `LaunchedEffect`-driven
 * permission check composed in user code sees the override.
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

  init {
    // Apply the seed eagerly so `Context.checkSelfPermission(...)` reads through the new
    // `ShadowApplication` grant state on the very first composition. The planner constructs a
    // fresh instance per render in the `OuterEnvironment` phase, which runs before user-environment
    // composition starts, so this is the right hook for "seed before any consumer read".
    //
    // `seed = null` triggers `PermissionsController.set(null)` which clears the previous render's
    // grant map and re-syncs `ShadowApplication` to a permission-free baseline — the always-on
    // planner contract means every render that omits an override still gets a clean slate, not
    // whatever the previous render left behind.
    PermissionsController.set(seed)
  }

  @Composable
  override fun AroundComposable(content: @Composable () -> Unit) {
    DisposableEffect(seed) { onDispose { PermissionsController.set(null) } }
    content()
  }

  companion object {
    val ID: DataExtensionId = DataExtensionId(PermissionsDataProductRegistry.KIND)
  }
}

/**
 * Planner that maps `renderNow.overrides.permissions` to a [PermissionsOverrideExtension].
 * **Always** returns a non-null extension — like `KeyboardPreviewOverrideExtension`. The
 * around-composable's controller seed + shadow-tracker hookup need to be in place on every render
 * so a screen's `ContextCompat.checkSelfPermission(...)` (or any standard Android check API) lands
 * in the controller's recordQuery path.
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
        // anyway — the next `ContextCompat.checkSelfPermission(...)` read in the recomposition
        // picks up the new value through the platform path — no need to ask the dispatcher to
        // queue an extra render.
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
