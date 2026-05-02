package ee.schimke.composeai.data.render

import java.io.File
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Core producer/model for Perfetto-importable render trace artifacts. */
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
