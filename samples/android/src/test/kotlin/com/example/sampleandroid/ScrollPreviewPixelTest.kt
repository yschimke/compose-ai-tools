package com.example.sampleandroid

import com.google.common.truth.Truth.assertThat
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import org.junit.Test

/**
 * End-to-end check that `@ScrollingPreview(modes = [TOP, END])` produces two distinct captures (top
 * red, end blue) from one preview, reading the PNGs `:samples:android:composePreviewRenderAll`
 * renders before `test`.
 */
class ScrollPreviewPixelTest {

  private val rendersDir = File("build/compose-previews/renders")
  // A GIF scroll capture is a data product, not a `renders/` sibling — see the
  // `render/scroll/gif` kind in the manifest.
  private val scrollGifDir = File("build/compose-previews/data/render-scroll-gif")
  private val baseName = "RedToBlueScrollPreview_Scroll"

  private data class Avg(val r: Double, val g: Double, val b: Double) {
    fun dominant(): Char =
      when {
        r > g && r > b -> 'R'
        g > r && g > b -> 'G'
        else -> 'B'
      }
  }

  private fun averageColor(file: File): Avg {
    val img = ImageIO.read(file)
    var rs = 0L
    var gs = 0L
    var bs = 0L
    val w = img.width
    val h = img.height
    for (y in 0 until h) for (x in 0 until w) {
      val argb = img.getRGB(x, y)
      rs += (argb shr 16) and 0xff
      gs += (argb shr 8) and 0xff
      bs += argb and 0xff
    }
    val n = (w * h).toDouble()
    return Avg(rs / n, gs / n, bs / n)
  }

  @Test
  fun `TOP capture is red-dominant`() {
    val file = renderFile(rendersDir, baseName, "_SCROLL_top")
    assertThat(file.exists()).isTrue()
    val avg = averageColor(file)
    assertThat(avg.dominant()).isEqualTo('R')
    assertThat(avg.r).isGreaterThan(150.0)
    assertThat(avg.b).isLessThan(120.0)
  }

  @Test
  fun `END capture is blue-dominant`() {
    val file = renderFile(rendersDir, baseName, "_SCROLL_end")
    assertThat(file.exists()).isTrue()
    val avg = averageColor(file)
    // If scroll-to-end silently fails, the image is red-dominant — this
    // is exactly the regression this test guards against.
    assertThat(avg.dominant()).isEqualTo('B')
    assertThat(avg.b).isGreaterThan(150.0)
    assertThat(avg.r).isLessThan(120.0)
  }

  /**
   * The [ScrollMode.GIF] pipeline end to end: frames captured, encoded as a looping GIF, and
   * decodable by `ImageIO`. Single-mode, so the file has no `_SCROLL_gif` suffix.
   */
  @Test
  fun `GIF capture animates red to blue`() {
    val file = renderFile(scrollGifDir, "RedToBlueScrollGifPreview_ScrollGif", ext = "gif")
    assertThat(file.exists()).isTrue()

    val frames = readGifFrames(file)
    // Need at least two frames for an animation, and the driver should
    // typically produce many more (several per viewport).
    assertThat(frames.size).isAtLeast(2)

    val first = avgOfImage(frames.first())
    val last = avgOfImage(frames.last())
    // Frame 0 is the unscrolled top → red-dominant.
    assertThat(first.dominant()).isEqualTo('R')
    // Final frame is at (or near) the end → blue-dominant. Wider
    // tolerances than the PNG END test because GIF quantisation shifts
    // per-channel averages by a few points.
    assertThat(last.dominant()).isEqualTo('B')
    assertThat(last.b).isGreaterThan(130.0)
    assertThat(last.r).isLessThan(140.0)
  }

  /**
   * Regression guard: with `modes = [END, GIF]` sharing one composition, the GIF must scroll back
   * to the top first, so frame 0 is red-dominant.
   */
  @Test
  fun `GIF capture following END resets scroll and still animates`() {
    val base = "RedToBlueEndThenGifPreview_EndThenGif"
    // END is the only `renders/` capture here, so it carries no `_SCROLL_end` suffix; the GIF
    // sibling moves to the scroll data-product directory.
    val endPng = renderFile(rendersDir, base)
    val gif = renderFile(scrollGifDir, base, "_SCROLL_gif", ext = "gif")
    assertThat(endPng.exists()).isTrue()
    assertThat(gif.exists()).isTrue()

    // END still lands on the bottom of the gradient (sanity check the
    // earlier capture wasn't accidentally disturbed by the reset).
    val endAvg = averageColor(endPng)
    assertThat(endAvg.dominant()).isEqualTo('B')

    val frames = readGifFrames(gif)
    // The driver should produce many frames per viewport scrolled;
    // a 1-frame GIF is the pre-fix regression signature.
    assertThat(frames.size).isAtLeast(2)

    val first = avgOfImage(frames.first())
    val last = avgOfImage(frames.last())
    // Pre-fix: `first` was blue-dominant because the GIF started from
    // wherever END left the scrollable.
    assertThat(first.dominant()).isEqualTo('R')
    assertThat(last.dominant()).isEqualTo('B')
  }

  private fun avgOfImage(img: BufferedImage): Avg {
    var rs = 0L
    var gs = 0L
    var bs = 0L
    val w = img.width
    val h = img.height
    for (y in 0 until h) for (x in 0 until w) {
      val argb = img.getRGB(x, y)
      rs += (argb shr 16) and 0xff
      gs += (argb shr 8) and 0xff
      bs += argb and 0xff
    }
    val n = (w * h).toDouble()
    return Avg(rs / n, gs / n, bs / n)
  }

  /**
   * Reads every frame of an animated GIF with the standard `javax.imageio` reader, doubling as a
   * round-trip check on [ScrollGifEncoder]'s metadata.
   */
  private fun readGifFrames(file: File): List<BufferedImage> {
    val reader = ImageIO.getImageReadersByFormatName("gif").next()
    javax.imageio.stream.FileImageInputStream(file).use { input ->
      reader.input = input
      val count = reader.getNumImages(true)
      return List(count) { reader.read(it) }
    }
  }
}
