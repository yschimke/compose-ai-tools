package ee.schimke.composeai.daemon

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import ee.schimke.composeai.daemon.devices.DeviceDimensions
import ee.schimke.composeai.daemon.protocol.DataFetchResult
import ee.schimke.composeai.daemon.protocol.DataProductAttachment
import ee.schimke.composeai.daemon.protocol.DataProductCapability
import ee.schimke.composeai.daemon.protocol.DataProductFacet
import ee.schimke.composeai.daemon.protocol.DataProductTransport
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.data.render.PreviewClip
import ee.schimke.composeai.data.render.PreviewContext
import ee.schimke.composeai.data.render.PreviewDeviceContext
import ee.schimke.composeai.data.render.PreviewDeviceSpec
import ee.schimke.composeai.data.render.extensions.DataExtensionCapability
import ee.schimke.composeai.data.render.extensions.DataExtensionConstraints
import ee.schimke.composeai.data.render.extensions.DataExtensionId
import ee.schimke.composeai.data.render.extensions.DataExtensionPhase
import ee.schimke.composeai.data.render.extensions.compose.AroundComposableExtension
import ee.schimke.composeai.data.render.pipeline.SamplingPolicy
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Metadata product describing the device-frame clip implied by the preview render context.
 *
 * The registry seeds contexts from [PreviewIndex] so clients can fetch the product before a render
 * has completed. When a render finishes, the actual [PreviewContext] replaces the seed so runtime
 * overrides and backend-resolved dimensions win.
 */
class DeviceClipDataProductRegistry(previewIndex: PreviewIndex) : DataProductRegistry {
  private val contexts: ConcurrentHashMap<String, PreviewContext> =
    ConcurrentHashMap(
      previewIndex.snapshot().mapValues { (_, preview) ->
        PreviewContext.Builder(
            previewId = preview.id,
            backend = null,
            renderMode = null,
            outputBaseName = null,
          )
          .device(preview.previewDeviceContext())
          .build()
      }
    )

  override val capabilities: List<DataProductCapability> =
    listOf(
      DataProductCapability(
        kind = KIND,
        schemaVersion = SCHEMA_VERSION,
        transport = DataProductTransport.INLINE,
        attachable = true,
        fetchable = true,
        requiresRerender = false,
        displayName = "Device clip",
        facets = listOf(DataProductFacet.STRUCTURED),
        sampling = SamplingPolicy.End,
      )
    )

  override fun fetch(
    previewId: String,
    kind: String,
    params: JsonElement?,
    inline: Boolean,
  ): DataProductRegistry.Outcome {
    if (kind != KIND) return DataProductRegistry.Outcome.Unknown
    val payload = payloadFor(previewId) ?: return DataProductRegistry.Outcome.NotAvailable
    return DataProductRegistry.Outcome.Ok(
      DataFetchResult(kind = KIND, schemaVersion = SCHEMA_VERSION, payload = payload)
    )
  }

  override fun attachmentsFor(previewId: String, kinds: Set<String>): List<DataProductAttachment> {
    if (KIND !in kinds) return emptyList()
    val payload = payloadFor(previewId) ?: return emptyList()
    return listOf(
      DataProductAttachment(kind = KIND, schemaVersion = SCHEMA_VERSION, payload = payload)
    )
  }

  override fun onRender(
    previewId: String,
    result: RenderResult,
    overrides: PreviewOverrides?,
    previewContext: PreviewContext?,
  ) {
    val context = result.previewContext ?: return
    contexts[previewId] = context
  }

  private fun payloadFor(previewId: String): JsonElement? {
    val device = contexts[previewId]?.device ?: return null
    // Through the shared resolver rather than inlining the circle here: serve draws the same clip
    // as CSS and the scorer masks with it, and three copies of "half the shorter side" is exactly
    // how the backdrop used to disagree with itself across surfaces.
    val clip =
      when (val shape = PreviewClip.resolve(device.isRound, device.widthDp, device.heightDp)) {
        is PreviewClip.Shape.Circle ->
          buildJsonObject {
            put("shape", "circle")
            put("centerXDp", shape.centerXDp)
            put("centerYDp", shape.centerYDp)
            put("radiusDp", shape.radiusDp)
          }
        null -> JsonNull
      }
    return buildJsonObject { put("clip", clip) }
  }

  companion object {
    const val KIND: String = "render/deviceClip"
    const val SCHEMA_VERSION: Int = 1
  }
}

/**
 * Clean Compose-facing connector for applying the selected device-frame clip.
 *
 * The metadata product still owns device discovery and payload shape. Hosts that want the clip
 * applied in composition can plan this extension instead of hardcoding a renderer-side round-device
 * wrapper.
 */
class DeviceClipExtension(private val shape: DeviceClipShape?) :
  AroundComposableExtension(
    id = DataExtensionId(DeviceClipDataProductRegistry.KIND),
    constraints =
      DataExtensionConstraints(
        phase = DataExtensionPhase.OuterEnvironment,
        before = setOf(DataExtensionId(DeviceBackgroundDataProductRegistry.KIND)),
        provides = setOf(DataExtensionCapability(DeviceClipDataProductRegistry.KIND)),
      ),
  ) {
  @Composable
  override fun AroundComposable(content: @Composable () -> Unit) {
    when (shape) {
      is DeviceClipShape.Circle -> Box(modifier = Modifier.clip(CircleShape)) { content() }
      null -> content()
    }
  }
}

sealed interface DeviceClipShape {
  data class Circle(val centerXDp: Double, val centerYDp: Double, val radiusDp: Double) :
    DeviceClipShape
}

private fun PreviewInfoDto.previewDeviceContext(): PreviewDeviceContext {
  val params = params
  val device = params?.device?.takeIf { it.isNotBlank() }
  val resolvedDevice = device?.let(DeviceDimensions::resolve)
  return PreviewDeviceContext(
    device = device,
    widthDp = params?.widthDp?.toDouble() ?: resolvedDevice?.widthDp?.toDouble(),
    heightDp = params?.heightDp?.toDouble() ?: resolvedDevice?.heightDp?.toDouble(),
    density = params?.density ?: resolvedDevice?.density,
    resolvedDevice = resolvedDevice?.previewDeviceSpec(),
  )
}

private fun DeviceDimensions.DeviceSpec.previewDeviceSpec(): PreviewDeviceSpec =
  PreviewDeviceSpec(widthDp = widthDp, heightDp = heightDp, density = density, isRound = isRound)
