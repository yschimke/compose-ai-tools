package com.example.sampleandroid

import com.google.common.truth.Truth.assertThat
import java.io.File
import javax.imageio.ImageIO
import org.junit.Test

/**
 * End-to-end verification that a `@Preview` with no `showBackground` / `backgroundColor` writes a
 * PNG with a real alpha channel — same contract Roborazzi gives consumers from
 * `RoborazziComposeOptions.background(showBackground = false, backgroundColor = 0)`.
 *
 * Reads the file produced by `:samples:android:composePreviewRenderAll` (wired into this module's `test`
 * task in `build.gradle.kts`) and pixel-asserts that the four corner pixels outside the circle
 * button are alpha=0 while the centre pixel is fully opaque. Without alpha preservation a
 * `Button(shape = CircleShape)` would sit on an opaque material-surface background and lose the
 * circle's transparent corners — that's the regression this test guards against.
 */
class TransparentBackgroundPreviewPixelTest {

    private val rendersDir = File("build/compose-previews/renders")
    private val pngName =
        "TransparentBackgroundPreviewsKt.CircleButtonTransparentPreview_Circle_Button_Transparent.png"

    @Test
    fun `circle button preview keeps alpha=0 in the corners outside the circle`() {
        val file = File(rendersDir, pngName)
        assertThat(file.exists()).isTrue()
        val img = ImageIO.read(file)

        // PNGs without an alpha channel decode as `TYPE_INT_RGB` and `getRGB`
        // would silently return 0xFF in the alpha slot. Assert explicitly so a
        // future regression that drops the alpha plane fails here instead of
        // sneaking past the per-pixel checks below.
        assertThat(img.colorModel.hasAlpha()).isTrue()

        val w = img.width
        val h = img.height
        // Sanity: density-default captures at widthDp/heightDp=100 produce well
        // over 40px square — guard against a zero-sized capture silently
        // passing the corner reads.
        assertThat(w).isAtLeast(40)
        assertThat(h).isAtLeast(40)

        // 60dp circle centred in a 100dp box: the four absolute corners are
        // ~20dp from the button on each axis, comfortably outside the circle.
        listOf(0 to 0, w - 1 to 0, 0 to h - 1, w - 1 to h - 1).forEach { (x, y) ->
            val alpha = (img.getRGB(x, y) ushr 24) and 0xff
            assertThat(alpha).isEqualTo(0)
        }

        // Centre of the image sits inside the button — must be fully opaque.
        val centreAlpha = (img.getRGB(w / 2, h / 2) ushr 24) and 0xff
        assertThat(centreAlpha).isEqualTo(0xff)
    }
}
