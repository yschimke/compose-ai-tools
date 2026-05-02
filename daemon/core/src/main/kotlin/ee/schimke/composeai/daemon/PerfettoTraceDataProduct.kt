package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.DataFetchResult
import ee.schimke.composeai.daemon.protocol.DataProductAttachment
import ee.schimke.composeai.daemon.protocol.DataProductCapability
import ee.schimke.composeai.daemon.protocol.DataProductExtra
import ee.schimke.composeai.daemon.protocol.DataProductTransport
import java.io.File
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Data product for Perfetto-importable render trace artifacts. */
object PerfettoTraceDataProducer {
  const val KIND: String = "render/composeAiTrace"
  const val SCHEMA_VERSION: Int = 1
  const val FILE: String = "render-perfetto-trace.json"
  const val ENABLED_PROP: String = "composeai.daemon.perfettoTrace"

  private val json = Json {
    encodeDefaults = false
    prettyPrint = false
  }

  fun enabled(): Boolean = System.getProperty(ENABLED_PROP) == "true"

  fun recorder(previewId: String, backend: String, enabled: Boolean = enabled()): Recorder =
    Recorder(previewId = previewId, backend = backend, enabled = enabled)

  fun writeArtifacts(rootDir: File, previewId: String, trace: TracePayload) {
    val previewDir = rootDir.resolve(previewId).also { it.mkdirs() }
    previewDir.resolve(FILE).writeText(json.encodeToString(trace))
  }

  class Recorder(
    private val previewId: String,
    private val backend: String,
    private val enabled: Boolean,
  ) {
    private val originNs: Long = System.nanoTime()
    private val events = mutableListOf<TraceEvent>()

    fun <T> section(name: String, category: String = "compose-preview", block: () -> T): T {
      if (!enabled) return block()
      val startNs = System.nanoTime()
      try {
        return block()
      } finally {
        record(name = name, category = category, startNs = startNs, endNs = System.nanoTime())
      }
    }

    fun record(name: String, category: String = "compose-preview", startNs: Long, endNs: Long) {
      if (!enabled) return
      events +=
        TraceEvent(
          name = name,
          category = category,
          timestampMicros = (startNs - originNs) / 1_000.0,
          durationMicros = (endNs - startNs).coerceAtLeast(0L) / 1_000.0,
          args = mapOf("previewId" to previewId, "backend" to backend),
        )
    }

    fun payload(): TracePayload =
      TracePayload(
        traceEvents = events,
        metadata =
          TraceMetadata(
            previewId = previewId,
            backend = backend,
            composeRuntimeTracingOnClasspath = composeRuntimeTracingOnClasspath(),
          ),
      )

    fun write(rootDir: File) {
      if (enabled) writeArtifacts(rootDir = rootDir, previewId = previewId, trace = payload())
    }
  }

  private fun composeRuntimeTracingOnClasspath(): Boolean =
    runCatching {
        Class.forName(
          "androidx.compose.runtime.tracing.ComposeRuntimeTracing",
          false,
          Thread.currentThread().contextClassLoader
            ?: PerfettoTraceDataProducer::class.java.classLoader,
        )
      }
      .isSuccess
}

@Serializable
data class TracePayload(
  @SerialName("traceEvents") val traceEvents: List<TraceEvent>,
  val displayTimeUnit: String = "ms",
  val metadata: TraceMetadata,
)

@Serializable
data class TraceMetadata(
  val previewId: String,
  val backend: String,
  val format: String = "chrome-trace-json",
  val composeRuntimeTracingOnClasspath: Boolean = false,
)

@Serializable
data class TraceEvent(
  val name: String,
  @SerialName("cat") val category: String,
  @SerialName("ph") val phase: String = "X",
  @SerialName("ts") val timestampMicros: Double,
  @SerialName("dur") val durationMicros: Double,
  @SerialName("pid") val processId: Int = 1,
  @SerialName("tid") val threadId: Int = 1,
  val args: Map<String, String> = emptyMap(),
)

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
