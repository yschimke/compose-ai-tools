package ee.schimke.composeai.daemon

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import ee.schimke.composeai.daemon.protocol.AmbientOverride
import ee.schimke.composeai.daemon.protocol.AmbientStateOverride
import ee.schimke.composeai.daemon.protocol.DataFetchResult
import ee.schimke.composeai.daemon.protocol.DataProductAttachment
import ee.schimke.composeai.daemon.protocol.DataProductCapability
import ee.schimke.composeai.daemon.protocol.DataProductTransport
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.data.ambient.AmbientPayload
import ee.schimke.composeai.data.ambient.Material3AmbientProduct
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
 * `AroundComposable` extension that primes [AmbientStateController] with the active override
 * before the consumer's `AmbientAware { ... }` reaches the registered observer.
 *
 * Runs in the [DataExtensionPhase.OuterEnvironment] phase so the controller is set before the
 * `UserEnvironment` phase (where wallpaper / theme sit) — by the time the user code reaches
 * `AmbientAware { ... }` the controller is primed.
 *
 * Does **not** install a `CompositionLocalProvider` for `LocalAmbientState`. That's the
 * consumer's responsibility via `AmbientAware`; the controller drives the consumer's existing
 * callback chain.
 */
class AmbientOverrideExtension(private val override: AmbientOverride?) :
  AroundComposableExtension(
    id = ID,
    constraints =
      DataExtensionConstraints(
        phase = DataExtensionPhase.OuterEnvironment,
        provides = setOf(DataExtensionCapability(AmbientDataProductRegistry.KIND)),
      ),
  ) {
  @Composable
  override fun AroundComposable(content: @Composable () -> Unit) {
    AmbientStateController.set(override)
    DisposableEffect(override) { onDispose { AmbientStateController.set(null) } }
    content()
  }

  companion object {
    val ID: DataExtensionId = DataExtensionId(AmbientDataProductRegistry.KIND)
  }
}

/**
 * Planner that maps `renderNow.overrides.ambient` to an [AmbientOverrideExtension]. No-op when
 * the field is null — matches the wallpaper / theme planners.
 */
class AmbientPreviewOverrideExtension : DataExtension<PreviewOverrides> {
  override val id: DataExtensionId = AmbientOverrideExtension.ID

  override fun plan(request: PreviewOverrides): PlannedDataExtension? =
    request.ambient?.let(::AmbientOverrideExtension)
}

/**
 * Daemon-side registry adapter for `compose/ambient`.
 *
 * The registry tracks the ambient state last applied via `renderNow.overrides.ambient`. A
 * `data/fetch` after an ambient-aware render returns the captured payload; before any render or
 * after the override is dropped, it returns [DataProductRegistry.Outcome.NotAvailable]. Clients
 * update the state by sending a fresh `renderNow.overrides.ambient`.
 */
class AmbientDataProductRegistry : DataProductRegistry {
  private val latestPayloads = ConcurrentHashMap<String, AmbientPayload>()

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

  fun capture(previewId: String?, payload: AmbientPayload) {
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
        payload = json.encodeToJsonElement(AmbientPayload.serializer(), payload),
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
        payload = json.encodeToJsonElement(AmbientPayload.serializer(), payload),
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
    val applied = overrides?.ambient
    if (applied != null) {
      capture(previewId, payloadFor(applied))
    } else {
      clear(previewId)
    }
  }

  private fun payloadFor(applied: AmbientOverride): AmbientPayload =
    AmbientPayload(
      state = applied.state.wireName(),
      burnInProtectionRequired =
        applied.state == AmbientStateOverride.AMBIENT &&
          (applied.burnInProtectionRequired ?: false),
      deviceHasLowBitAmbient =
        applied.state == AmbientStateOverride.AMBIENT &&
          (applied.deviceHasLowBitAmbient ?: false),
      updateTimeMillis = applied.updateTimeMillis ?: System.currentTimeMillis(),
    )

  companion object {
    const val KIND: String = Material3AmbientProduct.KIND
    const val SCHEMA_VERSION: Int = Material3AmbientProduct.SCHEMA_VERSION

    private val json = Json {
      encodeDefaults = true
      prettyPrint = false
    }

    private fun AmbientStateOverride.wireName(): String =
      when (this) {
        AmbientStateOverride.INTERACTIVE -> "interactive"
        AmbientStateOverride.AMBIENT -> "ambient"
        AmbientStateOverride.INACTIVE -> "inactive"
      }
  }
}
