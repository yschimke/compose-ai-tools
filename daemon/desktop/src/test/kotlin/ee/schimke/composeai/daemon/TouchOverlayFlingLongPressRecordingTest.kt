package ee.schimke.composeai.daemon

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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
 * Showcases the two release/timing overlay effects on an empty scene ([BlankGestureFixture] draws
 * only a flat background), so each reads on its own:
 * 1. **Long-press** — a lone finger held in place grows a deep-orange progress arc that completes
 *    at the long-press timeout, then fires a confirm flash.
 * 2. **Fling** — a fast swipe-and-release emits a green velocity arrow whose length tracks speed.
 *
 * Produces `overlay-fling-longpress.gif`. Inlined fixture per the same convention as
 * [MultiGestureCanvasFixture]; resolved reflectively via the
 * `ee.schimke.composeai.daemon.TouchOverlayFlingLongPressRecordingTestKt` class name.
 */
class TouchOverlayFlingLongPressRecordingTest {

  @get:Rule val tempFolder: TemporaryFolder = TemporaryFolder()

  private var savedRecordingsDir: String? = null

  @After
  fun tearDown() {
    val saved = savedRecordingsDir
    if (saved == null) System.clearProperty(DesktopHost.RECORDINGS_DIR_PROP)
    else System.setProperty(DesktopHost.RECORDINGS_DIR_PROP, saved)
  }

  @Test
  fun long_press_then_fling_render_arc_and_velocity_arrow() {
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
              className = "ee.schimke.composeai.daemon.TouchOverlayFlingLongPressRecordingTestKt",
              functionName = "BlankGestureFixture",
              widthPx = CANVAS_PX,
              heightPx = CANVAS_PX,
              density = 1.0f,
              outputBaseName = "overlay-fling-longpress",
            )
          } else null
        },
      )
    host.start()
    try {
      val session =
        host.acquireRecordingSession(
          previewId = PREVIEW_ID,
          recordingId = "test-rec-fling-longpress",
          classLoader =
            TouchOverlayFlingLongPressRecordingTest::class.java.classLoader
              ?: ClassLoader.getSystemClassLoader(),
          fps = FPS,
          scale = 1.0f,
          overrides = PreviewOverrides(touchOverlay = true),
        )
      try {
        session.postScript(buildFlingThenLongPressScript())

        val result = session.stop()
        assertTrue(
          "expected >= $MIN_FRAMES frames; got ${result.frameCount}",
          result.frameCount >= MIN_FRAMES,
        )

        val apng = session.encode(RecordingFormat.APNG)
        assertTrue("APNG must be non-empty", File(apng.videoPath).isFile && apng.sizeBytes > 0)

        val gifTarget =
          TouchOverlayTestSupport.ARTIFACT_DIR.also { it.mkdirs() }
            .resolve("overlay-fling-longpress.gif")
        TouchOverlayTestSupport.encodeFramesAsGif(
          File(result.framesDir),
          result.frameCount,
          gifTarget,
          fps = FPS,
        )
        assertTrue("GIF artifact must be written", gifTarget.isFile && gifTarget.length() > 0)

        // Fling: a frame shortly after the fast release (~180ms) must carry green velocity-arrow
        // pixels. The fling is first, so there are plenty of following frames to sample.
        val flingFrame =
          TouchOverlayTestSupport.readPng(
            File(result.framesDir, "frame-${"%05d".format((180L / STEP_MS).toInt())}.png")
          )
        val green =
          TouchOverlayTestSupport.pixelMatchPctApprox(
            flingFrame,
            0x00C853,
            perChannelTolerance = 50,
          )
        assertTrue("post-release frame must contain green fling pixels; got $green", green > 0.0003)

        // Long-press: a frame just after the hold timeout (~880ms) must carry deep-orange pixels
        // (the progress arc / confirm flash).
        val holdFrame =
          TouchOverlayTestSupport.readPng(
            File(result.framesDir, "frame-${"%05d".format((880L / STEP_MS).toInt())}.png")
          )
        val orange =
          TouchOverlayTestSupport.pixelMatchPctApprox(holdFrame, 0xFF5722, perChannelTolerance = 50)
        assertTrue(
          "hold frame must contain deep-orange long-press pixels; got $orange",
          orange > 0.0003,
        )
      } finally {
        session.close()
      }
    } finally {
      host.shutdown()
    }
  }

  /** A fast swipe-and-release (fling) followed by a stationary hold (long-press). */
  private fun buildFlingThenLongPressScript(): List<RecordingScriptEvent> {
    val events = mutableListOf<RecordingScriptEvent>()

    fun pointer(tMs: Long, kind: String, x: Int, y: Int, id: Int = A) =
      RecordingScriptEvent(tMs = tMs, kind = kind, pixelX = x, pixelY = y, pointerId = id)

    // 1) Fling — a quick diagonal swipe with a high release velocity. First so the velocity arrow
    // has following frames to fade across.
    val steps = ((FLING_END_MS - FLING_START_MS) / STEP_MS).toInt()
    events += pointer(FLING_START_MS, "input.pointerDown", FLING_FROM_X, FLING_FROM_Y)
    for (i in 1..steps) {
      val f = i.toFloat() / steps.toFloat()
      val x = (FLING_FROM_X + (FLING_TO_X - FLING_FROM_X) * f).toInt()
      val y = (FLING_FROM_Y + (FLING_TO_Y - FLING_FROM_Y) * f).toInt()
      events += pointer(FLING_START_MS + i * STEP_MS, "input.pointerMove", x, y)
    }
    events += pointer(FLING_END_MS, "input.pointerUp", FLING_TO_X, FLING_TO_Y)

    // 2) Long press — finger down and held in place well past the timeout, no movement. The hold
    // also provides the tail time over which the earlier fling arrow fades out.
    events += pointer(HOLD_START_MS, "input.pointerDown", HOLD_X, HOLD_Y)
    events += pointer(HOLD_END_MS, "input.pointerUp", HOLD_X, HOLD_Y)

    return events
  }

  companion object {
    private const val PREVIEW_ID = "overlay-fling-longpress"
    private const val CANVAS_PX = 240
    private const val FPS = 30
    private const val STEP_MS = 1000L / 30L

    private const val A = 1

    // Fast swipe: ~180px of travel in ~132ms => ~1.4px/ms, well over the fling threshold.
    private const val FLING_START_MS = 0L
    private const val FLING_END_MS = 132L
    private const val FLING_FROM_X = 40
    private const val FLING_FROM_Y = 175
    private const val FLING_TO_X = 210
    private const val FLING_TO_Y = 110

    // Hold begins after the fling settles; held well past the 500ms timeout so the arc completes
    // and the confirm flash fires.
    private const val HOLD_START_MS = 330L
    private const val HOLD_X = 80
    private const val HOLD_Y = 80
    private const val HOLD_END_MS = 1056L

    private const val MIN_FRAMES = 30
  }
}

/** Empty scene: a flat background only, so the overlay's fling/long-press effects stand alone. */
@Composable
fun BlankGestureFixture() {
  Box(Modifier.fillMaxSize().background(Color(0xFFFAFAFA)))
}
