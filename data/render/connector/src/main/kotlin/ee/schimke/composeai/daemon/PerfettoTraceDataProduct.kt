package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.DataFetchResult
import ee.schimke.composeai.daemon.protocol.DataProductAttachment
import ee.schimke.composeai.daemon.protocol.DataProductCapability
import ee.schimke.composeai.daemon.protocol.DataProductExtra
import ee.schimke.composeai.daemon.protocol.DataProductTransport
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

typealias TraceEvent = ee.schimke.composeai.data.render.TraceEvent

typealias TraceMetadata = ee.schimke.composeai.data.render.TraceMetadata

typealias TracePayload = ee.schimke.composeai.data.render.TracePayload

object PerfettoTraceDataProducer {
  const val KIND: String = ee.schimke.composeai.data.render.PerfettoTraceDataProducer.KIND
  const val SCHEMA_VERSION: Int =
    ee.schimke.composeai.data.render.PerfettoTraceDataProducer.SCHEMA_VERSION
  const val FILE: String = ee.schimke.composeai.data.render.PerfettoTraceDataProducer.FILE
  const val ENABLED_PROP: String =
    ee.schimke.composeai.data.render.PerfettoTraceDataProducer.ENABLED_PROP

  fun enabled(): Boolean = ee.schimke.composeai.data.render.PerfettoTraceDataProducer.enabled()

  fun recorder(previewId: String, backend: String, enabled: Boolean = enabled()): Recorder =
    Recorder(
      ee.schimke.composeai.data.render.PerfettoTraceDataProducer.recorder(
        previewId = previewId,
        backend = backend,
        enabled = enabled,
      )
    )

  fun writeArtifacts(rootDir: File, previewId: String, trace: TracePayload) {
    ee.schimke.composeai.data.render.PerfettoTraceDataProducer.writeArtifacts(
      rootDir = rootDir,
      previewId = previewId,
      trace = trace,
    )
  }

  class Recorder(
    private val delegate: ee.schimke.composeai.data.render.PerfettoTraceDataProducer.Recorder
  ) {
    fun <T> section(name: String, category: String = "compose-preview", block: () -> T): T =
      delegate.section(name = name, category = category, block = block)

    fun record(name: String, category: String = "compose-preview", startNs: Long, endNs: Long) {
      delegate.record(name = name, category = category, startNs = startNs, endNs = endNs)
    }

    fun payload(): TracePayload = delegate.payload()

    fun write(rootDir: File) {
      delegate.write(rootDir)
    }
  }
}

/** Registry side for `render/perfettoTrace`; reads the latest trace JSON artifact from disk. */
class PerfettoTraceDataProductRegistry(private val rootDir: File) : DataProductRegistry {
  private val json = Json { ignoreUnknownKeys = true }

  override val capabilities: List<DataProductCapability> =
    listOf(
      DataProductCapability(
        kind = PerfettoTraceDataProducer.KIND,
        schemaVersion = PerfettoTraceDataProducer.SCHEMA_VERSION,
        transport = DataProductTransport.BOTH,
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
    if (kind != PerfettoTraceDataProducer.KIND) return DataProductRegistry.Outcome.Unknown
    val file = fileFor(previewId)
    if (!file.exists()) return DataProductRegistry.Outcome.NotAvailable
    if (!inline) {
      return DataProductRegistry.Outcome.Ok(
        DataFetchResult(
          kind = kind,
          schemaVersion = PerfettoTraceDataProducer.SCHEMA_VERSION,
          path = file.absolutePath,
          payload = metadataPayload(file),
          extras = listOf(traceExtra(file)),
        )
      )
    }
    val payload: JsonObject =
      try {
        json.parseToJsonElement(file.readText()) as JsonObject
      } catch (t: Throwable) {
        return DataProductRegistry.Outcome.FetchFailed(
          message = "could not parse $kind for $previewId: ${t.message}"
        )
      }
    return DataProductRegistry.Outcome.Ok(
      DataFetchResult(
        kind = kind,
        schemaVersion = PerfettoTraceDataProducer.SCHEMA_VERSION,
        payload = payload,
        extras = listOf(traceExtra(file)),
      )
    )
  }

  override fun attachmentsFor(previewId: String, kinds: Set<String>): List<DataProductAttachment> {
    if (PerfettoTraceDataProducer.KIND !in kinds) return emptyList()
    val file = fileFor(previewId)
    if (!file.exists()) return emptyList()
    return listOf(
      DataProductAttachment(
        kind = PerfettoTraceDataProducer.KIND,
        schemaVersion = PerfettoTraceDataProducer.SCHEMA_VERSION,
        path = file.absolutePath,
        extras = listOf(traceExtra(file)),
      )
    )
  }

  private fun fileFor(previewId: String): File =
    rootDir.resolve(previewId).resolve(PerfettoTraceDataProducer.FILE)

  private fun metadataPayload(file: File): JsonObject = buildJsonObject {
    put("format", "chrome-trace-json")
    put("mediaType", "application/json")
    put("sizeBytes", file.length())
  }

  private fun traceExtra(file: File): DataProductExtra =
    DataProductExtra(
      name = "perfetto",
      path = file.absolutePath,
      mediaType = "application/json",
      sizeBytes = file.length(),
    )
}
