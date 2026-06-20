package ee.schimke.composeai.daemon

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
 * Companion to [TouchOverlayDrawingCanvasGesturesRecordingTest] that records the *same* tap → drag
 * → pinch script against a deliberately empty scene ([BlankShowcaseFixture] draws only a flat
 * background, no circles / strokes / scaling). With the canvas contributing nothing, the captured
 * `overlay-effects-only.gif` isolates the touch overlay itself so each bit is legible on its own:
 * the alpha tap flash, the cyan "whoosh" drag trail, and the purple two-finger pinch caliper.
 *
 * The fixture is inlined here (same convention as [MultiGestureCanvasFixture]) so the test needs no
 * cross-module classpath; the reflective resolver finds it via the
 * `ee.schimke.composeai.daemon.TouchOverlayShowcaseRecordingTestKt` class name.
 */
class TouchOverlayShowcaseRecordingTest {

  @get:Rule val tempFolder: TemporaryFolder = TemporaryFolder()

  private var savedRecordingsDir: String? = null

  @After
  fun tearDown() {
    val saved = savedRecordingsDir
    if (saved == null) System.clearProperty(DesktopHost.RECORDINGS_DIR_PROP)
    else System.setProperty(DesktopHost.RECORDINGS_DIR_PROP, saved)
  }

  @Test
  fun overlay_effects_on_blank_scene_render_tap_trail_and_caliper() {
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
              className = "ee.schimke.composeai.daemon.TouchOverlayShowcaseRecordingTestKt",
              functionName = "BlankShowcaseFixture",
              widthPx = CANVAS_PX,
              heightPx = CANVAS_PX,
              density = 1.0f,
              outputBaseName = "overlay-effects-only",
            )
          } else null
        },
      )
    host.start()
    try {
      val session =
        host.acquireRecordingSession(
          previewId = PREVIEW_ID,
          recordingId = "test-rec-overlay-showcase",
          classLoader =
            TouchOverlayShowcaseRecordingTest::class.java.classLoader
              ?: ClassLoader.getSystemClassLoader(),
          fps = FPS,
          scale = 1.0f,
          overrides = PreviewOverrides(touchOverlay = true),
        )
      try {
        session.postScript(buildTapDragPinchScript())

        val result = session.stop()
        assertTrue(
          "expected >= $MIN_FRAMES frames; got ${result.frameCount}",
          result.frameCount >= MIN_FRAMES,
        )

        // Primary artifact (APNG) plus the GIF the demo embeds.
        val apng = session.encode(RecordingFormat.APNG)
        assertTrue("APNG must be non-empty", File(apng.videoPath).isFile && apng.sizeBytes > 0)

        val gifTarget =
          TouchOverlayTestSupport.ARTIFACT_DIR.also { it.mkdirs() }
            .resolve("overlay-effects-only.gif")
        TouchOverlayTestSupport.encodeFramesAsGif(
          File(result.framesDir),
          result.frameCount,
          gifTarget,
          fps = FPS,
        )
        assertTrue("GIF artifact must be written", gifTarget.isFile && gifTarget.length() > 0)

        // Trail/ring assertion — a mid-drag frame must carry cyan overlay pixels.
        val dragFrame =
          TouchOverlayTestSupport.readPng(
            File(result.framesDir, "frame-${"%05d".format(result.frameCount / 3)}.png")
          )
        val cyan =
          TouchOverlayTestSupport.pixelMatchPctApprox(dragFrame, 0x00BCD4, perChannelTolerance = 60)
        assertTrue("mid-drag frame must contain cyan overlay pixels; got $cyan", cyan > 0.0005)

        // Caliper assertion — a pinch-phase frame must carry purple caliper pixels. The pinch runs
        // in the final third, so sample near the end where the spread (and the caliper) is largest.
        val pinchFrame =
          TouchOverlayTestSupport.readPng(
            File(result.framesDir, "frame-${"%05d".format(result.frameCount - 4)}.png")
          )
        val purple =
          TouchOverlayTestSupport.pixelMatchPctApprox(
            pinchFrame,
            0x7C4DFF,
            perChannelTolerance = 50,
          )
        assertTrue(
          "pinch-phase frame must contain purple caliper pixels; got $purple",
          purple > 0.0005,
        )
      } finally {
        session.close()
      }
    } finally {
      host.shutdown()
    }
  }

  /** Tap → drag → pinch, identical timeline to the drawing-canvas demo so the bits line up. */
  private fun buildTapDragPinchScript(): List<RecordingScriptEvent> {
    val events = mutableListOf<RecordingScriptEvent>()

    fun pointer(tMs: Long, kind: String, x: Int, y: Int, id: Int) =
      RecordingScriptEvent(tMs = tMs, kind = kind, pixelX = x, pixelY = y, pointerId = id)

    // 1) Tap.
    events += pointer(TAP_START_MS, "input.pointerDown", TAP_X, TAP_Y, A)
    events += pointer(TAP_END_MS, "input.pointerUp", TAP_X, TAP_Y, A)

    // 2) Drag along a shallow zigzag.
    val dragSteps = ((DRAG_END_MS - DRAG_START_MS) / STEP_MS).toInt()
    events += pointer(DRAG_START_MS, "input.pointerDown", DRAG_FROM_X, DRAG_Y, A)
    for (i in 1..dragSteps) {
      val f = i.toFloat() / dragSteps.toFloat()
      val x = (DRAG_FROM_X + (DRAG_TO_X - DRAG_FROM_X) * f).toInt()
      val y = DRAG_Y + (kotlin.math.sin(f * Math.PI * 4).toFloat() * DRAG_AMPL).toInt()
      events += pointer(DRAG_START_MS + i * STEP_MS, "input.pointerMove", x, y, A)
    }
    events += pointer(DRAG_END_MS, "input.pointerUp", DRAG_TO_X, DRAG_Y, A)

    // 3) Pinch — two pointers spread outward along the diagonal.
    val pinchSteps = ((PINCH_END_MS - PINCH_START_MS) / STEP_MS).toInt()
    val c = CANVAS_PX / 2
    val start = (CANVAS_PX * 0.10f).toInt()
    val end = (CANVAS_PX * 0.35f).toInt()
    events += pointer(PINCH_START_MS, "input.pointerDown", c - start, c - start, A)
    events += pointer(PINCH_START_MS, "input.pointerDown", c + start, c + start, B)
    for (i in 1..pinchSteps) {
      val f = i.toFloat() / pinchSteps.toFloat()
      val s = (start + (end - start) * f).toInt()
      events += pointer(PINCH_START_MS + i * STEP_MS, "input.pointerMove", c - s, c - s, A)
      events += pointer(PINCH_START_MS + i * STEP_MS, "input.pointerMove", c + s, c + s, B)
    }
    events += pointer(PINCH_END_MS, "input.pointerUp", c - end, c - end, A)
    events += pointer(PINCH_END_MS, "input.pointerUp", c + end, c + end, B)

    return events
  }

  companion object {
    private const val PREVIEW_ID = "overlay-effects-only"
    private const val CANVAS_PX = 240
    private const val FPS = 30
    private const val STEP_MS = 1000L / 30L

    private const val A = 1
    private const val B = 2

    // Same doubled timeline as the drawing-canvas demo.
    private const val TAP_START_MS = 0L
    private const val TAP_END_MS = 132L
    private const val TAP_X = 60
    private const val TAP_Y = 70

    private const val DRAG_START_MS = 660L
    private const val DRAG_END_MS = 1320L
    private const val DRAG_FROM_X = 30
    private const val DRAG_TO_X = 210
    private const val DRAG_Y = 140
    private const val DRAG_AMPL = 18

    private const val PINCH_START_MS = 1848L
    private const val PINCH_END_MS = 2904L

    private const val MIN_FRAMES = 60
  }
}

/** Empty scene: a flat background and nothing else, so only the touch overlay is visible. */
@Composable
fun BlankShowcaseFixture() {
  androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().background(Color(0xFFFAFAFA)))
}
