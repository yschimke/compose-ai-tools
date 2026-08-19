package com.example.sampleandroid

import com.google.common.truth.Truth.assertThat
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import org.junit.Test

/**
 * End-to-end verification that `@SettledPreview` moves the shutter past a time-driven reveal on the
 * Android (Robolectric) lane — issue #4202.
 *
 * Reads the PNGs `:samples:android:composePreviewRenderAll` produced for the pair in
 * `SettledPreviews.kt`: the same composable rendered with and without the annotation. The unsettled
 * capture is asserted too, not just the settled one — it is what makes the pair evidence rather
 * than a screenshot, and it fails loudly if the settle ever becomes unconditional.
 */
class SettledPreviewPixelTest {

  private val rendersDir = File("build/compose-previews/renders")

  /** The fill [RevealCard] arrives at: the 72dp `Color(0xFF4CAF50)` disc at the frame's centre. */
  private val revealGreen = Triple(0x4C, 0xAF, 0x50)

  /** The container the reveal sits on, `Color(0xFF102027)`. */
  private val container = Triple(0x10, 0x20, 0x27)

  @Test
  fun `the unsettled capture is the bare container`() {
    val file = renderFile(rendersDir, "RevealCardUnsettledPreview_Reveal_unsettled")
    assertThat(file.exists()).isTrue()
    // Centre pixel is the container, not the disc: the reveal is still inside its delay.
    assertThat(centre(file)).isEqualTo(container)
  }

  @Test
  fun `the settled capture shows the arrived content`() {
    val file = renderFile(rendersDir, "RevealCardSettledPreview_Reveal_settled")
    assertThat(file.exists()).isTrue()
    assertThat(centre(file)).isEqualTo(revealGreen)
  }

  @Test
  fun `an explicit window settles a value that lands after first composition`() {
    val before = renderFile(rendersDir, "DeferredValueUnsettledPreview_Deferred_unsettled")
    val after = renderFile(rendersDir, "DeferredValueSettledPreview_Deferred_value")
    assertThat(before.exists()).isTrue()
    assertThat(after.exists()).isTrue()
    // The placeholder is a single em-dash glyph; the arrived value is ten characters of date. Count
    // dark pixels rather than read text: an order-of-magnitude gap needs no OCR to be decisive.
    assertThat(darkPixels(after)).isGreaterThan(darkPixels(before) * 5)
  }

  private fun centre(file: File): Triple<Int, Int, Int> {
    val img: BufferedImage = ImageIO.read(file)
    val argb = img.getRGB(img.width / 2, img.height / 2)
    return Triple((argb shr 16) and 0xff, (argb shr 8) and 0xff, argb and 0xff)
  }

  private fun darkPixels(file: File): Int {
    val img: BufferedImage = ImageIO.read(file)
    var count = 0
    for (y in 0 until img.height) for (x in 0 until img.width) {
      val argb = img.getRGB(x, y)
      val luminance =
        ((argb shr 16) and 0xff) * 0.299 + ((argb shr 8) and 0xff) * 0.587 + (argb and 0xff) * 0.114
      if (luminance < 128) count++
    }
    return count
  }
}
