@file:OptIn(
  androidx.compose.ui.InternalComposeUiApi::class,
  androidx.compose.ui.ExperimentalComposeUiApi::class,
)

package ee.schimke.composeai.daemon

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.scene.ComposeScenePointer
import ee.schimke.composeai.daemon.protocol.RecordingFormat
import ee.schimke.composeai.daemon.protocol.RecordingInputParams
import ee.schimke.composeai.daemon.protocol.RecordingScriptEvent
import ee.schimke.composeai.daemon.protocol.RecordingScriptEvidence
import ee.schimke.composeai.daemon.protocol.SemanticsTargetUnresolvedCode
import ee.schimke.composeai.daemon.protocol.SemanticsTargetUnresolvedReason
import ee.schimke.composeai.data.layoutinspector.SemanticsTargets
import ee.schimke.composeai.data.layoutinspector.TargetResolution
import ee.schimke.composeai.data.render.extensions.RecordingScriptDataExtensions
import ee.schimke.composeai.io.SystemFileSystem
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import okio.FileSystem
import okio.Path.Companion.toPath
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
  private val fileSystem: FileSystem = SystemFileSystem,
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
      writeFramePng(image, frameIndex, virtualTimeMs = tMs)
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
   * real Skiko `sendPointerEvent` calls. `rotaryScroll` reuses the pointer pipeline's
   * `PointerEventType.Scroll`; `keyDown` / `keyUp` route through `sendKeyEvent` with the desktop
   * key translation table (`DesktopKeyDispatch.kt`). Issue #1203 closed the no-op gap that lived
   * here pre-v3. The probe extension handler appears once, here, instead of leaking into the
   * dispatch loop's special-case branch.
   */
  private val scriptHandlers: RecordingScriptHandlerRegistry = buildScriptHandlers()

  private fun buildScriptHandlers(): RecordingScriptHandlerRegistry =
    RecordingScriptHandlerRegistry(
      buildMap {
        put(InputTouchRecordingScriptEvents.CLICK_EVENT, clickHandler())
        put(
          InputTouchRecordingScriptEvents.POINTER_DOWN_EVENT,
          pointerHandler(PointerEventType.Press),
        )
        put(
          InputTouchRecordingScriptEvents.POINTER_MOVE_EVENT,
          pointerHandler(PointerEventType.Move),
        )
        put(
          InputTouchRecordingScriptEvents.POINTER_UP_EVENT,
          pointerHandler(PointerEventType.Release),
        )
        put("input.rotaryScroll", rotaryScrollHandler())
        put(InputKeyboardRecordingScriptEvents.KEY_DOWN_EVENT, keyHandler(KeyEventType.KeyDown))
        put(InputKeyboardRecordingScriptEvents.KEY_UP_EVENT, keyHandler(KeyEventType.KeyUp))
        put(
          RecordingScriptDataExtensions.PROBE_EVENT,
          RecordingScriptEventHandler { e, _ ->
            // Snapshot the live semantics at the probe (issue #1786) so the codegen path can diff
            // consecutive probes into assertions. Reuses the same projection target resolution
            // walks, so the captured testTags/text match what a generated `onNodeWith…` finder
            // targets. Null root (nothing rendered yet) leaves the snapshot absent → TODO stub.
            val probeNodes = state.scene.composeSemanticsRoot()?.toProbeNodes()
            appliedEvidence(e, "probe marker reached", probeSemantics = probeNodes)
          },
        )
      }
    )

  /**
   * Per-pointer-id active state — keyed by [RecordingScriptEvent.pointerId] (`null` collapses to
   * `0` for backwards compatibility). Updated by [pointerHandler] on every `pointerDown` /
   * `pointerMove` / `pointerUp` so the next multi-pointer dispatch sees the full set of currently-
   * pressed fingers. Cleared per id on `pointerUp`. This is what enables real pinch-to-zoom in
   * scripted recordings — Compose's gesture pipeline needs to see both fingers simultaneously to
   * fire `Modifier.transformable {}`'s zoom handler.
   */
  private val activePointers: MutableMap<Int, Offset> = mutableMapOf()

  private fun pointerIdOrDefault(event: RecordingScriptEvent): Int = event.pointerId ?: 0

  /**
   * Resolved image-natural pixel coordinates for a pointer event, or a reason it couldn't resolve.
   */
  private sealed interface ResolvedPixels {
    data class At(val px: Int, val py: Int) : ResolvedPixels

    data class Unresolved(
      val reason: String,
      val targetUnresolvedReason: SemanticsTargetUnresolvedReason? = null,
    ) : ResolvedPixels
  }

  /**
   * Resolve where a pointer event lands: explicit [RecordingScriptEvent.pixelX]/`pixelY` win;
   * otherwise the [RecordingScriptEvent.target] semantic handle is resolved against the held
   * scene's live semantics tree (issue #1784). Unlike `interactive/input`, recording reports the
   * miss as `unsupported` script evidence — both a human-readable reason string and, for a target
   * that matched no node or more than one, a structured [SemanticsTargetUnresolvedReason] carrying
   * the candidate nodes so the agent can disambiguate without re-rendering.
   */
  private fun resolveEventPixels(event: RecordingScriptEvent): ResolvedPixels {
    val px = event.pixelX
    val py = event.pixelY
    if (px != null && py != null) return ResolvedPixels.At(px, py)
    val wireTarget = event.target
    val target =
      wireTarget?.toSemanticsTarget()
        ?: return ResolvedPixels.Unresolved(
          "${event.kind} requires pixelX/pixelY or a resolvable target"
        )
    val root =
      state.scene.composeSemanticsRoot()
        ?: return ResolvedPixels.Unresolved(
          "no semantics root available for target $target",
          semanticsTargetUnresolvedReason(
            SemanticsTargetUnresolvedCode.NO_SEMANTICS_ROOT,
            wireTarget,
            matchCount = 0,
            candidates = emptyList(),
          ),
        )
    return when (val res = SemanticsTargets.resolve(root, target)) {
      is TargetResolution.Resolved -> ResolvedPixels.At(res.point.x, res.point.y)
      TargetResolution.NotFound ->
        ResolvedPixels.Unresolved(
          "target $target matched no node",
          semanticsTargetUnresolvedReason(
            SemanticsTargetUnresolvedCode.NO_MATCH,
            wireTarget,
            matchCount = 0,
            candidates = SemanticsTargets.targetableNodes(root),
          ),
        )
      is TargetResolution.Ambiguous ->
        ResolvedPixels.Unresolved(
          "target $target matched ${res.candidates.size} nodes; use a ref to disambiguate",
          semanticsTargetUnresolvedReason(
            SemanticsTargetUnresolvedCode.AMBIGUOUS,
            wireTarget,
            matchCount = res.candidates.size,
            candidates = res.candidates,
          ),
        )
    }
  }

  private fun clickHandler(): RecordingScriptEventHandler =
    RecordingScriptEventHandler { event, ctx ->
      val (px, py) =
        when (val resolved = resolveEventPixels(event)) {
          is ResolvedPixels.At -> resolved.px to resolved.py
          is ResolvedPixels.Unresolved ->
            return@RecordingScriptEventHandler unsupportedEvidence(
              event,
              resolved.reason,
              targetUnresolvedReason = resolved.targetUnresolvedReason,
            )
        }
      val id = pointerIdOrDefault(event)
      val offset = sceneOffset(px, py)
      activePointers[id] = offset
      dispatchMultiPointer(eventType = PointerEventType.Press, timeMillis = ctx.tMs)
      state.scene.render(nanoTime = ctx.tNanos)
      activePointers.remove(id)
      dispatchMultiPointer(
        eventType = PointerEventType.Release,
        timeMillis = ctx.tMs,
        releasedPointer = id to offset,
      )
      appliedEvidence(event)
    }

  /**
   * Single-event pointer dispatch. `Press` carries the primary-button-pressed buttons mask; `Move`
   * keeps the primary button held (a drag); `Release` clears the mask. Matches the pattern
   * [DesktopInteractiveSession] uses so `Modifier.clickable {}` and other tap-gesture detectors see
   * consistent down→up sequences regardless of mode.
   *
   * Multi-pointer aware via [RecordingScriptEvent.pointerId]: each event updates [activePointers]
   * for its own id, then dispatches a single multi-pointer `sendPointerEvent` carrying every
   * currently-pressed pointer. That's what pinch-to-zoom needs — without seeing both fingers at the
   * same `tMs`, Compose's `Modifier.transformable` zoom detector treats the two fingers as
   * independent drags and never fires the zoom callback.
   */
  private fun pointerHandler(eventType: PointerEventType): RecordingScriptEventHandler =
    RecordingScriptEventHandler { event, ctx ->
      val (px, py) =
        when (val resolved = resolveEventPixels(event)) {
          is ResolvedPixels.At -> resolved.px to resolved.py
          is ResolvedPixels.Unresolved ->
            return@RecordingScriptEventHandler unsupportedEvidence(
              event,
              resolved.reason,
              targetUnresolvedReason = resolved.targetUnresolvedReason,
            )
        }
      val id = pointerIdOrDefault(event)
      val offset = sceneOffset(px, py)
      val releasedPointer: Pair<Int, Offset>?
      when (eventType) {
        PointerEventType.Press -> {
          activePointers[id] = offset
          releasedPointer = null
        }
        PointerEventType.Move -> {
          activePointers[id] = offset
          releasedPointer = null
        }
        PointerEventType.Release -> {
          // Remove BEFORE dispatch but pass the released id+position into [dispatchMultiPointer]
          // so Compose sees the up event with `pressed = false` for this pointer alongside any
          // still-active fingers. Without the explicit released entry, dropping the pointer here
          // and dispatching only remaining actives would deliver a Move-shaped event and the
          // gesture detector would never see the "finger lifted" signal.
          activePointers.remove(id)
          releasedPointer = id to offset
        }
        else -> releasedPointer = null
      }
      dispatchMultiPointer(
        eventType = eventType,
        timeMillis = ctx.tMs,
        releasedPointer = releasedPointer,
      )
      appliedEvidence(event)
    }

  /**
   * Dispatch one multi-pointer event carrying every currently-active pointer (and, optionally, a
   * just-released pointer with `pressed = false`). Skiko's `BaseComposeScene.sendPointerEvent`
   * overload that takes `List<ComposeScenePointer>` is what makes pinch-to-zoom work — Compose's
   * pointer pipeline tracks gesture id-by-id and fires `Modifier.transformable {}`'s rotation /
   * zoom / pan callbacks only when it sees ≥ 2 pointers in a single event.
   *
   * Falls back to a no-op when there are no pointers to dispatch (defensive — the handlers always
   * provide either an active set, a releasedPointer, or both).
   */
  private fun dispatchMultiPointer(
    eventType: PointerEventType,
    timeMillis: Long,
    releasedPointer: Pair<Int, Offset>? = null,
  ) {
    val pointers = buildList {
      for ((pid, off) in activePointers) {
        add(
          ComposeScenePointer(
            id = PointerId(pid.toLong()),
            position = off,
            pressed = true,
            type = PointerType.Touch,
          )
        )
      }
      if (releasedPointer != null) {
        add(
          ComposeScenePointer(
            id = PointerId(releasedPointer.first.toLong()),
            position = releasedPointer.second,
            pressed = false,
            type = PointerType.Touch,
          )
        )
      }
    }
    if (pointers.isEmpty()) return
    val anyPressed = pointers.any { it.pressed }
    state.scene.sendPointerEvent(
      eventType = eventType,
      pointers = pointers,
      buttons = PointerButtons(isPrimaryPressed = anyPressed),
      timeMillis = timeMillis,
      button = if (eventType == PointerEventType.Press) PointerButton.Primary else null,
    )
  }

  /**
   * Reuses Skiko's pointer-event pipeline for rotary scrolling — the same path a wheel input on a
   * watch-face preview would take. `scrollDeltaY` follows the browser-wheel convention (positive
   * means scrolling toward the user / page-down) which matches how the desktop interactive session
   * forwards it.
   */
  private fun rotaryScrollHandler(): RecordingScriptEventHandler =
    RecordingScriptEventHandler { event, ctx ->
      val (px, py) =
        when (val resolved = resolveEventPixels(event)) {
          is ResolvedPixels.At -> resolved.px to resolved.py
          is ResolvedPixels.Unresolved ->
            return@RecordingScriptEventHandler unsupportedEvidence(
              event,
              resolved.reason,
              targetUnresolvedReason = resolved.targetUnresolvedReason,
            )
        }
      val deltaY = event.scrollDeltaY
      if (deltaY == null) {
        return@RecordingScriptEventHandler unsupportedEvidence(
          event,
          "${event.kind} requires scrollDeltaY",
        )
      }
      state.scene.sendPointerEvent(
        eventType = PointerEventType.Scroll,
        position = sceneOffset(px, py),
        timeMillis = ctx.tMs,
        scrollDelta = Offset(0f, deltaY),
      )
      appliedEvidence(event)
    }

  /**
   * Translate the wire `keyCode` (Android `KEYCODE_*` int as a decimal string — see
   * `InteractiveKeyCodes`) to a Compose [androidx.compose.ui.input.key.Key] and dispatch via
   * `BaseComposeScene.sendKeyEvent`. Unmapped codes surface as `unsupported` script evidence so the
   * agent learns which key didn't make it through.
   */
  private fun keyHandler(type: KeyEventType): RecordingScriptEventHandler =
    RecordingScriptEventHandler { event, _ ->
      val key = androidKeycodeToComposeKey(event.keyCode)
      if (key == null) {
        return@RecordingScriptEventHandler unsupportedEvidence(
          event,
          "${event.kind} keyCode '${event.keyCode}' is not in the desktop key translation table",
        )
      }
      state.scene.sendKeyEvent(KeyEvent(key, type))
      appliedEvidence(event)
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
        // tick. Routes through the same [scriptHandlers] registry the scripted path uses — the
        // typed `RecordingInputParams` translates to a synthetic [RecordingScriptEvent] keyed by
        // `kind.wireName()`. Live mode discards the per-event evidence (only the scripted
        // [stop] result carries `scriptEvents`); the dispatch's side effects on the held scene
        // are what live mode cares about.
        val ctx = SimpleRecordingDispatchContext(tNanos = tNanos, tMs = tMs)
        while (true) {
          val next = liveInputs.poll() ?: break
          scriptHandlers.dispatch(next.toScriptEvent(tMs), ctx)
        }

        val image = state.scene.render(nanoTime = tNanos)
        writeFramePng(image, liveFrameCount, virtualTimeMs = tMs)
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
        // Frame delay in millis = 1000/fps. APNG carries delay as a numerator/denominator fraction
        // so we keep the exact rate by passing the fps as denominator and `1` as numerator.
        ApngEncoder.encodeFromPngFrames(
          frames = framePngs(r.frameCount),
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
      RecordingFormat.GIF -> {
        val target = File(encodedDir, "$recordingId.gif")
        GifEncoder.encodeFromPngFrames(frames = framePngs(r.frameCount), fps = fps, out = target)
        EncodedRecording(
          videoPath = target.absolutePath,
          mimeType = "image/gif",
          sizeBytes = target.length(),
        )
      }
      RecordingFormat.MP4 ->
        encodeViaFfmpeg(FfmpegEncoder.RecordingFormatChoice.MP4, "mp4", "video/mp4")
      RecordingFormat.WEBM ->
        encodeViaFfmpeg(FfmpegEncoder.RecordingFormatChoice.WEBM, "webm", "video/webm")
    }
  }

  /**
   * The contiguous per-frame PNGs `frame-00000.png`..`frame-NNNNN.png` the playback loop wrote, in
   * order. Shared by the [ApngEncoder] and [GifEncoder] paths (the ffmpeg path globs the directory
   * itself via the `frame-%05d.png` pattern). Each frame is asserted present so a truncated
   * recording surfaces a clear error rather than a half-encoded file.
   */
  private fun framePngs(frameCount: Int): List<File> =
    (0 until frameCount).map { i ->
      File(framesDir, "frame-${"%05d".format(i)}.png").also {
        check(it.isFile) { "DesktopRecordingSession.encode: missing frame PNG ${it.absolutePath}" }
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

  private fun writeFramePng(image: Image, frameIndex: Int) {
    writeFramePng(image, frameIndex, virtualTimeMs = 0L)
  }

  /**
   * Frame write. [virtualTimeMs] is unused today; threaded through so future per-frame data-product
   * sinks (e.g. a frame-timing trace alongside the PNG) can be added without changing call sites.
   * The touch-event visualization specifically does NOT go through a PNG post-process — it's
   * implemented as a Compose-level `AroundComposableHook` ([TouchOverlayExtension]) so the overlay
   * shares the held scene's frame clock + density, works uniformly on every backend that runs
   * Compose, and doesn't depend on Skia-only primitives. See PR description for the design.
   */
  private fun writeFramePng(
    image: Image,
    frameIndex: Int,
    @Suppress("UNUSED_PARAMETER") virtualTimeMs: Long,
  ) {
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
    fileSystem.write(outFile.path.toPath()) { write(bytes) }
  }

  private fun sceneOffset(px: Int, py: Int): androidx.compose.ui.geometry.Offset {
    val d = state.density.density
    return androidx.compose.ui.geometry.Offset(px.toFloat() / d, py.toFloat() / d)
  }
}

/**
 * Translate a typed live-mode [RecordingInputParams] into a synthetic [RecordingScriptEvent] keyed
 * by the same wire-name string the scripted path emits — so the script-event handler registry can
 * dispatch both modes through one path. `tMs` is the **frame's** virtual time (live mode stamps the
 * input at the current frame boundary, same convention scripted playback uses).
 *
 * `pointerId` is threaded through so live multi-touch (pinch / two-finger rotate) groups events by
 * finger the same way scripted mode does — without this, every live input collapsed to pointer 0
 * and Compose's gesture pipeline never saw two simultaneous fingers, so `Modifier.transformable {}`
 * zoom / rotate callbacks never fired despite the multi-pointer dispatch the same PR landed. See
 * compose-ai-tools#1360 finding #2.
 *
 * Top-level (not a member of [DesktopRecordingSession]) so the unit test can exercise the data
 * mapping without standing up an `ImageComposeScene` / `RenderEngine` per assertion.
 */
internal fun RecordingInputParams.toScriptEvent(tMs: Long): RecordingScriptEvent =
  RecordingScriptEvent(
    tMs = tMs,
    kind = kind.wireName(),
    pixelX = pixelX,
    pixelY = pixelY,
    pointerId = pointerId,
    scrollDeltaY = scrollDeltaY,
    keyCode = keyCode,
  )
