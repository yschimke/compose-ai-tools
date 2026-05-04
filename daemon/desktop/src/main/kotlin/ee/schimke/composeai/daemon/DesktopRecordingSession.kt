package ee.schimke.composeai.daemon

import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import ee.schimke.composeai.daemon.protocol.InteractiveInputKind
import ee.schimke.composeai.daemon.protocol.RecordingFormat
import ee.schimke.composeai.daemon.protocol.RecordingInputParams
import ee.schimke.composeai.daemon.protocol.RecordingScriptEvent
import ee.schimke.composeai.daemon.protocol.RecordingScriptEvidence
import ee.schimke.composeai.data.render.extensions.RecordingScriptDataExtensions
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
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
  override val live: Boolean = false,
  private val engine: RenderEngine,
  private val state: RenderEngine.SceneState,
  private val sandboxStats: SandboxLifecycleStats,
  private val framesDir: File,
  private val encodedDir: File,
) : RecordingSession {

  private val timeline = mutableListOf<RecordingScriptEvent>()

  // Live mode pending-input queue. Drained by the tick thread at every frame boundary.
  // ConcurrentLinkedQueue so postInput callers (notification handler thread) and the tick
  // thread don't contend on a lock — adds and polls are wait-free.
  private val liveInputs = ConcurrentLinkedQueue<RecordingInputParams>()

  @Volatile private var stopped: Boolean = false

  @Volatile private var closed: Boolean = false

  // Live mode signal: set true by stop() to make the tick thread exit cleanly. The thread polls
  // this on every iteration; the join in stop() then guarantees no further tick after return.
  @Volatile private var liveStopRequested: Boolean = false

  private var result: RecordingResult? = null

  private val frameWidthPx: Int = (state.spec.widthPx * scale).toInt().coerceAtLeast(1)

  private val frameHeightPx: Int = (state.spec.heightPx * scale).toInt().coerceAtLeast(1)

  // Live mode bookkeeping: wall-clock anchor + frame counter. Both written only by the tick
  // thread (with frameCount also read by stop() after the join).
  @Volatile private var liveStartNs: Long = 0L

  @Volatile private var liveFrameCount: Int = 0

  // Live mode failure latch. Set by the tick loop's catch when scene.render or writeFramePng
  // throws; read by stopLive() (after the join) to propagate the underlying error to the caller.
  // Without this, a runtime exception on the background tick thread would silently truncate the
  // recording and stopLive() would still report a successful (but partial) result — the
  // asymmetry vs scripted mode that Codex flagged.
  @Volatile private var liveFailure: Throwable? = null

  private val liveTickThread: Thread? =
    if (live) {
      framesDir.mkdirs()
      Thread({ runLiveTickLoop() }, "compose-ai-daemon-recording-live-$recordingId").apply {
        isDaemon = true
        // Belt + suspenders for failure capture. The tick body's own try/catch handles failures
        // that escape `dispatchInput` / `scene.render` / `writeFramePng` synchronously. But
        // Compose's recomposer dispatches recompositions onto a coroutine that uses *this thread*
        // as its dispatcher; if the recomposition body throws, the coroutine's exception surfaces
        // via the thread's UncaughtExceptionHandler, NOT out of the next `scene.render()` call.
        // Hooking the handler latches those into [liveFailure] too so [stopLive] propagates them
        // exactly the same way.
        uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, t ->
          if (liveFailure == null) liveFailure = t
          System.err.println(
            "compose-ai-daemon: DesktopRecordingSession($recordingId) tick-thread uncaught " +
              "exception (likely Compose recomposition error): ${t.javaClass.simpleName}: " +
              "${t.message}"
          )
        }
        start()
      }
    } else null

  override fun postScript(events: List<RecordingScriptEvent>) {
    check(!live) {
      "DesktopRecordingSession.postScript called on a live recording (recordingId='$recordingId'); " +
        "use postInput for live mode"
    }
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

  override fun postInput(input: RecordingInputParams) {
    check(live) {
      "DesktopRecordingSession.postInput called on a scripted recording " +
        "(recordingId='$recordingId'); use postScript for scripted mode"
    }
    if (stopped) return // Late inputs after stop() are dropped silently.
    liveInputs.add(input)
  }

  override fun stop(): RecordingResult {
    val cached = result
    if (cached != null) return cached
    if (stopped) error("DesktopRecordingSession.stop() called twice without a cached result")
    stopped = true

    // try/finally so the held scene is always torn down — including when stopLive() rethrows a
    // captured live-tick failure, or when the scripted playback loop throws mid-render. Without
    // this wrapping a render-time exception would leak the Skia Surface for the JVM's lifetime.
    // engine.tearDown is idempotent, so the eventual close() call is still a no-op.
    return try {
      val r = if (live) stopLive() else stopScripted()
      result = r
      r
    } finally {
      engine.tearDown(state)
    }
  }

  /**
   * Scripted-mode stop: play the timeline back at virtual frame time. Identical to the v1 scripted
   * path; lifted into its own method now that [stop] dispatches by mode.
   */
  private fun stopScripted(): RecordingResult {
    framesDir.mkdirs()
    val sortedEvents = synchronized(timeline) { timeline.toList() }
    val durationMs: Long = sortedEvents.maxOfOrNull { it.tMs } ?: 0L
    // Frames are paced as `frameIndex * 1000 / fps` (integer division on the ms side keeps the
    // virtual cadence stable across long timelines; nanoTime stays exact via the 1e9/fps split
    // below). We always render frame 0 plus enough frames to drain every event in the timeline.
    // So a 67ms timeline at 30fps produces frames 0..3 (t = 0, 33, 66, 100ms), and the 67ms event
    // is dispatched before the final frame instead of silently missing both input and evidence.
    val totalFrames = ((durationMs * fps + 999L) / 1000L).toInt() + 1

    val startNs = System.nanoTime()
    var nextEventIdx = 0
    val evidence = mutableListOf<RecordingScriptEvidence>()
    for (frameIndex in 0 until totalFrames) {
      val tNanos: Long = frameIndex.toLong() * 1_000_000_000L / fps.toLong()
      val tMs: Long = tNanos / 1_000_000L

      while (nextEventIdx < sortedEvents.size && sortedEvents[nextEventIdx].tMs <= tMs) {
        val e = sortedEvents[nextEventIdx]
        val ctx = SimpleRecordingDispatchContext(tNanos = tNanos, tMs = tMs)
        evidence.add(scriptHandlers.dispatch(e, ctx))
        nextEventIdx++
      }

      val image = state.scene.render(nanoTime = tNanos)
      writeFramePng(image, frameIndex)
    }
    val tookMs = (System.nanoTime() - startNs) / 1_000_000L
    System.err.println(
      "compose-ai-daemon: DesktopRecordingSession.stop($recordingId, scripted): " +
        "rendered $totalFrames frame(s) covering ${durationMs}ms virtual time in ${tookMs}ms wall " +
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

  /**
   * Live-mode stop: signal the tick thread, join it, return metadata for what was written. Duration
   * matches the wall-clock window between construction and stop, rounded to a frame boundary. Frame
   * count is the number of ticks the loop completed before observing the signal.
   */
  private fun stopLive(): RecordingResult {
    liveStopRequested = true
    val thread = liveTickThread
    if (thread != null) {
      // Bound the join — the tick body itself is bounded by one render (typ. 5–30ms on desktop)
      // plus one frame interval. 5s is generous; if it ever times out something is wrong with the
      // tick body and we'd rather surface that than wait forever.
      thread.join(5_000)
      if (thread.isAlive) {
        System.err.println(
          "compose-ai-daemon: DesktopRecordingSession.stop($recordingId, live): " +
            "tick thread did not exit within 5s after liveStopRequested; continuing anyway"
        )
      }
    }
    // Failure propagation: if the tick loop caught an exception before exiting, surface it now
    // so JsonRpcServer.handleRecordingStop returns a wire-level error instead of a successful
    // (truncated) result. Mirrors scripted mode's contract — there, stopScripted's synchronous
    // loop throws directly out of stop().
    val failure = liveFailure
    if (failure != null) {
      throw IllegalStateException(
        "live recording '$recordingId' failed mid-flight: " +
          "${failure.javaClass.simpleName}: ${failure.message}",
        failure,
      )
    }
    val frameCount = liveFrameCount
    val durationMs: Long = if (frameCount == 0) 0L else (frameCount - 1).toLong() * 1000L / fps
    System.err.println(
      "compose-ai-daemon: DesktopRecordingSession.stop($recordingId, live): " +
        "captured $frameCount frame(s) over ~${durationMs}ms wall time " +
        "(scale=$scale, fps=$fps, ${frameWidthPx}x${frameHeightPx}px)"
    )
    return RecordingResult(
      frameCount = frameCount,
      durationMs = durationMs,
      framesDir = framesDir.absolutePath,
      frameWidthPx = frameWidthPx,
      frameHeightPx = frameHeightPx,
    )
  }

  /**
   * Per-session script-event handler registry. Built once per session so each handler closes over
   * the held [state.scene]; the dispatch loop in [stopScripted] just calls
   * [scriptHandlers.dispatch] and never branches on event kind directly.
   *
   * Built-in input kinds (`click`, `pointerDown`, `pointerMove`, `pointerUp`) are registered as
   * real Skiko `sendPointerEvent` calls. `rotaryScroll`, `keyDown`, and `keyUp` register as
   * unsupported handlers so the agent gets a specific reason rather than the generic "no handler"
   * message. The probe extension handler appears once, here, instead of leaking into the dispatch
   * loop's special-case branch.
   */
  private val scriptHandlers: RecordingScriptHandlerRegistry = buildScriptHandlers()

  private fun buildScriptHandlers(): RecordingScriptHandlerRegistry =
    RecordingScriptHandlerRegistry(
      buildMap {
        put("click", clickHandler())
        put("pointerDown", pointerHandler(PointerEventType.Press))
        put("pointerMove", pointerHandler(PointerEventType.Move))
        put("pointerUp", pointerHandler(PointerEventType.Release))
        put("rotaryScroll", desktopUnsupported("rotary scroll"))
        put("keyDown", desktopUnsupported("keyDown"))
        put("keyUp", desktopUnsupported("keyUp"))
        put(RecordingScriptDataExtensions.PROBE_EVENT, RecordingScriptEventHandler { e, _ ->
          appliedEvidence(e, "probe marker reached")
        })
      }
    )

  private fun clickHandler(): RecordingScriptEventHandler =
    RecordingScriptEventHandler { event, ctx ->
      val px = event.pixelX
      val py = event.pixelY
      if (px == null || py == null) {
        return@RecordingScriptEventHandler unsupportedEvidence(
          event,
          "${event.kind} requires both pixelX and pixelY",
        )
      }
      val offset = sceneOffset(px, py)
      state.scene.sendPointerEvent(
        eventType = PointerEventType.Press,
        position = offset,
        timeMillis = ctx.tMs,
        button = PointerButton.Primary,
        buttons = PointerButtons(isPrimaryPressed = true),
      )
      state.scene.render(nanoTime = ctx.tNanos)
      state.scene.sendPointerEvent(
        eventType = PointerEventType.Release,
        position = offset,
        timeMillis = ctx.tMs,
        button = PointerButton.Primary,
        buttons = PointerButtons(),
      )
      appliedEvidence(event)
    }

  /**
   * Single-event pointer dispatch. `Press` carries the primary-button-pressed buttons mask;
   * `Move` keeps the primary button held (a drag); `Release` clears the mask. Matches the
   * pattern [DesktopInteractiveSession] uses so `Modifier.clickable {}` and other tap-gesture
   * detectors see consistent down→up sequences regardless of mode.
   */
  private fun pointerHandler(eventType: PointerEventType): RecordingScriptEventHandler =
    RecordingScriptEventHandler { event, ctx ->
      val px = event.pixelX
      val py = event.pixelY
      if (px == null || py == null) {
        return@RecordingScriptEventHandler unsupportedEvidence(
          event,
          "${event.kind} requires both pixelX and pixelY",
        )
      }
      val pressed = eventType != PointerEventType.Release
      state.scene.sendPointerEvent(
        eventType = eventType,
        position = sceneOffset(px, py),
        timeMillis = ctx.tMs,
        button = PointerButton.Primary,
        buttons = PointerButtons(isPrimaryPressed = pressed),
      )
      appliedEvidence(event)
    }

  private fun desktopUnsupported(label: String): RecordingScriptEventHandler =
    RecordingScriptEventHandler { event, _ ->
      unsupportedEvidence(event, "$label dispatch is not implemented for desktop recording")
    }

  /**
   * Live tick loop body. Runs on the dedicated `compose-ai-daemon-recording-live-<id>` thread.
   * Anchors the virtual clock at construction time (`liveStartNs`); each iteration:
   *
   * 1. Computes virtual `tNanos` from `System.nanoTime() - liveStartNs`.
   * 2. Drains every pending input from [liveInputs] and dispatches it at `tNanos`.
   * 3. Renders one frame at the same `tNanos` and writes it as `frame-NNNNN.png`.
   * 4. Sleeps until the next frame boundary (`nextTickNs - now`), preserving fps cadence even when
   *    the render body undershoots the budget.
   *
   * **Initial-frame guarantee.** Structured as a `do { ... } while (!liveStopRequested)` so at
   * least one frame always lands on disk, even when `recording/stop` arrives so quickly that
   * `liveStopRequested` is set before the OS schedules this thread for its first iteration. Without
   * this the very-short-recording path produced 0 frames and `recording/encode` (APNG specifically)
   * would later fail with "at least one frame required" — a non-deterministic failure depending on
   * thread-scheduling timing.
   *
   * **Failure capture.** Any throwable from `dispatchInput`, `scene.render`, or `writeFramePng` is
   * caught into [liveFailure] and the loop exits. [stopLive] reads the latch after joining and
   * rethrows so the wire-side `recording/stop` returns a clean error rather than a
   * silently-truncated successful result. Without this, a render-time exception (e.g. a state
   * mutation that triggers an `error("…")` inside the composition) would terminate the tick thread
   * asynchronously and `stop()` would lie about success.
   *
   * The dispatch pattern matches scripted mode (CLICK splits into Press → render-tick → Release at
   * the same nanoTime) so `Modifier.clickable {}` and other tap-gesture-detecting modifiers see a
   * clean down→up sequence.
   */
  private fun runLiveTickLoop() {
    liveStartNs = System.nanoTime()
    val frameIntervalNs: Long = 1_000_000_000L / fps.toLong()
    try {
      do {
        val tNanos = System.nanoTime() - liveStartNs
        val tMs = tNanos / 1_000_000L

        // Drain inputs accumulated since the last tick; dispatch each at the current virtual
        // nanoTime. Inputs that arrive *during* the dispatch+render below are picked up next
        // tick.
        while (true) {
          val next = liveInputs.poll() ?: break
          dispatchInput(next.kind, next.pixelX, next.pixelY, tNanos, tMs)
        }

        val image = state.scene.render(nanoTime = tNanos)
        writeFramePng(image, liveFrameCount)
        liveFrameCount++

        // Sleep until the next frame boundary. If the render body overran (unlikely on desktop;
        // common on Android one day), `sleepFor` clamps to 0 — we just take the next frame
        // immediately rather than chasing missed frames retrospectively.
        val nextTickNs = liveStartNs + liveFrameCount.toLong() * frameIntervalNs
        val sleepNs = (nextTickNs - System.nanoTime()).coerceAtLeast(0L)
        if (sleepNs > 0) {
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
        "compose-ai-daemon: DesktopRecordingSession.runLiveTickLoop($recordingId) failed at " +
          "frame $liveFrameCount: ${t.javaClass.simpleName}: ${t.message}"
      )
    }
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
      // Auto-stop path: idle-timeout fired or daemon shutdown caught us mid-recording. For live
      // recordings we still need to signal + join the tick thread so it doesn't keep rendering
      // into a torn-down scene; the bounded join in [stopLive] handles that. For scripted, the
      // playback loop never started, so tearing the scene down directly is enough.
      if (live) {
        liveStopRequested = true
        liveTickThread?.let { thread ->
          thread.join(5_000)
          if (thread.isAlive) {
            System.err.println(
              "compose-ai-daemon: DesktopRecordingSession.close($recordingId, live): " +
                "tick thread did not exit within 5s; tearing down anyway"
            )
          }
        }
      }
      engine.tearDown(state)
    }
    // When stopped is true, stop() already called engine.tearDown(state); a second call is safe
    // (RenderEngine.tearDown is idempotent) but unnecessary.
  }

  /**
   * Common pointer-input dispatch shared by scripted ([stopScripted]) and live ([runLiveTickLoop])
   * paths. Translates protocol [InteractiveInputKind] into Skiko `sendPointerEvent` calls at the
   * supplied virtual `tNanos` / `tMs`. Same Press → render-tick → Release pattern for CLICK as
   * [DesktopInteractiveSession] uses, so `Modifier.clickable {}` and other tap-gesture detectors
   * see a clean down→up sequence regardless of mode.
   */
  private fun dispatchInput(
    kind: InteractiveInputKind,
    pixelX: Int?,
    pixelY: Int?,
    tNanos: Long,
    tMs: Long,
  ) {
    val px = pixelX
    val py = pixelY
    when (kind) {
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
      InteractiveInputKind.POINTER_MOVE -> {
        if (px == null || py == null) return
        state.scene.sendPointerEvent(
          eventType = PointerEventType.Move,
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
      InteractiveInputKind.ROTARY_SCROLL,
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
