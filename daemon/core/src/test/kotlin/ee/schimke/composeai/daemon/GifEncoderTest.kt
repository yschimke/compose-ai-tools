package ee.schimke.composeai.daemon

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Unit tests for [GifEncoder] — the pure-JVM `javax.imageio` animated-GIF encoder promoted from the
 * touch-overlay test helper. Pins the always-available property the recording surface relies on:
 * given a directory of contiguous `frame-NNNNN.png` files it produces a single readable,
 * multi-frame GIF with no native dependency.
 */
class GifEncoderTest {

  @get:Rule val tmp = TemporaryFolder()

  private fun writeFrame(dir: File, index: Int, color: Color, w: Int = 8, h: Int = 8): File {
    val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
    val g = img.createGraphics()
    g.color = color
    g.fillRect(0, 0, w, h)
    g.dispose()
    val file = File(dir, "frame-${"%05d".format(index)}.png")
    ImageIO.write(img, "png", file)
    return file
  }

  @Test
  fun encodes_contiguous_frames_into_a_readable_multi_frame_gif() {
    val framesDir = tmp.newFolder("frames")
    val colors = listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW)
    val frames = colors.mapIndexed { i, c -> writeFrame(framesDir, i, c) }
    val out = File(tmp.newFolder("out"), "demo.gif")

    GifEncoder.encodeFromPngFrames(frames = frames, fps = 30, out = out)

    assertTrue("GIF file should exist", out.isFile)
    assertTrue("GIF should be non-empty", out.length() > 0)

    // Re-read the GIF and count the frames the imageio reader sees back out.
    val reader = ImageIO.getImageReadersByFormatName("gif").next()
    ImageIO.createImageInputStream(out).use { iis ->
      reader.input = iis
      assertEquals("GIF should round-trip every frame", colors.size, reader.getNumImages(true))
    }
    reader.dispose()
  }

  @Test
  fun single_frame_is_valid() {
    val framesDir = tmp.newFolder("frames")
    val frames = listOf(writeFrame(framesDir, 0, Color.MAGENTA))
    val out = File(tmp.newFolder("out"), "single.gif")

    GifEncoder.encodeFromPngFrames(frames = frames, fps = 24, out = out)

    assertTrue(out.isFile)
    assertTrue(out.length() > 0)
  }

  @Test
  fun empty_frame_list_is_rejected() {
    val out = File(tmp.newFolder("out"), "empty.gif")
    assertThrows(IllegalArgumentException::class.java) {
      GifEncoder.encodeFromPngFrames(frames = emptyList(), fps = 30, out = out)
    }
  }

  @Test
  fun out_of_range_fps_is_rejected() {
    val framesDir = tmp.newFolder("frames")
    val frames = listOf(writeFrame(framesDir, 0, Color.CYAN))
    val out = File(tmp.newFolder("out"), "badfps.gif")
    assertThrows(IllegalArgumentException::class.java) {
      GifEncoder.encodeFromPngFrames(frames = frames, fps = 0, out = out)
    }
  }
}
