package ee.schimke.composeai.fakeemulator.app

import ee.schimke.composeai.daemon.protocol.StreamCodec
import ee.schimke.composeai.daemon.protocol.StreamFrameParams
import ee.schimke.composeai.fakeemulator.DisplaySize
import ee.schimke.composeai.fakeemulator.EmulatorFrame
import ee.schimke.composeai.fakeemulator.FrameSource
import ee.schimke.composeai.fakeemulator.MutableFrameSource
import ee.schimke.composeai.fakeemulator.PreviewLaunchRequest
import ee.schimke.composeai.fakeemulator.PreviewLaunchResult
import ee.schimke.composeai.fakeemulator.PreviewLauncher
import ee.schimke.composeai.render.session.RenderSession
import java.util.Base64
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Bridges a [RenderSession] (the daemon / `serve` render path) to the emulator's display. It is
 * both the [FrameSource] the ADB `screencap` + gRPC screenshot lanes read and the [PreviewLauncher]
 * the `am start … PreviewActivity` intent drives:
 *
 * 1. `launch(request)` maps the composable FQN to a preview id and calls
 *    [RenderSession.streamStart].
 * 2. The daemon then pushes `streamFrame` notifications; we decode each into an [EmulatorFrame] and
 *    publish it — so the launched preview's pixels become the emulator screen.
 *
 * This is the "wire to serve/daemon" path: no new streaming machinery, just the existing
 * held-stream frame feed re-published as a device display.
 */
class RenderSessionFrameSource(
  private val session: RenderSession,
  override val display: DisplaySize,
  private val json: Json = Json { ignoreUnknownKeys = true },
) : FrameSource, PreviewLauncher, AutoCloseable {
  private val delegate = MutableFrameSource(display)
  private val seq = AtomicLong(0)
  @Volatile private var currentStreamId: String? = null

  private val subscription = session.onNotification { method, params ->
    if (method == "streamFrame" && params != null) onStreamFrame(params)
  }

  override fun latest(): EmulatorFrame? = delegate.latest()

  override fun subscribe(sink: (EmulatorFrame) -> Unit): AutoCloseable = delegate.subscribe(sink)

  override fun launch(request: PreviewLaunchRequest): PreviewLaunchResult {
    val previewId = previewIdFor(request)
    return try {
      currentStreamId?.let { runCatching { session.streamStop(it) } }
      val result = session.streamStart(previewId, codec = StreamCodec.PNG)
      currentStreamId = result.frameStreamId
      PreviewLaunchResult.Launched
    } catch (e: Exception) {
      PreviewLaunchResult.Rejected(e.message ?: "stream start failed for $previewId")
    }
  }

  private fun onStreamFrame(params: JsonObject) {
    val frame =
      runCatching { json.decodeFromJsonElement(StreamFrameParams.serializer(), params) }.getOrNull()
        ?: return
    if (frame.frameStreamId != currentStreamId) return
    val payload = frame.payloadBase64 ?: return // unchanged-heartbeat: nothing to paint
    val bytes = runCatching { Base64.getDecoder().decode(payload) }.getOrNull() ?: return
    delegate.push(EmulatorFrame(frame.widthPx, frame.heightPx, bytes, seq.incrementAndGet()))
  }

  override fun close() {
    subscription.close()
    currentStreamId?.let { runCatching { session.streamStop(it) } }
  }

  private companion object {
    /**
     * The preview id is, by convention in this repo, the `className.functionName` FQN — which is
     * exactly what the `composable` intent extra carries. Use it directly; a richer mapping
     * (manifest lookup, parameter-provider suffixes) is future work.
     */
    fun previewIdFor(request: PreviewLaunchRequest): String = request.composableFqn
  }
}
