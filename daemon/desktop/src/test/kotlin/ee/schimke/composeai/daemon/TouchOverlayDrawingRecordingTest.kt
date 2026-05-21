@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package ee.schimke.composeai.daemon

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.RecordingFormat
import ee.schimke.composeai.daemon.protocol.RecordingScriptEvent
import java.io.File
import kotlin.math.cos
import kotlin.math.sin
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Second touch-overlay integration test — drives [DrawingCanvasFixture] (a three-finger drawing
 * canvas in the same shape as `MultiTouchDrawingPreview`) with a synchronised 3-pointer swirl and
 * asserts:
 *
 * 1. The overlay cyan rings paint over the captured frames (overlay actually fired).
 * 2. The composition itself saw all three pointers — the final frame contains pixels from at least
 *    three distinct palette colours, which can only happen if `DesktopRecordingSession` packed all
 *    three `ComposeScenePointer`s into one `sendPointerEvent` call. Without true multi-pointer
 *    dispatch the trails would be limited to whichever pointer the last single-pointer event
 *    referenced.
 *
 * The 3-pointer pattern (concentric circles at different radii, identical angular velocity) catches
 * a subtler bug than the 2-finger pinch test: a `Map` keyed by `pointerId` that only tracked the
 * most-recent pointer would still pass the pinch test (since each `pointerDown` sequence is short)
 * but would drop the third pointer here and the test would fail on the colour-count assertion.
 *
 * Output GIF lives at `build/touch-overlay-artifacts/touch-overlay-drawing.gif` next to the pinch
 * GIF — the same PR-upload step picks both up.
 */
class TouchOverlayDrawingRecordingTest {

  @get:Rule val tempFolder: TemporaryFolder = TemporaryFolder()

  private var savedRecordingsDir: String? = null

  @After
  fun tearDown() {
    val saved = savedRecordingsDir
    if (saved == null) System.clearProperty(DesktopHost.RECORDINGS_DIR_PROP)
    else System.setProperty(DesktopHost.RECORDINGS_DIR_PROP, saved)
  }

  @Test
  fun three_finger_swirl_paints_three_distinct_trails_with_overlay() {
    val outputDir = tempFolder.newFolder("touch-overlay-drawing-renders")
    val recordingsRoot = tempFolder.newFolder("touch-overlay-drawing-recordings")
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
          if (previewId == DRAWING_PREVIEW_ID) {
            RenderSpec(
              className = "ee.schimke.composeai.daemon.TouchOverlayDrawingRecordingTestKt",
              functionName = "DrawingCanvasFixture",
              widthPx = CANVAS_PX,
              heightPx = CANVAS_PX,
              density = 1.0f,
              outputBaseName = "touch-overlay-drawing",
            )
          } else null
        },
      )
    host.start()
    try {
      val session =
        host.acquireRecordingSession(
          previewId = DRAWING_PREVIEW_ID,
          recordingId = "test-rec-drawing",
          classLoader =
            TouchOverlayDrawingRecordingTest::class.java.classLoader
              ?: ClassLoader.getSystemClassLoader(),
          fps = FPS,
          scale = 1.0f,
          overrides = PreviewOverrides(touchOverlay = true),
        )
      try {
        val script = buildThreeFingerSwirlScript(durationMs = DURATION_MS)
        session.postScript(script)

        val result = session.stop()
        assertTrue(
          "expected ≥ 12 frames over $DURATION_MS ms at $FPS fps; got ${result.frameCount}",
          result.frameCount >= 12,
        )

        // APNG = the daemon's canonical artifact — confirms the encode pipeline still works.
        val apng = session.encode(RecordingFormat.APNG)
        val apngFile = File(apng.videoPath)
        assertTrue(
          "encoded APNG must be non-empty: ${apngFile.absolutePath}",
          apngFile.isFile && apng.sizeBytes > 0,
        )

        // GIF artifact for the PR comment — same wiring as the pinch test.
        val gifTarget =
          TouchOverlayTestSupport.ARTIFACT_DIR.also { it.mkdirs() }
            .resolve("touch-overlay-drawing.gif")
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

        // 1. Overlay-painted assertion — mid-swirl frame must carry cyan ring pixels.
        val midFrame =
          TouchOverlayTestSupport.readPng(
            File(result.framesDir, "frame-${"%05d".format(result.frameCount / 2)}.png")
          )
        val cyanMatch =
          TouchOverlayTestSupport.pixelMatchPctApprox(
            midFrame,
            expectedRgb = 0x00BCD4,
            perChannelTolerance = 60,
          )
        assertTrue(
          "mid-swirl frame must contain cyan overlay-ring pixels (touch viz painted); " +
            "got ${"%.4f".format(cyanMatch * 100)}% — if 0, the TouchOverlayExtension didn't fire",
          cyanMatch > 0.0005,
        )

        // 2. Multi-pointer reach assertion — final frame must contain pixels from ≥ 3 of the
        //    fixture's 4-colour palette. Trails accumulate across the recording, so by the last
        //    frame every active pointer has painted dozens of dots and each colour has clearly
        //    distinct coverage. If multi-pointer dispatch were broken, only the most-recently
        //    pressed pointer's dots would survive.
        val lastFrame =
          TouchOverlayTestSupport.readPng(
            File(result.framesDir, "frame-${"%05d".format(result.frameCount - 1)}.png")
          )
        val coverages = STROKE_PALETTE.map { rgb ->
          rgb to
            TouchOverlayTestSupport.pixelMatchPctApprox(
              lastFrame,
              expectedRgb = rgb,
              perChannelTolerance = 24,
            )
        }
        val present = coverages.count { (_, pct) -> pct > 0.0005 }
        assertTrue(
          "final frame must contain pixels from ≥ 3 distinct palette colours " +
            "(multi-pointer dispatch reached the composition); got $present — " +
            "per-colour coverages: ${coverages.joinToString { "0x${"%06X".format(it.first)}=${"%.4f%%".format(it.second * 100)}" }}",
          present >= 3,
        )
      } finally {
        session.close()
      }
    } finally {
      host.shutdown()
    }
  }

  /**
   * Build a multi-pointer swirl timeline: three fingers trace concentric circles around the canvas
   * centre. All three share the same angular velocity but start at offset angles (0°, 120°, 240°)
   * and use different radii (40 / 60 / 80 px on a 240×240 canvas). Each step emits one
   * `pointerMove` per finger at the same `tMs` so the recording session's multi-pointer dispatch
   * packs all three into a single `sendPointerEvent`.
   */
  private fun buildThreeFingerSwirlScript(durationMs: Long): List<RecordingScriptEvent> {
    val centre = CANVAS_PX / 2
    val radii = intArrayOf(40, 60, 80)
    val phaseDeg = doubleArrayOf(0.0, 120.0, 240.0)
    val pointerIds = intArrayOf(POINTER_A, POINTER_B, POINTER_C)
    val steps = (durationMs / STEP_MS).toInt().coerceAtLeast(1)
    // One full revolution over the recording, so each finger draws a complete coloured ring.
    val totalRotations = 1.0

    fun positionAt(pointerIx: Int, tMs: Long): Pair<Int, Int> {
      val phase = Math.toRadians(phaseDeg[pointerIx])
      val angle = phase + 2 * Math.PI * totalRotations * (tMs.toDouble() / durationMs)
      val r = radii[pointerIx]
      val x = (centre + r * cos(angle)).toInt()
      val y = (centre + r * sin(angle)).toInt()
      return x to y
    }

    return buildList {
      // Initial pointerDown for all three at tMs = 0.
      for (i in 0..2) {
        val (x, y) = positionAt(i, 0L)
        add(
          RecordingScriptEvent(
            tMs = 0L,
            kind = "input.pointerDown",
            pixelX = x,
            pixelY = y,
            pointerId = pointerIds[i],
          )
        )
      }
      // Frame-aligned moves — every STEP_MS the three fingers advance along their circles.
      for (s in 1..steps) {
        val tMs = s.toLong() * STEP_MS
        for (i in 0..2) {
          val (x, y) = positionAt(i, tMs)
          add(
            RecordingScriptEvent(
              tMs = tMs,
              kind = "input.pointerMove",
              pixelX = x,
              pixelY = y,
              pointerId = pointerIds[i],
            )
          )
        }
      }
      // Release all three.
      for (i in 0..2) {
        val (x, y) = positionAt(i, durationMs)
        add(
          RecordingScriptEvent(
            tMs = durationMs,
            kind = "input.pointerUp",
            pixelX = x,
            pixelY = y,
            pointerId = pointerIds[i],
          )
        )
      }
    }
  }

  companion object {
    private const val DRAWING_PREVIEW_ID = "multi-touch-drawing-canvas"
    private const val CANVAS_PX = 240
    private const val FPS = 30
    private const val STEP_MS = 1000L / 30L // one move per virtual frame
    private const val DURATION_MS = 600L
    private const val POINTER_A = 0
    private const val POINTER_B = 1
    private const val POINTER_C = 2

    /**
     * The fixture's palette in the same order as `MultiTouchDrawingPreview.STROKE_PALETTE`. Kept as
     * raw RGB ints so the pixel-match helper (which works on integer channel values) doesn't need a
     * `Color.toArgb()` round-trip per assertion.
     */
    private val STROKE_PALETTE: IntArray =
      intArrayOf(
        0xFFAB00, // amber — pointer 0
        0xE91E63, // pink — pointer 1
        0x00C853, // green — pointer 2
        0x2979FF, // blue — pointer 3 (unused in 3-finger script but kept for symmetry)
      )
  }
}

/**
 * Recording-test fixture matching the public `MultiTouchDrawingPreview` sample shape — a black
 * canvas where every pressed pointer leaves a coloured dot trail (4-entry palette keyed by
 * `pointerId mod 4`). Inlined in this file's test source set (not the cmp sample module) so the
 * test doesn't need a cross-module classpath — same pattern as [PinchableSquare] in
 * [TouchOverlayPinchRecordingTest].
 *
 * The reflective resolver in [DesktopRecordingSession] finds it via the
 * `ee.schimke.composeai.daemon.TouchOverlayDrawingRecordingTestKt` class name (the synthetic `Kt`
 * companion for top-level functions in this file).
 */
@Composable
fun DrawingCanvasFixture() {
  val points = remember { mutableStateListOf<DrawingFixturePoint>() }
  Canvas(
    modifier =
      Modifier.fillMaxSize().background(Color(0xFF101010)).pointerInput(Unit) {
        awaitPointerEventScope {
          while (true) {
            val event = awaitPointerEvent()
            for (change in event.changes) {
              if (change.pressed) {
                points.add(DrawingFixturePoint(change.id.value, change.position))
              }
              change.consume()
            }
          }
        }
      }
  ) {
    points.forEach { pt ->
      val color = FIXTURE_PALETTE[(pt.pointerId.toInt() % FIXTURE_PALETTE.size).coerceAtLeast(0)]
      drawCircle(color = color, radius = 4f, center = pt.position)
    }
  }
}

private data class DrawingFixturePoint(val pointerId: Long, val position: Offset)

private val FIXTURE_PALETTE: List<Color> =
  listOf(
    Color(0xFFFFAB00), // amber — pointer 0
    Color(0xFFE91E63), // pink — pointer 1
    Color(0xFF00C853), // green — pointer 2
    Color(0xFF2979FF), // blue — pointer 3
  )
