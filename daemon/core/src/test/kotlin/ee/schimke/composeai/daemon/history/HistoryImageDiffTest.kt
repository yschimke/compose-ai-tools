package ee.schimke.composeai.daemon.history

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryImageDiffTest {

  @Test
  fun identical_images_have_zero_pixel_diff_and_perfect_ssim() {
    val png = png(2, 2) { _, _ -> Color.BLUE.rgb }

    val diff = HistoryImageDiff.diff(png, png)

    assertEquals(0L, diff.diffPx)
    assertEquals(1.0, diff.ssim, 0.0)
    assertNotNull(diff.markedPng)
  }

  @Test
  fun alpha_only_changes_count_as_different_pixels() {
    val from = png(1, 1) { _, _ -> 0x00FF0000 }
    val to = png(1, 1) { _, _ -> 0xFFFF0000.toInt() }

    val diff = HistoryImageDiff.diff(from, to)

    assertEquals(1L, diff.diffPx)
    assertTrue("SSIM should move when alpha changes", diff.ssim < 1.0)
  }

  @Test
  fun mismatched_dimensions_return_everything_changed_without_overlay() {
    val diff = HistoryImageDiff.diff(png(2, 2), png(3, 1))

    assertEquals(4L, diff.diffPx)
    assertEquals(0.0, diff.ssim, 0.0)
    assertNull(diff.markedPng)
  }

  @Test
  fun result_uses_structural_byte_array_equality() {
    val a = HistoryImageDiff.Result(1, 0.5, byteArrayOf(1, 2, 3))
    val b = HistoryImageDiff.Result(1, 0.5, byteArrayOf(1, 2, 3))

    assertEquals(a, b)
    assertEquals(a.hashCode(), b.hashCode())
    assertArrayEquals(a.markedPng, b.markedPng)
  }

  @Test
  fun undecodable_bytes_throw_typed_exception() {
    assertThrows(HistoryImageDiff.UndecodableImageException::class.java) {
      HistoryImageDiff.diff("nope".toByteArray(), png(1, 1))
    }
  }

  private fun png(
    width: Int,
    height: Int,
    argb: (Int, Int) -> Int = { _, _ -> Color.RED.rgb },
  ): ByteArray {
    val img = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    for (y in 0 until height) {
      for (x in 0 until width) {
        img.setRGB(x, y, argb(x, y))
      }
    }
    return ByteArrayOutputStream().use { out ->
      ImageIO.write(img, "png", out)
      out.toByteArray()
    }
  }
}
