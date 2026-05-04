package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.InteractiveInputKind
import ee.schimke.composeai.daemon.protocol.InteractiveInputParams
import ee.schimke.composeai.daemon.protocol.RecordingFormat
import ee.schimke.composeai.daemon.protocol.RecordingInputParams
import ee.schimke.composeai.daemon.protocol.RecordingScriptEvent
import ee.schimke.composeai.daemon.protocol.RecordingScriptEventStatus
import ee.schimke.composeai.daemon.protocol.RecordingScriptEvidence
import ee.schimke.composeai.data.render.extensions.RecordingScriptDataExtensions
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import javax.imageio.ImageIO

/**
 * Android (Robolectric) [RecordingSession]. Wraps an [AndroidInteractiveSession] (the v3 held-rule
 * loop documented in
 * [INTERACTIVE-ANDROID.md](../../../../../../docs/daemon/INTERACTIVE-ANDROID.md)) and replays a
 * scripted timeline frame-by-frame on top of it.
 *
 * **Why wrap interactive.** The held-rule loop already handles all the hard cross-classloader
 * marshalling — `MotionEvent` synthesis, `mainClock` advance, `captureRoboImage` — and runs
 * inside Robolectric's instrumented sandbox. Recording layers on top: at [stop] we walk the
 * timeline frame-by-frame, calling `interactive.dispatch` for events whose `tMs` has elapsed and
 * `interactive.render` to capture each frame. Each render's PNG is copied (and scaled if
 * `scale != 1.0`) into a per-frame path the encoder reads.
 *
 * **Slot pinning trade-off.** Android v3 supports only one held session at a time per host, so a
 * recording in progress excludes a concurrent interactive session against any preview in this
 * host. Documented constraint; we'll lift it when v4 ships multi-target Android.
 *
 * **Live mode.** Real-time focus-view recording uses a background tick thread that drains
 * `recording/input` events and calls [InteractiveSession.render] once per frame. This intentionally
 * reuses the held interactive bridge instead of introducing a separate sandbox runner; it favours a
 * narrow, functional implementation over ideal Android throughput.
 *
 * **Virtual-time fidelity.** Each captured frame passes an explicit virtual-clock delta into the
 * held-rule loop, so animation time advances according to [fps] instead of the interactive
 * backend's default render-settle window.
 */
class AndroidRecordingSession(
  override val previewId: String,
  override val recordingId: String,
  override val fps: Int,
  override val scale: Float,
  override val live: Boolean = false,
  private val interactive: InteractiveSession,
  private val framesDir: File,
  private val encodedDir: File,
) : RecordingSession {

  private val timeline = mutableListOf<RecordingScriptEvent>()

  private val liveInputs = ConcurrentLinkedQueue<RecordingInputParams>()

  @Volatile private var stopped: Boolean = false

  @Volatile private var closed: Boolean = false

  @Volatile private var liveStopRequested: Boolean = false

  @Volatile private var liveFrameCount: Int = 0

  @Volatile private var liveDurationMs: Long = 0L

  @Volatile private var liveFrameWidthPx: Int = 0

  @Volatile private var liveFrameHeightPx: Int = 0

  @Volatile private var liveFailure: Throwable? = null

  private var result: RecordingResult? = null

  private val liveTickThread: Thread? =
    if (live) {
      framesDir.mkdirs()
      Thread({ runLiveTickLoop() }, "compose-ai-daemon-android-recording-live-$recordingId")
        .apply {
          isDaemon = true
          uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, t ->
            if (liveFailure == null) liveFailure = t
            System.err.println(
              "compose-ai-daemon: AndroidRecordingSession($recordingId) tick-thread uncaught " +
                "${t.javaClass.simpleName}: ${t.message}"
            )
          }
          start()
        }
    } else null

  override fun postScript(events: List<RecordingScriptEvent>) {
    check(!live) {
      "AndroidRecordingSession.postScript called on a live recording (recordingId='$recordingId'); " +
        "use postInput for live mode"
    }
    check(!stopped) {
      "AndroidRecordingSession.postScript called after stop() for recordingId='$recordingId'"
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

  override fun postInput(input: RecordingInputParams) {
    check(live) {
      "AndroidRecordingSession.postInput called on a scripted recording " +
        "(recordingId='$recordingId'); use postScript for scripted mode"
    }
    if (stopped) return
    liveInputs.add(input)
  }

  override fun stop(): RecordingResult {
    val cached = result
    if (cached != null) return cached
    if (stopped) error("AndroidRecordingSession.stop() called twice without a cached result")
    stopped = true
    return try {
      val r = if (live) stopLive() else stopScripted()
      result = r
      r
    } finally {
      // Release the held interactive session — frees the pinned sandbox slot for the next caller.
      // close() is idempotent; the explicit close() call here mirrors DesktopRecordingSession's
      // engine.tearDown and runs even when the playback loop threw.
      try {
        interactive.close()
      } catch (t: Throwable) {
        System.err.println(
          "compose-ai-daemon: AndroidRecordingSession.stop($recordingId): interactive.close threw " +
            "${t.javaClass.simpleName}: ${t.message}; continuing"
        )
      }
    }
  }

  private fun stopScripted(): RecordingResult {
    framesDir.mkdirs()
    val sortedEvents = synchronized(timeline) { timeline.toList() }
    val durationMs: Long = sortedEvents.maxOfOrNull { it.tMs } ?: 0L
    val totalFrames = ((durationMs * fps + 999L) / 1000L).toInt() + 1

    // Probe the natural frame size from the first interactive render so the per-frame copies +
    // optional scaling know the output dimensions without hard-coding the spec. Robolectric
    // captures whatever the held composition rendered to, so this is the authoritative source.
    val startNs = System.nanoTime()
    var nextEventIdx = 0
    var lastFrameTimeMs = 0L
    var frameWidthPx = 0
    var frameHeightPx = 0
    val evidence = mutableListOf<RecordingScriptEvidence>()
    for (frameIndex in 0 until totalFrames) {
      val tMs: Long = frameIndex.toLong() * 1000L / fps.toLong()

      // Drain script events whose virtual tMs has elapsed by this frame's tMs and dispatch each
      // through the held-rule loop. Translation back through the InteractiveInputParams shape
      // because that's what AndroidInteractiveSession.dispatch consumes; the recordingId field
      // doesn't matter on the host side (the underlying session has its own streamId).
      while (nextEventIdx < sortedEvents.size && sortedEvents[nextEventIdx].tMs <= tMs) {
        val e = sortedEvents[nextEventIdx]
        val inputKind = e.kind.toInteractiveInputKindOrNull()
        if (e.kind == RecordingScriptDataExtensions.PROBE_EVENT) {
          evidence.add(e.appliedEvidence("probe marker reached"))
        } else if (inputKind == null) {
          evidence.add(e.unsupportedEvidence("script event kind '${e.kind}' is not implemented"))
        } else if (
          inputKind == InteractiveInputKind.KEY_DOWN || inputKind == InteractiveInputKind.KEY_UP
        ) {
          evidence.add(e.unsupportedEvidence("key dispatch is not implemented for Android recording"))
        } else {
          interactive.dispatch(
            InteractiveInputParams(
              frameStreamId = "android-recording-internal",
              kind = inputKind,
              pixelX = e.pixelX,
              pixelY = e.pixelY,
              scrollDeltaY = e.scrollDeltaY,
              keyCode = e.keyCode,
            )
          )
          evidence.add(e.appliedEvidence())
        }
        nextEventIdx++
      }

      val advanceTimeMs = if (frameIndex == 0) 0L else tMs - lastFrameTimeMs
      lastFrameTimeMs = tMs
      val rendered =
        interactive.render(
          requestId = RenderHost.nextRequestId(),
          advanceTimeMs = advanceTimeMs,
        )
      val srcPath =
        rendered.pngPath
          ?: error(
            "AndroidRecordingSession: interactive.render returned no pngPath at frame $frameIndex"
          )
      val dst = File(framesDir, "frame-${"%05d".format(frameIndex)}.png")
      val dims = copyAndMaybeScale(File(srcPath), dst, scale)
      if (frameWidthPx == 0) {
        frameWidthPx = dims.first
        frameHeightPx = dims.second
      }
    }
    val tookMs = (System.nanoTime() - startNs) / 1_000_000L
    System.err.println(
      "compose-ai-daemon: AndroidRecordingSession.stop($recordingId): rendered $totalFrames " +
        "frame(s) covering ${durationMs}ms scripted time in ${tookMs}ms wall " +
        "(scale=$scale, fps=$fps, ${frameWidthPx}x${frameHeightPx}px)"
    )
    return RecordingResult(
      frameCount = totalFrames,
      durationMs = durationMs,
      framesDir = framesDir.absolutePath,
      frameWidthPx = frameWidthPx,
      frameHeightPx = frameHeightPx,
      scriptEvents = evidence,
    )
  }

  private fun stopLive(): RecordingResult {
    liveStopRequested = true
    liveTickThread?.let { thread ->
      thread.join(10_000)
      if (thread.isAlive) {
        System.err.println(
          "compose-ai-daemon: AndroidRecordingSession.stop($recordingId, live): " +
            "tick thread did not exit within 10s; continuing anyway"
        )
      }
    }
    liveFailure?.let { failure ->
      throw IllegalStateException(
        "live Android recording '$recordingId' failed mid-flight: " +
          "${failure.javaClass.simpleName}: ${failure.message}",
        failure,
      )
    }
    return RecordingResult(
      frameCount = liveFrameCount,
      durationMs = liveDurationMs,
      framesDir = framesDir.absolutePath,
      frameWidthPx = liveFrameWidthPx,
      frameHeightPx = liveFrameHeightPx,
    )
  }

  private fun RecordingScriptEvent.appliedEvidence(message: String? = null): RecordingScriptEvidence =
    RecordingScriptEvidence(
      tMs = tMs,
      kind = kind,
      status = RecordingScriptEventStatus.APPLIED,
      label = label,
      checkpointId = checkpointId,
      lifecycleEvent = lifecycleEvent,
      tags = tags,
      message = message,
    )

  private fun RecordingScriptEvent.unsupportedEvidence(message: String): RecordingScriptEvidence =
    RecordingScriptEvidence(
      tMs = tMs,
      kind = kind,
      status = RecordingScriptEventStatus.UNSUPPORTED,
      label = label,
      checkpointId = checkpointId,
      lifecycleEvent = lifecycleEvent,
      tags = tags,
      message = message,
    )

  private fun runLiveTickLoop() {
    val startNs = System.nanoTime()
    val frameIntervalNs = 1_000_000_000L / fps.toLong()
    var lastFrameTimeMs = 0L
    try {
      do {
        val frameIndex = liveFrameCount
        val tNanos = System.nanoTime() - startNs
        val tMs = tNanos / 1_000_000L
        while (true) {
          val next = liveInputs.poll() ?: break
          dispatchLiveInput(next)
        }

        val advanceTimeMs = if (frameIndex == 0) 0L else (tMs - lastFrameTimeMs).coerceAtLeast(0L)
        lastFrameTimeMs = tMs
        val rendered =
          interactive.render(
            requestId = RenderHost.nextRequestId(),
            advanceTimeMs = advanceTimeMs,
          )
        val srcPath =
          rendered.pngPath
            ?: error(
              "AndroidRecordingSession: interactive.render returned no pngPath at live frame " +
                frameIndex
            )
        val dst = File(framesDir, "frame-${"%05d".format(frameIndex)}.png")
        val dims = copyAndMaybeScale(File(srcPath), dst, scale)
        if (liveFrameWidthPx == 0) {
          liveFrameWidthPx = dims.first
          liveFrameHeightPx = dims.second
        }
        liveFrameCount = frameIndex + 1
        liveDurationMs = tMs

        val nextTickNs = startNs + liveFrameCount.toLong() * frameIntervalNs
        val sleepNs = (nextTickNs - System.nanoTime()).coerceAtLeast(0L)
        if (sleepNs > 0L) {
          try {
            Thread.sleep(sleepNs / 1_000_000L, (sleepNs % 1_000_000L).toInt())
          } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return
          }
        }
      } while (!liveStopRequested)
    } catch (t: Throwable) {
      liveFailure = t
      System.err.println(
        "compose-ai-daemon: AndroidRecordingSession.runLiveTickLoop($recordingId) failed at " +
          "frame $liveFrameCount: ${t.javaClass.simpleName}: ${t.message}"
      )
    }
  }

  private fun dispatchLiveInput(input: RecordingInputParams) {
    if (input.kind == InteractiveInputKind.KEY_DOWN || input.kind == InteractiveInputKind.KEY_UP) {
      return
    }
    interactive.dispatch(
      InteractiveInputParams(
        frameStreamId = "android-recording-live",
        kind = input.kind,
        pixelX = input.pixelX,
        pixelY = input.pixelY,
        keyCode = input.keyCode,
      )
    )
  }

  override fun encode(format: RecordingFormat): EncodedRecording {
    val r =
      result
        ?: error(
          "AndroidRecordingSession.encode($format): stop() must be called before encode() " +
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
                "AndroidRecordingSession.encode: missing frame PNG ${it.absolutePath}"
              }
            }
          }
        ApngEncoder.encodeFromPngFrames(
          frames = frames,
          delayNumerator = 1,
          delayDenominator = fps.toShort(),
          loopCount = 0,
          out = target,
        )
        EncodedRecording(
          videoPath = target.absolutePath,
          mimeType = "image/apng",
          sizeBytes = target.length(),
        )
      }
      RecordingFormat.MP4 ->
        encodeViaFfmpeg(FfmpegEncoder.RecordingFormatChoice.MP4, "mp4", "video/mp4")
      RecordingFormat.WEBM ->
        encodeViaFfmpeg(FfmpegEncoder.RecordingFormatChoice.WEBM, "webm", "video/webm")
    }
  }

  private fun encodeViaFfmpeg(
    choice: FfmpegEncoder.RecordingFormatChoice,
    extension: String,
    mimeType: String,
  ): EncodedRecording {
    val target = File(encodedDir, "$recordingId.$extension")
    FfmpegEncoder.encodeFromPngFrames(
      framesDir = framesDir,
      fps = fps,
      format = choice,
      out = target,
    )
    return EncodedRecording(
      videoPath = target.absolutePath,
      mimeType = mimeType,
      sizeBytes = target.length(),
    )
  }

  override fun close() {
    if (closed) return
    closed = true
    if (!stopped) {
      if (live) {
        liveStopRequested = true
        liveTickThread?.join(10_000)
      }
      // Auto-stop / daemon-shutdown path: stop() never ran, so we still own the held interactive
      // session. Release it here. A subsequent encode() will fail with "stop() must be called
      // first" — same contract as desktop.
      try {
        interactive.close()
      } catch (t: Throwable) {
        System.err.println(
          "compose-ai-daemon: AndroidRecordingSession.close($recordingId): interactive.close threw " +
            "${t.javaClass.simpleName}: ${t.message}; continuing"
        )
      }
    }
  }

  /**
   * Copy [src] PNG to [dst]; if [scaleMul] != 1.0, decode + redraw at the scaled dimensions before
   * encoding. Returns the resulting `(widthPx, heightPx)` so [stop] can record them in the
   * [RecordingResult]. Pure javax.imageio + AWT — no Skiko dependency, so this works inside the
   * Robolectric sandbox classloader without crossing back to the host.
   *
   * `BILINEAR` interpolation matches what desktop's Skiko `SamplingMode.LINEAR` does for the same
   * up/down-scale. We deliberately don't expose interpolation as a knob — agents that want
   * pixel-perfect upscale can pass `scale = 1.0` and resample client-side.
   */
  private fun copyAndMaybeScale(src: File, dst: File, scaleMul: Float): Pair<Int, Int> {
    val img =
      ImageIO.read(src)
        ?: error("AndroidRecordingSession: failed to decode PNG ${src.absolutePath}")
    val outW = (img.width * scaleMul).toInt().coerceAtLeast(1)
    val outH = (img.height * scaleMul).toInt().coerceAtLeast(1)
    val out =
      if (scaleMul == 1.0f && outW == img.width && outH == img.height) {
        img
      } else {
        BufferedImage(outW, outH, BufferedImage.TYPE_INT_ARGB).also { scaled ->
          val g = scaled.createGraphics()
          try {
            g.setRenderingHint(
              RenderingHints.KEY_INTERPOLATION,
              RenderingHints.VALUE_INTERPOLATION_BILINEAR,
            )
            g.drawImage(img, 0, 0, outW, outH, null)
          } finally {
            g.dispose()
          }
        }
      }
    ImageIO.write(out, "png", dst)
    return outW to outH
  }
}
