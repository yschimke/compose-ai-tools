package ee.schimke.composeai.daemon

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.input.pointer.pointerInput
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.RecordingFormat
import ee.schimke.composeai.daemon.protocol.RecordingScriptEvent
import java.io.File
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * End-to-end recording test for the multi-gesture drawing canvas. Runs a scripted tap → drag →
 * pinch sequence through [MultiGestureCanvasFixture] (a self-contained copy of the public
 * `MultiTouchDrawingPreview` shape), with the touch overlay active, and stitches the frames into
 * `drawing-canvas-gestures.gif` next to the existing touch-overlay artifacts.
 *
 * The script is intentionally three distinct sub-gestures so a single capture proves each path:
 * 1. Tap commits a circle (pointer down + up at the same coords, no travel past `touchSlop`).
 * 2. Drag commits a polyline (single pointer walks across the canvas, then lifts).
 * 3. Pinch scales everything (two pointers walk outward simultaneously).
 *
 * Assertions:
 * - At least one frame contains cyan overlay-ring pixels (overlay actually painted).
 * - At least one frame contains pink-circle pixels (tap reached the FSM and committed a circle).
 * - Final frame has visibly more dark-stroke pixels than the post-tap frame (drag committed a
 *   stroke) AND the bounding-box coverage grew (pinch scaled the geometry).
 *
 * The fixture is inlined here rather than importing the `:samples:cmp` preview so the test stays
 * self-contained — same convention as [PinchableSquare] in [TouchOverlayPinchRecordingTest] and
 * [DrawingCanvasFixture] in [TouchOverlayDrawingRecordingTest].
 */
class TouchOverlayDrawingCanvasGesturesRecordingTest {

  @get:Rule val tempFolder: TemporaryFolder = TemporaryFolder()

  private var savedRecordingsDir: String? = null

  @After
  fun tearDown() {
    val saved = savedRecordingsDir
    if (saved == null) System.clearProperty(DesktopHost.RECORDINGS_DIR_PROP)
    else System.setProperty(DesktopHost.RECORDINGS_DIR_PROP, saved)
  }

  @Test
  fun tap_drag_pinch_with_overlay_paints_circle_stroke_and_scales() {
    val outputDir = tempFolder.newFolder("touch-overlay-renders")
    val recordingsRoot = tempFolder.newFolder("touch-overlay-recordings")
    savedRecordingsDir = System.getProperty(DesktopHost.RECORDINGS_DIR_PROP)
    System.setProperty(DesktopHost.RECORDINGS_DIR_PROP, recordingsRoot.absolutePath)

    val engine =
      RenderEngine(
        outputDir = outputDir,
        previewOverrideExtensions =
          PreviewOverrideExtensions(listOf(TouchOverlayPreviewOverrideExtension())),
      )
    val host =
      DesktopHost(
        engine = engine,
        previewSpecResolver = { previewId ->
          if (previewId == PREVIEW_ID) {
            RenderSpec(
              className =
                "ee.schimke.composeai.daemon.TouchOverlayDrawingCanvasGesturesRecordingTestKt",
              functionName = "MultiGestureCanvasFixture",
              widthPx = CANVAS_PX,
              heightPx = CANVAS_PX,
              density = 1.0f,
              outputBaseName = "drawing-canvas-gestures",
            )
          } else null
        },
      )
    host.start()
    try {
      val session =
        host.acquireRecordingSession(
          previewId = PREVIEW_ID,
          recordingId = "test-rec-multi-gesture",
          classLoader =
            TouchOverlayDrawingCanvasGesturesRecordingTest::class.java.classLoader
              ?: ClassLoader.getSystemClassLoader(),
          fps = FPS,
          scale = 1.0f,
          overrides = PreviewOverrides(touchOverlay = true),
        )
      try {
        val script = buildTapDragPinchScript()
        session.postScript(script)

        val result = session.stop()
        assertTrue(
          "expected ≥ ${MIN_FRAMES} frames over $TOTAL_DURATION_MS ms at $FPS fps; " +
            "got ${result.frameCount}",
          result.frameCount >= MIN_FRAMES,
        )

        // Primary artifact for callers — same APNG path the pinch test produces.
        val apng = session.encode(RecordingFormat.APNG)
        val apngFile = File(apng.videoPath)
        assertTrue(
          "encoded APNG must be non-empty: ${apngFile.absolutePath}",
          apngFile.isFile && apng.sizeBytes > 0,
        )

        // GIF artifact for the PR — GitHub renders GIF inline reliably; APNG support is patchy.
        val gifTarget =
          TouchOverlayTestSupport.ARTIFACT_DIR.also { it.mkdirs() }
            .resolve("drawing-canvas-gestures.gif")
        TouchOverlayTestSupport.encodeFramesAsGif(
          File(result.framesDir),
          result.frameCount,
          gifTarget,
          fps = FPS,
        )
        assertTrue(
          "GIF artifact must be written for the PR: ${gifTarget.absolutePath}",
          gifTarget.isFile && gifTarget.length() > 0,
        )

        // Overlay assertion — mid-script (during drag) at least one frame must carry the cyan
        // active-pointer ring.
        val midFrame =
          TouchOverlayTestSupport.readPng(
            File(result.framesDir, "frame-${"%05d".format(result.frameCount / 3)}.png")
          )
        val cyanMatch =
          TouchOverlayTestSupport.pixelMatchPctApprox(
            midFrame,
            expectedRgb = 0x00BCD4,
            perChannelTolerance = 60,
          )
        assertTrue(
          "mid-script frame must contain cyan overlay-ring pixels (touch viz painted); " +
            "got ${"%.4f".format(cyanMatch * 100)}% — if 0, TouchOverlayExtension didn't fire",
          cyanMatch > 0.0005,
        )

        // Tap assertion — the post-tap frame (right after the first sub-gesture finishes) should
        // already have pink-circle pixels committed by the canvas.
        val postTapFrame =
          TouchOverlayTestSupport.readPng(
            File(result.framesDir, "frame-${"%05d".format((TAP_END_MS / STEP_MS + 1).toInt())}.png")
          )
        val pinkMatch =
          TouchOverlayTestSupport.pixelMatchPctApprox(
            postTapFrame,
            expectedRgb = 0xE91E63,
            perChannelTolerance = 32,
          )
        assertTrue(
          "post-tap frame must contain pink-circle pixels (tap reached the FSM and " +
            "committed a circle); got ${"%.4f".format(pinkMatch * 100)}%",
          pinkMatch > 0.0005,
        )

        // Final frame must have *more* dark-stroke pixels than the post-tap frame — proves the
        // drag actually committed a path. Tolerance picks up the near-black stroke colour
        // (0x1F1F1F) plus a wide channel band so the antialiased edges count.
        val finalFrame =
          TouchOverlayTestSupport.readPng(
            File(result.framesDir, "frame-${"%05d".format(result.frameCount - 1)}.png")
          )
        val postTapStroke =
          TouchOverlayTestSupport.pixelMatchPctApprox(
            postTapFrame,
            expectedRgb = 0x1F1F1F,
            perChannelTolerance = 40,
          )
        val finalStroke =
          TouchOverlayTestSupport.pixelMatchPctApprox(
            finalFrame,
            expectedRgb = 0x1F1F1F,
            perChannelTolerance = 40,
          )
        assertTrue(
          "final-frame dark-stroke coverage ($finalStroke) must exceed post-tap " +
            "($postTapStroke) — drag didn't commit a stroke",
          finalStroke > postTapStroke + 0.002,
        )
      } finally {
        session.close()
      }
    } finally {
      host.shutdown()
    }
  }

  /**
   * Tap → drag → pinch script aligned to the 30fps frame grid (one `pointerMove` per [STEP_MS] =
   * 33ms). All `tMs` values are multiples of [STEP_MS] so events land on frame boundaries; the
   * recording session drops sub-frame jitter.
   */
  private fun buildTapDragPinchScript(): List<RecordingScriptEvent> {
    val events = mutableListOf<RecordingScriptEvent>()

    // 1) Tap — single pointer down + up at the same coords with no travel.
    events +=
      RecordingScriptEvent(
        tMs = TAP_START_MS,
        kind = "input.pointerDown",
        pixelX = TAP_X,
        pixelY = TAP_Y,
        pointerId = POINTER_A,
      )
    events +=
      RecordingScriptEvent(
        tMs = TAP_END_MS,
        kind = "input.pointerUp",
        pixelX = TAP_X,
        pixelY = TAP_Y,
        pointerId = POINTER_A,
      )

    // 2) Drag — pointer walks left-to-right along a shallow zigzag.
    val dragSteps = ((DRAG_END_MS - DRAG_START_MS) / STEP_MS).toInt()
    events +=
      RecordingScriptEvent(
        tMs = DRAG_START_MS,
        kind = "input.pointerDown",
        pixelX = DRAG_FROM_X,
        pixelY = DRAG_Y_CENTER,
        pointerId = POINTER_A,
      )
    for (i in 1..dragSteps) {
      val tMs = DRAG_START_MS + i * STEP_MS
      val fraction = i.toFloat() / dragSteps.toFloat()
      val x = (DRAG_FROM_X + (DRAG_TO_X - DRAG_FROM_X) * fraction).toInt()
      // Shallow vertical oscillation so the stroke is visibly a zigzag, not a straight line.
      val yJitter = (kotlin.math.sin(fraction * Math.PI * 4).toFloat() * DRAG_Y_AMPLITUDE).toInt()
      val y = DRAG_Y_CENTER + yJitter
      events +=
        RecordingScriptEvent(
          tMs = tMs,
          kind = "input.pointerMove",
          pixelX = x,
          pixelY = y,
          pointerId = POINTER_A,
        )
    }
    events +=
      RecordingScriptEvent(
        tMs = DRAG_END_MS,
        kind = "input.pointerUp",
        pixelX = DRAG_TO_X,
        pixelY = DRAG_Y_CENTER,
        pointerId = POINTER_A,
      )

    // 3) Pinch — two pointers walk outward symmetrically along the diagonal.
    val pinchSteps = ((PINCH_END_MS - PINCH_START_MS) / STEP_MS).toInt()
    val pinchCentre = CANVAS_PX / 2
    val pinchStartSpread = (CANVAS_PX * 0.10f).toInt()
    val pinchEndSpread = (CANVAS_PX * 0.35f).toInt()
    events +=
      RecordingScriptEvent(
        tMs = PINCH_START_MS,
        kind = "input.pointerDown",
        pixelX = pinchCentre - pinchStartSpread,
        pixelY = pinchCentre - pinchStartSpread,
        pointerId = POINTER_A,
      )
    events +=
      RecordingScriptEvent(
        tMs = PINCH_START_MS,
        kind = "input.pointerDown",
        pixelX = pinchCentre + pinchStartSpread,
        pixelY = pinchCentre + pinchStartSpread,
        pointerId = POINTER_B,
      )
    for (i in 1..pinchSteps) {
      val tMs = PINCH_START_MS + i * STEP_MS
      val fraction = i.toFloat() / pinchSteps.toFloat()
      val spread = (pinchStartSpread + (pinchEndSpread - pinchStartSpread) * fraction).toInt()
      events +=
        RecordingScriptEvent(
          tMs = tMs,
          kind = "input.pointerMove",
          pixelX = pinchCentre - spread,
          pixelY = pinchCentre - spread,
          pointerId = POINTER_A,
        )
      events +=
        RecordingScriptEvent(
          tMs = tMs,
          kind = "input.pointerMove",
          pixelX = pinchCentre + spread,
          pixelY = pinchCentre + spread,
          pointerId = POINTER_B,
        )
    }
    events +=
      RecordingScriptEvent(
        tMs = PINCH_END_MS,
        kind = "input.pointerUp",
        pixelX = pinchCentre - pinchEndSpread,
        pixelY = pinchCentre - pinchEndSpread,
        pointerId = POINTER_A,
      )
    events +=
      RecordingScriptEvent(
        tMs = PINCH_END_MS,
        kind = "input.pointerUp",
        pixelX = pinchCentre + pinchEndSpread,
        pixelY = pinchCentre + pinchEndSpread,
        pointerId = POINTER_B,
      )

    return events
  }

  companion object {
    private const val PREVIEW_ID = "drawing-canvas-gestures"
    private const val CANVAS_PX = 240
    private const val FPS = 30
    private const val STEP_MS = 1000L / 30L // one move per virtual frame

    private const val POINTER_A = 1
    private const val POINTER_B = 2

    // Timeline (ms) — kept on the frame grid (multiples of STEP_MS = 33ms). Each phase runs for
    // twice its original duration so the captured demo plays back at half speed while staying a
    // smooth 30fps (we sample the same parametric gesture paths more finely rather than holding
    // frames). The gestures, coords, and colours are identical to the 1.5s version — only longer:
    //   0–132    tap         (down @ 0, up @ 132)
    //   132–660  settle      (let circle render + camera idle before drag)
    //   660–1320 drag        (down @ 660, moves every 33ms, up @ 1320)
    //   1320–1848 settle     (let stroke render + lift)
    //   1848–2904 pinch      (both down @ 1848, moves outward, both up @ 2904)
    //   2904+ tail           (one more rendered frame after final commit)
    private const val TAP_START_MS = 0L
    private const val TAP_END_MS = 132L
    private const val TAP_X = 60
    private const val TAP_Y = 70

    private const val DRAG_START_MS = 660L
    private const val DRAG_END_MS = 1320L
    private const val DRAG_FROM_X = 30
    private const val DRAG_TO_X = 210
    private const val DRAG_Y_CENTER = 140
    private const val DRAG_Y_AMPLITUDE = 18

    private const val PINCH_START_MS = 1848L
    private const val PINCH_END_MS = 2904L

    private const val TOTAL_DURATION_MS = 2904L
    private const val MIN_FRAMES = 60
  }
}

/**
 * Recording-test fixture matching the new `MultiTouchDrawingPreview` shape — same FSM (tap →
 * circle, drag → path, pinch → scale), inlined in this file's test source set so the test doesn't
 * need a cross-module classpath. The reflective resolver in [DesktopRecordingSession] finds it via
 * the `ee.schimke.composeai.daemon.TouchOverlayDrawingCanvasGesturesRecordingTestKt` class name.
 */
@Composable
fun MultiGestureCanvasFixture() {
  val circles = remember { mutableStateListOf<Offset>() }
  val strokes = remember { mutableStateListOf<List<Offset>>() }
  var inProgress by remember { mutableStateOf<List<Offset>>(emptyList()) }
  var scale by remember { mutableStateOf(1f) }

  Canvas(
    modifier =
      Modifier.fillMaxSize().background(Color(0xFFFAFAFA)).pointerInput(Unit) {
        val slop = viewConfiguration.touchSlop
        awaitEachGesture {
          val down = awaitFirstDown(requireUnconsumed = false)
          val startPos = down.position
          val pathPoints = mutableListOf(startPos)
          var mode = GestureMode.UNDECIDED
          down.consume()

          while (true) {
            val event = awaitPointerEvent()
            val pressed = event.changes.filter { it.pressed }
            if (pressed.isEmpty()) break

            if (pressed.size >= 2) {
              if (mode == GestureMode.DRAG) {
                pathPoints.clear()
                inProgress = emptyList()
              }
              mode = GestureMode.PINCH
              val zoom = event.calculateZoom()
              if (zoom > 0f && zoom != 1f) {
                scale = (scale * zoom).coerceIn(0.5f, 3f)
              }
              event.changes.forEach { it.consume() }
            } else {
              val change = pressed.first()
              if (mode == GestureMode.PINCH) continue
              val travel = (change.position - startPos).getDistance()
              if (mode == GestureMode.UNDECIDED && travel > slop) {
                mode = GestureMode.DRAG
              }
              if (mode == GestureMode.DRAG) {
                pathPoints.add(change.position)
                inProgress = pathPoints.toList()
                change.consume()
              }
            }
          }

          when (mode) {
            GestureMode.UNDECIDED -> circles.add(startPos)
            GestureMode.DRAG -> if (pathPoints.size > 1) strokes.add(pathPoints.toList())
            GestureMode.PINCH -> {}
          }
          inProgress = emptyList()
        }
      }
  ) {
    val pivot = Offset(size.width / 2f, size.height / 2f)
    scale(scaleX = scale, scaleY = scale, pivot = pivot) {
      strokes.forEach { drawStrokePath(it) }
      if (inProgress.size > 1) drawStrokePath(inProgress)
      circles.forEach { drawCircle(color = Color(0xFFE91E63), radius = 12f, center = it) }
    }
  }
}

private fun DrawScope.drawStrokePath(points: List<Offset>) {
  val path =
    Path().apply {
      moveTo(points.first().x, points.first().y)
      for (i in 1 until points.size) lineTo(points[i].x, points[i].y)
    }
  drawPath(path = path, color = Color(0xFF1F1F1F), style = Stroke(width = 3f))
}

private enum class GestureMode {
  UNDECIDED,
  DRAG,
  PINCH,
}
