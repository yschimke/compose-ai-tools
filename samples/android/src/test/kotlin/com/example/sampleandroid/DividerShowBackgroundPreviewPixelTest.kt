package com.example.sampleandroid

import com.google.common.truth.Truth.assertThat
import java.io.File
import javax.imageio.ImageIO
import org.junit.Test

/**
 * End-to-end verification for issue #2974: a dark `@Preview(showBackground = true)` whose only drawn
 * child is a thin divider centred in a taller fixed-size `Box` fills the **whole** preview with the
 * dark backing colour, not just the divider's bounds.
 *
 * Reads the PNG produced by `:samples:android:composePreviewRenderAll` (wired into this module's
 * `test` task in `build.gradle.kts`) and asserts every corner — well outside the centred divider —
 * is the opaque Material dark surface (`#1C1B1F`, `PreviewBackground.NIGHT_ARGB`). This is the PNG
 * half of the PNG/SVG parity the layered-SVG export must match: the SVG background rect has to cover
 * the same full crop these pixels do (asserted against the SVG in
 * `FigmaSvgShowBackgroundBoundsRenderTest`).
 */
class DividerShowBackgroundPreviewPixelTest {

  private val rendersDir = File("build/compose-previews/renders")
  private val pngName = "DividerShowBackgroundDarkPreview_Divider_Dark.png"

  /** `PreviewBackground.NIGHT_ARGB` (`#1C1B1F`) — Material 3's dark surface. */
  private val nightRgb = 0x1C1B1F

  @Test
  fun `dark showBackground divider preview fills the whole crop with the dark surface`() {
    val file = File(rendersDir, pngName)
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
