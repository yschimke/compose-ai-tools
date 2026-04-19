package com.example.sampleandroid

import com.google.common.truth.Truth.assertThat
import java.io.File
import javax.imageio.ImageIO
import org.junit.Test

/**
 * End-to-end verification that `@ScrollingPreview(modes = [TOP, END])`
 * produces two distinct captures from one preview function: an unscrolled
 * top frame and a scrolled-to-end frame. Reads the PNGs produced by
 * `:sample-android:renderAllPreviews` and asserts colour dominance matches
 * the expected top (red) / bottom (blue) of [RedToBlueList].
 *
 * The `renderAllPreviews` task is wired into this module's `test` task
 * dependency graph in build.gradle.kts so running `:sample-android:test` (or
 * `:check`) renders the PNGs first.
 */
class ScrollPreviewPixelTest {

    private val rendersDir = File("build/compose-previews/renders")
    private val baseName = "com.example.sampleandroid.ScrollPreviewsKt.RedToBlueScrollPreview_Scroll"

    private data class Avg(val r: Double, val g: Double, val b: Double) {
        fun dominant(): Char = when {
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
        val file = File(rendersDir, "${baseName}_SCROLL_top.png")
        assertThat(file.exists()).isTrue()
        val avg = averageColor(file)
        assertThat(avg.dominant()).isEqualTo('R')
        assertThat(avg.r).isGreaterThan(150.0)
        assertThat(avg.b).isLessThan(120.0)
    }

    @Test
    fun `END capture is blue-dominant`() {
        val file = File(rendersDir, "${baseName}_SCROLL_end.png")
        assertThat(file.exists()).isTrue()
        val avg = averageColor(file)
        // If scroll-to-end silently fails, the image is red-dominant — this
        // is exactly the regression this test guards against.
        assertThat(avg.dominant()).isEqualTo('B')
        assertThat(avg.b).isGreaterThan(150.0)
        assertThat(avg.r).isLessThan(120.0)
    }
}
