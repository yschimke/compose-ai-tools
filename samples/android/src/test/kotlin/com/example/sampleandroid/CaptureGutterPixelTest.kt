package com.example.sampleandroid

import com.google.common.truth.Truth.assertThat
import java.io.File
import javax.imageio.ImageIO
import org.junit.Test

/**
 * End-to-end verification that `@CaptureGutter` extends the capture bounds on the Android
 * (Robolectric) lane, and extends them by exactly the declared gutter — m3-catalog#179.
 *
 * Reads the PNGs `:samples:android:composePreviewRenderAll` produced for the pair in
 * `CaptureGutterPreviews.kt`: one composable, rendered with and without the annotation. Asserting
 * the *difference* rather than either canvas on its own is what makes this a regression test for
 * the promise the annotation makes — the component measures the same and only the canvas grows, so
 * a gutter that shrank the component (padding inside the tree) would leave the two canvases the
 * same size and fail here.
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
   * The promise, at a fractional density: a `fillMaxWidth` child on a fixed 400dp frame measures
   * the *same pixels* with and without a gutter.
   *
   * At 2.625 the window grows by a dp figure that doesn't divide evenly into the rounded per-edge
   * pixels, so deriving the child's viewport by subtracting those edges from the enlarged window
   * loses a pixel — enough for fill-width content to lay out differently from the un-guttered
   * render. The green band's drawn width is the component's own measure, so comparing it across the
   * pair is the assertion that the gutter changed the canvas and nothing else.
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
