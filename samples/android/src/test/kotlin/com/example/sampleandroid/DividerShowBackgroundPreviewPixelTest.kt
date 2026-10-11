package com.example.sampleandroid

import com.google.common.truth.Truth.assertThat
import java.io.File
import javax.imageio.ImageIO
import org.junit.Test

/**
 * A dark `@Preview(showBackground = true)` with a thin divider in a taller `Box` must fill the
 * whole preview with the backing colour: every corner is `#1C1B1F`
 * (`PreviewBackground.NIGHT_ARGB`). The SVG half of this parity is
 * `FigmaSvgShowBackgroundBoundsRenderTest`.
 */
class DividerShowBackgroundPreviewPixelTest {

  private val rendersDir = File("build/compose-previews/renders")
  private val pngStem = "DividerShowBackgroundDarkPreview_Divider_Dark"

  /** `PreviewBackground.NIGHT_ARGB` (`#1C1B1F`) — Material 3's dark surface. */
  private val nightRgb = 0x1C1B1F

  @Test
  fun `dark showBackground divider preview fills the whole crop with the dark surface`() {
    val file = renderFile(rendersDir, pngStem)
    assertThat(file.exists()).isTrue()
    val img = ImageIO.read(file)

    val w = img.width
    val h = img.height
    // 100×26dp fixed Box: guard against a zero-/thin-sized capture silently passing the reads
    // below (the bug shrank the SVG canvas to the ~1px divider — the PNG never had that problem,
    // but a broken render should still fail loudly here rather than read an empty image).
    assertThat(w).isAtLeast(40)
    assertThat(h).isAtLeast(10)

    // Every corner sits above/below the centred hairline divider, so each must be the opaque dark
    // backing — this is the "full background coverage" the divider used to strand as transparency
    // in the SVG.
    listOf(0 to 0, w - 1 to 0, 0 to h - 1, w - 1 to h - 1).forEach { (x, y) ->
      val argb = img.getRGB(x, y)
      val alpha = (argb ushr 24) and 0xff
      assertThat(alpha).isEqualTo(0xff)
      assertThat(argb and 0xffffff).isEqualTo(nightRgb)
    }
  }
}
