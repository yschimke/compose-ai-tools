package ee.schimke.composeai.render.session

import ee.schimke.composeai.daemon.protocol.ChangeType
import ee.schimke.composeai.daemon.protocol.DataFetchResult
import ee.schimke.composeai.daemon.protocol.DataSubscribeResult
import ee.schimke.composeai.daemon.protocol.ExtensionsDisableResult
import ee.schimke.composeai.daemon.protocol.ExtensionsEnableResult
import ee.schimke.composeai.daemon.protocol.ExtensionsListResult
import ee.schimke.composeai.daemon.protocol.FileKind
import ee.schimke.composeai.daemon.protocol.HistoryDiffMode
import ee.schimke.composeai.daemon.protocol.HistoryDiffResult
import ee.schimke.composeai.daemon.protocol.HistoryListParams
import ee.schimke.composeai.daemon.protocol.HistoryListResult
import ee.schimke.composeai.daemon.protocol.HistoryReadResultDto
import ee.schimke.composeai.daemon.protocol.InitializeResult
import ee.schimke.composeai.daemon.protocol.InteractiveInputKind
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.RecordingEncodeResult
import ee.schimke.composeai.daemon.protocol.RecordingFormat
import ee.schimke.composeai.daemon.protocol.RecordingScriptEvent
import ee.schimke.composeai.daemon.protocol.RecordingStartResult
import ee.schimke.composeai.daemon.protocol.RecordingStopResult
import ee.schimke.composeai.daemon.protocol.RenderNowResult
import ee.schimke.composeai.daemon.protocol.RenderTier
import ee.schimke.composeai.daemon.protocol.StreamCodec
import ee.schimke.composeai.daemon.protocol.StreamStartResult
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Live render session bound to one preview module. The session encapsulates the renderer lifecycle
 * (initialize, render, fetch data, subscribe to live updates, close); implementations choose how to
 * host the renderer — most commonly as a daemon subprocess driven over JSON-RPC, but a future
 * in-process embedded driver presents the same contract.
 *
 * ## Lifecycle
 *
 * A session is born *initialized*: [RenderSessionFactory] performs the renderer-side `initialize`
 * handshake before handing the session back, so callers never observe an un-initialized session.
 * The publicly observable lifecycle is just `open → drive → close`.
 *
 * ## Threading
 *
 * Implementations are required to be safe for concurrent calls from one calling thread sequenced by
 * the caller. Multi-threaded use is implementation-defined — the subprocess backend's underlying
 * client is internally synchronised, but the contract here does not promise it.
 *
 * ## Notifications
 *
 * Daemon backends emit asynchronous notifications (`renderFinished`, `discoveryUpdated`,
 * `classpathDirty`, `dataProduct`, …) as renders progress. Register a listener via [onNotification]
 * when you need to react to them; the default session ignores them after the synchronous reply
 * returns. Notifications are dispatched from an implementation-defined thread; handlers must not
 * block.
 *
 * ## Errors
 *
 * Every request method throws [RenderSessionException] (or a more specific subtype) on transport /
 * protocol failure. Wire-level data-product errors (`DataProductUnknown`,
 * `DataProductNotAvailable`, etc.) are surfaced as [DataProductException].
 */
interface RenderSession : AutoCloseable {
  /** Absolute path to the workspace root the session was opened against. */
  val workspaceRoot: String

  /** Gradle path of the target module (e.g. `:samples:android`). */
  val modulePath: String

  /** Result of the initialize handshake. Surfaces daemon version, capabilities, and PID. */
  val initializeResult: InitializeResult

  /** Backend that hosts this session — informational; behaviour is identical across backends. */
  val backendKind: RenderSessionBackend

  // ---------------------------------------------------------------------------
  // Editor-state notifications. None of these return data; they update the
  // session's view of which previews matter so subscriptions / live updates
  // stay scoped.
  // ---------------------------------------------------------------------------

  /** Set the most-recently-visible preview ids. Drives sticky subscription liveness. */
  fun setVisible(previewIds: List<String>)

  /** Set the most-recently-focused preview ids. Drives focus-aware rendering. */
  fun setFocus(previewIds: List<String>)

  /**
   * Notify the session of a source / classpath change so it can invalidate caches, swap user
   * classloaders, and (depending on backend) trigger incremental discovery.
   */
  fun fileChanged(
    path: String,
    kind: FileKind = FileKind.SOURCE,
    changeType: ChangeType = ChangeType.MODIFIED,
  )

  // ---------------------------------------------------------------------------
  // Render. Synchronous — returns when every requested preview has either
  // rendered or failed. Caller decides whether to issue one wide call or many
  // narrow ones.
  // ---------------------------------------------------------------------------

  /**
   * Render the given previews. Idempotent at the client level — the backend caches and may serve
   * unchanged results without re-running compose. Pass [PreviewOverrides] to drive
   * device/locale/inspection-mode tweaks per render without mutating the on-disk preview spec.
   */
  fun renderNow(
    previewIds: List<String>,
    tier: RenderTier = RenderTier.FULL,
    reason: String? = null,
    overrides: PreviewOverrides? = null,
    timeout: Duration = 30.seconds,
  ): RenderNowResult

  // ---------------------------------------------------------------------------
  // Data products.
  // ---------------------------------------------------------------------------

  /**
   * Fetch one data product for one preview. The backend re-renders when the artefact isn't
   * materialised yet and the kind is marked `requiresRerender` (e.g. `a11y/atf`). Inline transport
   * returns the payload as a parsed [JsonElement]; path transport returns an on-disk file path the
   * caller reads directly. Pass [inline] = `true` to coerce inline transport on kinds that support
   * it.
   *
   * @throws DataProductException on wire-level data-product errors.
   */
  fun fetchData(
    previewId: String,
    kind: String,
    inline: Boolean = false,
    params: JsonElement? = null,
    timeout: Duration = 30.seconds,
  ): DataFetchResult

  /**
   * Subscribe to a data product kind on one preview. The session keeps the subscription "sticky
   * while visible" — when the preview leaves the most recent [setVisible] set the backend drops the
   * subscription automatically. Re-subscribe when it returns to view.
   */
  fun subscribeData(
    previewId: String,
    kind: String,
    params: JsonElement? = null,
    timeout: Duration = 15.seconds,
  ): DataSubscribeResult

  /** Unsubscribe. See [subscribeData]. */
  fun unsubscribeData(
    previewId: String,
    kind: String,
    timeout: Duration = 15.seconds,
  ): DataSubscribeResult

  // ---------------------------------------------------------------------------
  // Extensions — descriptor introspection + per-session enable/disable.
  // ---------------------------------------------------------------------------

  /** Enumerate the extensions advertised by the backend. */
  fun listExtensions(timeout: Duration = 15.seconds): ExtensionsListResult

  /** Enable specific extension contributions on this session. */
  fun enableExtensions(ids: List<String>, timeout: Duration = 15.seconds): ExtensionsEnableResult

  /** Disable specific extension contributions on this session. */
  fun disableExtensions(ids: List<String>, timeout: Duration = 15.seconds): ExtensionsDisableResult

  // ---------------------------------------------------------------------------
  // History — per-render archive surface. Optional on every backend; backends
  // that don't archive returns empty results / fail with the same exception
  // shape so callers can probe without backend-specific branching.
  // ---------------------------------------------------------------------------

  /** List archived render entries. Pass [HistoryListParams] to filter by preview id / time. */
  fun historyList(
    params: HistoryListParams = HistoryListParams(),
    timeout: Duration = 30.seconds,
  ): HistoryListResult

  /** Read one archived entry. Set [inline] = `true` to receive base64 PNG bytes inline. */
  fun historyRead(
    entryId: String,
    inline: Boolean = false,
    timeout: Duration = 30.seconds,
  ): HistoryReadResultDto

  /** Diff two archived entries. */
  fun historyDiff(
    fromId: String,
    toId: String,
    mode: HistoryDiffMode = HistoryDiffMode.METADATA,
    timeout: Duration = 30.seconds,
  ): HistoryDiffResult

  // ---------------------------------------------------------------------------
  // Recording — scripted screen-recording surface.
  // ---------------------------------------------------------------------------

  /** Start a recording session against one preview. Returns the daemon-allocated recording id. */
  fun recordingStart(
    previewId: String,
    fps: Int? = null,
    scale: Float? = null,
    overrides: PreviewOverrides? = null,
    timeout: Duration = 30.seconds,
  ): RecordingStartResult

  /** Send recording-script events (fire-and-forget notification). */
  fun recordingScript(recordingId: String, events: List<RecordingScriptEvent>)

  /** Stop recording — blocks until the daemon's playback loop finishes writing frames. */
  fun recordingStop(recordingId: String, timeout: Duration = 5.minutes): RecordingStopResult

  /** Encode a stopped recording into a single file (APNG by default). */
  fun recordingEncode(
    recordingId: String,
    format: RecordingFormat = RecordingFormat.APNG,
    timeout: Duration = 60.seconds,
  ): RecordingEncodeResult

  // ---------------------------------------------------------------------------
  // Streaming (optional — daemon `stream/start` + `streamFrame` + `interactive/input`).
  // ---------------------------------------------------------------------------

  /**
   * Start a held streamed-frame session for one preview (daemon `stream/start`). The renderer then
   * pushes `streamFrame` notifications — observe via [onNotification] — carrying inline base64
   * frames keyed by [StreamStartResult.frameStreamId]. The default throws
   * [UnsupportedOperationException]; callers that want graceful degradation catch it (or any
   * failure) and fall back to [renderNow]-per-frame. Only the subprocess/daemon backend overrides
   * this.
   */
  fun streamStart(
    previewId: String,
    codec: StreamCodec? = null,
    maxFps: Int? = null,
    overrides: PreviewOverrides? = null,
    timeout: Duration = 30.seconds,
  ): StreamStartResult = throw UnsupportedOperationException("streaming not supported")

  /** Stop a held stream (daemon `stream/stop`, fire-and-forget). Default throws. */
  fun streamStop(frameStreamId: String): Unit =
    throw UnsupportedOperationException("streaming not supported")

  /**
   * Dispatch an input event into a held stream's live composition (daemon `interactive/input`,
   * fire-and-forget); the resulting frame arrives as a `streamFrame`. Default throws.
   */
  fun interactiveInput(
    frameStreamId: String,
    kind: InteractiveInputKind,
    pixelX: Int? = null,
    pixelY: Int? = null,
    pointerId: Int? = null,
    scrollDeltaY: Float? = null,
    keyCode: String? = null,
  ): Unit = throw UnsupportedOperationException("streaming not supported")

  // ---------------------------------------------------------------------------
  // Notifications.
  // ---------------------------------------------------------------------------

  /**
   * Register a notification listener. The returned [AutoCloseable] removes the listener when closed
   * — typical use is `session.onNotification { … }.use { runRenders() }` so the subscription is
   * scoped to one block. Handlers must not block; offload to a worker if real work is needed.
   */
  fun onNotification(listener: NotificationListener): AutoCloseable

  /**
   * Close the session. Idempotent. After close, every other method throws [IllegalStateException].
   * Implementations are responsible for tearing down their transport (subprocess shutdown,
   * classloader release, etc.) without leaking resources.
   */
  override fun close()
}

/**
 * Sink for daemon-side notifications. [method] is the JSON-RPC method name (`renderFinished`,
 * `discoveryUpdated`, `classpathDirty`, `dataProduct`, …); [params] is the raw notification body as
 * a JSON object (or `null` when the daemon emits an empty params field).
 *
 * Functional interface so callers can pass lambdas directly:
 * ```kotlin
 * session.onNotification { method, _ -> println("[notif] $method") }
 * ```
 */
fun interface NotificationListener {
  fun onNotification(method: String, params: JsonObject?)
}

/** Backend hosting a [RenderSession]. Informational; behaviour is identical across backends. */
enum class RenderSessionBackend {
  /** Daemon JVM spawned as a subprocess, driven over JSON-RPC. The default. */
  Subprocess,

  /**
   * In-process embedded driver. Currently unsupported on most JVMs — the renderer needs the full
   * Robolectric + AGP + Compose classpath, which is rare outside of unit-test runners. Reserved for
   * future implementations.
   */
  Embedded,
}

/**
 * Common base for failures observed across the session contract. Implementations preserve the
 * underlying [cause] (e.g. an `IOException` from the transport, a `JsonRpcException` from the
 * protocol layer) where one exists.
 */
open class RenderSessionException(message: String, cause: Throwable? = null) :
  RuntimeException(message, cause)

/**
 * Thrown when [RenderSession.fetchData] returns a wire-level data-product error. [code] is the
 * JSON-RPC error code (`-32020`..`-32023`); [wireMessage] is the daemon's human-readable message;
 * [data] is the optional structured payload some errors carry.
 */
class DataProductException(
  val code: Int,
  val wireMessage: String,
  val data: JsonObject?,
  cause: Throwable? = null,
) : RenderSessionException("data/fetch wire error $code: $wireMessage", cause) {
  companion object {
    const val UNKNOWN: Int = -32020
    const val NOT_AVAILABLE: Int = -32021
    const val FETCH_FAILED: Int = -32022
    const val BUDGET_EXCEEDED: Int = -32023
  }
}
