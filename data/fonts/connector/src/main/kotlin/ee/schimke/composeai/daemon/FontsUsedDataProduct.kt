package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.DataFetchResult
import ee.schimke.composeai.daemon.protocol.DataProductAttachment
import ee.schimke.composeai.daemon.protocol.DataProductCapability
import ee.schimke.composeai.daemon.protocol.DataProductTransport
import java.io.File
import kotlinx.serialization.json.JsonElement

typealias FontUsedEntry = ee.schimke.composeai.data.fonts.FontUsedEntry

typealias FontsUsedDataProducer = ee.schimke.composeai.data.fonts.FontsUsedDataProducer

typealias FontsUsedPayload = ee.schimke.composeai.data.fonts.FontsUsedPayload

/** Daemon registry adapter for the path-backed `fonts/used` product. */
class FontsUsedDataProductRegistry(private val rootDir: File) : DataProductRegistry {
  override val capabilities: List<DataProductCapability> =
    listOf(
      DataProductCapability(
        kind = FontsUsedDataProducer.KIND,
        schemaVersion = FontsUsedDataProducer.SCHEMA_VERSION,
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
    if (kind != FontsUsedDataProducer.KIND) return DataProductRegistry.Outcome.Unknown
    val payload =
      try {
        FontsUsedDataProducer.readPayload(rootDir, previewId)
      } catch (t: Throwable) {
        return DataProductRegistry.Outcome.FetchFailed(
          message = "could not parse $kind for $previewId: ${t.message}"
        )
      } ?: return DataProductRegistry.Outcome.NotAvailable
    return DataProductRegistry.Outcome.Ok(
      DataFetchResult(
        kind = FontsUsedDataProducer.KIND,
        schemaVersion = FontsUsedDataProducer.SCHEMA_VERSION,
        payload =
          FontsUsedDataProducer.json.encodeToJsonElement(FontsUsedPayload.serializer(), payload),
      )
    )
  }

  override fun attachmentsFor(previewId: String, kinds: Set<String>): List<DataProductAttachment> {
    if (FontsUsedDataProducer.KIND !in kinds) return emptyList()
    val payload =
      try {
        FontsUsedDataProducer.readPayload(rootDir, previewId)
      } catch (_: Throwable) {
        null
      } ?: return emptyList()
    return listOf(
      DataProductAttachment(
        kind = FontsUsedDataProducer.KIND,
        schemaVersion = FontsUsedDataProducer.SCHEMA_VERSION,
        payload =
          FontsUsedDataProducer.json.encodeToJsonElement(FontsUsedPayload.serializer(), payload),
      )
    )
  }
}
