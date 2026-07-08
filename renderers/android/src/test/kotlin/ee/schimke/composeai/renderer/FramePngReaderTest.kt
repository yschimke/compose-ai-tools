package ee.schimke.composeai.renderer

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM cover for [FramePngReader] — no Robolectric, so it runs as a plain JUnit test. Pins the
 * diagnosis the animated-capture paths emit when a per-frame PNG is empty / not-a-PNG / truncated,
 * the failure mode behind the `composePreviewRenderAll` "render produced no output file" flake on
 * the heavy AGSL animated previews.
 */
class FramePngReaderTest {

  private fun newPng(width: Int, height: Int): File {
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    val file = File.createTempFile("frame_", ".png")
    file.deleteOnExit()
    ImageIO.write(image, "png", file)
    return file
  }

  private fun tempFile(prefix: String, bytes: ByteArray): File {
    val file = File.createTempFile(prefix, ".png")
    file.deleteOnExit()
    file.writeBytes(bytes)
    return file
  }

  private fun expectError(block: () -> Unit): IllegalStateException {
    try {
      block()
    } catch (e: IllegalStateException) {
      return e
    }
    throw AssertionError("expected IllegalStateException, but nothing was thrown")
  }

  @Test
  fun `decodes a well-formed frame`() {
    val decoded = FramePngReader.decode(newPng(8, 6), role = "animation")
    assertEquals(8, decoded.width)
    assertEquals(6, decoded.height)
  }

  @Test
  fun `empty frame is reported as a write failure naming the role`() {
    val file = tempFile("frame_empty_", ByteArray(0))
    val error = expectError { FramePngReader.decode(file, role = "animation") }
    assertTrue(error.message, error.message!!.contains("empty"))
    assertTrue(error.message, error.message!!.contains("animation"))
  }

  @Test
  fun `truncated frame is reported as a partial write, not a corrupt read`() {
    // A signature-valid PNG with its trailing IEND chunk lopped off — what a
    // half-finished captureRoboImage write leaves on disk.
    val full = newPng(16, 16).readBytes()
    val truncated = full.copyOf(full.size - 12)
    val file = tempFile("frame_trunc_", truncated)
    val error = expectError { FramePngReader.decode(file, role = "scroll GIF") }
    assertTrue(error.message, error.message!!.contains("truncated"))
    assertTrue(error.message, error.message!!.contains("IEND"))
    assertTrue(error.message, error.message!!.contains("scroll GIF"))
  }

  @Test
  fun `non-png is reported as a missing signature`() {
    val file = tempFile("frame_notpng_", "not a png".toByteArray())
    val error = expectError { FramePngReader.decode(file, role = "focus GIF") }
    assertTrue(error.message, error.message!!.contains("not a PNG"))
  }
}
