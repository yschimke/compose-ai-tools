package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.DataFetchResult
import ee.schimke.composeai.daemon.protocol.DataProductAttachment
import ee.schimke.composeai.daemon.protocol.DataProductCapability
import ee.schimke.composeai.daemon.protocol.DataProductTransport
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/** Path-backed producer for `fonts/used`, written by backend render loops in default mode. */
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
        readPayload(previewId)
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
        readPayload(previewId)
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

  private fun readPayload(previewId: String): FontsUsedPayload? {
    val file = rootDir.resolve(previewId).resolve(FontsUsedDataProducer.FILE)
    if (!file.exists()) return null
    return FontsUsedDataProducer.json.decodeFromString(
      FontsUsedPayload.serializer(),
      file.readText(),
    )
  }
}

object FontsUsedDataProducer {
  const val KIND: String = "fonts/used"
  const val SCHEMA_VERSION: Int = 1
  const val FILE: String = "fonts-used.json"

  val json: Json = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
    prettyPrint = false
  }

  fun writeArtifacts(rootDir: File, previewId: String, payload: FontsUsedPayload) {
    val previewDir = rootDir.resolve(previewId).also { it.mkdirs() }
    previewDir.resolve(FILE).writeText(json.encodeToString(FontsUsedPayload.serializer(), payload))
  }
}

@Serializable data class FontsUsedPayload(val fonts: List<FontUsedEntry>)

@Serializable
data class FontUsedEntry(
  val requestedFamily: String,
  val resolvedFamily: String,
  val weight: Int,
  val style: String,
  val sourceFile: String? = null,
  val fellBackFrom: List<String>? = null,
  val consumerNodeIds: List<String> = emptyList(),
)
