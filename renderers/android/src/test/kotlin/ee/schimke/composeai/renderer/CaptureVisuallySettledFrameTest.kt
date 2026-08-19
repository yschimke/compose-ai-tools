package ee.schimke.composeai.renderer

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureVisuallySettledFrameTest {

  @Test
  fun `an exact settle is a snapshot and skips the quiescence probe`() {
    // `@SettledPreview(afterMs = N)` names an instant, exactly as `advanceTimeMillis` does. The
    // probe spends at least one more frame and up to the sample budget, which would publish an
    // "exact 350ms" capture from somewhere in 366-414ms.
    assertFalse(
      shouldAdvanceClockForVisualSettling(
        advanceTimeMillis = null,
        hasFollowingJobs = false,
        hasExactSettle = true,
      )
    )
    // Auto settle names a bound, not a moment, so it keeps the probe.
    assertTrue(
      shouldAdvanceClockForVisualSettling(
        advanceTimeMillis = null,
        hasFollowingJobs = false,
        hasExactSettle = false,
      )
    )
  }

  @Test
  fun `settling may advance only an untimed final job`() {
    assertTrue(
      shouldAdvanceClockForVisualSettling(
        advanceTimeMillis = null,
        hasFollowingJobs = false,
      )
    )
    assertFalse(
      shouldAdvanceClockForVisualSettling(
        advanceTimeMillis = 200L,
        hasFollowingJobs = false,
      )
    )
    assertFalse(
      shouldAdvanceClockForVisualSettling(
        advanceTimeMillis = null,
        hasFollowingJobs = true,
      )
    )
  }

  @Test
  fun `an unchanging frame is never declared settled on the strength of two samples`() {
    // The old fast path returned after two matching frames. Two identical frames at t=0 is the
    // expected opening of any delayed reveal, so that declared quiescence before the animation
    // began (issue #4239). The budget is now spent in full before anything is concluded.
    val file = File.createTempFile("settled_final_", ".png").apply { deleteOnExit() }
    var captures = 0
    var advances = 0

    val outcome =
      captureVisuallySettledFrame(
        file = file,
        role = "test final",
        advanceFrame = { advances++ },
      ) { candidate ->
        captures++
        writeFrame(candidate, argb = 0xff336699.toInt())
      }

    assertEquals(VisualSettleOutcome.NEVER_CHANGED, outcome)
    assertTrue(outcome.isQuiescent)
    assertEquals(VISUAL_SETTLE_MAX_SAMPLES, captures)
    assertEquals(captures - 1, advances)
  }

  @Test
  fun `a frame that moves once and then holds still is the only settled outcome`() {
    // The distinction the enum exists for: SETTLED means "something happened and then stopped",
    // which is a claim about the composition; NEVER_CHANGED only means "nothing was seen".
    val file = File.createTempFile("moved_final_", ".png").apply { deleteOnExit() }
    val colours =
      listOf(0xff000001.toInt(), 0xff000002.toInt(), 0xff000002.toInt(), 0xff000002.toInt())
    var captures = 0

    val outcome =
      captureVisuallySettledFrame(file, role = "test final", advanceFrame = {}) { candidate ->
        writeFrame(candidate, colours[captures++])
      }

    assertEquals(VisualSettleOutcome.SETTLED, outcome)
    assertEquals(null, outcome.describe("still"))
  }

  @Test
  fun `waits through a changing frame then accepts the stable tail`() {
    val file = File.createTempFile("settling_final_", ".png").apply { deleteOnExit() }
    val colours =
      listOf(0xff000001.toInt(), 0xff000002.toInt(), 0xff000002.toInt(), 0xff000002.toInt())
    var captures = 0

    val outcome =
      captureVisuallySettledFrame(file, role = "test final", advanceFrame = {}) { candidate ->
        writeFrame(candidate, colours[captures++])
      }

    assertEquals(VisualSettleOutcome.SETTLED, outcome)
    assertEquals(4, captures)
    assertEquals(colours.last(), ImageIO.read(file).getRGB(0, 0))
  }

  @Test
  fun `does not majority-vote an alternating animation`() {
    val file = File.createTempFile("animated_final_", ".png").apply { deleteOnExit() }
    val colours = listOf(0xff101010.toInt(), 0xff202020.toInt())
    var captures = 0

    val outcome =
      captureVisuallySettledFrame(file, role = "test final", advanceFrame = {}) { candidate ->
        writeFrame(candidate, colours[captures++ % colours.size])
      }

    assertEquals(VisualSettleOutcome.STILL_CHANGING, outcome)
    assertFalse(outcome.isQuiescent)
    assertTrue(outcome.describe("still")!!.contains("did not become visually quiescent"))
    assertEquals(VISUAL_SETTLE_MAX_SAMPLES, captures)
    // On timeout the most recent valid frame is retained; the render remains useful and the caller
    // emits a diagnostic instead of choosing the majority phase as a false "stable" result.
    assertEquals(colours.first(), ImageIO.read(file).getRGB(0, 0))
  }

  private fun writeFrame(file: File, argb: Int) {
    val image = BufferedImage(5, 4, BufferedImage.TYPE_INT_ARGB)
    for (y in 0 until image.height) for (x in 0 until image.width) image.setRGB(x, y, argb)
    ImageIO.write(image, "png", file)
  }
}
