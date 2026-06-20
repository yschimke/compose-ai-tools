package ee.schimke.composeai.daemon

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.RecordingScriptEvent
import java.io.File
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Demonstrates the consumed-vs-unconsumed up marker. [ConsumptionFixture] has a `clickable` left
 * half (which consumes taps) and a dead right half (no pointer input). The script taps the dead
 * half first, then the interactive half:
 * - The dead-half tap is consumed by nothing → the overlay draws the distinct red dashed-ring + ✕
 *   "unhandled" marker.
 * - The interactive-half tap is consumed by `clickable` → the overlay draws the ordinary up flash,
 *   no red.
 *
 * So "the UI didn't take this touch" reads as the exception. Produces `overlay-consumption.gif`.
 */
class TouchOverlayConsumptionRecordingTest {

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
  fun unconsumed_tap_marks_red_consumed_tap_does_not() {
    val engine =
      RenderEngine(
        outputDir = tempFolder.newFolder("touch-overlay-renders"),
        previewOverrideExtensions =
          PreviewOverrideExtensions(listOf(TouchOverlayPreviewOverrideExtension())),
      )
    val host =
      DesktopHost(
        engine = engine,
        previewSpecResolver = { previewId ->
          if (previewId == PREVIEW_ID) {
            RenderSpec(
              className = "ee.schimke.composeai.daemon.TouchOverlayConsumptionRecordingTestKt",
              functionName = "ConsumptionFixture",
              widthPx = CANVAS_PX,
              heightPx = CANVAS_PX,
              density = 1.0f,
              outputBaseName = "overlay-consumption",
            )
          } else null
        },
      )
    host.start()
    try {
      val session =
        host.acquireRecordingSession(
          previewId = PREVIEW_ID,
          recordingId = "test-rec-consumption",
          classLoader =
            TouchOverlayConsumptionRecordingTest::class.java.classLoader
              ?: ClassLoader.getSystemClassLoader(),
          fps = FPS,
          scale = 1.0f,
          overrides = PreviewOverrides(touchOverlay = true),
        )
      try {
        session.postScript(buildTwoTapScript())
        val result = session.stop()
        assertTrue(
          "expected >= $MIN_FRAMES frames; got ${result.frameCount}",
          result.frameCount >= MIN_FRAMES,
        )

        val gifTarget =
          TouchOverlayTestSupport.ARTIFACT_DIR.also { it.mkdirs() }
            .resolve("overlay-consumption.gif")
        TouchOverlayTestSupport.encodeFramesAsGif(
          File(result.framesDir),
          result.frameCount,
          gifTarget,
          fps = FPS,
        )
        assertTrue("GIF artifact must be written", gifTarget.isFile && gifTarget.length() > 0)

        // Just after the dead-half tap lifts (~99ms) — the unhandled marker is red.
        val deadRed = colorAt(result, ms = 132L, rgb = 0xF44336)
        assertTrue(
          "dead-half (unconsumed) tap must show red unhandled pixels; got $deadRed",
          deadRed > 0.0003,
        )

        // During the interactive-half tap (~560ms) — a normal marker (orange/amber), and NO red,
        // because `clickable` consumed it. The earlier red ✕ has fully faded by now.
        val liveRed = colorAt(result, ms = 560L, rgb = 0xF44336)
        val liveOrange = colorAt(result, ms = 560L, rgb = 0xFF9800)
        assertTrue(
          "consumed tap must NOT show red unhandled pixels; got $liveRed",
          liveRed < 0.0002,
        )
        assertTrue(
          "consumed tap must still show a normal marker; got $liveOrange",
          liveOrange > 0.0003,
        )
      } finally {
        session.close()
      }
    } finally {
      host.shutdown()
    }
  }

  private fun colorAt(result: RecordingResult, ms: Long, rgb: Int): Double {
    val index = (ms / STEP_MS).toInt().coerceIn(0, result.frameCount - 1)
    val frame =
      TouchOverlayTestSupport.readPng(File(result.framesDir, "frame-${"%05d".format(index)}.png"))
    return TouchOverlayTestSupport.pixelMatchPctApprox(frame, rgb, perChannelTolerance = 45)
  }

  /**
   * Tap the dead (right) half first, then the interactive (left) half, spaced so markers don't
   * overlap.
   */
  private fun buildTwoTapScript(): List<RecordingScriptEvent> {
    fun tap(downMs: Long, upMs: Long, x: Int, y: Int) =
      listOf(
        RecordingScriptEvent(
          tMs = downMs,
          kind = "input.pointerDown",
          pixelX = x,
          pixelY = y,
          pointerId = 1,
        ),
        RecordingScriptEvent(
          tMs = upMs,
          kind = "input.pointerUp",
          pixelX = x,
          pixelY = y,
          pointerId = 1,
        ),
      )
    return tap(0L, 33L, DEAD_X, TAP_Y) + tap(495L, 594L, LIVE_X, TAP_Y)
  }

  companion object {
    private const val PREVIEW_ID = "overlay-consumption"
    private const val CANVAS_PX = 240
    private const val FPS = 30
    private const val STEP_MS = 1000L / 30L

    private const val TAP_Y = 120
    private const val LIVE_X = 60 // left, clickable half
    private const val DEAD_X = 180 // right, no pointer input

    private const val MIN_FRAMES = 18
  }
}

/** Left half consumes taps (`clickable`); right half is inert, so taps there fall through. */
@Composable
fun ConsumptionFixture() {
  Row(Modifier.fillMaxSize()) {
    Box(Modifier.weight(1f).fillMaxHeight().background(Color(0xFFC8E6C9)).clickable {})
    Box(Modifier.weight(1f).fillMaxHeight().background(Color(0xFFF5F5F5)))
  }
}
