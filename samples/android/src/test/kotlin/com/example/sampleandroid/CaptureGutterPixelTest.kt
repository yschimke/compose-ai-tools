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
}
