package ee.schimke.composeai.daemon

import java.io.File
import javax.imageio.ImageIO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * An endlessly-running Compose animation keeps producing new frames in a held live session.
 *
 * This is the property the whole live lane rests on for the components that never come to rest — an
 * indeterminate progress indicator, a shimmer, a `rememberInfiniteTransition` of any kind. They
 * have no settled state to reach, so "did the frame change?" is the only evidence that the held
 * composition's clock is moving at all, and a still frame is indistinguishable from a preview
 * served off the baked snapshot lane. That ambiguity is what makes it worth pinning: when someone
 * reports a live indeterminate indicator sitting motionless, the first thing to rule out is that
 * the daemon stopped advancing it.
 *
 * It also guards the **idle backoff** from the far side. That backoff keys off consecutive
 * byte-identical frames, so a preview that genuinely never stops moving must never trip it — the
 * run has to keep resetting. An infinite animation is the strongest case: if anything here ever
 * starts returning identical frames, the backoff would quietly throttle a preview that is still
 * animating, and this test fails before that can ship.
 */
class LiveInfiniteAnimationTest {

  @get:Rule val tempFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun `an infinite animation keeps advancing across held renders`() {
    val outputDir = tempFolder.newFolder("infinite-renders")
    System.setProperty(RenderEngine.OUTPUT_DIR_PROP, outputDir.absolutePath)
    System.setProperty("roborazzi.test.record", "true")

    val host = RobolectricHost(sandboxCount = 2, previewSpecResolver = previewSpecResolver())
    host.start()
    try {
      val session =
        host.acquireInteractiveSession(
          previewId = INFINITE_PREVIEW_ID,
          classLoader = javaClass.classLoader!!,
        )
      try {
        // Sample the way the live frame loop does: repeated renders, each advancing the held
        // clock by a slice of the animation's 1000ms cycle.
        val fills =
          (1..FRAMES).map {
            val result = session.render(RenderHost.nextRequestId(), advanceTimeMs = STEP_MS)
            val png = File(requireNotNull(result.pngPath) { "held render produced no PNG" })
            val image = ImageIO.read(png)
            image.getRGB(image.width / 2, image.height / 2) and 0xFFFFFF
          }

        // Every frame distinct. The fixture sweeps a full-bounds colour across a 1000ms cycle and
        // is sampled 8 times at 125ms, so any two adjacent frames are far apart — a repeat here
        // means the clock stalled, not that the animation happened to return to the same value.
        assertEquals(
          "every held frame of an infinite animation should differ; got " +
            fills.joinToString { "#%06X".format(it) },
          fills.size,
          fills.toSet().size,
        )

        // …and it is genuinely sweeping rather than drifting by a rounding step.
        val spread = fills.maxOf { it shr 16 and 0xFF } - fills.minOf { it shr 16 and 0xFF }
        assertTrue(
          "the animation should sweep a wide range across a full cycle, saw a red spread of $spread",
          spread > 100,
        )
      } finally {
        session.close()
      }
    } finally {
      host.shutdown()
    }
  }

  @Test
  fun `the live loop's render advances by the elapsed wall clock, not the floor`() {
    // The sibling test above passes an explicit `advanceTimeMs`, which is what a recording does.
    // The **live** loop does not: `JsonRpcServer.submitInteractiveRenderAsync` calls
    // `session.render(hostId)` with no advance, and `AndroidInteractiveSession.render` substitutes
    // the wall-clock delta since the previous render — floored at `AUTO_ADVANCE_FLOOR_MS` (32ms),
    // capped at `MAX_AUTO_ADVANCE_MS` (1000ms). That substitution is what keeps a live animation
    // running at the speed a visitor expects rather than in slow motion, and it is what this pins.
    //
    // **Asserting "the frames differ" would not pin it.** Every regression worth catching here —
    // forwarding `null` through, ignoring `resolvedAdvance`, always applying the 32ms floor — still
    // advances the clock by *something*, so every frame still differs and a distinctness assertion
    // passes while live animations crawl at a fraction of real time. The fixture's `LinearEasing`
    // exists so the frame can be read as a clock instead: red is exactly `phase × 255`, so the
    // millisecond delta the render actually applied can be decoded and compared against the elapsed
    // wall clock measured around it.
    val outputDir = tempFolder.newFolder("infinite-auto-renders")
    System.setProperty(RenderEngine.OUTPUT_DIR_PROP, outputDir.absolutePath)
    System.setProperty("roborazzi.test.record", "true")

    val host = RobolectricHost(sandboxCount = 2, previewSpecResolver = previewSpecResolver())
    host.start()
    try {
      val session =
        host.acquireInteractiveSession(
          previewId = INFINITE_PREVIEW_ID,
          classLoader = javaClass.classLoader!!,
        )
      try {
        // `lastRenderAtMs` is stamped at the *start* of each render, so the delta the daemon
        // substitutes is (start of B) − (start of A). Bracket both calls to measure the same span.
        val startedA = System.currentTimeMillis()
        val phaseA = renderPhaseMs(session)
        Thread.sleep(GAP_MS)
        val startedB = System.currentTimeMillis()
        val phaseB = renderPhaseMs(session)

        val expected =
          (startedB - startedA).coerceIn(AUTO_ADVANCE_FLOOR_MS, MAX_AUTO_ADVANCE_MS).toDouble()
        // The phase cannot wrap: these are the session's first two renders, so it starts near zero,
        // and the cap is well under the fixture's period ([SWEEP_PERIOD_MS]).
        val observed = phaseB - phaseA

        assertTrue(
          "a held render should advance by the elapsed wall clock (~${expected.toInt()}ms), " +
            "observed ${observed.toInt()}ms of animation between two renders " +
            "${startedB - startedA}ms apart",
          observed >= expected * (1 - TOLERANCE) && observed <= expected * (1 + TOLERANCE),
        )
        // Stated separately because it is the specific regression the tolerance band above could
        // otherwise absorb if the gap were ever shortened: the floor must not be what is applied.
        assertTrue(
          "the advance must come from the elapsed wall clock, not the ${AUTO_ADVANCE_FLOOR_MS}ms " +
            "floor — observed only ${observed.toInt()}ms across a ${startedB - startedA}ms gap",
          observed > AUTO_ADVANCE_FLOOR_MS * 2,
        )
      } finally {
        session.close()
      }
    } finally {
      host.shutdown()
    }
  }

  /**
   * Render one held frame and decode how far into its cycle the animation has run, in milliseconds.
   *
   * Reads the red channel, which [InfiniteSweepSquare]'s `LinearEasing` makes exactly `phase ×
   * 255`. Quantisation to 8 bits costs ~6ms of resolution at a 1500ms period, which the caller's
   * tolerance absorbs.
   */
  private fun renderPhaseMs(session: InteractiveSession): Double {
    val result = session.render(RenderHost.nextRequestId())
    val png = File(requireNotNull(result.pngPath) { "held render produced no PNG" })
    val image = ImageIO.read(png)
    val red = image.getRGB(image.width / 2, image.height / 2) shr 16 and 0xFF
    return red / 255.0 * SWEEP_PERIOD_MS
  }

  private fun previewSpecResolver(): (String) -> RenderSpec? = { previewId ->
    if (previewId == INFINITE_PREVIEW_ID) {
      RenderSpec(
        className = "ee.schimke.composeai.daemon.RedFixturePreviewsKt",
        functionName = "InfiniteSweepSquare",
        widthPx = 64,
        heightPx = 64,
        density = 1.0f,
        showBackground = true,
        outputBaseName = "interactive-infinite",
      )
    } else null
  }

  private companion object {
    private const val INFINITE_PREVIEW_ID = "interactive-infinite"

    /** Eight samples spanning two thirds of the fixture's cycle. */
    private const val FRAMES = 8
    private const val STEP_MS = 125L

    /**
     * Wall-clock gap deliberately left between the two auto-advance renders.
     *
     * Comfortably above `AUTO_ADVANCE_FLOOR_MS` so the floor and the elapsed delta cannot be
     * confused for one another, and comfortably below `MAX_AUTO_ADVANCE_MS` so the assertion is
     * about the substituted delta rather than about the cap.
     */
    private const val GAP_MS = 300L

    /** Mirrors `AndroidInteractiveSession`'s private floor and cap. */
    private const val AUTO_ADVANCE_FLOOR_MS = 32L
    private const val MAX_AUTO_ADVANCE_MS = 1_000L

    /**
     * Slack on the decoded advance. Wide because it has to absorb 8-bit colour quantisation (~6ms),
     * the frame the render itself composes, and a loaded CI worker's scheduling — none of which the
     * regressions this guards against could hide inside.
     */
    private const val TOLERANCE = 0.35
  }
}
