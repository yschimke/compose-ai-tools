package ee.schimke.composeai.daemon

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
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
 * Showcases the multi-finger caliper additions on a blank scene ([BlankMultiFingerFixture]):
 * 1. **Two-finger rotate + pan** — the pair turns and its centroid drifts (no zoom), so the caliper
 *    shows its rotation arc and pan arrow.
 * 2. **Three-finger spread** — three pointers fan out, drawing the convex-hull outline; a dot badge
 *    reports the pointer count throughout.
 *
 * Produces `overlay-multifinger.gif`. Asserts the purple caliper/hull renders in both phases.
 */
class TouchOverlayMultiFingerRecordingTest {

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
  fun rotate_pan_then_three_finger_hull_render() {
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
              className = "ee.schimke.composeai.daemon.TouchOverlayMultiFingerRecordingTestKt",
              functionName = "BlankMultiFingerFixture",
              widthPx = CANVAS_PX,
              heightPx = CANVAS_PX,
              density = 1.0f,
              outputBaseName = "overlay-multifinger",
            )
          } else null
        },
      )
    host.start()
    try {
      val session =
        host.acquireRecordingSession(
          previewId = PREVIEW_ID,
          recordingId = "test-rec-multifinger",
          classLoader =
            TouchOverlayMultiFingerRecordingTest::class.java.classLoader
              ?: ClassLoader.getSystemClassLoader(),
          fps = FPS,
          scale = 1.0f,
          overrides = PreviewOverrides(touchOverlay = true),
        )
      try {
        session.postScript(buildScript())
        val result = session.stop()
        assertTrue(
          "expected >= $MIN_FRAMES frames; got ${result.frameCount}",
          result.frameCount >= MIN_FRAMES,
        )

        val gifTarget =
          TouchOverlayTestSupport.ARTIFACT_DIR.also { it.mkdirs() }
            .resolve("overlay-multifinger.gif")
        TouchOverlayTestSupport.encodeFramesAsGif(
          File(result.framesDir),
          result.frameCount,
          gifTarget,
          fps = FPS,
        )
        assertTrue("GIF artifact must be written", gifTarget.isFile && gifTarget.length() > 0)

        // Two-finger phase (~400ms): the purple caliper (with rotation arc / pan arrow) is present.
        val twoFinger = purpleAt(result, ms = 400L)
        assertTrue(
          "two-finger frame must contain purple caliper pixels; got $twoFinger",
          twoFinger > 0.0005,
        )

        // Three-finger phase (~1200ms): the purple convex-hull outline is present.
        val threeFinger = purpleAt(result, ms = 1200L)
        assertTrue(
          "three-finger frame must contain purple hull pixels; got $threeFinger",
          threeFinger > 0.0005,
        )
      } finally {
        session.close()
      }
    } finally {
      host.shutdown()
    }
  }

  private fun purpleAt(result: RecordingResult, ms: Long): Double {
    val index = (ms / STEP_MS).toInt().coerceIn(0, result.frameCount - 1)
    val frame =
      TouchOverlayTestSupport.readPng(File(result.framesDir, "frame-${"%05d".format(index)}.png"))
    return TouchOverlayTestSupport.pixelMatchPctApprox(frame, 0x7C4DFF, perChannelTolerance = 50)
  }

  private fun p(tMs: Long, kind: String, x: Float, y: Float, id: Int) =
    RecordingScriptEvent(
      tMs = tMs,
      kind = kind,
      pixelX = x.toInt(),
      pixelY = y.toInt(),
      pointerId = id,
    )

  private fun buildScript(): List<RecordingScriptEvent> {
    val e = mutableListOf<RecordingScriptEvent>()

    // Phase 1 — two fingers rotate ~45° while the centroid pans, holding the spread fixed (no
    // zoom).
    val steps1 = (P1_END_MS / STEP_MS).toInt()
    fun pair(angleDeg: Double, cx: Float, cy: Float): Pair<Offset, Offset> {
      val a = Math.toRadians(angleDeg)
      val dx = (HALF_SPAN * kotlin.math.cos(a)).toFloat()
      val dy = (HALF_SPAN * kotlin.math.sin(a)).toFloat()
      return Offset(cx - dx, cy - dy) to Offset(cx + dx, cy + dy)
    }
    val (a0, b0) = pair(0.0, C0X, C0Y)
    e += p(0L, "input.pointerDown", a0.x, a0.y, A)
    e += p(0L, "input.pointerDown", b0.x, b0.y, B)
    for (i in 1..steps1) {
      val f = i.toFloat() / steps1
      val cx = C0X + (C1X - C0X) * f
      val cy = C0Y + (C1Y - C0Y) * f
      val (a, b) = pair(45.0 * f, cx, cy)
      e += p(i * STEP_MS, "input.pointerMove", a.x, a.y, A)
      e += p(i * STEP_MS, "input.pointerMove", b.x, b.y, B)
    }
    val (aEnd, bEnd) = pair(45.0, C1X, C1Y)
    e += p(P1_END_MS, "input.pointerUp", aEnd.x, aEnd.y, A)
    e += p(P1_END_MS, "input.pointerUp", bEnd.x, bEnd.y, B)

    // Phase 2 — three fingers fan outward from a small triangle (draws the convex hull + 3-dot
    // badge).
    val steps2 = ((P2_END_MS - P2_START_MS) / STEP_MS).toInt()
    val cx = CANVAS_PX / 2f
    val cy = 130f
    val base = listOf(Offset(0f, -22f), Offset(-20f, 16f), Offset(20f, 16f))
    val ids = listOf(A, B, C)
    fun finger(k: Int, s: Float) = Offset(cx + base[k].x * s, cy + base[k].y * s)
    for (k in 0 until 3) e +=
      p(P2_START_MS, "input.pointerDown", finger(k, 1f).x, finger(k, 1f).y, ids[k])
    for (i in 1..steps2) {
      val f = i.toFloat() / steps2
      val s = 1f + (2.2f - 1f) * f
      val t = P2_START_MS + i * STEP_MS
      for (k in 0 until 3) e += p(t, "input.pointerMove", finger(k, s).x, finger(k, s).y, ids[k])
    }
    for (k in 0 until 3) e +=
      p(P2_END_MS, "input.pointerUp", finger(k, 2.2f).x, finger(k, 2.2f).y, ids[k])

    return e
  }

  companion object {
    private const val PREVIEW_ID = "overlay-multifinger"
    private const val CANVAS_PX = 240
    private const val FPS = 30
    private const val STEP_MS = 1000L / 30L

    private const val A = 1
    private const val B = 2
    private const val C = 3

    private const val HALF_SPAN = 42.0
    private const val C0X = 110f
    private const val C0Y = 120f
    private const val C1X = 150f
    private const val C1Y = 150f
    private const val P1_END_MS = 594L

    private const val P2_START_MS = 792L
    private const val P2_END_MS = 1386L

    private const val MIN_FRAMES = 40
  }
}

/** Empty scene: a flat background only, so the multi-finger overlays stand alone. */
@Composable
fun BlankMultiFingerFixture() {
  Box(Modifier.fillMaxSize().background(Color(0xFFFAFAFA)))
}
