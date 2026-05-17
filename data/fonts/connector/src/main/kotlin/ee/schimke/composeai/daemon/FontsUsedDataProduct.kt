package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.DataProductCapability
import ee.schimke.composeai.daemon.protocol.DataProductTransport
import java.io.File
import kotlinx.serialization.json.JsonElement

typealias FontUsedEntry = ee.schimke.composeai.data.fonts.FontUsedEntry

typealias FontsUsedDataProducer = ee.schimke.composeai.data.fonts.FontsUsedDataProducer

typealias FontsUsedPayload = ee.schimke.composeai.data.fonts.FontsUsedPayload

/**
 * Daemon registry adapter for the inline-transport `fonts/used` product. The on-disk format is
 * typed (`FontsUsedPayload`), so the inline read path defers to [FontsUsedDataProducer.readPayload]
 * rather than the default raw-JSON decode; everything else (capabilities table, missing-file →
 * NotAvailable, attach-on-render plumbing) comes from [FileBackedDataProductRegistry].
 */
class FontsUsedDataProductRegistry(private val rootDir: File) :
  FileBackedDataProductRegistry(
    capabilities =
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
  ) {
  override fun fileFor(previewId: String, kind: String): File? =
    if (kind == FontsUsedDataProducer.KIND)
      rootDir.resolve(previewId).resolve(FontsUsedDataProducer.FILE)
    else null

  override fun readInlinePayload(previewId: String, kind: String, file: File): JsonElement? {
    val payload = FontsUsedDataProducer.readPayload(rootDir, previewId) ?: return null
    return FontsUsedDataProducer.json.encodeToJsonElement(FontsUsedPayload.serializer(), payload)
  }
}
