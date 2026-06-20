package ee.schimke.composeai.cli.serve

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
import ee.schimke.composeai.daemon.protocol.Manifest
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.RecordingEncodeResult
import ee.schimke.composeai.daemon.protocol.RecordingFormat
import ee.schimke.composeai.daemon.protocol.RecordingScriptEvent
import ee.schimke.composeai.daemon.protocol.RecordingStartResult
import ee.schimke.composeai.daemon.protocol.RecordingStopResult
import ee.schimke.composeai.daemon.protocol.RejectedRender
import ee.schimke.composeai.daemon.protocol.RenderNowResult
import ee.schimke.composeai.daemon.protocol.RenderTier
import ee.schimke.composeai.daemon.protocol.ServerCapabilities
import ee.schimke.composeai.render.session.NotificationListener
import ee.schimke.composeai.render.session.RenderSession
import ee.schimke.composeai.render.session.RenderSessionBackend
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Shared test [RenderSession]: each [renderNow] writes a PNG (bytes encode the overrides, so
 * distinct overrides → distinct bytes) and synchronously emits the `renderFinished` callers await.
 *
 * [renderHook] (when set) overrides the default emit-immediately behaviour: it receives the 1-based
 * call index and an `emit` that writes given bytes to a fresh PNG and fires `renderFinished`. A
 * hook that emits nothing models a render that times out (the daemon owes a late event), which
 * drives the stale-event path. [rejectAll] rejects every render.
 */
internal class FakeRenderSession(
  private val renderRoot: File,
  private val rejectAll: Boolean = false,
  private val renderHook: ((call: Int, emit: (ByteArray) -> Unit) -> Unit)? = null,
) : RenderSession {
  val renderCount = AtomicInteger(0)
  private val listeners = CopyOnWriteArrayList<NotificationListener>()
  private val counter = AtomicInteger(0)

  private fun emitFinished(id: String, bytes: ByteArray) {
    renderRoot.mkdirs()
    val file = File(renderRoot, "$id-${counter.incrementAndGet()}.png").apply { writeBytes(bytes) }
    val params = buildJsonObject {
      put("id", id)
      put("pngPath", file.absolutePath)
    }
    listeners.forEach { it.onNotification("renderFinished", params) }
  }

  override val workspaceRoot: String = renderRoot.absolutePath
  override val modulePath: String = ":sample"
  override val initializeResult: InitializeResult =
    InitializeResult(
      protocolVersion = 2,
      daemonVersion = "fake",
      pid = 0,
      capabilities =
        ServerCapabilities(
          incrementalDiscovery = false,
          sandboxRecycle = false,
          leakDetection = emptyList(),
        ),
      classpathFingerprint = "",
      manifest = Manifest(path = "", previewCount = 0),
    )
  override val backendKind: RenderSessionBackend = RenderSessionBackend.Subprocess

  override fun setVisible(previewIds: List<String>) = Unit

  override fun setFocus(previewIds: List<String>) = Unit

  override fun fileChanged(path: String, kind: FileKind, changeType: ChangeType) = Unit

  override fun renderNow(
    previewIds: List<String>,
    tier: RenderTier,
    reason: String?,
    overrides: PreviewOverrides?,
    timeout: kotlin.time.Duration,
  ): RenderNowResult {
    val call = renderCount.incrementAndGet()
    val id = previewIds.single()
    if (rejectAll) {
      return RenderNowResult(queued = emptyList(), rejected = listOf(RejectedRender(id, "nope")))
    }
    val hook = renderHook
    if (hook != null) {
      hook(call) { bytes -> emitFinished(id, bytes) }
    } else {
      val content = "png:${overrides?.uiMode}:${overrides?.localeTag}:${overrides?.device}"
      emitFinished(id, content.toByteArray())
    }
    return RenderNowResult(queued = previewIds, rejected = emptyList())
  }

  override fun fetchData(
    previewId: String,
    kind: String,
    inline: Boolean,
    params: JsonElement?,
    timeout: kotlin.time.Duration,
  ): DataFetchResult = error("unused")

  override fun subscribeData(
    previewId: String,
    kind: String,
    params: JsonElement?,
    timeout: kotlin.time.Duration,
  ): DataSubscribeResult = error("unused")

  override fun unsubscribeData(
    previewId: String,
    kind: String,
    timeout: kotlin.time.Duration,
  ): DataSubscribeResult = error("unused")

  override fun listExtensions(timeout: kotlin.time.Duration): ExtensionsListResult = error("unused")

  override fun enableExtensions(
    ids: List<String>,
    timeout: kotlin.time.Duration,
  ): ExtensionsEnableResult = error("unused")

  override fun disableExtensions(
    ids: List<String>,
    timeout: kotlin.time.Duration,
  ): ExtensionsDisableResult = error("unused")

  override fun historyList(
    params: HistoryListParams,
    timeout: kotlin.time.Duration,
  ): HistoryListResult = error("unused")

  override fun historyRead(
    entryId: String,
    inline: Boolean,
    timeout: kotlin.time.Duration,
  ): HistoryReadResultDto = error("unused")

  override fun historyDiff(
    fromId: String,
    toId: String,
    mode: HistoryDiffMode,
    timeout: kotlin.time.Duration,
  ): HistoryDiffResult = error("unused")

  override fun recordingStart(
    previewId: String,
    fps: Int?,
    scale: Float?,
    overrides: PreviewOverrides?,
    timeout: kotlin.time.Duration,
  ): RecordingStartResult = error("unused")

  override fun recordingScript(recordingId: String, events: List<RecordingScriptEvent>) =
    error("unused")

  override fun recordingStop(
    recordingId: String,
    timeout: kotlin.time.Duration,
  ): RecordingStopResult = error("unused")

  override fun recordingEncode(
    recordingId: String,
    format: RecordingFormat,
    timeout: kotlin.time.Duration,
  ): RecordingEncodeResult = error("unused")

  override fun onNotification(listener: NotificationListener): AutoCloseable {
    listeners.add(listener)
    return AutoCloseable { listeners.remove(listener) }
  }

  override fun close() = Unit
}
