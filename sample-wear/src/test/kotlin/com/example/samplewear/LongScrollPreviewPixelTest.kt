package com.example.samplewear

import com.google.common.truth.Truth.assertThat
import java.io.File
import javax.imageio.ImageIO
import org.junit.Test

/**
 * End-to-end regression for `@ScrollingPreview(mode = LONG)` — drives the
 * renderer to produce a stitched tall PNG of the `ActivityListLongPreview`
 * (`TransformingLazyColumn` + `EdgeButton`) and asserts:
 *  - Output height > viewport height (proves multi-slice stitching ran,
 *    not a single-frame fallback).
 *  - Pixels in the top / middle / bottom thirds differ — proves real
 *    scroll-through content rather than the same frame repeated.
 *
 * `testDebugUnitTest.dependsOn("renderAllPreviews")` in sample-wear's
 * build.gradle.kts guarantees the PNG exists by the time this test runs.
 */
class LongScrollPreviewPixelTest {

    private val longPng = File(
        "build/compose-previews/renders/com.example.samplewear.PreviewsKt.ActivityListLongPreview_Devices - Large Round.png",
    )

    @Test
    fun `LONG preview produces a tall stitched PNG`() {
        assertThat(longPng.exists()).isTrue()
        val img = ImageIO.read(longPng)
        // Large round Wear device: 227dp at 2x density ≈ 454 px. LONG output
        // for 15 items is consistently > 2 viewports tall.
        val viewportPx = 454
        assertThat(img.height).isGreaterThan(viewportPx * 2)
    }

    @Test
    fun `LONG preview shows different content across vertical thirds`() {
        val img = ImageIO.read(longPng)
        val w = img.width
        val h = img.height

        fun meanRgbFor(startRow: Int, endRow: Int): Triple<Double, Double, Double> {
            var r = 0L
            var g = 0L
            var b = 0L
            var count = 0L
            for (y in startRow until endRow) for (x in 0 until w) {
                val argb = img.getRGB(x, y)
                val a = (argb ushr 24) and 0xff
                if (a == 0) continue // skip transparent pill-clip regions
                r += (argb shr 16) and 0xff
                g += (argb shr 8) and 0xff
                b += argb and 0xff
                count++
            }
            val n = count.coerceAtLeast(1L).toDouble()
            return Triple(r / n, g / n, b / n)
        }

        val top = meanRgbFor(0, h / 3)
        val mid = meanRgbFor(h / 3, 2 * h / 3)
        val bot = meanRgbFor(2 * h / 3, h)

        fun distance(a: Triple<Double, Double, Double>, b: Triple<Double, Double, Double>): Double {
            val dr = a.first - b.first
            val dg = a.second - b.second
            val db = a.third - b.third
            return dr * dr + dg * dg + db * db
        }

        // Dark-card-on-black content keeps mean RGB values close, so the
        // bar is deliberately low — we just need to prove the stitch produced
        // more than one distinct frame. Any two thirds differing at all
        // catches a repeat-the-same-frame regression; the full bottom→top
        // stretch is the strongest signal because the bottom contains the
        // light-purple EdgeButton that isn't present at the top.
        assertThat(distance(top, bot)).isGreaterThan(5.0)
        assertThat(distance(top, mid)).isGreaterThan(0.5)
        assertThat(distance(mid, bot)).isGreaterThan(0.5)
    }
}
