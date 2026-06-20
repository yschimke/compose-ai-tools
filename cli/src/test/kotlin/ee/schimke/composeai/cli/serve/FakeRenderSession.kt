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
import ee.schimke.composeai.daemon.protocol.InteractiveInputKind
import ee.schimke.composeai.daemon.protocol.InteractiveInputParams
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
import ee.schimke.composeai.daemon.protocol.StreamCodec
import ee.schimke.composeai.daemon.protocol.StreamFrameParams
import ee.schimke.composeai.daemon.protocol.StreamStartResult
import ee.schimke.composeai.render.session.NotificationListener
import ee.schimke.composeai.render.session.RenderSession
import ee.schimke.composeai.render.session.RenderSessionBackend
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
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
  /** When false (default), the streaming methods throw (mimicking a non-streaming backend). */
  private val streaming: Boolean = false,
  /**
   * `StreamStartResult.heldSession` value when [streaming]; false models a non-interactive host.
   */
  private val heldSession: Boolean = true,
  /** When set, [streamStart] emits a keyframe with this base64 payload *before* it returns. */
  private val emitKeyframeOnStart: String? = null,
) : RenderSession {
  val renderCount = AtomicInteger(0)
  private val listeners = CopyOnWriteArrayList<NotificationListener>()
  private val counter = AtomicInteger(0)

  // Streaming spies (only meaningful when streaming = true).
  val streamStarts = AtomicInteger(0)
  val interactiveInputs = CopyOnWriteArrayList<InteractiveInputParams>()
  val streamStops = CopyOnWriteArrayList<String>()
  @Volatile
  var lastFrameStreamId: String? = null
    private set

  private val streamJson = Json { ignoreUnknownKeys = true }

  /** Fire a `streamFrame` notification to registered listeners (test driver for the live lane). */
  fun emitStreamFrame(frameStreamId: String, seq: Long, payloadBase64: String?) {
    val params =
      streamJson
        .encodeToJsonElement(
          StreamFrameParams.serializer(),
          StreamFrameParams(
            frameStreamId = frameStreamId,
            seq = seq,
            ptsMillis = 0,
            widthPx = 2,
            heightPx = 2,
            codec = StreamCodec.PNG,
            payloadBase64 = payloadBase64,
          ),
        )
        .jsonObject
    listeners.forEach { it.onNotification("streamFrame", params) }
  }

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

  override fun streamStart(
    previewId: String,
    codec: StreamCodec?,
    maxFps: Int?,
    overrides: PreviewOverrides?,
    timeout: kotlin.time.Duration,
  ): StreamStartResult {
    if (!streaming) throw UnsupportedOperationException("streaming not supported")
    val fsid = "fs-${streamStarts.incrementAndGet()}"
    lastFrameStreamId = fsid
    // Model a daemon that emits the initial keyframe before the RPC response returns.
    emitKeyframeOnStart?.let { emitStreamFrame(fsid, seq = 0, payloadBase64 = it) }
    return StreamStartResult(
      frameStreamId = fsid,
      codec = StreamCodec.PNG,
      heldSession = heldSession,
    )
  }

  override fun streamStop(frameStreamId: String) {
    streamStops.add(frameStreamId)
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
    interactiveInputs.add(
      InteractiveInputParams(
        frameStreamId = frameStreamId,
        kind = kind,
        pixelX = pixelX,
        pixelY = pixelY,
        pointerId = pointerId,
        scrollDeltaY = scrollDeltaY,
        keyCode = keyCode,
      )
    )
  }

  override fun onNotification(listener: NotificationListener): AutoCloseable {
    listeners.add(listener)
    return AutoCloseable { listeners.remove(listener) }
  }

  override fun close() = Unit
}
