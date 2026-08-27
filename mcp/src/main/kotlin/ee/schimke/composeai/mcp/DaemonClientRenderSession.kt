package ee.schimke.composeai.mcp

import ee.schimke.composeai.daemon.client.DaemonClient
import ee.schimke.composeai.daemon.client.DataProductWireException
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
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.RecordingEncodeResult
import ee.schimke.composeai.daemon.protocol.RecordingFormat
import ee.schimke.composeai.daemon.protocol.RecordingScriptEvent
import ee.schimke.composeai.daemon.protocol.RecordingStartResult
import ee.schimke.composeai.daemon.protocol.RecordingStopResult
import ee.schimke.composeai.daemon.protocol.RenderNowResult
import ee.schimke.composeai.daemon.protocol.RenderTier
import ee.schimke.composeai.render.session.DataProductException
import ee.schimke.composeai.render.session.NotificationListener
import ee.schimke.composeai.render.session.RenderSession
import ee.schimke.composeai.render.session.RenderSessionBackend
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.time.Duration
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Internal view that adapts a [DaemonClient] to the public [RenderSession] surface. Lifecycle is
 * owned by the supervising [SupervisedDaemon] — calling [close] on this view does *not* shut the
 * daemon down (other callers may be sharing it). The supervisor's `shutdown`/`detachSpawn` is the
 * single seam that tears down the underlying subprocess.
 *
 * This is the "surface-only migration" half of the render-session library work: the MCP server
 * keeps using [DaemonClient] directly for its own lifecycle plumbing (replicas, classpath dirty
 * respawn, per-preview routing), but consumers that prefer the published library can ask the
 * supervisor for [SupervisedDaemon.session] and drive the daemon through the same [RenderSession]
 * contract `:render-session-subprocess` and `:render-session-embedded-desktop` expose.
 *
 * Notification fan-out: [onNotification] registrations are aggregated locally and replayed from
 * whatever sink the supervisor's existing `onNotification` callback routes through. New listeners
 * see notifications fired *after* registration; missed events from before are not replayed.
 */
internal class DaemonClientRenderSession(
  override val workspaceRoot: String,
  override val modulePath: String,
  override val initializeResult: InitializeResult,
  private val client: DaemonClient,
  private val notificationFanout: NotificationFanout,
) : RenderSession {

  override val backendKind: RenderSessionBackend = RenderSessionBackend.Subprocess

  override fun setVisible(previewIds: List<String>) = client.setVisible(previewIds)

  override fun setFocus(previewIds: List<String>) = client.setFocus(previewIds)

  override fun fileChanged(path: String, kind: FileKind, changeType: ChangeType) =
    client.fileChanged(path, kind, changeType)

  override fun renderNow(
    previewIds: List<String>,
    tier: RenderTier,
    reason: String?,
    overrides: PreviewOverrides?,
    timeout: Duration,
  ): RenderNowResult =
    client.renderNow(
      previews = previewIds,
      tier = tier,
      reason = reason,
      overrides = overrides,
      timeout = timeout,
    )

  override fun fetchData(
    previewId: String,
    kind: String,
    inline: Boolean,
    params: JsonElement?,
    timeout: Duration,
  ): DataFetchResult =
    try {
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

  override fun subscribeData(
    previewId: String,
    kind: String,
    params: JsonElement?,
    timeout: Duration,
  ): DataSubscribeResult =
    client.dataSubscribe(previewId = previewId, kind = kind, timeout = timeout)

  override fun unsubscribeData(
    previewId: String,
    kind: String,
    timeout: Duration,
  ): DataSubscribeResult =
    client.dataUnsubscribe(previewId = previewId, kind = kind, timeout = timeout)

  override fun listExtensions(timeout: Duration): ExtensionsListResult =
    client.extensionsList(timeout)

  override fun enableExtensions(ids: List<String>, timeout: Duration): ExtensionsEnableResult =
    client.extensionsEnable(ids = ids, timeout = timeout)

  override fun disableExtensions(ids: List<String>, timeout: Duration): ExtensionsDisableResult =
    client.extensionsDisable(ids = ids, timeout = timeout)

  override fun historyList(params: HistoryListParams, timeout: Duration): HistoryListResult =
    client.historyList(params = params, timeout = timeout)

  override fun historyRead(
    entryId: String,
    inline: Boolean,
    timeout: Duration,
  ): HistoryReadResultDto =
    client.historyRead(entryId = entryId, inline = inline, timeout = timeout)

  override fun historyDiff(
    fromId: String,
    toId: String,
    mode: HistoryDiffMode,
    timeout: Duration,
  ): HistoryDiffResult =
    client.historyDiff(fromId = fromId, toId = toId, mode = mode, timeout = timeout)

  override fun recordingStart(
    previewId: String,
    fps: Int?,
    scale: Float?,
    overrides: PreviewOverrides?,
    timeout: Duration,
  ): RecordingStartResult =
    client.recordingStart(
      previewId = previewId,
      fps = fps,
      scale = scale,
      overrides = overrides,
      timeout = timeout,
    )

  override fun recordingScript(recordingId: String, events: List<RecordingScriptEvent>) =
    client.recordingScript(recordingId, events)

  override fun recordingStop(recordingId: String, timeout: Duration): RecordingStopResult =
    client.recordingStop(recordingId = recordingId, timeout = timeout)

  override fun recordingEncode(
    recordingId: String,
    format: RecordingFormat,
    timeout: Duration,
  ): RecordingEncodeResult =
    client.recordingEncode(recordingId = recordingId, format = format, timeout = timeout)

  override fun onNotification(listener: NotificationListener): AutoCloseable =
    notificationFanout.register(listener)

  /**
   * No-op. The supervisor owns the underlying daemon's lifecycle — closing this view doesn't shut
   * the daemon down because other callers may be sharing the same client. Use
   * [DaemonSupervisor.shutdown] (or let the supervised daemon's `classpathDirty` respawn happen)
   * when you actually want the JVM to go away.
   */
  override fun close() {
    // intentional no-op — see KDoc.
  }
}

/**
 * Fan-out registry for the supervisor's notification stream. [SupervisedDaemon] installs a single
 * sink on the underlying [DaemonClient]'s `onNotification` callback at spawn time and pipes every
 * event into [dispatch]; [DaemonClientRenderSession.onNotification] uses [register] to add a
 * listener that sees subsequent events.
 *
 * Lifetime is the supervised daemon's: cleared by [DaemonSupervisor.shutdown] /
 * [SupervisedDaemon.detachSpawn]. Listeners that hold onto the [AutoCloseable] handle after
 * shutdown observe no further events; calling `close()` on a stale handle is harmless.
 */
internal class NotificationFanout {
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
