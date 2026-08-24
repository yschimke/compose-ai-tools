@file:OptIn(
  androidx.compose.ui.InternalComposeUiApi::class,
  androidx.compose.ui.ExperimentalComposeUiApi::class,
)

package ee.schimke.composeai.daemon

import androidx.compose.ui.input.pointer.PointerEventType
import ee.schimke.composeai.cli.AccessibilityNode
import ee.schimke.composeai.cli.TalkBackOverlayFrames
import ee.schimke.composeai.cli.TalkBackTraversal
import ee.schimke.composeai.daemon.protocol.RecordingFormat
import ee.schimke.composeai.daemon.protocol.RecordingInputParams
import ee.schimke.composeai.daemon.protocol.RecordingScriptEvent
import ee.schimke.composeai.daemon.protocol.RecordingScriptEvidence
import ee.schimke.composeai.daemon.protocol.SemanticsInputTarget
import ee.schimke.composeai.daemon.protocol.SemanticsTargetUnresolvedCode
import ee.schimke.composeai.daemon.protocol.SemanticsTargetUnresolvedReason
import ee.schimke.composeai.data.layoutinspector.SemanticsTarget
import ee.schimke.composeai.data.layoutinspector.SemanticsTargets
import ee.schimke.composeai.data.layoutinspector.TargetResolution
import ee.schimke.composeai.data.render.extensions.RecordingScriptDataExtensions
import ee.schimke.composeai.io.SystemFileSystem
import ee.schimke.composeai.renderer.encodePngData
import java.awt.AlphaComposite
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import javax.imageio.ImageIO
import kotlin.math.ceil
import okio.FileSystem
import okio.Path.Companion.toPath
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

  // Coordinate-free timeline captured from live inputs (the record-live bridge, issue #2047).
  // Appended by the tick thread as it dispatches each input; read by stopLive() after the join.
  // Guarded for the belt-and-suspenders case where a reader races the tick thread's last append.
  private val capturedLiveScript = mutableListOf<RecordingScriptEvent>()

  @Volatile private var stopped: Boolean = false

  @Volatile private var closed: Boolean = false

  // Live mode signal: set true by stop() to make the tick thread exit cleanly. The thread polls
  // this on every iteration; the join in stop() then guarantees no further tick after return.
  @Volatile private var liveStopRequested: Boolean = false

  private var result: RecordingResult? = null

  // The scene the preview actually composed into: the sandbox (or declared frame) plus the gutter.
  // A `@CaptureGutter` preview composes into a scene grown by the gutter (issue #4443), so
  // deriving this from `spec.widthPx` alone would leave the frame short of what was rendered.
  private val sceneWidthPx: Int = state.spec.widthPx + state.spec.captureGutterPx().horizontalPx

  private val sceneHeightPx: Int = state.spec.heightPx + state.spec.captureGutterPx().verticalPx

  /**
   * The largest content size [ComposePreviewContentBox] measured over the whole recording.
   *
   * A still measures once, so it crops to *the* intrinsic size. A recording has no such single
   * size: a component that expands mid-recording — a menu opening, a card growing into its detail
   * state, a list revealing an item — is bigger at frame 90 than at frame 0, and cropping to the
   * opening measurement would cut the expansion off exactly when it becomes the thing worth looking
   * at. So every rendered frame folds its measure pass in here and the crop is taken from the
   * maximum, once the recording has ended (issue #4467).
   *
   * The batch motion path solves the same problem by re-recording at the larger size once it
   * notices the growth (`MotionBoundsTracker`). A held session cannot: it is driven by a client in
   * real time, so replaying would re-dispatch real inputs. Deferring the crop is the equivalent
   * that works here.
   *
   * Written by whichever thread renders (the playback loop, or live mode's tick thread) and read by
   * `stop()` after that thread has finished, hence `@Volatile` rather than a lock.
   */
  @Volatile private var maxMeasuredWidthPx: Int = 0

  @Volatile private var maxMeasuredHeightPx: Int = 0

  /**
   * The smallest and largest **content-box** size measured over the recording — the range that
   * answers "did this component grow?", which decides whether the earlier frames need the backdrop
   * laid under them: a frame taken while the component was smaller is transparent everywhere it had
   * not reached, whatever the final size turns out to be. Without it the wholesale no-op skips the
   * fill exactly when growth happens to end on the scene's own bounds (issue #4467).
   *
   * Deliberately NOT [maxMeasuredWidthPx]: that one is the *crop* extent, folded with every
   * semantics owner so a popup outside the content box is not cut off. Comparing a content-box
   * minimum against a popup-inflated maximum would read a static component with a dropdown reaching
   * the scene bounds as grown, and fill the whole scene with the opaque background — so the growth
   * test compares like with like.
   *
   * [contentBoundsObserved] is the "nothing measured yet" sentinel rather than a zero minimum: a
   * wrapped layout can legitimately measure an axis to zero (a collapsed `AnimatedVisibility`, a
   * zero-sized placeholder), and dropping that leaves a later expansion looking like it was always
   * its final size.
   */
  @Volatile private var contentBoundsObserved: Boolean = false

  @Volatile private var minContentWidthPx: Int = 0

  @Volatile private var minContentHeightPx: Int = 0

  @Volatile private var maxContentWidthPx: Int = 0

  @Volatile private var maxContentHeightPx: Int = 0

  /**
   * The size the scene actually rendered at, observed rather than recomputed.
   *
   * [sceneWidthPx] is a prediction from the spec, and it can be wrong: `composePreviewSceneSize`
   * also folds in `PreviewSizeBounds`, so a preview with a `minWidthPx` larger than its sandbox
   * composes bigger than `widthPx + gutter`. Reading it off the frame keeps the fixed-axis and
   * `fillMax*` clauses honest for those previews, and lets the no-op check below compare against
   * what is genuinely on disk. Falls back to the prediction when nothing rendered at all.
   */
  @Volatile private var renderedSceneWidthPx: Int = 0

  @Volatile private var renderedSceneHeightPx: Int = 0

  /**
   * Whether this recording's framing is settled before it starts.
   *
   * With both axes fixed there is nothing to crop — the natural size IS the scene — and no
   * measurement that can move, so a frame can be scaled the moment it is taken, exactly as it was
   * before framing was deferred. Deferring those would mean holding every frame at full scene
   * resolution until `stop()`: a long `scale = 0.25` recording of a 4K preview would keep sixteen
   * times the pixel area it asked for on disk, and can run the recordings volume out of space
   * before it ever gets to be shrunk (issue #4467 review).
   *
   * Only a **wrapped** axis needs the deferral, because only it can grow mid-recording.
   */
  private val framingKnownUpFront: Boolean = !state.spec.wrapWidth && !state.spec.wrapHeight

  /**
   * A TalkBack recording is never reframed.
   *
   * The focus overlay is post-draw decoration, not part of the composition: it is drawn onto the
   * finished PNG with its announcement card against the image's bottom edge. Cropping afterwards
   * would discard the card and leave the stroke floating in a frame it was not measured against.
   * The still path avoids this by cropping *then* overlaying; a recording cannot, because the
   * overlay is per-frame live semantics and the crop is not known until the recording ends.
   *
   * So those recordings publish at the scene's size — exactly what they did before framing was
   * deferred, and the size the overlay was positioned for (issue #4467 review).
   */
  private val overlayFramedAgainstScene: Boolean = state.spec.overrides?.talkBack == true

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
    // `assert.pixels` events are evaluated in a post-loop pass: the frame they compare against is
    // the one rendered *after* this dispatch pass, so we can't diff it while dispatching. Each
    // pending entry reserves its evidence slot (placeholder below) so timeline order is preserved.
    val pendingPixels = mutableListOf<PendingPixelAssert>()
    for (frameIndex in 0 until totalFrames) {
      val tNanos: Long = frameIndex.toLong() * 1_000_000_000L / fps.toLong()
      val tMs: Long = tNanos / 1_000_000L

      while (nextEventIdx < sortedEvents.size && sortedEvents[nextEventIdx].tMs <= tMs) {
        val e = sortedEvents[nextEventIdx]
        if (e.kind == RecordingScriptDataExtensions.ASSERT_PIXELS_EVENT) {
          // Snapshot the frame *now* — at this event's position in the timeline, before any later
          // events sharing this frame bucket are dispatched — so the golden check observes the UI
          // as
          // of the assertion's position, not after a same-bucket input. Rendered at the bucket's
          // tNanos (the instant the written frame uses), so absent later same-bucket events the
          // snapshot is byte-identical to the on-disk frame a baseline is captured from. Only the
          // diff is deferred (post-loop), to keep evidence ordering; the pixels are frozen here.
          // The
          // placeholder is FAILED so a (bug) skipped finalization fails closed.
          val snapshot = frameBytes(renderRecordingFrame(tNanos), frameIndex)
          pendingPixels.add(PendingPixelAssert(evidence.size, snapshot, e))
          evidence.add(failedEvidence(e, "assert.pixels: not evaluated"))
        } else {
          val ctx = SimpleRecordingDispatchContext(tNanos = tNanos, tMs = tMs)
          evidence.add(scriptHandlers.dispatch(e, ctx))
        }
        nextEventIdx++
      }

      val image = renderRecordingFrame(tNanos)
      writeFramePng(image, frameIndex, virtualTimeMs = tMs)
    }
    // Post-loop, and in this order: frame everything to the size the whole recording settled on,
    // THEN diff. The snapshots are framed by the same pass as the on-disk frames, so a golden
    // check still compares exactly the image that landed in the output.
    val (frameWidthPx, frameHeightPx) = finalizeFrames(pendingPixels, totalFrames)
    for (p in pendingPixels) {
      evidence[p.evidenceIndex] = evaluatePixelAssert(p.event, p.snapshotPng)
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
   * A deferred `assert.pixels` event (issue #1967): [snapshotPng] is the frame frozen at the
   * event's timeline position during playback; the diff against the baseline is run after the loop
   * and its result replaces the placeholder evidence at [evidenceIndex] so the event keeps its
   * position. Not a `data class` — it holds a `ByteArray` and is only ever appended to a list (no
   * equality needed).
   */
  private class PendingPixelAssert(
    val evidenceIndex: Int,
    var snapshotPng: ByteArray,
    val event: RecordingScriptEvent,
  )

  /**
   * Golden-image assertion (issue #1967): diff the [actualPng] snapshot (frozen at the event's
   * position in [stopScripted]) against the committed baseline PNG named by the event's `inputText`
   * (resolved CLI-side against `--baseline-dir`). The pure verdict lives in [pixelAssertVerdict]
   * (reusing the `PixelDiff` comparator); this wrapper reads the baseline, turns the verdict into
   * `RecordingScriptEvidence`, and on failure writes actual/expected/diff PNGs next to the encoded
   * output so the drift is inspectable without re-running.
   */
  private fun evaluatePixelAssert(
    event: RecordingScriptEvent,
    actualPng: ByteArray,
  ): RecordingScriptEvidence {
    val baselinePath =
      event.inputText
        ?: return failedEvidence(
          event,
          "assert.pixels requires the baseline PNG path in the 'inputText' field",
        )
    val baselineBytes = File(baselinePath).takeIf { it.isFile }?.readBytes()
    return when (val verdict = pixelAssertVerdict(actualPng, baselineBytes)) {
      AssertionVerdict.Passed ->
        appliedEvidence(event, "assert.pixels matched baseline '${File(baselinePath).name}'")
      is AssertionVerdict.Failed -> {
        if (baselineBytes != null) {
          // Best-effort diagnostics — never let an artefact-write failure mask the real verdict.
          try {
            val diffDir = File(encodedDir, "pixel-diff-t${event.tMs}ms").apply { mkdirs() }
            PixelDiff.writeDiffArtefacts(actualPng, baselineBytes, diffDir)
          } catch (t: Throwable) {
            System.err.println(
              "compose-ai-daemon: assert.pixels diff-artefact write failed at t=${event.tMs}ms: " +
                "${t.javaClass.simpleName}: ${t.message}"
            )
          }
        }
        failedEvidence(event, verdict.reason)
      }
    }
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
    // Only once the tick thread has genuinely terminated. `join` returning is not that — the
    // branch above deliberately continues when it times out — and reframing under a live tick
    // would race a frame write and could leave a trailing scene-sized PNG written *after* the
    // pass, in a set whose reported dimensions say otherwise.
    //
    // A fixed-size recording is unaffected either way: its frames were framed as they were taken,
    // so `finalizeFrames` only reports the size and touches nothing.
    val tickThreadExited = thread == null || !thread.isAlive
    val (frameWidthPx, frameHeightPx) =
      if (tickThreadExited || framingKnownUpFront) {
        finalizeFrames(emptyList(), frameCount)
      } else {
        // Report what is actually on disk — the un-reframed scene size — rather than dimensions
        // the frames do not have.
        System.err.println(
          "compose-ai-daemon: DesktopRecordingSession.stop($recordingId, live): tick thread " +
            "still running, so frames are published at the scene's size without reframing"
        )
        val sceneWidth = if (renderedSceneWidthPx > 0) renderedSceneWidthPx else sceneWidthPx
        val sceneHeight = if (renderedSceneHeightPx > 0) renderedSceneHeightPx else sceneHeightPx
        sceneWidth to sceneHeight
      }
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
      capturedScript = synchronized(capturedLiveScript) { capturedLiveScript.toList() },
    )
  }

  /**
   * Per-session script-event handler registry. Built once per session so each handler closes over
   * the held [state.scene]; the dispatch loop in [stopScripted] just calls
   * [scriptHandlers.dispatch] and never branches on event kind directly.
   *
   * Built-in input kinds (`click`, `pointerDown`, `pointerMove`, `pointerUp`) are registered as
   * real Skiko `sendPointerEvent` calls. `rotaryScroll` reuses the pointer pipeline's
   * `PointerEventType.Scroll`; `keyDown` / `keyUp` route through the shared [SceneKeyDispatch],
   * which pairs the desktop key translation table (`DesktopKeyDispatch.kt`) with the
   * typed-character dispatch a `TextField` actually inserts from. Issue #1203 closed the no-op gap
   * that lived here pre-v3; issue #3545 replaced the recording lane's private copies of the key and
   * pointer dispatch with the interactive lane's, so typing and mouse-selection reach recordings
   * too. The probe extension handler appears once, here, instead of leaking into the dispatch
   * loop's special-case branch.
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
        put(InputKeyboardRecordingScriptEvents.KEY_DOWN_EVENT, keyDownHandler())
        put(InputKeyboardRecordingScriptEvents.KEY_UP_EVENT, keyUpHandler())
        put(
          RecordingScriptDataExtensions.ASSERT_VISIBLE_EVENT,
          assertVisibilityHandler(expectVisible = true),
        )
        put(
          RecordingScriptDataExtensions.ASSERT_NOT_VISIBLE_EVENT,
          assertVisibilityHandler(expectVisible = false),
        )
        put(RecordingScriptDataExtensions.ASSERT_TEXT_EQUALS_EVENT, assertTextEqualsHandler())
        put(
          RecordingScriptDataExtensions.PROBE_EVENT,
          RecordingScriptEventHandler { e, _ ->
            // Snapshot the live semantics at the probe (issue #1786) so the codegen path can diff
            // consecutive probes into assertions. Reuses the same projection target resolution
            // walks, so the captured testTags/text match what a generated `onNodeWith…` finder
            // targets. Null root (nothing rendered yet) leaves the snapshot absent → TODO stub.
            val probeNodes = engine.laidOutSemanticsRoot(state)?.toProbeNodes()
            appliedEvidence(e, "probe marker reached", probeSemantics = probeNodes)
          },
        )
      }
    )

  /**
   * Pointer translation + per-pointer-id active state, keyed by [RecordingScriptEvent.pointerId]
   * (`null` collapses to `0` for backwards compatibility). This is what enables real pinch-to-zoom
   * in recordings — Compose's gesture pipeline needs to see both fingers simultaneously to fire
   * `Modifier.transformable {}`'s zoom handler.
   *
   * The same [ScenePointerDispatch] [DesktopInteractiveSession] uses, so a recording synthesises
   * mouse / pen / touch pointers exactly like the interactive lane does (issue #3545). Every
   * dispatch passes the event's virtual `tMs` / `tNanos` explicitly, so neither wall-clock default
   * is ever used.
   */
  private val pointers: ScenePointerDispatch =
    ScenePointerDispatch(
      scene = { state.scene },
      defaultTimeMillis = { 0L },
      defaultFrameNanos = { 0L },
      settleFrame = { nanoTime -> engine.renderSettlingFrame(state, nanoTime) },
    )

  /**
   * One recording frame off the held scene, composed inside the preview's locale scope.
   *
   * Every `scene.render` in this class goes through here (issue #3721). A recording composes on its
   * own playback / live-tick thread, so a bare render is two bugs at once: it can run while a
   * *different* held session has the process-global JVM default `Locale` installed and resolve
   * `stringResource(...)` in that session's language, and — since a recording recomposes on every
   * dispatched input, not just at `setUp` — a **localized** recording would re-resolve its own
   * strings at the host default from the first input onward.
   */
  /**
   * Fold this frame's measure pass into the running maximum. Called after every render, because a
   * wrap-content component can be a different size on any frame — see [maxMeasuredWidthPx].
   */
  @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
  private fun observeMeasuredBounds(image: Image) {
    val measured = state.measuredContent
    if (measured[0] > maxMeasuredWidthPx) maxMeasuredWidthPx = measured[0]
    if (measured[1] > maxMeasuredHeightPx) maxMeasuredHeightPx = measured[1]
    // The content-box range, kept apart from the crop extent below — see [minContentWidthPx]. A
    // measured zero is a real measurement here, so the first frame seeds both ends of the range.
    if (!contentBoundsObserved || measured[0] < minContentWidthPx) minContentWidthPx = measured[0]
    if (!contentBoundsObserved || measured[1] < minContentHeightPx) minContentHeightPx = measured[1]
    if (!contentBoundsObserved || measured[0] > maxContentWidthPx) maxContentWidthPx = measured[0]
    if (!contentBoundsObserved || measured[1] > maxContentHeightPx) maxContentHeightPx = measured[1]
    contentBoundsObserved = true
    // Every semantics owner, not just the content box. A `DropdownMenu`, tooltip or dialog paints
    // into an owner of its own, outside the box entirely — so a crop taken from the box alone would
    // cut the popup off, which is a regression against recording the whole scene. The batch motion
    // path folds every root in for exactly this reason (`observeMotionRootBounds`).
    runCatching {
      for (owner in state.scene.semanticsOwners) {
        val bounds = owner.unmergedRootSemanticsNode.boundsInWindow
        val right = ceil(bounds.right.toDouble()).toInt()
        val bottom = ceil(bounds.bottom.toDouble()).toInt()
        if (right > maxMeasuredWidthPx) maxMeasuredWidthPx = right
        if (bottom > maxMeasuredHeightPx) maxMeasuredHeightPx = bottom
      }
    }
      .onFailure {
        System.err.println(
          "compose-ai-daemon: DesktopRecordingSession($recordingId): could not read semantics " +
            "owner bounds (${it.javaClass.simpleName}); popup content may be cropped"
        )
      }
    renderedSceneWidthPx = image.width
    renderedSceneHeightPx = image.height
  }

  private fun renderRecordingFrame(tNanos: Long): Image {
    // A recording runs its own clock; the engine's one-shot cursor never moves. Recording the
    // timestamp here is what lets `layOutForSemantics` re-render at *this* frame rather than
    // snapping an in-flight animation to the wall clock and back (issue #4470 review).
    state.recordFrameNanos(tNanos)
    val image =
      RenderEngine.withPreviewLocale(state.spec.localeTag) { state.scene.render(nanoTime = tNanos) }
    observeMeasuredBounds(image)
    return image
  }

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
      engine.laidOutSemanticsRoot(state)
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

  /**
   * Maestro-style visibility assertion. Resolves the event's [RecordingScriptEvent.target] against
   * the held scene's live semantics tree (the same resolver the tap path uses) and records
   * [appliedEvidence] when the condition holds or [failedEvidence] (status `FAILED`) when it
   * doesn't. A missing/empty `target` is itself a failed assertion — the script asked to check
   * something it never named. The verdict logic lives in the pure [evaluateVisibilityAssertion] so
   * it's testable without a scene; this handler only does the resolution + evidence wiring.
   */
  private fun assertVisibilityHandler(expectVisible: Boolean): RecordingScriptEventHandler =
    RecordingScriptEventHandler { event, _ ->
      val wireTarget =
        event.target
          ?: return@RecordingScriptEventHandler failedEvidence(
            event,
            "${event.kind} requires a 'target' (ref / testTag / role+text) to assert on",
          )
      val target =
        wireTarget.toSemanticsTarget()
          ?: return@RecordingScriptEventHandler failedEvidence(
            event,
            "${event.kind} target has no resolvable field; set ref, testTag, role, or text",
          )
      val root = engine.laidOutSemanticsRoot(state)
      var matchCount = 0
      var candidates: List<ComposeSemanticsNode> = emptyList()
      if (root != null) {
        when (val res = SemanticsTargets.resolve(root, target)) {
          is TargetResolution.Resolved -> matchCount = 1
          TargetResolution.NotFound -> {
            matchCount = 0
            candidates = SemanticsTargets.targetableNodes(root)
          }
          is TargetResolution.Ambiguous -> {
            matchCount = res.candidates.size
            candidates = res.candidates
          }
        }
      }
      when (
        val verdict = evaluateVisibilityAssertion(expectVisible, matchCount, target.toString())
      ) {
        AssertionVerdict.Passed -> appliedEvidence(event, "${event.kind} satisfied")
        is AssertionVerdict.Failed -> {
          // Attach the candidate list when the target was expected but absent, so the agent sees
          // what *is* on screen without re-rendering — same affordance the tap-miss path gives.
          val reason =
            if (expectVisible && root != null) {
              semanticsTargetUnresolvedReason(
                SemanticsTargetUnresolvedCode.NO_MATCH,
                wireTarget,
                matchCount = matchCount,
                candidates = candidates,
              )
            } else null
          failedEvidence(event, verdict.reason, targetUnresolvedReason = reason)
        }
      }
    }

  /**
   * `assert.textEquals` — resolve the event's [RecordingScriptEvent.target] to a single node and
   * fail unless that node's text equals the expected string carried in the event's existing
   * `inputText` field (reused rather than adding a new wire field). A missing target/expected, or a
   * target that matches no node (or more than one, so "the text" is ambiguous), is a failed
   * assertion. The string comparison itself lives in the pure [evaluateTextEqualsAssertion].
   */
  private fun assertTextEqualsHandler(): RecordingScriptEventHandler =
    RecordingScriptEventHandler { event, _ ->
      val expected =
        event.inputText
          ?: return@RecordingScriptEventHandler failedEvidence(
            event,
            "${event.kind} requires the expected text in the 'inputText' field",
          )
      val wireTarget =
        event.target
          ?: return@RecordingScriptEventHandler failedEvidence(
            event,
            "${event.kind} requires a 'target' (ref / testTag / role+text) to assert on",
          )
      val target =
        wireTarget.toSemanticsTarget()
          ?: return@RecordingScriptEventHandler failedEvidence(
            event,
            "${event.kind} target has no resolvable field; set ref, testTag, role, or text",
          )
      val root =
        engine.laidOutSemanticsRoot(state)
          ?: return@RecordingScriptEventHandler failedEvidence(
            event,
            "${event.kind}: nothing rendered yet, so $target resolved to no node",
          )
      when (val res = SemanticsTargets.resolve(root, target)) {
        is TargetResolution.Resolved -> {
          when (
            val verdict =
              evaluateTextEqualsAssertion(expected, resolvedNodeText(res.node), target.toString())
          ) {
            AssertionVerdict.Passed -> appliedEvidence(event, "${event.kind} satisfied")
            is AssertionVerdict.Failed -> failedEvidence(event, verdict.reason)
          }
        }
        TargetResolution.NotFound ->
          failedEvidence(
            event,
            "${event.kind}: $target matched no node",
            targetUnresolvedReason =
              semanticsTargetUnresolvedReason(
                SemanticsTargetUnresolvedCode.NO_MATCH,
                wireTarget,
                matchCount = 0,
                candidates = SemanticsTargets.targetableNodes(root),
              ),
          )
        is TargetResolution.Ambiguous ->
          failedEvidence(
            event,
            "${event.kind}: $target matched ${res.candidates.size} nodes; narrow it to assert text",
            targetUnresolvedReason =
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
      val type = composePointerType(event.pointerType)
      // [ScenePointerDispatch.press] renders between the press and the release, so the tap detector
      // sees the down before the up.
      pointers.press(id, offset, type, timeMillis = ctx.tMs, frameNanos = ctx.tNanos)
      pointers.release(id, offset, type, timeMillis = ctx.tMs)
      appliedEvidence(event)
    }

  /**
   * Single-event pointer dispatch. `Press` carries the primary-button-pressed buttons mask; `Move`
   * keeps the primary button held (a drag); `Release` clears the mask. Matches the pattern
   * [DesktopInteractiveSession] uses so `Modifier.clickable {}` and other tap-gesture detectors see
   * consistent down→up sequences regardless of mode.
   *
   * Multi-pointer aware via [RecordingScriptEvent.pointerId]: each event updates [pointers] for its
   * own id, then dispatches a single multi-pointer `sendPointerEvent` carrying every
   * currently-pressed pointer. That's what pinch-to-zoom needs — without seeing both fingers at the
   * same `tMs`, Compose's `Modifier.transformable` zoom detector treats the two fingers as
   * independent drags and never fires the zoom callback.
   *
   * Device-class aware via [RecordingScriptEvent.pointerType] (issue #3545): a script that means to
   * mouse-drag a text selection says `"mouse"`, and a move / release inherits whatever class its
   * press established, so a drag can't change device mid-stream. Absent ⇒ touch, so every script
   * written before the field existed replays exactly as it did.
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
      val type = composePointerType(event.pointerType)
      when (eventType) {
        PointerEventType.Press ->
          pointers.press(id, offset, type, timeMillis = ctx.tMs, frameNanos = ctx.tNanos)
        PointerEventType.Move -> pointers.move(id, offset, type, timeMillis = ctx.tMs)
        PointerEventType.Release -> pointers.release(id, offset, type, timeMillis = ctx.tMs)
        else -> Unit
      }
      appliedEvidence(event)
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
      pointers.scroll(sceneOffset(px, py), deltaY, timeMillis = ctx.tMs)
      appliedEvidence(event)
    }

  /**
   * `input.keyDown` through the shared [SceneKeyDispatch] — the same implementation
   * [DesktopInteractiveSession] uses, so a recorded keystroke behaves exactly like a live one
   * (issue #3545).
   *
   * Both halves go out: the wire `keyCode` (Android `KEYCODE_*` as a decimal string, see
   * `InteractiveKeyCodes`) as a Compose `Key`, and the `text` it typed as a `KEY_TYPED`-backed
   * event — the only half a `TextField` inserts from. Either half alone is enough, so a
   * non-US-layout character with no `KEYCODE_*` still types.
   *
   * An event carrying neither a mapped keycode nor printable text surfaces as `unsupported` script
   * evidence, so the agent learns which key didn't make it through.
   */
  private fun keyDownHandler(): RecordingScriptEventHandler =
    RecordingScriptEventHandler { event, _ ->
      if (!SceneKeyDispatch.keyDown(state.scene, event.keyCode, event.text)) {
        return@RecordingScriptEventHandler unsupportedEvidence(
          event,
          "${event.kind} has nothing to dispatch: keyCode '${event.keyCode}' is not in the " +
            "desktop key translation table and text ${event.text?.let { "'$it'" } ?: "(absent)"} " +
            "is not a single printable character",
        )
      }
      appliedEvidence(event)
    }

  /**
   * `input.keyUp` counterpart. No typed-character half — AWT emits `KEY_TYPED` only between press
   * and release — so an unmapped keycode is all there is to fail on.
   */
  private fun keyUpHandler(): RecordingScriptEventHandler =
    RecordingScriptEventHandler { event, _ ->
      if (!SceneKeyDispatch.keyUp(state.scene, event.keyCode)) {
        return@RecordingScriptEventHandler unsupportedEvidence(
          event,
          "${event.kind} keyCode '${event.keyCode}' is not in the desktop key translation table",
        )
      }
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
  /**
   * Record one dispatched live input into the coordinate-free [capturedLiveScript] (issue #2047 —
   * the record-live bridge). When the event already carries a semantic
   * [RecordingScriptEvent.target] (an agent drove the live recording by handle), keep it verbatim.
   * Otherwise, when it carries pixel coordinates, resolve them back to the stable handle of the
   * node under that point against the held scene's live semantics tree and record *that* — dropping
   * the pixels — so a panel click also becomes a coordinate-free, layout-resilient step. Falls back
   * to the raw pixel event when no targetable node is hit (canvas / custom-drawn surfaces) so the
   * timeline still reflects what happened. Non-pointer events (keys) pass through unchanged.
   *
   * [screenRoot] is the semantics tree of the screen the input landed on — projected by the caller
   * *before* dispatching, because the whole point is to name what the user aimed at rather than
   * whatever the click then put under the pointer.
   */
  private fun captureLiveEvent(event: RecordingScriptEvent, screenRoot: ComposeSemanticsNode?) {
    val px = event.pixelX
    val py = event.pixelY
    val resolved =
      if (event.target == null && px != null && py != null) {
        val handle = screenRoot?.let { SemanticsTargets.nodeAt(it, px, py) }
        if (handle != null)
          event.copy(target = handle.toInputTarget(), pixelX = null, pixelY = null)
        else event
      } else {
        event
      }
    synchronized(capturedLiveScript) { capturedLiveScript.add(resolved) }
  }

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
        // Projected *before* each dispatch, and reused for as long as it stays current. The
        // reverse map has to answer "what was under the pointer on the screen the user clicked",
        // so a click that navigates away, or a scroll that slides a different item under the
        // pointer, must not be mapped against the screen its own dispatch produced.
        //
        // Keyed on `renderGeneration` rather than projected once per tick: a dispatch can render.
        // `ScenePointerDispatch.press` settles a frame of its own, so after the first click of a
        // drain the layout really has moved and a cached projection would name a node from the
        // previous screen. A burst of pointer *moves* renders nothing, so those still share one
        // projection — which is the case worth saving, since laying out per event would put a full
        // `scene.render()` between every move and push the recorder past its frame cadence.
        var tickRoot: ComposeSemanticsNode? = null
        var tickRootGeneration = -1L
        while (true) {
          val next = liveInputs.poll() ?: break
          val scriptEvent = next.toScriptEvent(tMs)
          if (tickRoot == null || tickRootGeneration != state.renderGeneration) {
            tickRoot = engine.laidOutSemanticsRoot(state)
            tickRootGeneration = state.renderGeneration
          }
          scriptHandlers.dispatch(scriptEvent, ctx)
          captureLiveEvent(scriptEvent, tickRoot)
        }

        val image = renderRecordingFrame(tNanos)
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
    fileSystem.write(outFile.path.toPath()) { write(frameBytes(image, frameIndex)) }
  }

  /**
   * The exact PNG bytes the recording emits for [frameIndex] — the single source of truth shared by
   * the frame write ([writeFramePng]) and the `assert.pixels` snapshot ([stopScripted]), so the
   * golden check compares the same image that lands in the output. When `overrides.talkBack` is set
   * the TalkBack focus overlay is composited in (issue #1956); otherwise it's the plain scaled
   * encode.
   */
  private fun frameBytes(image: Image, frameIndex: Int): ByteArray {
    // Scaling happens here only when the framing cannot change — see [framingKnownUpFront]. A
    // wrapped preview's frames stay at scene size until `stop()` knows how big the component
    // ever got.
    val scaleNow = framingKnownUpFront && scale != 1.0f
    val scaledWidth = (image.width * scale).toInt().coerceAtLeast(1)
    val scaledHeight = (image.height * scale).toInt().coerceAtLeast(1)

    if (state.spec.overrides?.talkBack == true) {
      // The overlay is drawn onto finished PNG bytes, so this path has no `Image` left to scale
      // from and takes the raster scaler.
      val overlaid = talkBackFrameBytes(image, frameIndex)
      return if (!scaleNow) overlaid
      else scalePngBytes(overlaid, image.width, image.height, scaledWidth, scaledHeight)
    }
    if (!scaleNow) return encodeNaturalPng(image)
    // Scaled in Skia and encoded once, rather than encoded, decoded and re-encoded. A live 4K
    // recording at `scale = 0.25` would otherwise pay full-resolution PNG compression AND
    // decompression on every tick, which costs capture cadence for nothing (issue #4467).
    return encodeScaledPng(image, scaledWidth, scaledHeight)
  }

  /**
   * [image] drawn into a `width x height` raster surface and encoded once.
   *
   * `LINEAR` sampling is the right default for both up- and down-scaling: cheaper than CATMULL_ROM,
   * no aliasing for typical UI content, and what browsers do for `<img>`. The sampling mode is not
   * exposed on the wire — a caller wanting a pixel-perfect upscale passes `scale = 1.0` and
   * resamples client-side.
   */
  private fun encodeScaledPng(image: Image, width: Int, height: Int): ByteArray {
    val surface = Surface.makeRasterN32Premul(width, height)
    return try {
      surface.canvas.drawImageRect(
        image = image,
        src = Rect.makeWH(image.width.toFloat(), image.height.toFloat()),
        dst = Rect.makeWH(width.toFloat(), height.toFloat()),
        samplingMode = SamplingMode.LINEAR,
        paint = null,
        strict = true,
      )
      val snapshot = surface.makeImageSnapshot()
      try {
        snapshot.encodePngData()?.bytes ?: error("encodePngData() returned null (scaled)")
      } finally {
        snapshot.close()
      }
    } finally {
      surface.close()
    }
  }

  /**
   * The size every frame of this recording is published at, resolved once the recording has ended.
   *
   * `first`/`second` are the natural (pre-scale) pixel size. Follows the still's crop rule clause
   * for clause (`RenderEngine.cropToMeasured`), because the claim is that a preview's still and its
   * recording agree about how big the preview is — with the measurement taken as the maximum over
   * the recording rather than a single pass.
   */
  private fun resolveNaturalSize(): Pair<Int, Int> {
    val sceneWidth = if (renderedSceneWidthPx > 0) renderedSceneWidthPx else sceneWidthPx
    val sceneHeight = if (renderedSceneHeightPx > 0) renderedSceneHeightPx else sceneHeightPx
    // A TalkBack recording keeps the scene as its natural size — see [overlayFramedAgainstScene].
    // Only the CROP is given up, not the scale: returning early instead would drop `scale` on the
    // floor for a wrapped TalkBack recording, which every other combination honours.
    if (overlayFramedAgainstScene) return sceneWidth to sceneHeight
    return recordingNaturalAxisPx(state.spec.wrapWidth, maxMeasuredWidthPx, sceneWidth) to
      recordingNaturalAxisPx(state.spec.wrapHeight, maxMeasuredHeightPx, sceneHeight)
  }

  /**
   * Crop and scale every written frame — and every held `assert.pixels` snapshot — to the size
   * [resolveNaturalSize] settled on, and return that frame size in output pixels.
   *
   * This is the whole reason framing is deferred: the crop cannot be known until the last frame has
   * been measured. Doing it here rather than per frame is also what keeps `frameBytes`' invariant
   * intact — the snapshots go through the identical pass, so a golden check still compares exactly
   * the image that lands in the output. The `assert.pixels` diff was already deferred to this point
   * for evidence ordering, so the snapshots are still in hand.
   *
   * A no-op for anything already the right size, which is every fixed-size preview at `scale = 1`:
   * those frames are neither re-decoded nor rewritten.
   */
  /**
   * Every frame this recording claims to have written is still on disk, by index — not a glob of
   * [framesDir], for the reasons the rewrite loop below spells out.
   *
   * Missing is a failure, exactly as unreadable is. Skipping would let `stop()` report the original
   * frame count and the finalized dimensions over a set with a hole in it — which the encoder
   * either rejects, or (ffmpeg's numbered input) silently truncates at the gap.
   */
  private fun requireContiguousFrames(frameCount: Int) {
    for (index in 0 until frameCount) {
      val name = "frame-${"%05d".format(index)}.png"
      if (!File(framesDir, name).isFile) {
        error("recording '$recordingId': frame $name is missing at finalization")
      }
    }
  }

  private fun finalizeFrames(
    pendingPixels: List<PendingPixelAssert>,
    frameCount: Int,
  ): Pair<Int, Int> {
    val (naturalWidth, naturalHeight) = resolveNaturalSize()
    val frameWidth = (naturalWidth * scale).toInt().coerceAtLeast(1)
    val frameHeight = (naturalHeight * scale).toInt().coerceAtLeast(1)

    // Decided from the sizes alone, BEFORE touching a single file, and requiring BOTH halves to be
    // no-ops: nothing to crop (the published natural size is the whole scene) and nothing to scale
    // (the frame size is that natural size). Comparing only the final frame size against the scene
    // would collide — a 400x800 component in an 800x1600 scene at `scale = 2` publishes 800x1600,
    // the scene's own size, while still needing both a crop and a scale.
    //
    // Checking inside `framed` instead would decode every PNG just to discover it had no work,
    // which for a fixed-size recording is the entire frame set, synchronously inside `stop()`.
    val sceneWidth = if (renderedSceneWidthPx > 0) renderedSceneWidthPx else sceneWidthPx
    val sceneHeight = if (renderedSceneHeightPx > 0) renderedSceneHeightPx else sceneHeightPx
    val nothingToCrop = naturalWidth == sceneWidth && naturalHeight == sceneHeight
    val nothingToScale = frameWidth == naturalWidth && frameHeight == naturalHeight
    // "Nothing to crop, nothing to scale" is not the whole test. An opaque-background component
    // that GREW still needs the backdrop laid under its earlier frames, and growth that happens to
    // finish on the scene's own bounds satisfies both clauses while leaving exactly that work
    // undone — the case the fill was added for (issue #4467).
    val grew =
      contentBoundsObserved &&
        ((state.spec.wrapWidth && minContentWidthPx < maxContentWidthPx) ||
          (state.spec.wrapHeight && minContentHeightPx < maxContentHeightPx))
    val backdropOwed =
      grew && !overlayFramedAgainstScene && (previewBackgroundArgb(state.spec) ushr 24) == 0xFF
    // Ahead of the fast path, not inside the rewrite loop: a hole in the frame set is a failure for
    // EVERY recording, and the recordings that take the no-op return — fixed-size ones above all —
    // would otherwise have `stop()` report success and the original frame count over a set with a
    // gap in it. `isFile` per index costs a stat and no decode, so the no-decode/no-rewrite
    // optimization survives intact.
    requireContiguousFrames(frameCount)
    if (framingKnownUpFront || (nothingToCrop && nothingToScale && !backdropOwed)) {
      return frameWidth to frameHeight
    }

    fun framed(bytes: ByteArray): ByteArray =
      scalePngBytes(bytes, naturalWidth, naturalHeight, frameWidth, frameHeight)

    // This recording's own frames, by index — NOT a glob of the directory. `framesDir` is keyed by
    // `recordingId`, the counter behind it restarts at `rec-1` when the daemon does, and nothing
    // clears the directory on setup. A glob would therefore sweep up any longer previous run's
    // trailing frames: a one-frame recording could synchronously rewrite thousands of stale PNGs,
    // and one unreadable leftover would fail a perfectly good recording.
    //
    // Failures propagate, as the original frame writes do. Swallowing them would let `stop()`
    // report success and the new dimensions over a frame set that is half reframed — which the
    // encoder then turns into a corrupt mixed-size recording, or which leaves pixel-assert
    // evidence disagreeing with the frame on disk.
    for (index in 0 until frameCount) {
      val file = File(framesDir, "frame-${"%05d".format(index)}.png")
      val bytes = file.readBytes()
      val framedBytes = framed(bytes)
      if (!framedBytes.contentEquals(bytes)) {
        fileSystem.write(file.path.toPath()) { write(framedBytes) }
      }
    }
    pendingPixels.forEach { it.snapshotPng = framed(it.snapshotPng) }
    return frameWidth to frameHeight
  }

  /**
   * Encode [image] to PNG bytes at the scene's **natural** size — no crop, no scale.
   *
   * Framing is deliberately not done here. A recording's crop is only knowable once the recording
   * has ended (see [maxMeasuredWidthPx]), so every frame is written whole and [finalizeFrames]
   * crops and scales the lot at `stop()`. Doing it per frame would mean committing to the opening
   * measurement and clipping anything that grew afterwards.
   */
  private fun encodeNaturalPng(image: Image): ByteArray =
    image.encodePngData()?.bytes ?: error("encodePngData() returned null")

  /**
   * Composite the TalkBack focus overlay onto [image] and write the result to [outFile]. Extracts
   * this frame's accessibility nodes from the live semantics tree, picks the focus stop the walk
   * has reached at [frameIndex] ([TalkBackOverlayFrames]), draws the overlay in natural pixel space
   * via [DesktopTalkBackFocusOverlay], then scales to the recording's frame size if `scale != 1.0`.
   * If there are no focus stops (or anything fails) the frame is the scene unchanged.
   */
  private fun talkBackFrameBytes(image: Image, frameIndex: Int): ByteArray {
    val naturalBytes =
      image.encodePngData()?.bytes
        ?: error("encodePngData() returned null at frame $frameIndex (talkBack)")
    val nodes = extractTalkBackNodes()
    val stopCount = TalkBackTraversal.focusStops(nodes).size
    val focusedStop = TalkBackOverlayFrames.focusedStopForFrame(frameIndex, fps, stopCount)
    val overlaidNatural =
      if (stopCount > 0) {
        DesktopTalkBackFocusOverlay.overlayPngBytes(naturalBytes, nodes, focusedStop)
          ?: naturalBytes
      } else {
        naturalBytes
      }
    // Natural size, like the plain path — [finalizeFrames] frames it at `stop()`. The overlay is
    // drawn in natural pixel space, so cropping afterwards keeps it registered with the component.
    return overlaidNatural
  }

  /** This frame's accessibility nodes from the held scene's live semantics tree (pre-order). */
  private fun extractTalkBackNodes(): List<AccessibilityNode> {
    val root =
      state.scene.semanticsOwners.firstOrNull()?.unmergedRootSemanticsNode ?: return emptyList()
    return DesktopAccessibilityNodeExtractor.extractNodes(root)
  }

  /** Crop and scale PNG [bytes] — see [reframePngBytes], which this names for the recording. */
  private fun scalePngBytes(
    bytes: ByteArray,
    srcWidth: Int,
    srcHeight: Int,
    w: Int,
    h: Int,
  ): ByteArray =
    reframePngBytes(
      bytes,
      srcWidth,
      srcHeight,
      w,
      h,
      "recording '$recordingId'",
      // No backdrop for a scene-framed recording: nothing is being padded there — the whole
      // scene is kept precisely so the TalkBack caption stays where it was drawn — and filling
      // would turn its transparent sandbox opaque merely because a scale was requested.
      backdropArgb = if (overlayFramedAgainstScene) 0 else previewBackgroundArgb(state.spec),
    )

  private fun sceneOffset(px: Int, py: Int): androidx.compose.ui.geometry.Offset {
    // Recording scripts use the same image-natural pixel contract as interactive/input.
    // ImageComposeScene pointer positions are already physical pixels; density only scales dp
    // during layout, so applying it here again shifts every non-1x input toward the top-left.
    return androidx.compose.ui.geometry.Offset(px.toFloat(), py.toFloat())
  }
}

/**
 * Crop PNG [bytes] to `srcWidth x srcHeight` and scale that to `w x h` to [w]×[h] via AWT (the
 * overlay path's scaler; matches the recording size).
 */
internal fun reframePngBytes(
  bytes: ByteArray,
  srcWidth: Int,
  srcHeight: Int,
  w: Int,
  h: Int,
  label: String,
  backdropArgb: Int = 0,
): ByteArray {
  // A frame that will not decode is an error, not something to pass through. Returning the
  // original bytes would leave it un-reframed while `stop()` reports the new dimensions, handing
  // the encoder a mixed-size set — the same failure the propagating writes above exist to avoid.
  val src =
    ImageIO.read(ByteArrayInputStream(bytes))
      ?: error("$label: a captured frame is not a decodable PNG")
  // Fully opaque only. A partly-transparent background is already painted into the source by the
  // composition, so laying it underneath as well composites it twice — alpha 128 lands near 192,
  // shifting pixels across the whole component and putting the recording at odds with its still.
  // Nothing needs filling in that case anyway: whatever the crop exposes was transparent in the
  // composition too.
  val opaqueBackdrop = (backdropArgb ushr 24) == 0xFF
  // A frame that happens to need nothing keeps its exact bytes. The wholesale no-op is caught
  // by [finalizeFrames] before any decode; this is the per-frame case, where the set is being
  // reframed but this particular frame already matches.
  //
  // An opaque backdrop is never "nothing": a frame taken before the component grew is the right
  // SIZE while still being transparent everywhere the component had not reached, and that is
  // precisely what the fill below is for.
  if (
    !opaqueBackdrop &&
      src.width == w &&
      src.height == h &&
      srcWidth >= src.width &&
      srcHeight >= src.height
  ) {
    return bytes
  }
  val cropWidth = srcWidth.coerceIn(1, src.width)
  val cropHeight = srcHeight.coerceIn(1, src.height)
  // A pure crop with nothing to lay under it copies the raster instead of drawing. Java2D's
  // default `SrcOver` onto a zeroed canvas round-trips every pixel through premultiplied alpha,
  // which visibly rounds the RGB of low-alpha pixels — antialiased edges, shadows. The still
  // path's Skia crop copies them untouched, and `PixelDiff` compares RGB regardless of alpha, so
  // that rounding alone could push a recording past its cap against a still-derived baseline.
  //
  // An opaque [backdropArgb] takes the drawing path instead, and deliberately: there the source
  // really is being composited over a background, which is what the composition would have done
  // itself had it been that size.
  if (cropWidth == w && cropHeight == h && !opaqueBackdrop) {
    val cropped = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
    cropped.setRGB(0, 0, w, h, src.getRGB(0, 0, w, h, null, 0, w), 0, w)
    return ByteArrayOutputStream().use { out ->
      ImageIO.write(cropped, "png", out)
      out.toByteArray()
    }
  }
  val dst = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
  val g = dst.createGraphics()
  try {
    // The preview's own background, laid down first. A wrap-content component that GREW during
    // the recording leaves earlier frames with the background painted only inside their
    // then-smaller content box — everything the later maximum crops in around it is bare scene.
    // Without this the backdrop visibly flashes in as the component expands. The batch motion
    // collector fills the same exposed space with its `padArgb`.
    if (opaqueBackdrop) {
      g.color = java.awt.Color(backdropArgb, true)
      g.composite = AlphaComposite.Src
      g.fillRect(0, 0, w, h)
      g.composite = AlphaComposite.SrcOver
    } else {
      // `Src` rather than the default `SrcOver`, for the reason above: the destination starts
      // fully transparent, and compositing over it is what mangles translucent source pixels.
      g.composite = AlphaComposite.Src
    }
    g.setRenderingHint(
      RenderingHints.KEY_INTERPOLATION,
      RenderingHints.VALUE_INTERPOLATION_BILINEAR,
    )
    g.drawImage(src, 0, 0, w, h, 0, 0, cropWidth, cropHeight, null)
  } finally {
    g.dispose()
  }
  return ByteArrayOutputStream().use { out ->
    ImageIO.write(dst, "png", out)
    out.toByteArray()
  }
}

/**
 * One axis of a recording's natural frame size: the composable's measured extent on a **wrapped**
 * axis, the composed scene's own extent otherwise.
 *
 * Deliberately the same rule `RenderEngine.cropToMeasured` applies to a still, clause for clause,
 * because the point is that a preview's still and its recording agree about how big the preview is
 * (issue #4467). In particular a measured size that meets or exceeds the scene keeps the scene:
 * that is a `fillMax*` composable, which genuinely is the sandbox, and cropping to a measurement
 * that ran past the bound would sample off the image.
 *
 * [measuredPx] is `0` before the first layout and on an axis that isn't wrapped, which falls
 * through to [scenePx] on the same clause.
 */
internal fun recordingNaturalAxisPx(wrapped: Boolean, measuredPx: Int, scenePx: Int): Int =
  if (wrapped && measuredPx in 1 until scenePx) measuredPx else scenePx

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
 * `text` and `pointerType` are threaded for the same reason (issue #3545): everything not on the
 * script event is gone by the time [scriptHandlers] dispatches, so a viewer sending the character a
 * key typed — or saying a drag came from a mouse — used to have both silently discarded the moment
 * a recording was active. They also ride into [RecordingStopResult.capturedScript], so a captured
 * session replays as the keystrokes and the selection it actually was.
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
    target = target,
    pointerId = pointerId,
    scrollDeltaY = scrollDeltaY,
    keyCode = keyCode,
    text = text,
    pointerType = pointerType,
  )

/**
 * Project a resolved [SemanticsTarget] (from [SemanticsTargets.nodeAt]) onto the wire-level
 * [SemanticsInputTarget] recorded in the coordinate-free captured script (issue #2047).
 */
internal fun SemanticsTarget.toInputTarget(): SemanticsInputTarget =
  when (this) {
    is SemanticsTarget.Tag -> SemanticsInputTarget(testTag = testTag)
    is SemanticsTarget.RoleText -> SemanticsInputTarget(role = role, text = text)
    is SemanticsTarget.Ref -> SemanticsInputTarget(ref = ref)
  }
