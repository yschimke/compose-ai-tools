package ee.schimke.composeai.fakeemulator.app

import ee.schimke.composeai.daemon.protocol.StreamCodec
import ee.schimke.composeai.daemon.protocol.StreamFrameParams
import ee.schimke.composeai.fakeemulator.DeviceSettings
import ee.schimke.composeai.fakeemulator.DeviceSettingsController
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
 * 1. `launch(request)` maps the composable FQN to a preview id and opens a held stream.
 * 2. The daemon pushes `streamFrame` notifications; we decode each into an [EmulatorFrame] and
 *    publish it — so the launched preview's pixels become the emulator screen.
 * 3. When Android Studio flips a device toggle (dark theme, font size, density, rotation, …) it
 *    arrives as a [DeviceSettings] change; we re-open the held stream with the mapped
 *    [PreviewOverrides][ee.schimke.composeai.daemon.protocol.PreviewOverrides] so the preview
 *    re-renders under that override. (`stream/start` fixes overrides for the held session, so an
 *    override change is a restart — same pattern as the `serve` live lane.)
 *
 * ## Threading
 *
 * The daemon reader thread delivers both `streamFrame` notifications **and** the responses to
 * synchronous RPCs (`streamStart` / `streamStop` / `subscribeData` / …). So [onStreamFrame] (which
 * runs on that reader thread) must never block on the lock we hold while issuing those RPCs — doing
 * so would stall the reader and the RPC would sit until its timeout. We therefore keep
 * [currentStreamId] `@Volatile` and read it lock-free in [onStreamFrame], and serialise the launch
 * / settings-driven restarts under a separate [opLock] the reader never touches.
 */
class RenderSessionFrameSource(
  private val session: RenderSession,
  override val display: DisplaySize,
  private val settings: DeviceSettingsController,
  private val json: Json = Json { ignoreUnknownKeys = true },
) : FrameSource, PreviewLauncher, AutoCloseable {
  private val delegate = MutableFrameSource(display)
  private val seq = AtomicLong(0)

  /**
   * Serialises launch / settings-driven restarts + a11y subscription changes. Deliberately NOT
   * acquired by [onStreamFrame], so holding it across the synchronous stream / data RPCs can't
   * deadlock the daemon reader thread that delivers those RPCs' responses.
   */
  private val opLock = Any()

  /** Read lock-free by [onStreamFrame] on the daemon reader thread; written under [opLock]. */
  @Volatile private var currentStreamId: String? = null
  private var currentPreviewId: String? = null
  private var a11yPreviewId: String? = null

  private val frameSubscription = session.onNotification { method, params ->
    if (method == "streamFrame" && params != null) onStreamFrame(params)
  }

  // Re-render the held preview whenever Studio changes a device setting.
  private val settingsSubscription = settings.addListener { onSettingsChanged(it) }

  override fun latest(): EmulatorFrame? = delegate.latest()

  override fun subscribe(sink: (EmulatorFrame) -> Unit): AutoCloseable = delegate.subscribe(sink)

  override fun launch(request: PreviewLaunchRequest): PreviewLaunchResult {
    val previewId = request.composableFqn
    return try {
      synchronized(opLock) {
        currentPreviewId = previewId
        restartStream(previewId)
        applyA11y(settings.current, previewId)
      }
      PreviewLaunchResult.Launched
    } catch (e: Exception) {
      PreviewLaunchResult.Rejected(e.message ?: "stream start failed for $previewId")
    }
  }

  private fun onSettingsChanged(snapshot: DeviceSettings) {
    synchronized(opLock) {
      val previewId = currentPreviewId ?: return
      runCatching { restartStream(previewId) }
      applyA11y(snapshot, previewId)
    }
  }

  /**
   * Caller holds [opLock]. Stops any current held stream and opens a new one with current
   * overrides. The RPCs run under [opLock] — safe because the reader thread never contends on it.
   */
  private fun restartStream(previewId: String) {
    currentStreamId?.let { runCatching { session.streamStop(it) } }
    val overrides = settings.current.toPreviewOverrides().takeUnless { it.isEmpty() }
    currentStreamId =
      session.streamStart(previewId, codec = StreamCodec.PNG, overrides = overrides).frameStreamId
  }

  /**
   * Caller holds [opLock]. TalkBack has no direct render override; instead we subscribe the a11y
   * data product so the daemon computes accessibility findings for the shown preview while a screen
   * reader is "on" (and drop it when off). Daemon subscriptions are keyed by `(previewId, kind)`,
   * so we track *which* preview is subscribed — launching a different preview while TalkBack stays
   * on moves the subscription to the new preview (dropping the old one). Best-effort — guarded so
   * an older daemon without the kind degrades silently.
   */
  private fun applyA11y(snapshot: DeviceSettings, previewId: String) {
    val desired = if (snapshot.talkBack) previewId else null
    if (desired == a11yPreviewId) return
    a11yPreviewId?.let { runCatching { session.unsubscribeData(it, A11Y_KIND) } }
    desired?.let { runCatching { session.subscribeData(it, A11Y_KIND) } }
    a11yPreviewId = desired
  }

  private fun onStreamFrame(params: JsonObject) {
    val frame =
      runCatching { json.decodeFromJsonElement(StreamFrameParams.serializer(), params) }.getOrNull()
        ?: return
    if (frame.frameStreamId != currentStreamId)
      return // volatile read — no lock on the reader thread
    val payload = frame.payloadBase64 ?: return // unchanged-heartbeat: nothing to paint
    val bytes = runCatching { Base64.getDecoder().decode(payload) }.getOrNull() ?: return
    delegate.push(EmulatorFrame(frame.widthPx, frame.heightPx, bytes, seq.incrementAndGet()))
  }

  override fun close() {
    frameSubscription.close()
    settingsSubscription.close()
    synchronized(opLock) {
      currentStreamId?.let { runCatching { session.streamStop(it) } }
      a11yPreviewId?.let { runCatching { session.unsubscribeData(it, A11Y_KIND) } }
      a11yPreviewId = null
    }
  }

  private companion object {
    const val A11Y_KIND = "a11y/atf"
  }
}
