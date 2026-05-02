package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.devices.DeviceDimensions
import ee.schimke.composeai.daemon.protocol.DataFetchResult
import ee.schimke.composeai.daemon.protocol.DataProductAttachment
import ee.schimke.composeai.daemon.protocol.DataProductCapability
import ee.schimke.composeai.daemon.protocol.DataProductTransport
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Stateless metadata product describing the device-frame clip implied by `@Preview(device = ...)`.
 */
class DeviceClipDataProductRegistry(private val previewIndex: PreviewIndex) : DataProductRegistry {
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

  private fun payloadFor(previewId: String): JsonElement? {
    val preview = previewIndex.byId(previewId) ?: return null
    val params = preview.params
    val device = params?.device?.takeIf { it.isNotBlank() }
    val deviceSpec = device?.let(DeviceDimensions::resolve)
    val isRound = deviceSpec?.isRound == true
    val widthDp = params?.widthDp ?: deviceSpec?.widthDp
    val heightDp = params?.heightDp ?: deviceSpec?.heightDp
    val clip =
      if (isRound && widthDp != null && heightDp != null) {
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
