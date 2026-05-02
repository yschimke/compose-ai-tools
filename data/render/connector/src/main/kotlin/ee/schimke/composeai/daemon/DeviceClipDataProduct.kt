package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.devices.DeviceDimensions
import ee.schimke.composeai.daemon.protocol.DataFetchResult
import ee.schimke.composeai.daemon.protocol.DataProductAttachment
import ee.schimke.composeai.daemon.protocol.DataProductCapability
import ee.schimke.composeai.daemon.protocol.DataProductTransport
import ee.schimke.composeai.data.render.PreviewContext
import ee.schimke.composeai.data.render.PreviewDeviceContext
import ee.schimke.composeai.data.render.PreviewDeviceSpec
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

  override fun onRender(previewId: String, result: RenderResult) {
    val context = result.previewContext ?: return
    contexts[previewId] = context
  }

  private fun payloadFor(previewId: String): JsonElement? {
    val device = contexts[previewId]?.device ?: return null
    val widthDp = device.widthDp
    val heightDp = device.heightDp
    val clip =
      if (device.isRound && widthDp != null && heightDp != null) {
        val diameter = minOf(widthDp, heightDp)
        val radius = diameter / 2.0
        buildJsonObject {
          put("shape", "circle")
          put("centerXDp", widthDp / 2.0)
          put("centerYDp", heightDp / 2.0)
          put("radiusDp", radius)
        }
      } else {
        JsonNull
      }
    return buildJsonObject { put("clip", clip) }
  }

  companion object {
    const val KIND: String = "render/deviceClip"
    const val SCHEMA_VERSION: Int = 1
  }
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
