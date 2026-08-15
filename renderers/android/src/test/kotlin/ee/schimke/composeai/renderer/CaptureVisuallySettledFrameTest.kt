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
  fun `fast path accepts the first two identical frames`() {
    val file = File.createTempFile("settled_final_", ".png").apply { deleteOnExit() }
    var captures = 0
    var advances = 0

    val settled =
      captureVisuallySettledFrame(
        file = file,
        role = "test final",
        advanceFrame = { advances++ },
      ) { candidate ->
        captures++
        writeFrame(candidate, argb = 0xff336699.toInt())
      }

    assertTrue(settled)
    assertEquals(2, captures)
    assertEquals(captures - 1, advances)
  }

  @Test
  fun `waits through a changing frame then accepts the stable tail`() {
    val file = File.createTempFile("settling_final_", ".png").apply { deleteOnExit() }
    val colours =
      listOf(0xff000001.toInt(), 0xff000002.toInt(), 0xff000002.toInt(), 0xff000002.toInt())
    var captures = 0

    val settled =
      captureVisuallySettledFrame(file, role = "test final", advanceFrame = {}) { candidate ->
        writeFrame(candidate, colours[captures++])
      }

    assertTrue(settled)
    assertEquals(4, captures)
    assertEquals(colours.last(), ImageIO.read(file).getRGB(0, 0))
  }

  @Test
  fun `does not majority-vote an alternating animation`() {
    val file = File.createTempFile("animated_final_", ".png").apply { deleteOnExit() }
    val colours = listOf(0xff101010.toInt(), 0xff202020.toInt())
    var captures = 0

    val settled =
      captureVisuallySettledFrame(file, role = "test final", advanceFrame = {}) { candidate ->
        writeFrame(candidate, colours[captures++ % colours.size])
      }

    assertFalse(settled)
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
