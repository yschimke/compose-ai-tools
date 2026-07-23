package ee.schimke.composeai.data.render

import ee.schimke.composeai.io.SystemFileSystem
import java.io.File
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path.Companion.toPath

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

  /**
   * Optional platform-tracing mirror for [Recorder.section] spans. When set, every named section
   * additionally opens/closes a span on this backend — the Android engine installs an
   * `androidx.tracing.Trace` implementation (see `AndroidxTraceSections` in `:daemon:android`) so
   * the same phases the JSON recorder times (`compose:setContent`, `render:captureRoboImage`, …)
   * show up in atrace-level captures (Robolectric `ShadowTrace` / Perfetto / Compose
   * runtime-tracing timelines) alongside the framework's own sections. Null (the default) keeps the
   * recorder pure-JVM with zero platform coupling — desktop stays null.
   *
   * Process-global rather than per-recorder because platform trace sections are process-global too;
   * the backend is installed once per sandbox classloader. Backend failures must never fail a
   * render, so both calls are guarded at the call site.
   */
  @Volatile var sectionBackend: TraceSectionBackend? = null

  /** Begin/end pair for one [Recorder.section] span on a platform tracer. See [sectionBackend]. */
  interface TraceSectionBackend {
    fun begin(name: String)

    fun end()
  }

  fun recorder(previewId: String, backend: String, enabled: Boolean = enabled()): Recorder =
    Recorder(previewId = previewId, backend = backend, enabled = enabled)

  fun writeArtifacts(
    rootDir: File,
    previewId: String,
    trace: TracePayload,
    fileSystem: FileSystem = SystemFileSystem,
  ) {
    val previewDir = rootDir.resolve(previewId).also { it.mkdirs() }
    fileSystem.write(previewDir.resolve(FILE).path.toPath()) {
      writeUtf8(json.encodeToString(trace))
    }
  }

  class Recorder(
    private val previewId: String,
    private val backend: String,
    private val enabled: Boolean,
  ) {
    private val originNs: Long = System.nanoTime()
    private val events = mutableListOf<TraceEvent>()

    fun <T> section(name: String, category: String = "compose-preview", block: () -> T): T {
      // Mirror the span onto the platform tracer when one is installed (see [sectionBackend]).
      // Independent of [enabled] — the JSON recorder and an atrace capture are separate opt-ins —
      // and guarded so a tracer failure can never fail the render it's observing.
      val platform = sectionBackend
      if (platform != null) {
        try {
          platform.begin(name)
        } catch (_: Throwable) {}
      }
      try {
        if (!enabled) return block()
        val startNs = System.nanoTime()
        try {
          return block()
        } finally {
          record(name = name, category = category, startNs = startNs, endNs = System.nanoTime())
        }
      } finally {
        if (platform != null) {
          try {
            platform.end()
          } catch (_: Throwable) {}
        }
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
    ComposeRuntimeTracingAvailability.isAvailable()
}

/**
 * Domain API for optional Compose runtime tracing detection.
 *
 * This keeps reflective classpath probing out of the trace producer logic; callers only care
 * whether the optional tracing API is available.
 */
internal object ComposeRuntimeTracingAvailability {
  fun isAvailable(
    classLoader: ClassLoader =
      Thread.currentThread().contextClassLoader ?: PerfettoTraceDataProducer::class.java.classLoader
  ): Boolean =
    runCatching {
        Class.forName("androidx.compose.runtime.tracing.ComposeRuntimeTracing", false, classLoader)
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
