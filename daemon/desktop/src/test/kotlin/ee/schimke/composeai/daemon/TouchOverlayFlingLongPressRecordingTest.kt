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
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Showcases the two release/timing overlay effects on an empty scene ([BlankGestureFixture] draws
 * only a flat background), so each reads on its own:
 * 1. **Fling** — a fast swipe-and-release emits a green velocity arrow whose length tracks speed.
 * 2. **Long-press** — a lone finger held in place grows a deep-orange progress arc that completes
 *    at the long-press timeout, then fires a confirm flash.
 *
 * Produces `overlay-fling-longpress.gif`, and separately guards that a two-finger gesture never
 * misfires a long-press once it drops back to one finger. Inlined fixture per the same convention
 * as [MultiGestureCanvasFixture]; resolved reflectively via the
 * `ee.schimke.composeai.daemon.TouchOverlayFlingLongPressRecordingTestKt` class name.
 */
class TouchOverlayFlingLongPressRecordingTest {

  @get:Rule val tempFolder: TemporaryFolder = TemporaryFolder()

  private var savedRecordingsDir: String? = null

  @Before
  fun setUp() {
    savedRecordingsDir = System.getProperty(DesktopHost.RECORDINGS_DIR_PROP)
    System.setProperty(
      DesktopHost.RECORDINGS_DIR_PROP,
      tempFolder.newFolder("touch-overlay-recordings").absolutePath,
    )
  }

  @After
  fun tearDown() {
    val saved = savedRecordingsDir
    if (saved == null) System.clearProperty(DesktopHost.RECORDINGS_DIR_PROP)
    else System.setProperty(DesktopHost.RECORDINGS_DIR_PROP, saved)
  }

  @Test
  fun fling_then_long_press_render_velocity_arrow_and_progress_arc() {
    val host = startedHost()
    try {
      val session = acquire(host, "test-rec-fling-longpress")
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
        val green = orangeOrGreenAt(result, ms = 180L, rgb = 0x00C853)
        assertTrue("post-release frame must contain green fling pixels; got $green", green > 0.0003)

        // Long-press: a frame just after the hold timeout (~880ms) must carry deep-orange pixels
        // (the progress arc / confirm flash).
        val orange = orangeOrGreenAt(result, ms = 880L, rgb = 0xFF5722)
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

  /**
   * Regression for the Codex review on #1959: a two-finger gesture whose first finger stays put
   * must NOT misfire a long-press once the second finger lifts. The lone remaining finger is older
   * than the timeout, but having been part of a multi-touch gesture disqualifies it — so there must
   * be no deep-orange long-press pixels after the second finger goes up.
   */
  @Test
  fun multi_touch_then_lone_finger_does_not_fire_long_press() {
    val host = startedHost()
    try {
      val session = acquire(host, "test-rec-multitouch-noop")
      try {
        session.postScript(buildMultiTouchThenHoldScript())
        val result = session.stop()

        // Sample two frames after the second finger lifts (≈ 760ms onward) — neither may contain
        // long-press deep-orange. Without the multi-touch disqualification this fires here.
        val afterLift = orangeOrGreenAt(result, ms = 820L, rgb = 0xFF5722)
        val nearEnd = orangeOrGreenAt(result, ms = 960L, rgb = 0xFF5722)
        assertTrue(
          "no long-press should fire after a two-finger gesture; got $afterLift / $nearEnd",
          afterLift < 0.0002 && nearEnd < 0.0002,
        )
      } finally {
        session.close()
      }
    } finally {
      host.shutdown()
    }
  }

  // --- helpers ---------------------------------------------------------------------------------

  private fun startedHost(): DesktopHost {
    val engine =
      RenderEngine(
        outputDir = tempFolder.newFolder("touch-overlay-renders-${System.nanoTime()}"),
        previewOverrideExtensions =
          PreviewOverrideExtensions(listOf(TouchOverlayPreviewOverrideExtension())),
      )
    return DesktopHost(
        engine = engine,
        previewSpecResolver = { previewId ->
          if (previewId == PREVIEW_ID) {
            RenderSpec(
              className = FIXTURE_CLASS,
              functionName = "BlankGestureFixture",
              widthPx = CANVAS_PX,
              heightPx = CANVAS_PX,
              density = 1.0f,
              outputBaseName = "overlay-fling-longpress",
            )
          } else null
        },
      )
      .also { it.start() }
  }

  private fun acquire(host: DesktopHost, recordingId: String) =
    host.acquireRecordingSession(
      previewId = PREVIEW_ID,
      recordingId = recordingId,
      classLoader =
        TouchOverlayFlingLongPressRecordingTest::class.java.classLoader
          ?: ClassLoader.getSystemClassLoader(),
      fps = FPS,
      scale = 1.0f,
      overrides = PreviewOverrides(touchOverlay = true),
    )

  /** Fraction of [rgb] pixels (tolerance 50) in the frame nearest [ms] of the recording. */
  private fun orangeOrGreenAt(result: RecordingResult, ms: Long, rgb: Int): Double {
    val index = (ms / STEP_MS).toInt().coerceIn(0, result.frameCount - 1)
    val frame =
      TouchOverlayTestSupport.readPng(File(result.framesDir, "frame-${"%05d".format(index)}.png"))
    return TouchOverlayTestSupport.pixelMatchPctApprox(frame, rgb, perChannelTolerance = 50)
  }

  /** A fast swipe-and-release (fling) followed by a stationary hold (long-press). */
  private fun buildFlingThenLongPressScript(): List<RecordingScriptEvent> {
    val events = mutableListOf<RecordingScriptEvent>()

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

  /**
   * Two stationary fingers (so neither is disqualified by slop), held past the long-press timeout,
   * then the second lifts leaving the first still down — the case that misfired before the fix.
   */
  private fun buildMultiTouchThenHoldScript(): List<RecordingScriptEvent> =
    listOf(
      pointer(0L, "input.pointerDown", HOLD_X, HOLD_Y, A),
      pointer(99L, "input.pointerDown", 160, 160, B),
      pointer(693L, "input.pointerUp", 160, 160, B),
      pointer(990L, "input.pointerUp", HOLD_X, HOLD_Y, A),
    )

  private fun pointer(tMs: Long, kind: String, x: Int, y: Int, id: Int = A) =
    RecordingScriptEvent(tMs = tMs, kind = kind, pixelX = x, pixelY = y, pointerId = id)

  companion object {
    private const val PREVIEW_ID = "overlay-fling-longpress"
    private const val FIXTURE_CLASS =
      "ee.schimke.composeai.daemon.TouchOverlayFlingLongPressRecordingTestKt"
    private const val CANVAS_PX = 240
    private const val FPS = 30
    private const val STEP_MS = 1000L / 30L

    private const val A = 1
    private const val B = 2

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
