package ee.schimke.composeai.daemon

import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import ee.schimke.composeai.daemon.protocol.InteractiveInputKind
import ee.schimke.composeai.daemon.protocol.RecordingFormat
import ee.schimke.composeai.daemon.protocol.RecordingScriptEvent
import java.io.File
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Surface

/**
 * Desktop concrete [RecordingSession] driving a virtual frame clock against a held
 * [androidx.compose.ui.ImageComposeScene]. See
 * [INTERACTIVE.md § 9](../../../../../../docs/daemon/INTERACTIVE.md#9-v2--click-dispatch-into-composition)
 * for the held-scene contract this builds on, and `RECORDING.md` for the v1 protocol.
 *
 * **Virtual frame clock.** Both the dispatched pointer events and `scene.render(nanoTime)` key off
 * the same monotonically-advancing virtual nanoTime — `frameIndex * 1e9 / fps`. A script of
 * `(tMs=0, click) + (tMs=500, click)` always produces 500 ms of inter-click animation in the output
 * regardless of how long the agent took to assemble the script. `LaunchedEffect`, `withFrameNanos`,
 * and `rememberInfiniteTransition` all read the scene's frame clock — so they advance with the
 * recording's virtual time, not with wall-clock.
 *
 * **CLICK dispatch.** Each `CLICK` event splits into Press → render-tick → Release at the same
 * virtual `tMs`, mirroring the [DesktopInteractiveSession] pattern. The intermediate render tick
 * doesn't write a frame to disk; only the per-frame loop's `scene.render(nanoTime)` produces an
 * output PNG. Press carries `button = PointerButton.Primary` so `Modifier.clickable {}` and other
 * primary-button-filtered modifiers fire.
 *
 * **Scale.** When `scale != 1.0` the held scene still composes at the spec's natural pixel size;
 * the captured `Image` is then drawn into a scaled raster surface before encoding. Pointer coords
 * on the wire stay in image-natural pixels — agents writing scripts never need to know the scale
 * multiplier. `scale = 1.0` short-circuits the surface allocation and encodes the held scene's
 * Image directly.
 *
 * **Threading.** Per the [RecordingSession] contract, JsonRpcServer serialises calls per-instance.
 * The playback loop in [stop] runs on whatever worker thread invoked it. Skiko isn't thread-safe;
 * the contract matches the underlying constraint.
 */
class DesktopRecordingSession(
  override val previewId: String,
  override val recordingId: String,
  override val fps: Int,
  override val scale: Float,
  private val engine: RenderEngine,
  private val state: RenderEngine.SceneState,
  private val sandboxStats: SandboxLifecycleStats,
  private val framesDir: File,
  private val encodedDir: File,
) : RecordingSession {

  private val timeline = mutableListOf<RecordingScriptEvent>()

  @Volatile private var stopped: Boolean = false

  @Volatile private var closed: Boolean = false

  private var result: RecordingResult? = null

  private val frameWidthPx: Int = (state.spec.widthPx * scale).toInt().coerceAtLeast(1)

  private val frameHeightPx: Int = (state.spec.heightPx * scale).toInt().coerceAtLeast(1)

  override fun postScript(events: List<RecordingScriptEvent>) {
    check(!stopped) {
      "DesktopRecordingSession.postScript called after stop() for recordingId='$recordingId'"
    }
    if (events.isEmpty()) return
    synchronized(timeline) {
      for (e in events) {
        require(e.tMs >= 0) { "RecordingScriptEvent.tMs must be ≥ 0; got ${e.tMs}" }
        timeline.add(e)
      }
      timeline.sortBy { it.tMs }
    }
  }

  override fun stop(): RecordingResult {
    val cached = result
    if (cached != null) return cached
    if (stopped) error("DesktopRecordingSession.stop() called twice without a cached result")
    stopped = true
    framesDir.mkdirs()

    val sortedEvents = synchronized(timeline) { timeline.toList() }
    val durationMs: Long = sortedEvents.maxOfOrNull { it.tMs } ?: 0L
    // Frames are paced as `frameIndex * 1000 / fps` (integer division on the ms side keeps the
    // virtual cadence stable across long timelines; nanoTime stays exact via the 1e9/fps split
    // below). We always render frame 0 plus one frame per `frameStep` covering the timeline up
    // to and including `durationMs`. So a 500ms timeline at 30fps produces frames 0..15 (16
    // frames in total — t = 0, 33, 66, ..., 500ms).
    val totalFrames = (durationMs * fps / 1000).toInt() + 1

    val startNs = System.nanoTime()
    var nextEventIdx = 0
    for (frameIndex in 0 until totalFrames) {
      val tNanos: Long = frameIndex.toLong() * 1_000_000_000L / fps.toLong()
      val tMs: Long = tNanos / 1_000_000L

      // Drain every scripted event whose virtual tMs has elapsed by this frame's tMs and dispatch
      // it through the held scene at the same nanoTime. CLICK splits into Press → tick → Release
      // (see class KDoc); the intermediate `scene.render(nanoTime)` makes the gesture detector
      // see the down event before the up event without writing a separate output frame.
      while (nextEventIdx < sortedEvents.size && sortedEvents[nextEventIdx].tMs <= tMs) {
        dispatchAtVirtual(sortedEvents[nextEventIdx], tNanos, tMs)
        nextEventIdx++
      }

      val image = state.scene.render(nanoTime = tNanos)
      writeFramePng(image, frameIndex)
    }
    val tookMs = (System.nanoTime() - startNs) / 1_000_000L
    System.err.println(
      "compose-ai-daemon: DesktopRecordingSession.stop($recordingId): " +
        "rendered $totalFrames frame(s) covering ${durationMs}ms virtual time in ${tookMs}ms wall " +
        "(scale=$scale, fps=$fps, ${frameWidthPx}x${frameHeightPx}px)"
    )

    val r =
      RecordingResult(
        frameCount = totalFrames,
        durationMs = durationMs,
        framesDir = framesDir.absolutePath,
        frameWidthPx = frameWidthPx,
        frameHeightPx = frameHeightPx,
      )
    result = r
    // The held scene is no longer needed; tear it down so subsequent close() is a no-op. The
    // per-frame PNGs and any encoded video stay on disk for `recording/encode`.
    engine.tearDown(state)
    return r
  }

  override fun encode(format: RecordingFormat): EncodedRecording {
    val r =
      result
        ?: error(
          "DesktopRecordingSession.encode($format): stop() must be called before encode() " +
            "(recordingId='$recordingId')"
        )
    encodedDir.mkdirs()
    return when (format) {
      RecordingFormat.APNG -> {
        val target = File(encodedDir, "$recordingId.apng")
        val frames =
          (0 until r.frameCount).map { i ->
            File(framesDir, "frame-${"%05d".format(i)}.png").also {
              check(it.isFile) {
                "DesktopRecordingSession.encode: missing frame PNG ${it.absolutePath}"
              }
            }
          }
        // Frame delay in millis = 1000/fps. APNG carries delay as a numerator/denominator fraction
        // so we keep the exact rate by passing the fps as denominator and `1` as numerator.
        ApngEncoder.encodeFromPngFrames(
          frames = frames,
          delayNumerator = 1,
          delayDenominator = fps.toShort(),
          loopCount = 0, // 0 = infinite
          out = target,
        )
        EncodedRecording(
          videoPath = target.absolutePath,
          mimeType = "image/apng",
          sizeBytes = target.length(),
        )
      }
    }
  }

  override fun close() {
    if (closed) return
    closed = true
    if (!stopped) {
      // Auto-stop path: idle-timeout fired or daemon shutdown caught us mid-recording. Tear the
      // held scene down without writing a final frame batch — partial frames already on disk stay
      // there in case `recording/encode` arrives later. Result map left null so a subsequent
      // encode call surfaces the "stop() must be called first" check.
      engine.tearDown(state)
    }
    // When stopped is true, stop() already called engine.tearDown(state); a second call is safe
    // (RenderEngine.tearDown is idempotent) but unnecessary.
  }

  private fun dispatchAtVirtual(event: RecordingScriptEvent, tNanos: Long, tMs: Long) {
    val px = event.pixelX
    val py = event.pixelY
    when (event.kind) {
      InteractiveInputKind.CLICK -> {
        if (px == null || py == null) return
        val offset = sceneOffset(px, py)
        state.scene.sendPointerEvent(
          eventType = PointerEventType.Press,
          position = offset,
          timeMillis = tMs,
          button = PointerButton.Primary,
          buttons = PointerButtons(isPrimaryPressed = true),
        )
        state.scene.render(nanoTime = tNanos)
        state.scene.sendPointerEvent(
          eventType = PointerEventType.Release,
          position = offset,
          timeMillis = tMs,
          button = PointerButton.Primary,
          buttons = PointerButtons(),
        )
      }
      InteractiveInputKind.POINTER_DOWN -> {
        if (px == null || py == null) return
        state.scene.sendPointerEvent(
          eventType = PointerEventType.Press,
          position = sceneOffset(px, py),
          timeMillis = tMs,
          button = PointerButton.Primary,
          buttons = PointerButtons(isPrimaryPressed = true),
        )
      }
      InteractiveInputKind.POINTER_UP -> {
        if (px == null || py == null) return
        state.scene.sendPointerEvent(
          eventType = PointerEventType.Release,
          position = sceneOffset(px, py),
          timeMillis = tMs,
          button = PointerButton.Primary,
          buttons = PointerButtons(),
        )
      }
      InteractiveInputKind.KEY_DOWN,
      InteractiveInputKind.KEY_UP -> {
        // Reserved for v2 key dispatch; same no-op as DesktopInteractiveSession.
      }
    }
  }

  private fun writeFramePng(image: Image, frameIndex: Int) {
    val outFile = File(framesDir, "frame-${"%05d".format(frameIndex)}.png")
    val bytes =
      if (scale == 1.0f && image.width == frameWidthPx && image.height == frameHeightPx) {
        // Fast path: no scaling needed, encode the held scene's Image directly.
        image.encodeToData(EncodedImageFormat.PNG)?.bytes
          ?: error("encodeToData(PNG) returned null at frame $frameIndex")
      } else {
        // Scaled path: draw the natural-size Image onto a `frameWidthPx × frameHeightPx` raster
        // surface and encode the snapshot. `LINEAR` sampling is the right default for both up- and
        // down-scaling: cheaper than CATMULL_ROM, no aliasing for typical UI content, matches what
        // browsers do for `<img>` rendering. We don't expose the sampling mode on the wire — if a
        // caller wants pixel-perfect upscale they can pass `scale = 1.0` and resample client-side.
        val surface = Surface.makeRasterN32Premul(frameWidthPx, frameHeightPx)
        try {
          surface.canvas.drawImageRect(
            image = image,
            src = Rect.makeWH(image.width.toFloat(), image.height.toFloat()),
            dst = Rect.makeWH(frameWidthPx.toFloat(), frameHeightPx.toFloat()),
            samplingMode = SamplingMode.LINEAR,
            paint = null,
            strict = true,
          )
          val snap = surface.makeImageSnapshot()
          try {
            snap.encodeToData(EncodedImageFormat.PNG)?.bytes
              ?: error("encodeToData(PNG) returned null at frame $frameIndex (scaled)")
          } finally {
            snap.close()
          }
        } finally {
          surface.close()
        }
      }
    outFile.writeBytes(bytes)
  }

  private fun sceneOffset(px: Int, py: Int): androidx.compose.ui.geometry.Offset {
    val d = state.density.density
    return androidx.compose.ui.geometry.Offset(px.toFloat() / d, py.toFloat() / d)
  }
}
