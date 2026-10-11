package com.example.sampleandroid

import com.google.common.truth.Truth.assertThat
import java.io.File
import javax.imageio.ImageIO
import org.junit.Test

/**
 * `@CaptureGutter` extends the capture bounds on the Android lane by exactly the declared gutter.
 * Compares the pair in `CaptureGutterPreviews.kt`: the canvas must grow while the component doesn't
 * (a gutter applied as inner padding would fail).
 */
class CaptureGutterPixelTest {

  private val rendersDir = File("build/compose-previews/renders")

  /** `:samples:android` renders at the AS phone default density. */
  private val density = 2.625f

  private fun size(file: File): Pair<Int, Int> {
    assertThat(file.exists()).isTrue()
    val img = ImageIO.read(file)
    return img.width to img.height
  }

  @Test
  fun `the gutter grows the canvas by exactly the declared dp on each edge`() {
    val (bareW, bareH) = size(renderFile(rendersDir, "ShadowStickerCroppedPreview_Shadow_cropped"))
    val (gutW, gutH) = size(renderFile(rendersDir, "ShadowStickerGutteredPreview_Shadow_guttered"))

    // `@CaptureGutter(all = 4, bottom = 5)`: 4dp start + 4dp end horizontally, 4dp top + 5dp
    // bottom vertically, each edge rounded independently at the render density.
    val edge4 = Math.round(4 * density)
    val edge5 = Math.round(5 * density)
    assertThat(gutW - bareW).isEqualTo(edge4 * 2)
    assertThat(gutH - bareH).isEqualTo(edge4 + edge5)
  }

  /**
   * At a fractional density (2.625), a `fillMaxWidth` child on a fixed 400dp frame must measure the
   * same pixels with and without a gutter; deriving the viewport by subtracting rounded edges would
   * lose a pixel.
   */
  @Test
  fun `a fill-width component measures identically with and without a gutter`() {
    val bare = drawnWidth(renderFile(rendersDir, "FillWidthCroppedPreview_Fill_fixed"))
    val guttered =
      drawnWidth(renderFile(rendersDir, "FillWidthGutteredPreview_Fill_fixed_guttered"))
    assertThat(guttered).isEqualTo(bare)
  }

  /** Width of the drawn (non-white) band — the fill-width component's own measured extent. */
  private fun drawnWidth(file: File): Int {
    assertThat(file.exists()).isTrue()
    val img = ImageIO.read(file)
    val y = img.height / 3
    var minX = img.width
    var maxX = -1
    for (x in 0 until img.width) {
      if ((img.getRGB(x, y) and 0xFFFFFF) == 0xFFFFFF) continue
      if (x < minX) minX = x
      if (x > maxX) maxX = x
    }
    check(maxX >= 0) { "row $y of ${file.name} is entirely background" }
    return maxX - minX + 1
  }
}
