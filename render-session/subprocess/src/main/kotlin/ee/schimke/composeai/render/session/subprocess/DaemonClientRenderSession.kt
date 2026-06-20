package ee.schimke.composeai.render.session.subprocess

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
import ee.schimke.composeai.mcp.DaemonClient
import ee.schimke.composeai.mcp.DataProductWireException
import ee.schimke.composeai.render.session.DataProductException
import ee.schimke.composeai.render.session.NotificationListener
import ee.schimke.composeai.render.session.RenderSession
import ee.schimke.composeai.render.session.RenderSessionBackend
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Canonical [RenderSession] implementation backed by a JSON-RPC [DaemonClient]. Shared by every
 * backend that ultimately drives a daemon over the same protocol — today: the subprocess backend in
 * this module, the embedded-desktop backend in `:render-session-embedded-desktop`, and the MCP
 * supervisor's `SupervisedDaemon.session` view in `:mcp`.
 *
 * **What this class owns**: the transport-agnostic surface. Every [RenderSession] method delegates
 * to the supplied [client]. [DataProductWireException]s from the client surface as the public
 * [DataProductException]. Notifications fan out through [notificationFanout].
 *
 * **What it does NOT own**: subprocess lifecycle, classloader management, or anything else
 * backend-specific. The caller passes a [closeAction] lambda that's invoked exactly once on the
 * first [close] — that's where the subprocess backend tears down `DaemonSpawn`, the embedded
 * backend joins its daemon thread and closes pipes, and the MCP supervisor's view leaves the daemon
 * running (no-op close).
 *
 * Pre-1.0 surface. The public constructor lets new backends construct one without owning a copy of
 * the ~150 LOC of pass-through delegate methods.
 */
class DaemonClientRenderSession(
  override val workspaceRoot: String,
  override val modulePath: String,
  override val initializeResult: InitializeResult,
  override val backendKind: RenderSessionBackend,
  private val client: DaemonClient,
  private val notificationFanout: NotificationFanout,
  private val closeAction: () -> Unit,
) : RenderSession {

  private val closed = AtomicBoolean(false)

  override fun setVisible(previewIds: List<String>) {
    checkOpen()
    client.setVisible(previewIds)
  }

  override fun setFocus(previewIds: List<String>) {
    checkOpen()
    client.setFocus(previewIds)
  }

  override fun fileChanged(path: String, kind: FileKind, changeType: ChangeType) {
    checkOpen()
    client.fileChanged(path, kind, changeType)
  }

  override fun renderNow(
    previewIds: List<String>,
    tier: RenderTier,
    reason: String?,
    overrides: PreviewOverrides?,
    timeout: Duration,
  ): RenderNowResult {
    checkOpen()
    return client.renderNow(
      previews = previewIds,
      tier = tier,
      reason = reason,
      overrides = overrides,
      timeout = timeout,
    )
  }

  override fun fetchData(
    previewId: String,
    kind: String,
    inline: Boolean,
    params: JsonElement?,
    timeout: Duration,
  ): DataFetchResult {
    checkOpen()
    return try {
      client.dataFetch(
        previewId = previewId,
        kind = kind,
        params = params,
        inline = inline,
        timeout = timeout,
      )
    } catch (e: DataProductWireException) {
      throw DataProductException(
        code = e.code,
        wireMessage = e.wireMessage,
        data = e.data,
        cause = e,
      )
    }
  }

  override fun subscribeData(
    previewId: String,
    kind: String,
    params: JsonElement?,
    timeout: Duration,
  ): DataSubscribeResult {
    checkOpen()
    return client.dataSubscribe(previewId = previewId, kind = kind, timeout = timeout)
  }

  override fun unsubscribeData(
    previewId: String,
    kind: String,
    timeout: Duration,
  ): DataSubscribeResult {
    checkOpen()
    return client.dataUnsubscribe(previewId = previewId, kind = kind, timeout = timeout)
  }

  override fun listExtensions(timeout: Duration): ExtensionsListResult {
    checkOpen()
    return client.extensionsList(timeout)
  }

  override fun enableExtensions(ids: List<String>, timeout: Duration): ExtensionsEnableResult {
    checkOpen()
    return client.extensionsEnable(ids = ids, timeout = timeout)
  }

  override fun disableExtensions(ids: List<String>, timeout: Duration): ExtensionsDisableResult {
    checkOpen()
    return client.extensionsDisable(ids = ids, timeout = timeout)
  }

  override fun historyList(params: HistoryListParams, timeout: Duration): HistoryListResult {
    checkOpen()
    return client.historyList(params = params, timeout = timeout)
  }

  override fun historyRead(
    entryId: String,
    inline: Boolean,
    timeout: Duration,
  ): HistoryReadResultDto {
    checkOpen()
    return client.historyRead(entryId = entryId, inline = inline, timeout = timeout)
  }

  override fun historyDiff(
    fromId: String,
    toId: String,
    mode: HistoryDiffMode,
    timeout: Duration,
  ): HistoryDiffResult {
    checkOpen()
    return client.historyDiff(fromId = fromId, toId = toId, mode = mode, timeout = timeout)
  }

  override fun recordingStart(
    previewId: String,
    fps: Int?,
    scale: Float?,
    overrides: PreviewOverrides?,
    timeout: Duration,
  ): RecordingStartResult {
    checkOpen()
    return client.recordingStart(
      previewId = previewId,
      fps = fps,
      scale = scale,
      overrides = overrides,
      timeout = timeout,
    )
  }

  override fun recordingScript(recordingId: String, events: List<RecordingScriptEvent>) {
    checkOpen()
    client.recordingScript(recordingId, events)
  }

  override fun recordingStop(recordingId: String, timeout: Duration): RecordingStopResult {
    checkOpen()
    return client.recordingStop(recordingId = recordingId, timeout = timeout)
  }

  override fun recordingEncode(
    recordingId: String,
    format: RecordingFormat,
    timeout: Duration,
  ): RecordingEncodeResult {
    checkOpen()
    return client.recordingEncode(recordingId = recordingId, format = format, timeout = timeout)
  }

  override fun streamStart(
    previewId: String,
    codec: StreamCodec?,
    maxFps: Int?,
    overrides: PreviewOverrides?,
    timeout: Duration,
  ): StreamStartResult {
    checkOpen()
    return client.streamStart(
      previewId = previewId,
      codec = codec,
      maxFps = maxFps,
      overrides = overrides,
      timeout = timeout,
    )
  }

  override fun streamStop(frameStreamId: String) {
    checkOpen()
    client.streamStop(frameStreamId)
  }

  override fun interactiveInput(
    frameStreamId: String,
    kind: InteractiveInputKind,
    pixelX: Int?,
    pixelY: Int?,
    pointerId: Int?,
    scrollDeltaY: Float?,
    keyCode: String?,
  ) {
    checkOpen()
    client.interactiveInput(
      frameStreamId = frameStreamId,
      kind = kind,
      pixelX = pixelX,
      pixelY = pixelY,
      pointerId = pointerId,
      scrollDeltaY = scrollDeltaY,
      keyCode = keyCode,
    )
  }

  override fun onNotification(listener: NotificationListener): AutoCloseable {
    checkOpen()
    return notificationFanout.register(listener)
  }

  override fun close() {
    if (!closed.compareAndSet(false, true)) return
    runCatching { closeAction() }
  }

  private fun checkOpen() {
    check(!closed.get()) { "RenderSession has been closed" }
  }
}

/**
 * Fan-out registry for the notification stream a [DaemonClientRenderSession] wraps. The owner of
 * the underlying [DaemonClient] installs a single sink on its `onNotification` callback and pipes
 * every event into [dispatch]; consumers register listeners via [RenderSession.onNotification]
 * (which delegates to [register]). Lifetime is the owner's: `clear()` on teardown so stale handles
 * to `close()` are harmless after the underlying daemon goes away.
 */
class NotificationFanout {
  private val listeners = CopyOnWriteArraySet<NotificationListener>()

  fun register(listener: NotificationListener): AutoCloseable {
    listeners.add(listener)
    return AutoCloseable { listeners.remove(listener) }
  }

  fun dispatch(method: String, params: JsonObject?) {
    for (l in listeners) runCatching { l.onNotification(method, params) }
  }

  fun clear() {
    listeners.clear()
  }
}
