package ee.schimke.composeai.daemon.protocol

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// ---------------------------------------------------------------------------
// Preview daemon — IPC protocol message types.
//
// Source of truth: docs/daemon/PROTOCOL.md (v1, locked). Field names match the
// JSON shapes in that document; we lean on Kotlin/JSON name parity and only
// use @SerialName when the JSON spelling diverges from idiomatic Kotlin.
//
// The TypeScript counterpart lives in vscode-extension/src/daemon/
// daemonProtocol.ts (Stream C, C1.1). Both suites round-trip the JSON
// fixtures under docs/daemon/protocol-fixtures/ as a shared corpus —
// see PROTOCOL.md § 9.
// ---------------------------------------------------------------------------

// =====================================================================
// 1. JSON-RPC envelope (PROTOCOL.md § 2)
//
// `params`, `result`, and `error.data` are typed as JsonElement so the
// envelope layer is generic. The dispatch layer parses these into the
// concrete message classes below using kotlinx.serialization.
// =====================================================================

// `jsonrpc: "2.0"` is mandatory on the wire per the JSON-RPC 2.0 spec, but
// having a default value keeps Kotlin construction ergonomic. @EncodeDefault
// forces it to be written even when a Json configuration sets
// `encodeDefaults = false`.
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class JsonRpcRequest(
  @EncodeDefault val jsonrpc: String = "2.0",
  val id: Long,
  val method: String,
  val params: JsonElement? = null,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class JsonRpcResponse(
  @EncodeDefault val jsonrpc: String = "2.0",
  val id: Long,
  val result: JsonElement? = null,
  val error: JsonRpcError? = null,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class JsonRpcNotification(
  @EncodeDefault val jsonrpc: String = "2.0",
  val method: String,
  val params: JsonElement? = null,
)

@Serializable
data class JsonRpcError(val code: Int, val message: String, val data: JsonElement? = null)

// =====================================================================
// 2. initialize (PROTOCOL.md § 3)
// =====================================================================

@Serializable
data class InitializeParams(
  val protocolVersion: Int,
  val clientVersion: String,
  val workspaceRoot: String,
  val moduleId: String,
  val moduleProjectDir: String,
  val capabilities: ClientCapabilities,
  val options: Options? = null,
)

@Serializable data class ClientCapabilities(val visibility: Boolean, val metrics: Boolean)

@Serializable
data class Options(
  val maxHeapMb: Int? = null,
  val warmSpare: Boolean? = null,
  val detectLeaks: DetectLeaks? = null,
  val foreground: Boolean? = null,
)

@Serializable
enum class DetectLeaks {
  @SerialName("off") OFF,
  @SerialName("light") LIGHT,
  @SerialName("heavy") HEAVY,
}

@Serializable
data class InitializeResult(
  val protocolVersion: Int,
  val daemonVersion: String,
  val pid: Long,
  val capabilities: ServerCapabilities,
  val classpathFingerprint: String,
  val manifest: Manifest,
)

@Serializable
data class ServerCapabilities(
  val incrementalDiscovery: Boolean,
  val sandboxRecycle: Boolean,
  // Subset of {"light","heavy"}; empty means leak detection unavailable.
  val leakDetection: List<LeakDetectionMode>,
)

@Serializable
enum class LeakDetectionMode {
  @SerialName("light") LIGHT,
  @SerialName("heavy") HEAVY,
}

@Serializable data class Manifest(val path: String, val previewCount: Int)

// =====================================================================
// 3. Client → daemon notifications (PROTOCOL.md § 4)
// =====================================================================

@Serializable data class SetVisibleParams(val ids: List<String>)

@Serializable data class SetFocusParams(val ids: List<String>)

@Serializable
data class FileChangedParams(val path: String, val kind: FileKind, val changeType: ChangeType)

@Serializable
enum class FileKind {
  @SerialName("source") SOURCE,
  @SerialName("resource") RESOURCE,
  @SerialName("classpath") CLASSPATH,
}

@Serializable
enum class ChangeType {
  @SerialName("modified") MODIFIED,
  @SerialName("created") CREATED,
  @SerialName("deleted") DELETED,
}

// =====================================================================
// 4. Client → daemon requests (PROTOCOL.md § 5)
// =====================================================================

@Serializable
data class RenderNowParams(
  val previews: List<String>,
  val tier: RenderTier,
  val reason: String? = null,
)

@Serializable
enum class RenderTier {
  @SerialName("fast") FAST,
  @SerialName("full") FULL,
}

@Serializable
data class RenderNowResult(val queued: List<String>, val rejected: List<RejectedRender>)

@Serializable data class RejectedRender(val id: String, val reason: String)

// =====================================================================
// 5. Daemon → client notifications (PROTOCOL.md § 6)
// =====================================================================

@Serializable
data class DiscoveryUpdatedParams(
  // PreviewInfo is the schema emitted by DiscoverPreviewsTask plus the
  // sourceFile field added in P0.2. Carried as JsonElement here because the
  // canonical shape lives in :gradle-plugin and we don't want to duplicate
  // the data class across modules — the daemon dispatch layer can decode
  // into the real type when it's wired up.
  val added: List<JsonElement>,
  val removed: List<String>,
  val changed: List<JsonElement>,
  val totalPreviews: Int,
)

@Serializable data class RenderStartedParams(val id: String, val queuedMs: Long)

@Serializable
data class RenderFinishedParams(
  val id: String,
  val pngPath: String,
  val tookMs: Long,
  val metrics: RenderMetrics? = null,
)

@Serializable
data class RenderMetrics(
  val heapAfterGcMb: Long,
  val nativeHeapMb: Long,
  val sandboxAgeRenders: Long,
  val sandboxAgeMs: Long,
) {
  companion object {
    /**
     * The four flat-map keys [RenderHost] implementations populate on `RenderResult.metrics` to
     * carry B2.3 measurement values across the renderer-agnostic seam.
     *
     * Pinned here so `:daemon:core`, `:daemon:android`, `:daemon:desktop`, and `:daemon:harness`
     * agree on the exact spelling without each reaching for a string literal at the call site.
     */
    const val KEY_HEAP_AFTER_GC_MB: String = "heapAfterGcMb"
    const val KEY_NATIVE_HEAP_MB: String = "nativeHeapMb"
    const val KEY_SANDBOX_AGE_RENDERS: String = "sandboxAgeRenders"
    const val KEY_SANDBOX_AGE_MS: String = "sandboxAgeMs"

    /**
     * Translates the flat `Map<String, Long>` carrier on `RenderResult.metrics` into a structured
     * [RenderMetrics] for the wire. Returns `null` when any of the four B2.3 keys is missing — we
     * deliberately do not emit a half-populated metrics object since callers can't tell the
     * difference between "field truly was zero" and "field was missing", and the wire-level
     * presence of `metrics: null` already encodes "measurement unavailable" cleanly. Extra unknown
     * keys (e.g. the renderer's pre-existing `tookMs`) are ignored — they continue to flow through
     * `RenderFinishedParams.tookMs` at the top level.
     *
     * Returns a `Result` so the caller (`JsonRpcServer.renderFinishedFromResult`) can warn-log the
     * partial-map case and observe drift — a common shape early in a host backend's measurement
     * plumbing.
     */
    fun fromFlatMap(map: Map<String, Long>?): FromFlatMapResult {
      if (map == null) return FromFlatMapResult.AbsentSource
      val heap = map[KEY_HEAP_AFTER_GC_MB]
      val native = map[KEY_NATIVE_HEAP_MB]
      val ageRenders = map[KEY_SANDBOX_AGE_RENDERS]
      val ageMs = map[KEY_SANDBOX_AGE_MS]
      val missing = buildList {
        if (heap == null) add(KEY_HEAP_AFTER_GC_MB)
        if (native == null) add(KEY_NATIVE_HEAP_MB)
        if (ageRenders == null) add(KEY_SANDBOX_AGE_RENDERS)
        if (ageMs == null) add(KEY_SANDBOX_AGE_MS)
      }
      if (missing.isNotEmpty()) return FromFlatMapResult.PartialMap(missing)
      return FromFlatMapResult.Populated(
        RenderMetrics(
          heapAfterGcMb = heap!!,
          nativeHeapMb = native!!,
          sandboxAgeRenders = ageRenders!!,
          sandboxAgeMs = ageMs!!,
        )
      )
    }
  }

  /**
   * Tagged outcome of [fromFlatMap]. The three cases the wire layer needs to distinguish:
   *
   * - [AbsentSource] — the host returned `null` metrics (e.g. the B1.5-era stub hosts that don't
   *   measure anything). The wire emits `metrics: null` with no log noise — pre-B2.3 behaviour.
   * - [PartialMap] — the host populated *some* B2.3 keys but not all four. The wire still emits
   *   `metrics: null` (no half-populated objects), but [JsonRpcServer.renderFinishedFromResult]
   *   logs a warn-level notification so caller-side drift is observable.
   * - [Populated] — all four keys present; the wire carries the structured object.
   */
  sealed interface FromFlatMapResult {
    data object AbsentSource : FromFlatMapResult

    data class PartialMap(val missingKeys: List<String>) : FromFlatMapResult

    data class Populated(val metrics: RenderMetrics) : FromFlatMapResult
  }
}

@Serializable data class RenderFailedParams(val id: String, val error: RenderError)

@Serializable
data class RenderError(
  val kind: RenderErrorKind,
  val message: String,
  val stackTrace: String? = null,
)

@Serializable
enum class RenderErrorKind {
  @SerialName("compile") COMPILE,
  @SerialName("runtime") RUNTIME,
  @SerialName("capture") CAPTURE,
  @SerialName("timeout") TIMEOUT,
  @SerialName("internal") INTERNAL,
}

@Serializable
data class ClasspathDirtyParams(
  val reason: ClasspathDirtyReason,
  val detail: String,
  val changedPaths: List<String>? = null,
)

@Serializable
enum class ClasspathDirtyReason {
  @SerialName("fingerprintMismatch") FINGERPRINT_MISMATCH,
  @SerialName("fileChanged") FILE_CHANGED,
  @SerialName("manifestMissing") MANIFEST_MISSING,
}

@Serializable
data class SandboxRecycleParams(
  val reason: SandboxRecycleReason,
  val ageMs: Long,
  val renderCount: Long,
  val warmSpareReady: Boolean,
)

@Serializable
enum class SandboxRecycleReason {
  @SerialName("heapCeiling") HEAP_CEILING,
  @SerialName("heapDrift") HEAP_DRIFT,
  @SerialName("renderTimeDrift") RENDER_TIME_DRIFT,
  @SerialName("histogramDrift") HISTOGRAM_DRIFT,
  @SerialName("renderCount") RENDER_COUNT,
  @SerialName("leakSuspected") LEAK_SUSPECTED,
  @SerialName("manual") MANUAL,
}

@Serializable data class DaemonWarmingParams(val etaMs: Long)

@Serializable
class DaemonReadyParams {
  // Empty-object payload per PROTOCOL.md § 6 ("daemonReady"). Modelled as a
  // class with no fields so kotlinx-serialization emits/accepts {}.
  override fun equals(other: Any?): Boolean = other is DaemonReadyParams

  override fun hashCode(): Int = 0

  override fun toString(): String = "DaemonReadyParams()"
}

@Serializable
data class LogParams(
  val level: LogLevel,
  val message: String,
  val category: String? = null,
  val context: Map<String, JsonElement>? = null,
)

@Serializable
enum class LogLevel {
  @SerialName("debug") DEBUG,
  @SerialName("info") INFO,
  @SerialName("warn") WARN,
  @SerialName("error") ERROR,
}
