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
  // D1 — data-product kinds the client wants ambient on every render. See
  // docs/daemon/DATA-PRODUCTS.md § "Wire surface". Most clients leave this
  // null/empty and use `data/subscribe` for sticky-while-visible attachment.
  val attachDataProducts: List<String>? = null,
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
  // D1 — kinds the daemon can produce. Empty list = pre-D1 daemon (the
  // client side treats absent and `[]` identically). See
  // docs/daemon/DATA-PRODUCTS.md § "Wire surface".
  val dataProducts: List<DataProductCapability> = emptyList(),
  // INTERACTIVE.md § 9 — `true` when the daemon's host can dispatch
  // `interactive/input` events into a held composition (v2). `false` means
  // `interactive/start` still works but inputs trigger a re-render rather than
  // mutating state (v1 fallback). Defaulted for old daemons that pre-date the
  // capability — clients treat absent and `false` identically.
  val interactive: Boolean = false,
  /**
   * The `@Preview(device = ...)` ids the daemon's `DeviceDimensions` catalog recognises, paired
   * with their resolved geometry. Lets clients build a "render this preview at..." picker without
   * re-bundling the catalog. Empty list = pre-feature daemon (clients treat absent and `[]`
   * identically). The `spec:width=…,height=…,dpi=…` grammar is not enumerable — clients pass it as
   * a free-form `device` override and the daemon parses it at resolve-time. See
   * `daemon/core/.../daemon/devices/DeviceDimensions.kt` for the source of truth.
   */
  val knownDevices: List<KnownDevice> = emptyList(),
)

/**
 * One entry in `ServerCapabilities.knownDevices`. The id is the string a caller passes via
 * `renderNow.overrides.device` (or `@Preview(device = ...)` at discovery time); the geometry fields
 * let a UI label the device ("Pixel 5 — 393×851 dp @ 2.75x") without re-resolving.
 */
@Serializable
data class KnownDevice(val id: String, val widthDp: Int, val heightDp: Int, val density: Float)

/**
 * One advertised data-product kind. Mirrors `DataProductCapability` in
 * `vscode-extension/src/daemon/daemonProtocol.ts`. See
 * [docs/daemon/DATA-PRODUCTS.md](../../../../../../../docs/daemon/DATA-PRODUCTS.md) § "The
 * primitive" for semantics — `transport` picks how the payload travels; `attachable` / `fetchable`
 * discriminate which surfaces support the kind; `requiresRerender = true` warns the client that a
 * `data/fetch` may pay a render cost when the latest pass didn't compute the kind.
 */
@Serializable
data class DataProductCapability(
  val kind: String,
  val schemaVersion: Int,
  val transport: DataProductTransport,
  val attachable: Boolean,
  val fetchable: Boolean,
  val requiresRerender: Boolean,
)

@Serializable
enum class DataProductTransport {
  @SerialName("inline") INLINE,
  @SerialName("path") PATH,
  @SerialName("both") BOTH,
}

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
  /**
   * Optional per-call display-property overrides. Applied to every preview in [previews] for this
   * call only; a subsequent `renderNow` without `overrides` reverts to the discovery-time
   * `RenderSpec` from `previews.json`. See PROTOCOL.md § 5 ("renderNow") and
   * docs/daemon/INTERACTIVE.md § "Display overrides".
   */
  val overrides: PreviewOverrides? = null,
)

/**
 * Per-render display-property overrides, threaded through to each backend's `RenderEngine`. Every
 * field is optional — fields left null fall back to the discovery-time `RenderSpec`. Backends that
 * don't model a particular field (e.g. desktop has no `uiMode` resource qualifier) ignore it. See
 * PROTOCOL.md § 5 ("renderNow.overrides").
 */
@Serializable
data class PreviewOverrides(
  /** Sandbox width in pixels. Mirrors `@Preview(widthDp=…)` × density. */
  val widthPx: Int? = null,
  /** Sandbox height in pixels. */
  val heightPx: Int? = null,
  /** Display density (1.0 = mdpi/160dpi, 2.0 = xhdpi/320dpi, etc.). */
  val density: Float? = null,
  /** BCP-47 locale tag (e.g. `"en-US"`, `"fr"`, `"ja-JP"`). Android-only today. */
  val localeTag: String? = null,
  /** Font scale multiplier (1.0 = system default, 1.3 = "large", 2.0 = max accessibility). */
  val fontScale: Float? = null,
  /** Light/dark mode override. Android-only today. */
  val uiMode: UiMode? = null,
  /** Portrait/landscape override. Android-only today. */
  val orientation: Orientation? = null,
  /**
   * `@Preview(device = ...)` string — `id:pixel_5`, `id:wearos_small_round`, `id:tv_1080p`, or a
   * full `spec:width=400dp,height=800dp,dpi=320,isRound=true` grammar. The daemon resolves the
   * string against its built-in catalog (`ee.schimke.composeai.daemon.devices.DeviceDimensions`)
   * and merges the resulting `widthPx` / `heightPx` / `density` into the render spec. Explicit
   * `widthPx` / `heightPx` / `density` overrides on this same object take precedence — so a caller
   * can say `device: "id:pixel_5", widthPx: 600` to force a wider window on the Pixel 5's density.
   * Unknown device ids fall back to the default (400×800 dp at xxhdpi).
   */
  val device: String? = null,
)

@Serializable
enum class UiMode {
  @SerialName("light") LIGHT,
  @SerialName("dark") DARK,
}

@Serializable
enum class Orientation {
  @SerialName("portrait") PORTRAIT,
  @SerialName("landscape") LANDSCAPE,
}

@Serializable
enum class RenderTier {
  @SerialName("fast") FAST,
  @SerialName("full") FULL,
}

@Serializable
data class RenderNowResult(val queued: List<String>, val rejected: List<RejectedRender>)

@Serializable data class RejectedRender(val id: String, val reason: String)

// ---------------------------------------------------------------------------
// D1 — data products (see docs/daemon/DATA-PRODUCTS.md).
//
// `params` is per-kind options carried as JsonElement so the dispatch surface
// stays kind-agnostic — kinds that take params (e.g. `layout/tree` keyed by
// nodeId) decode against their own serializer at producer time.
// ---------------------------------------------------------------------------

@Serializable
data class DataFetchParams(
  val previewId: String,
  val kind: String,
  val params: JsonElement? = null,
  val inline: Boolean = false,
)

@Serializable
data class DataFetchResult(
  val kind: String,
  val schemaVersion: Int,
  val payload: JsonElement? = null,
  val path: String? = null,
  // Reserved for non-local clients; populated only when caller passes
  // `inline: true` and the kind's transport is blob-shaped.
  val bytes: String? = null,
)

/** Shared params shape for `data/subscribe` and `data/unsubscribe`.
 *
 * `params` is the per-kind subscription option bag — e.g. `compose/recomposition` consumes
 * `{ frameStreamId, mode: "delta" }` from it. Stateless kinds (`a11y/atf`, `a11y/hierarchy`)
 * leave it null. See [docs/daemon/DATA-PRODUCTS.md](../../../../../../../docs/daemon/DATA-PRODUCTS.md)
 * § "Recomposition + interactive mode".
 */
@Serializable
data class DataSubscribeParams(
  val previewId: String,
  val kind: String,
  val params: JsonElement? = null,
)

/** Acknowledgement-only result; trivial by design so growing it stays additive. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class DataSubscribeResult(@EncodeDefault val ok: Boolean = true) {
  companion object {
    val OK: DataSubscribeResult = DataSubscribeResult(ok = true)
  }
}

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
  // D1 — populated only with the `(id, kind)` pairs the client subscribed
  // to (or globally attached via `attachDataProducts`). Absent and `[]` are
  // interchangeable on the wire. See docs/daemon/DATA-PRODUCTS.md.
  val dataProducts: List<DataProductAttachment>? = null,
  /**
   * Interactive-mode frame deduplication signal — see docs/daemon/INTERACTIVE.md § 5. When `true`
   * the daemon has determined the rendered bytes are byte-identical to the previously notified
   * frame for the same preview id, so the client can short-circuit the read-PNG → base64 →
   * postMessage hop. Always omitted (`null` on the wire) when dedup didn't fire — a fresh
   * `renderFinished` whose `unchanged` field is `null` means "client must paint these bytes".
   * Additive per PROTOCOL.md § 7; older clients ignore the field and keep painting unconditionally.
   */
  val unchanged: Boolean? = null,
)

/**
 * One data-product attachment riding on a `renderFinished`. `payload` is per-kind JSON when the
 * producer's transport is `inline`; `path` is an absolute path to a sibling file when the
 * producer's transport is `path`. Exactly one of the two is set per attachment.
 */
@Serializable
data class DataProductAttachment(
  val kind: String,
  val schemaVersion: Int,
  val payload: JsonElement? = null,
  val path: String? = null,
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

// =====================================================================
// 6. History — H1 + H2 wire-format. See docs/daemon/HISTORY.md § "Layer 2 —
//    JSON-RPC API" and `HistoryEntry` in
//    ee.schimke.composeai.daemon.history.
//
// The `entry`, `previewMetadata` fields below carry already-encoded JSON
// rather than typed Kotlin classes — kotlinx.serialization can't reach
// across the package boundary into ee.schimke.composeai.daemon.history
// without pulling its types onto the Messages.kt import surface, which
// would create a circular include for the JsonRpcServer dispatch path.
// We use JsonElement + the dispatch layer encodes/decodes against the
// real `HistoryEntry` / `PreviewInfoDto` serializers at the call site.
// =====================================================================

@Serializable
data class HistoryListParams(
  val previewId: String? = null,
  val since: String? = null,
  val until: String? = null,
  val limit: Int? = null,
  val cursor: String? = null,
  val branch: String? = null,
  val branchPattern: String? = null,
  val commit: String? = null,
  val worktreePath: String? = null,
  val agentId: String? = null,
  val sourceKind: String? = null,
  val sourceId: String? = null,
)

@Serializable
data class HistoryListResult(
  val entries: List<JsonElement>,
  val nextCursor: String? = null,
  val totalCount: Int,
)

@Serializable data class HistoryReadParams(val id: String, val inline: Boolean = false)

@Serializable
data class HistoryReadResultDto(
  val entry: JsonElement,
  val previewMetadata: JsonElement? = null,
  val pngPath: String,
  val pngBytes: String? = null,
)

@Serializable data class HistoryAddedParams(val entry: JsonElement)

// =====================================================================
// 5b. Interactive (live-stream) mode — see docs/daemon/INTERACTIVE.md § 8.
//
// Pins a previewId as one of the daemon's render-priority targets ("warm" sandbox semantics
// once B2.4 lands). Multi-target on the wire: each `interactive/start` registers a fresh
// slot and returns a unique stream id; concurrent streams targeting different (or even the
// same) preview ids coexist. Inputs route by `frameStreamId` so a stop on one stream leaves
// the others untouched. Inputs are fire-and-forget notifications; the daemon responds by
// emitting a fresh `renderFinished` for the target preview.
// =====================================================================

@Serializable data class InteractiveStartParams(val previewId: String)

/**
 * Opaque correlation token returned by `interactive/start`. The client passes it back on every
 * subsequent `interactive/input` and `interactive/stop` so the daemon can route the input to the
 * right frame stream and drop stale ids cleanly.
 */
@Serializable data class InteractiveStartResult(val frameStreamId: String)

@Serializable data class InteractiveStopParams(val frameStreamId: String)

@Serializable
data class InteractiveInputParams(
  val frameStreamId: String,
  val kind: InteractiveInputKind,
  /** Image-natural pixel coordinates. Daemon translates to dp using the last render's density. */
  val pixelX: Int? = null,
  val pixelY: Int? = null,
  /** For `keyDown` / `keyUp`. */
  val keyCode: String? = null,
)

@Serializable
enum class InteractiveInputKind {
  @SerialName("click") CLICK,
  @SerialName("pointerDown") POINTER_DOWN,
  @SerialName("pointerUp") POINTER_UP,
  @SerialName("keyDown") KEY_DOWN,
  @SerialName("keyUp") KEY_UP,
}

// ---------------------------------------------------------------------------
// H3 — `history/diff` metadata-mode wire shape. See HISTORY.md § "What this PR
// lands § H3" and PROTOCOL.md § 5 ("history/diff").
//
// Pixel-mode fields (`diffPx`, `ssim`, `diffPngPath`) are reserved on the
// `HistoryDiffResult` shape but always null in METADATA mode — H5 lands the
// full pixel pass. A METADATA caller asking for `mode = PIXEL` receives a
// distinct -32603 error so `null` pixel fields stay unambiguous.
// ---------------------------------------------------------------------------

@Serializable
enum class HistoryDiffMode {
  @SerialName("metadata") METADATA,
  @SerialName("pixel") PIXEL,
}

@Serializable
data class HistoryDiffParams(
  val from: String,
  val to: String,
  val mode: HistoryDiffMode = HistoryDiffMode.METADATA,
)

@Serializable
data class HistoryDiffResult(
  val pngHashChanged: Boolean,
  val fromMetadata: JsonElement,
  val toMetadata: JsonElement,
  // Pixel-mode fields — null in METADATA mode; populated by H5.
  val diffPx: Long? = null,
  val ssim: Double? = null,
  val diffPngPath: String? = null,
)

// ---------------------------------------------------------------------------
// H4 — `history/prune` request + `historyPruned` notification. See HISTORY.md
// § "Pruning policy" + § "historyPruned".
// ---------------------------------------------------------------------------

/**
 * Manual prune trigger. Each parameter is optional and overrides the daemon's configured default
 * for THIS call only — the auto-prune scheduler keeps using its configured defaults. Set any value
 * to `0` or negative to disable that knob (e.g. `maxAgeDays: 0` → no age-based pruning).
 *
 * `dryRun = true` returns the would-remove set without touching disk.
 */
@Serializable
data class HistoryPruneParams(
  val maxEntriesPerPreview: Int? = null,
  val maxAgeDays: Int? = null,
  val maxTotalSizeBytes: Long? = null,
  val dryRun: Boolean = false,
)

@Serializable
data class HistoryPruneSourceResult(val removedEntryIds: List<String>, val freedBytes: Long)

/**
 * Result of `history/prune`. [removedEntries] / [freedBytes] are the cross-source aggregate;
 * [sourceResults] is the per-source breakdown keyed by `HistorySource.id` (only writable sources
 * are listed — read-only git/HTTP sources don't participate in pruning).
 */
@Serializable
data class HistoryPruneResult(
  val removedEntries: List<String>,
  val freedBytes: Long,
  val sourceResults: Map<String, HistoryPruneSourceResult>,
)

/**
 * `historyPruned` notification (HISTORY.md § "historyPruned"). Emitted after each NON-EMPTY prune
 * pass — auto-prune passes that removed nothing produce no notification.
 */
@Serializable
data class HistoryPrunedParams(
  val removedIds: List<String>,
  val freedBytes: Long,
  val reason: PruneReasonWire,
)

@Serializable
enum class PruneReasonWire {
  @SerialName("auto") AUTO,
  @SerialName("manual") MANUAL,
}
