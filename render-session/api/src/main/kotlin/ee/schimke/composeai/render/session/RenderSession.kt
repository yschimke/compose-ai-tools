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
 * Live render session bound to one preview module: initialize, render, fetch data, subscribe to
 * live updates, close. Most commonly backed by a daemon subprocess driven over JSON-RPC.
 *
 * Methods taking a `kotlin.time.Duration` compile to mangled JVM names (`renderNow-9VgGkz4`) and
 * are not callable from Java; changing the value-class type is an ABI break.
 *
 * **Lifecycle:** [RenderSessionFactory] completes the `initialize` handshake before returning, so
 * the observable lifecycle is `open → drive → close`.
 *
 * **Threading:** safe for calls sequenced by the caller; concurrent use is implementation-defined.
 *
 * **Notifications** (`renderFinished`, `discoveryUpdated`, `classpathDirty`, `dataProduct`, …) are
 * delivered to [onNotification] listeners on an implementation-defined thread; handlers must not
 * block.
 *
 * **Errors:** request methods throw [RenderSessionException] on transport/protocol failure, and
 * [DataProductException] for wire-level data-product errors.
 */
public interface RenderSession : AutoCloseable {
  /** Absolute path to the workspace root the session was opened against. */
  public val workspaceRoot: String

  /** Gradle path of the target module (e.g. `:samples:android`). */
  public val modulePath: String

  /** Result of the initialize handshake. Surfaces daemon version, capabilities, and PID. */
  public val initializeResult: InitializeResult

  /** Backend that hosts this session — informational; behaviour is identical across backends. */
  public val backendKind: RenderSessionBackend

  // Editor-state notifications: no data returned; they keep subscriptions and live updates scoped.

  /** Set the most-recently-visible preview ids. Drives sticky subscription liveness. */
  public fun setVisible(previewIds: List<String>)

  /** Set the most-recently-focused preview ids. Drives focus-aware rendering. */
  public fun setFocus(previewIds: List<String>)

  /**
   * Notify the session of a source / classpath change so it can invalidate caches, swap user
   * classloaders, and (depending on backend) trigger incremental discovery.
   */
  public fun fileChanged(
    path: String,
    kind: FileKind = FileKind.SOURCE,
    changeType: ChangeType = ChangeType.MODIFIED,
  )

  // Render. Synchronous: returns when every requested preview has rendered or failed.

  /**
   * Render the given previews. Idempotent at the client level — the backend caches and may serve
   * unchanged results without re-running compose. Pass [PreviewOverrides] to drive
   * device/locale/inspection-mode tweaks per render without mutating the on-disk preview spec.
   */
  public fun renderNow(
    previewIds: List<String>,
    tier: RenderTier = RenderTier.FULL,
    reason: String? = null,
    overrides: PreviewOverrides? = null,
    timeout: Duration = 30.seconds,
  ): RenderNowResult

  // Data products.

  /**
   * Fetch one data product for one preview, re-rendering if the kind `requiresRerender` and isn't
   * materialised yet. Inline transport returns a parsed [JsonElement]; path transport returns a
   * file path. Pass [inline] = `true` to force inline where supported.
   *
   * @throws DataProductException on wire-level data-product errors.
   */
  public fun fetchData(
    previewId: String,
    kind: String,
    inline: Boolean = false,
    params: JsonElement? = null,
    timeout: Duration = 30.seconds,
  ): DataFetchResult

  /**
   * Subscribe to a data product kind on one preview. Sticky while visible: the backend drops it
   * when the preview leaves the latest [setVisible] set; re-subscribe when it returns.
   */
  public fun subscribeData(
    previewId: String,
    kind: String,
    params: JsonElement? = null,
    timeout: Duration = 15.seconds,
  ): DataSubscribeResult

  /** Unsubscribe. See [subscribeData]. */
  public fun unsubscribeData(
    previewId: String,
    kind: String,
    timeout: Duration = 15.seconds,
  ): DataSubscribeResult

  // Extensions — descriptor introspection + per-session enable/disable.

  /** Enumerate the extensions advertised by the backend. */
  public fun listExtensions(timeout: Duration = 15.seconds): ExtensionsListResult

  /** Enable specific extension contributions on this session. */
  public fun enableExtensions(
    ids: List<String>,
    timeout: Duration = 15.seconds,
  ): ExtensionsEnableResult

  /** Disable specific extension contributions on this session. */
  public fun disableExtensions(
    ids: List<String>,
    timeout: Duration = 15.seconds,
  ): ExtensionsDisableResult

  // History — per-render archive. Optional: non-archiving backends return empty results or fail
  // with the same exception shape, so callers can probe without branching.

  /** List archived render entries. Pass [HistoryListParams] to filter by preview id / time. */
  public fun historyList(
    params: HistoryListParams = HistoryListParams(),
    timeout: Duration = 30.seconds,
  ): HistoryListResult

  /** Read one archived entry. Set [inline] = `true` to receive base64 PNG bytes inline. */
  public fun historyRead(
    entryId: String,
    inline: Boolean = false,
    timeout: Duration = 30.seconds,
  ): HistoryReadResultDto

  /** Diff two archived entries. */
  public fun historyDiff(
    fromId: String,
    toId: String,
    mode: HistoryDiffMode = HistoryDiffMode.METADATA,
    timeout: Duration = 30.seconds,
  ): HistoryDiffResult

  // Recording — scripted screen recording.

  /** Start a recording session against one preview. Returns the daemon-allocated recording id. */
  public fun recordingStart(
    previewId: String,
    fps: Int? = null,
    scale: Float? = null,
    overrides: PreviewOverrides? = null,
    timeout: Duration = 30.seconds,
  ): RecordingStartResult

  /** Send recording-script events (fire-and-forget notification). */
  public fun recordingScript(recordingId: String, events: List<RecordingScriptEvent>)

  /** Stop recording — blocks until the daemon's playback loop finishes writing frames. */
  public fun recordingStop(recordingId: String, timeout: Duration = 5.minutes): RecordingStopResult

  /** Encode a stopped recording into a single file (APNG by default). */
  public fun recordingEncode(
    recordingId: String,
    format: RecordingFormat = RecordingFormat.APNG,
    timeout: Duration = 60.seconds,
  ): RecordingEncodeResult

  // Streaming (optional — daemon `stream/start` + `streamFrame` + `interactive/input`).

  /**
   * Start a held streamed-frame session for one preview (daemon `stream/start`); frames arrive as
   * `streamFrame` notifications keyed by [StreamStartResult.frameStreamId]. The default throws
   * [UnsupportedOperationException]; callers can fall back to [renderNow] per frame.
   */
  public fun streamStart(
    previewId: String,
    codec: StreamCodec? = null,
    maxFps: Int? = null,
    overrides: PreviewOverrides? = null,
    timeout: Duration = 30.seconds,
  ): StreamStartResult = throw UnsupportedOperationException("streaming not supported")

  /** Stop a held stream (daemon `stream/stop`, fire-and-forget). Default throws. */
  public fun streamStop(frameStreamId: String): Unit =
    throw UnsupportedOperationException("streaming not supported")

  /**
   * Tell the renderer whether anyone is watching a held stream (daemon `stream/visibility`). A
   * hidden stream stays warm but drops to [fps] (daemon default 1); the first frame after becoming
   * visible is a keyframe. Default throws.
   */
  public fun streamVisibility(frameStreamId: String, visible: Boolean, fps: Int? = null): Unit =
    throw UnsupportedOperationException("streaming not supported")

  /**
   * Dispatch an input event into a held stream's live composition (daemon `interactive/input`,
   * fire-and-forget); the resulting frame arrives as a `streamFrame`. Default throws.
   */
  public fun interactiveInput(
    frameStreamId: String,
    kind: InteractiveInputKind,
    pixelX: Int? = null,
    pixelY: Int? = null,
    pointerId: Int? = null,
    scrollDeltaY: Float? = null,
    keyCode: String? = null,
    text: String? = null,
    pointerType: String? = null,
  ): Unit = throw UnsupportedOperationException("streaming not supported")

  // Notifications.

  /**
   * Register a notification listener; closing the returned [AutoCloseable] removes it, e.g.
   * `session.onNotification { … }.use { runRenders() }`. Handlers must not block.
   */
  public fun onNotification(listener: NotificationListener): AutoCloseable

  /**
   * Close the session, tearing down its transport. Idempotent; afterwards every other method throws
   * [IllegalStateException].
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
public fun interface NotificationListener {
  public fun onNotification(method: String, params: JsonObject?)
}

/** Backend hosting a [RenderSession]. Informational; behaviour is identical across backends. */
public enum class RenderSessionBackend {
  /** Daemon JVM spawned as a subprocess, driven over JSON-RPC. The default. */
  Subprocess,

  /**
   * In-process embedded driver. Reserved: the renderer needs the full Robolectric + AGP + Compose
   * classpath, which is rare outside unit-test runners.
   */
  Embedded,
}

/**
 * Common base for session failures, preserving the underlying [cause] (transport `IOException`,
 * protocol `JsonRpcException`) where one exists.
 */
public open class RenderSessionException(message: String, cause: Throwable? = null) :
  RuntimeException(message, cause)

/**
 * Thrown when [RenderSession.fetchData] returns a wire-level data-product error. [code] is the
 * JSON-RPC error code (`-32020`..`-32023`); [wireMessage] is the daemon's human-readable message;
 * [data] is the optional structured payload some errors carry.
 */
public class DataProductException(
  public val code: Int,
  public val wireMessage: String,
  public val data: JsonObject?,
  cause: Throwable? = null,
) : RenderSessionException("data/fetch wire error $code: $wireMessage", cause) {
  public companion object {
    public const val UNKNOWN: Int = -32020
    public const val NOT_AVAILABLE: Int = -32021
    public const val FETCH_FAILED: Int = -32022
    public const val BUDGET_EXCEEDED: Int = -32023
  }
}
