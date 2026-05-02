package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.InteractiveInputKind
import ee.schimke.composeai.daemon.protocol.InteractiveInputParams
import ee.schimke.composeai.daemon.protocol.RecordingFormat
import ee.schimke.composeai.daemon.protocol.RecordingInputParams
import ee.schimke.composeai.daemon.protocol.RecordingScriptEvent
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
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
 * **Scripted-only for v1.** Live mode (real-time `recording/input` capture) is deferred — the
 * Android side's per-frame round-trip cost (each `dispatch` / `render` is a bridge sync wait) is
 * higher than desktop's, and a 30 fps live tick loop would saturate the bridge. The host throws
 * `UnsupportedOperationException` on `live = true` so the wire-side surfaces `MethodNotFound` and
 * the panel falls back. Scripted mode plays the whole timeline back at `stop()` time which is
 * insensitive to per-frame latency — the agent sees the recording as a single round-trip plus
 * the frame budget.
 *
 * **Virtual-time fidelity.** Per-frame clock advance comes from the held-rule loop's existing
 * `HELD_CAPTURE_ADVANCE_MS` (constant ~100 ms) plus `POINTER_HOLD_MS` per dispatch. That's
 * coarser than desktop's per-call `nanoTime` injection — animations may not look exactly the
 * same as desktop. v2 plumbs explicit per-frame `advanceTimeMs` through the bridge for
 * fidelity-critical recordings.
 */
class AndroidRecordingSession(
  override val previewId: String,
  override val recordingId: String,
  override val fps: Int,
  override val scale: Float,
  private val interactive: InteractiveSession,
  private val framesDir: File,
  private val encodedDir: File,
) : RecordingSession {

  override val live: Boolean = false

  private val timeline = mutableListOf<RecordingScriptEvent>()

  @Volatile private var stopped: Boolean = false

  @Volatile private var closed: Boolean = false

  private var result: RecordingResult? = null

  override fun postScript(events: List<RecordingScriptEvent>) {
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
    error(
      "AndroidRecordingSession.postInput: live mode unsupported on the Android backend in v1; " +
        "use scripted mode (postScript + stop) instead. recordingId='$recordingId'"
    )
  }

  override fun stop(): RecordingResult {
    val cached = result
    if (cached != null) return cached
    if (stopped) error("AndroidRecordingSession.stop() called twice without a cached result")
    stopped = true
    return try {
      framesDir.mkdirs()
      val sortedEvents = synchronized(timeline) { timeline.toList() }
      val durationMs: Long = sortedEvents.maxOfOrNull { it.tMs } ?: 0L
      val totalFrames = ((durationMs * fps + 999L) / 1000L).toInt() + 1

      // Probe the natural frame size from the first interactive render so the per-frame copies +
      // optional scaling know the output dimensions without hard-coding the spec. Robolectric
      // captures whatever the held composition rendered to, so this is the authoritative source.
      val startNs = System.nanoTime()
      var nextEventIdx = 0
      var frameWidthPx = 0
      var frameHeightPx = 0
      for (frameIndex in 0 until totalFrames) {
        val tMs: Long = frameIndex.toLong() * 1000L / fps.toLong()

        // Drain script events whose virtual tMs has elapsed by this frame's tMs and dispatch each
        // through the held-rule loop. Translation back through the InteractiveInputParams shape
        // because that's what AndroidInteractiveSession.dispatch consumes; the recordingId field
        // doesn't matter on the host side (the underlying session has its own streamId).
        while (nextEventIdx < sortedEvents.size && sortedEvents[nextEventIdx].tMs <= tMs) {
          val e = sortedEvents[nextEventIdx]
          if (e.kind != InteractiveInputKind.KEY_DOWN && e.kind != InteractiveInputKind.KEY_UP) {
            interactive.dispatch(
              InteractiveInputParams(
                frameStreamId = "android-recording-internal",
                kind = e.kind,
                pixelX = e.pixelX,
                pixelY = e.pixelY,
                keyCode = e.keyCode,
              )
            )
          }
          nextEventIdx++
        }

        val rendered = interactive.render(requestId = RenderHost.nextRequestId())
        val srcPath = rendered.pngPath
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
      val r =
        RecordingResult(
          frameCount = totalFrames,
          durationMs = durationMs,
          framesDir = framesDir.absolutePath,
          frameWidthPx = frameWidthPx,
          frameHeightPx = frameHeightPx,
        )
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
