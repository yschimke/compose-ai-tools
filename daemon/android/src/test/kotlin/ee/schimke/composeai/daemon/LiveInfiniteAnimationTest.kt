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
  fun `an infinite animation advances on the wall-clock path the live loop actually uses`() {
    // The sibling test above passes an explicit `advanceTimeMs`, which is what a recording does.
    // The **live** loop does not: `JsonRpcServer.submitInteractiveRenderAsync` calls
    // `session.render(hostId)` with no advance at all, and `AndroidInteractiveSession.render`
    // substitutes the wall-clock delta since the previous render — floored at 32ms, capped at
    // 1000ms. That substitution is the thing an animating live preview actually depends on, and
    // asserting only the explicit path would leave it untested while looking like coverage.
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
        val fills =
          (1..FRAMES).map {
            val result = session.render(RenderHost.nextRequestId())
            val png = File(requireNotNull(result.pngPath) { "held render produced no PNG" })
            val image = ImageIO.read(png)
            image.getRGB(image.width / 2, image.height / 2) and 0xFFFFFF
          }
        assertEquals(
          "every frame off the wall-clock auto-advance should differ; got " +
            fills.joinToString { "#%06X".format(it) },
          fills.size,
          fills.toSet().size,
        )
      } finally {
        session.close()
      }
    } finally {
      host.shutdown()
    }
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

    /** Eight samples across the fixture's 1000ms cycle. */
    private const val FRAMES = 8
    private const val STEP_MS = 125L
  }
}
