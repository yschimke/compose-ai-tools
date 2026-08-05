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

    /**
     * Closed sections, always collected — this is what [renderTrace] turns into the `render/trace`
     * data product.
     *
     * Deliberately *not* gated on [enabled]. That flag is the opt-in for writing the Perfetto JSON
     * artefact to disk, which is a different question from whether the daemon can answer "how long
     * did each phase of this render take" over the wire. The cost of always collecting is one
     * `System.nanoTime()` pair and one small object per section, and a render opens on the order of
     * a dozen — far below the noise floor of the work each section wraps.
     */
    private val spans = mutableListOf<RenderTrace.Recorded>()

    /** Sections shed once [MAX_SPANS] was reached. Reported on [RenderTrace.droppedSpans]. */
    private var droppedSpans: Int = 0

    /**
     * Running per-name aggregates, accumulated for **every** section including the ones the
     * retention cap sheds.
     *
     * Kept separately from [spans] rather than derived from them at the end, because those are two
     * different promises. `spans` is a timeline and is allowed to truncate — a UI can only draw so
     * many rows, and [RenderTrace.droppedSpans] says when it did. `sections` is the "where did the
     * time actually go" summary, and a summary that silently omits phases is worse than no summary:
     * it under-reports exactly the hot repeated phase that made a long session hit the cap in the
     * first place.
     */
    private val aggregates = LinkedHashMap<String, MutableAggregate>()

    /**
     * Bounds of *every* section the recorder saw, tracked alongside [aggregates] and for the same
     * reason: once [spans] truncates, the retained prefix ends before the render does, so a total
     * derived from it would be short — and the phase bars would be scaled against a window that
     * closed early.
     */
    private var firstStartNs: Long = Long.MAX_VALUE
    private var lastEndNs: Long = Long.MIN_VALUE

    /**
     * Current nesting level. Incremented for the duration of each [section] body, so a section
     * records the depth it was *entered* at — see [RenderTrace.Recorded.depth] for why that beats
     * reconstructing containment afterwards.
     */
    private var depth: Int = 0

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
      val enteredDepth = depth
      depth = enteredDepth + 1
      val startNs = System.nanoTime()
      try {
        return block()
      } finally {
        depth = enteredDepth
        record(
          name = name,
          category = category,
          startNs = startNs,
          endNs = System.nanoTime(),
          depth = enteredDepth,
        )
        if (platform != null) {
          try {
            platform.end()
          } catch (_: Throwable) {}
        }
      }
    }

    @JvmOverloads
    fun record(
      name: String,
      category: String = "compose-preview",
      startNs: Long,
      endNs: Long,
      depth: Int = 0,
    ) {
      val durationMicros = (endNs - startNs).coerceAtLeast(0L) / 1_000L
      aggregates.getOrPut(name) { MutableAggregate(category) }.add(durationMicros)
      if (startNs < firstStartNs) firstStartNs = startNs
      if (endNs > lastEndNs) lastEndNs = endNs
      if (spans.size < MAX_SPANS) {
        spans +=
          RenderTrace.Recorded(
            name = name,
            category = category,
            startNanos = startNs,
            endNanos = endNs,
            depth = depth,
          )
      } else {
        droppedSpans += 1
      }
      // The Chrome-trace event list stays behind the disk opt-in: it exists only to be written out
      // as `render-perfetto-trace.json`, and building it for every render would be pure waste.
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

    /**
     * The structured phase timings for this render, for `RenderResult.trace`.
     *
     * Snapshot semantics: call it after the outermost section has closed, or the phases still open
     * are simply absent. Cheap enough to call more than once.
     */
    fun renderTrace(): RenderTrace =
      RenderTrace.of(
        backend = backend,
        events = spans.toList(),
        sections =
          aggregates
            .map { (name, aggregate) -> aggregate.toSection(name) }
            .sortedByDescending { it.totalMicros },
        totalMicros =
          if (firstStartNs == Long.MAX_VALUE) null
          else (lastEndNs - firstStartNs).coerceAtLeast(0L) / 1_000L,
        droppedSpans = droppedSpans,
      )

    /** Mutable accumulator behind one [RenderTraceSection]. */
    private class MutableAggregate(private val category: String) {
      private var count: Int = 0
      private var totalMicros: Long = 0L
      private var maxMicros: Long = 0L

      fun add(durationMicros: Long) {
        count += 1
        totalMicros += durationMicros
        if (durationMicros > maxMicros) maxMicros = durationMicros
      }

      fun toSection(name: String): RenderTraceSection =
        RenderTraceSection(
          name = name,
          category = category,
          count = count,
          totalMicros = totalMicros,
          meanMicros = if (count == 0) 0L else totalMicros / count,
          maxMicros = maxMicros,
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

    private companion object {
      /**
       * Retention cap on the in-memory span list. A one-shot render opens a dozen sections; a long
       * interactive session reuses one recorder across many frames, so the bound exists to keep a
       * pathological case from growing without limit rather than to constrain normal use.
       */
      const val MAX_SPANS = 4_096
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
