package ee.schimke.composeai.daemon

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.wear.compose.material3.onehandedgesture.LocalOneHandedGestureEnabled
import ee.schimke.composeai.daemon.protocol.DataFetchResult
import ee.schimke.composeai.daemon.protocol.DataProductAttachment
import ee.schimke.composeai.daemon.protocol.DataProductCapability
import ee.schimke.composeai.daemon.protocol.DataProductTransport
import ee.schimke.composeai.daemon.protocol.GestureOverride
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.data.gestures.GesturePayload
import ee.schimke.composeai.data.gestures.Material3GestureProduct
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
 * `AroundComposable` extension that applies `renderNow.overrides.gestures` for a Wear preview.
 *
 * It primes [GestureStateController] with the override, installs the [LocalGestureRegistry] the
 * preview's [reportedOneHandedGesture] calls report into, and provides
 * [LocalOneHandedGestureEnabled] so a `gestures.enabled = false` override disables recognition for
 * the tree (the "disabled gesture" screen). When the override carries an `invoke`, a trailing
 * [LaunchedEffect] fires the matching handler once composition (and its handler registrations) has
 * settled — the interactive "invoke the gesture" path. `showHints` is consumed by [GestureHint]
 * through the controller's snapshot state.
 *
 * Runs in [DataExtensionPhase.OuterEnvironment] (like the ambient extension) so the controller and
 * composition locals are in place before the user's content composes.
 */
class GestureOverrideExtension(private val override: GestureOverride?) :
  AroundComposableExtension(
    id = ID,
    constraints =
      DataExtensionConstraints(
        phase = DataExtensionPhase.OuterEnvironment,
        provides = setOf(DataExtensionCapability(GestureDataProductRegistry.KIND)),
      ),
  ) {
  @Composable
  override fun AroundComposable(content: @Composable () -> Unit) {
    GestureStateController.set(override)
    DisposableEffect(override) { onDispose { GestureStateController.set(null) } }

    CompositionLocalProvider(
      LocalGestureRegistry provides GestureStateController,
      LocalOneHandedGestureEnabled provides (override?.enabled ?: true),
    ) {
      content()
    }

    val invoke = override?.invoke
    if (invoke != null) {
      // Keyed on the whole override instance so a fresh `renderNow` (a new GestureOverride) re-fires
      // the handler in a held interactive session, even when the invoke kind is unchanged.
      LaunchedEffect(override) { GestureStateController.invoke(invoke, override.invokeLabel) }
    }
  }

  companion object {
    val ID: DataExtensionId = DataExtensionId(GestureDataProductRegistry.KIND)
  }
}

/**
 * Planner that maps `renderNow.overrides.gestures` to a [GestureOverrideExtension]. No-op when the
 * field is null — matches the ambient / wallpaper / theme planners.
 */
class GesturePreviewOverrideExtension : DataExtension<PreviewOverrides> {
  override val id: DataExtensionId = GestureOverrideExtension.ID

  override fun plan(request: PreviewOverrides): PlannedDataExtension? =
    request.gestures?.let(::GestureOverrideExtension)
}

/**
 * Daemon-side registry adapter for `compose/gestures`.
 *
 * Captures [GestureStateController.snapshot] after any gesture-aware render (`overrides.gestures`
 * set) — the snapshot reflects the handlers the preview registered plus the applied enabled / hint /
 * invoke state. A `data/fetch` before any such render, or after the override is dropped, returns
 * [DataProductRegistry.Outcome.NotAvailable]. Clients change the state by sending a fresh
 * `renderNow.overrides.gestures`.
 */
class GestureDataProductRegistry : DataProductRegistry {
  private val latestPayloads = ConcurrentHashMap<String, GesturePayload>()

  override val capabilities: List<DataProductCapability> =
    listOf(
      DataProductCapability(
        kind = KIND,
        schemaVersion = SCHEMA_VERSION,
        transport = DataProductTransport.INLINE,
        attachable = true,
        fetchable = true,
        requiresRerender = false,
      )
    )

  fun capture(previewId: String?, payload: GesturePayload) {
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
        payload = json.encodeToJsonElement(GesturePayload.serializer(), payload),
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
        payload = json.encodeToJsonElement(GesturePayload.serializer(), payload),
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
    if (overrides?.gestures != null) {
      capture(previewId, GestureStateController.snapshot())
    } else {
      clear(previewId)
    }
  }

  companion object {
    const val KIND: String = Material3GestureProduct.KIND
    const val SCHEMA_VERSION: Int = Material3GestureProduct.SCHEMA_VERSION

    private val json = Json {
      encodeDefaults = true
      prettyPrint = false
    }
  }
}
