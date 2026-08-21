package ee.schimke.composeai.daemon

import java.awt.image.BufferedImage
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The crop runs on every frame of a wrap-content live preview, so what it does when there is
 * *nothing to crop* matters as much as what it does when there is (#4283): a `fillMax*` composable
 * measures the full window on both axes, and the old shape still decoded and inspected the whole
 * image to discover that. Now it asks the PNG header first.
 *
 * Byte-identity is the assertion for the no-op cases rather than a size comparison — a re-encode
 * that happened to round-trip the same pixels would pass the latter and still have cost the decode.
 */
class WrappedFrameCropTest {

  @Test
  fun cropsAWrappedAxisToTheMeasuredSize() {
    val file = pngFile(400, 300)
    WrappedFrameCrop.cropTopLeft(file, wrapWidth = true, wrapHeight = true, 165, 136)
    assertEquals(165 to 136, sizeOf(file))
  }

  @Test
  fun cropsOnlyTheWrappedAxis() {
    val file = pngFile(400, 300)
    WrappedFrameCrop.cropTopLeft(file, wrapWidth = false, wrapHeight = true, 165, 136)
    assertEquals(400 to 136, sizeOf(file))
  }

  @Test
  fun leavesAFullWindowFrameByteIdentical() {
    // The `fillMax*` case: measured == captured on both axes, so there is nothing to do and the
    // file must not be rewritten.
    val file = pngFile(400, 300)
    val before = file.readBytes()
    WrappedFrameCrop.cropTopLeft(file, wrapWidth = true, wrapHeight = true, 400, 300)
    assertArrayEquals(before, file.readBytes())
  }

  @Test
  fun leavesAFrameWithNoMeasuredSizeByteIdentical() {
    // Nothing recorded the measurement (`<= 0`): the captured frame is all we have.
    val file = pngFile(400, 300)
    val before = file.readBytes()
    WrappedFrameCrop.cropTopLeft(file, wrapWidth = true, wrapHeight = true, 0, 0)
    assertArrayEquals(before, file.readBytes())
  }

  @Test
  fun leavesAnUnwrappedFrameAndAMissingFileAlone() {
    val file = pngFile(400, 300)
    val before = file.readBytes()
    WrappedFrameCrop.cropTopLeft(file, wrapWidth = false, wrapHeight = false, 10, 10)
    assertArrayEquals(before, file.readBytes())

    val missing = File(file.parentFile, "gone.png")
    WrappedFrameCrop.cropTopLeft(missing, wrapWidth = true, wrapHeight = true, 10, 10)
    assertEquals(false, missing.exists())
  }

  @Test
  fun anUnreadableFileFallsBackToTheDecodePathAndSurvivesIt() {
    // Not a PNG at all: the header probe can't answer, the decode returns null, and the crop is a
    // no-op instead of an exception on the live frame loop.
    val file = File.createTempFile("frame", ".png").also { it.deleteOnExit() }
    file.writeText("not an image")
    WrappedFrameCrop.cropTopLeft(file, wrapWidth = true, wrapHeight = true, 10, 10)
    assertEquals("not an image", file.readText())
  }

  private fun sizeOf(file: File): Pair<Int, Int> =
    javax.imageio.ImageIO.read(file).let { it.width to it.height }

  private fun pngFile(width: Int, height: Int): File {
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    for (x in 0 until width) for (y in 0 until height) image.setRGB(x, y, 0xFF00FF00.toInt())
    return File.createTempFile("frame", ".png").also {
      it.deleteOnExit()
      javax.imageio.ImageIO.write(image, "PNG", it)
    }
  }
}
