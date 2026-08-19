package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.InteractiveInputKind
import ee.schimke.composeai.daemon.protocol.InteractiveInputParams
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Issue #4159 — how long a pressed ripple actually takes, filmed frame by frame in a held session.
 *
 * This test exists because the first diagnosis of #4159 was wrong. "Clicks show ripple artifacts"
 * looked like the ripple never progressing — Material's pressed state layer is a platform
 * `RippleDrawable` driven off the looper clock, and the held loop was not advancing that clock at
 * all (#4282). Filming the press proved otherwise: under this capture path the ripple animates and
 * settles either way. What it also measured is the thing that *does* break, and it is exactly what
 * the issue title said — frame rate:
 * ```
 * filmed at 50ms  (this test):     rest → 11.2% → 5.1% → 0.05% → settled   — a visible animation
 * filmed at 250ms (the live loop): rest →  0.0% → 12.5% → 0.0%  → settled   — ONE transition
 * ```
 *
 * The whole animation fits between two of the live loop's 250 ms ticks, so a viewer never sees it
 * move: the pressed state appears abruptly a tick late, and a press released inside one tick can
 * leave the ripple sampled mid-expansion and held on screen for the rest of it. That is the
 * artifact. The fixes are the cadence (#4283) and out-of-order painting in the viewer (#4285).
 *
 * So this is a **characterisation test**, not a regression test for any one fix: it pins that the
 * ripple animates across several frames and then converges, which is the property both of those
 * changes have to preserve. Re-run it after either one lands and the numbers above are the baseline
 * to compare against.
 *
 * Shares [RippleOnlySquare] with `LivePressRippleTest` rather than adding a second ripple fixture.
 * That one asks whether a live click produces press feedback *at all*; this one asks how that
 * feedback behaves over time, which is the question the cadence has to answer. Its inert handler
 * and full-bounds ripple are what make both questions decidable from pixels alone.
 *
 * Frames are written to `build/ripple-frames/` as a filmstrip — that is where the measurements
 * above came from, and re-running with [STEP_MS] retimed is how to take them again.
 */
class AndroidRippleFrameTest {

  @get:Rule val tempFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun pressedRippleKeepsAnimatingAcrossHeldFrames() {
    val outputDir = tempFolder.newFolder("ripple-renders")
    System.setProperty(RenderEngine.OUTPUT_DIR_PROP, outputDir.absolutePath)
    System.setProperty("roborazzi.test.record", "true")

    val filmstripDir = File("build/ripple-frames").apply { mkdirs() }

    val host = RobolectricHost(sandboxCount = 2, previewSpecResolver = previewSpecResolver())
    host.start()
    try {
      val session =
        host.acquireInteractiveSession(
          previewId = RIPPLE_PREVIEW_ID,
          classLoader = javaClass.classLoader!!,
        )
      try {
        // Frame 0 — at rest, before any input. The baseline the ripple has to depart from.
        val rest = capture(session, advanceTimeMs = null, into = filmstripDir, name = "00-rest")

        // Press and hold at the button's centre. `pointerDown` rather than `click` so the ripple
        // is held in its enter animation instead of immediately starting its exit.
        session.dispatch(
          InteractiveInputParams(
            frameStreamId = "ripple",
            kind = InteractiveInputKind.POINTER_DOWN,
            pixelX = FRAME_WIDTH_PX / 2,
            pixelY = FRAME_HEIGHT_PX / 2,
            pointerType = "touch",
          )
        )

        // Film the press. Each step advances the held clocks by one frame budget, so the sequence
        // covers ~`STEPS * STEP_MS` of animation the way a live stream's ticks would.
        val frames =
          (1..STEPS).map { i ->
            capture(
              session,
              advanceTimeMs = STEP_MS,
              into = filmstripDir,
              name = "%02d-press-%03dms".format(i, i * STEP_MS),
            )
          }

        // The press must draw *something* at some point in the sequence. Not asserted against the
        // first frame specifically: the ripple's enter animation has not visibly started 50ms
        // after the dispatch, so frame 1 is legitimately still identical to rest.
        val maxVsRest = frames.maxOf { changedPixelPct(rest, it) }
        assertTrue(
          "pressing the button must draw a ripple at some point in the " +
            "${STEPS * STEP_MS}ms filmed after the press (peak ${"%.3f".format(maxVsRest * 100)}%" +
            " of the frame differed from rest) — no change at all means the pointer never " +
            "reached the composition",
          maxVsRest > MIN_CHANGED_PCT,
        )

        // The ripple has to be in MOTION across several frames, not jump once and stop. This is
        // the property the cadence work (#4283) has to preserve: filmed at the live loop's 250ms
        // tick this count drops to 1, which is the whole of #4159 in one number.
        val movingFrames =
          frames.zipWithNext().count { (a, b) -> changedPixelPct(a, b) > MIN_CHANGED_PCT }
        assertTrue(
          "the ripple must animate across successive frames — only $movingFrames of the " +
            "${frames.size - 1} frame-to-frame transitions filmed over ${STEPS * STEP_MS}ms " +
            "showed motion, expected at least $MIN_MOVING_TRANSITIONS — a ripple sampled this " +
            "coarsely is not one a viewer can see animate (#4159).",
          movingFrames >= MIN_MOVING_TRANSITIONS,
        )

        // …and it has to come to rest, rather than being caught mid-expansion and left there. The
        // last two frames being identical is the animation having finished inside the window.
        val tailDelta = changedPixelPct(frames[frames.size - 2], frames.last())
        assertTrue(
          "the ripple must settle within ${STEPS * STEP_MS}ms of the press — the last two " +
            "frames still differ by ${"%.3f".format(tailDelta * 100)}%, so the animation is " +
            "either much slower than expected or is not converging",
          tailDelta <= MIN_CHANGED_PCT,
        )
      } finally {
        session.close()
      }
    } finally {
      host.shutdown()
    }
  }

  /** Render one held frame, copy it into the filmstrip directory, and return the decoded pixels. */
  private fun capture(
    session: InteractiveSession,
    advanceTimeMs: Long?,
    into: File,
    name: String,
  ): java.awt.image.BufferedImage {
    val result =
      session.render(requestId = RenderHost.nextRequestId(), advanceTimeMs = advanceTimeMs)
    val png = File(requireNotNull(result.pngPath) { "held render produced no PNG for $name" })
    png.copyTo(File(into, "$name.png"), overwrite = true)
    return ImageIO.read(png)
  }

  /**
   * Fraction of pixels that differ between two frames beyond [TOLERANCE].
   *
   * Per-channel with a small tolerance rather than a byte compare: the capture path is a real
   * hardware-rendered raster, and holding it to exact equality would make the test report
   * single-unit rounding as motion.
   */
  private fun changedPixelPct(
    a: java.awt.image.BufferedImage,
    b: java.awt.image.BufferedImage,
  ): Double {
    require(a.width == b.width && a.height == b.height) {
      "frame size changed mid-sequence: ${a.width}x${a.height} vs ${b.width}x${b.height}"
    }
    var changed = 0L
    for (y in 0 until a.height) {
      for (x in 0 until a.width) {
        val pa = a.getRGB(x, y)
        val pb = b.getRGB(x, y)
        val dr = abs(((pa shr 16) and 0xFF) - ((pb shr 16) and 0xFF))
        val dg = abs(((pa shr 8) and 0xFF) - ((pb shr 8) and 0xFF))
        val db = abs((pa and 0xFF) - (pb and 0xFF))
        if (dr > TOLERANCE || dg > TOLERANCE || db > TOLERANCE) changed++
      }
    }
    return changed.toDouble() / (a.width.toLong() * a.height.toLong())
  }

  private fun previewSpecResolver(): (String) -> RenderSpec? = { previewId ->
    if (previewId == RIPPLE_PREVIEW_ID) {
      RenderSpec(
        className = "ee.schimke.composeai.daemon.RedFixturePreviewsKt",
        functionName = "RippleOnlySquare",
        widthPx = FRAME_WIDTH_PX,
        heightPx = FRAME_HEIGHT_PX,
        density = 1.0f,
        showBackground = true,
        outputBaseName = "interactive-ripple",
      )
    } else null
  }

  private companion object {
    private const val RIPPLE_PREVIEW_ID = "interactive-ripple"
    private const val FRAME_WIDTH_PX = 200
    private const val FRAME_HEIGHT_PX = 120

    /** Frames to film after the press, and the clock budget each one advances. */
    private const val STEPS = 8
    private const val STEP_MS = 50L

    /** Per-channel slack when deciding a pixel moved. */
    private const val TOLERANCE = 4

    /**
     * How much of the frame has to move to count as motion. A ripple on a 200×120 frame covers a
     * large fraction of it, so this is far below what a real animation produces — it is here to
     * reject rounding noise, not to be a tight bound.
     */
    private const val MIN_CHANGED_PCT = 0.005

    /**
     * How many frame-to-frame transitions have to show motion. Measured on the committed fixture
     * the ripple moves across four of the eight filmed transitions before settling; two is a floor
     * with room for the animation's exact frame boundaries to shift under a Compose upgrade, while
     * still being unreachable by the frozen-ripple regression (which produces zero).
     */
    private const val MIN_MOVING_TRANSITIONS = 2
  }
}
