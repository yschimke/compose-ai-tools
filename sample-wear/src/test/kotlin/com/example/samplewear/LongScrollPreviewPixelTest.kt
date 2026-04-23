package com.example.samplewear

import com.google.common.truth.Truth.assertThat
import java.io.File
import javax.imageio.ImageIO
import org.junit.Test

/**
 * End-to-end regression for `@ScrollingPreview(modes = [LONG])` — drives the
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
        "build/compose-previews/renders/PreviewsKt.ActivityListLongPreview_Devices_-_Large_Round.png",
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

    /**
     * Regression for `@ScrollingPreview(reduceMotion = true)`: without Wear's
     * `LocalReduceMotion` being honoured, `TransformingLazyColumn` items at
     * viewport edges are captured mid-scale (≈0.55–0.70 of full width) and
     * reappear in the next slice as narrower ghost cards — the stitcher has
     * no way to collapse them. Every `TitleCard` in `LongActivityListScreen`
     * uses `fillMaxWidth()`, so in a correctly-rendered stitched PNG the
     * distinguishing "scaled but not tiny" width band should be sparsely
     * populated by antialiasing / EdgeButton curvature, not by whole cards.
     *
     * Measured on the fixed render: 4.2% of content rows fall in the
     * [0.40, 0.70) band. With reduceMotion disabled the same band jumps to
     * 19.5%. Gate at 10% — ~2.5× headroom above the passing value, ~2×
     * below the failing value.
     */
    @Test
    fun `LONG preview has no scaled-card ghost rows at slice seams`() {
        val img = ImageIO.read(longPng)
        val w = img.width
        val h = img.height

        var contentRows = 0
        var scaledCardRows = 0
        for (y in 0 until h) {
            var left = -1
            var right = -1
            for (x in 0 until w) {
                val argb = img.getRGB(x, y)
                val alpha = (argb ushr 24) and 0xff
                if (alpha == 0) continue // pill-clip transparent margins
                val r = (argb shr 16) and 0xff
                val g = (argb shr 8) and 0xff
                val b = argb and 0xff
                // Card surfaces / text / EdgeButton all read > 60 summed;
                // pure-black background reads 0.
                if (r + g + b > 60) {
                    if (left < 0) left = x
                    right = x
                }
            }
            if (left < 0) continue
            contentRows++
            val extent = (right - left + 1).toDouble() / w
            // [0.40, 0.70) isolates "mid-scale TLC items" — narrower than a
            // full-width card, wider than the EdgeButton's narrow band or
            // a card's rounded-corner top/bottom rows.
            if (extent >= 0.40 && extent < 0.70) scaledCardRows++
        }

        assertThat(contentRows).isGreaterThan(0)
        val scaledRatio = scaledCardRows.toDouble() / contentRows
        assertThat(scaledRatio).isLessThan(0.10)
    }

    /**
     * Regression for the EdgeButton reveal animation at the bottom of a
     * scroll-to-end stitch: without a post-scroll settle pass, the final
     * slice captures `ScreenScaffold`'s `EdgeButton` mid-reveal, and the
     * stitched PNG shows a narrow pill at the very bottom instead of the
     * fully-expanded "Start workout" button. Once the renderer advances
     * the paused `mainClock` enough for the expand spec to complete
     * (`settlePostScrollAnimations` in `handleLongCapture`), the bottom
     * band contains a near-full-width run of primary-coloured pixels.
     *
     * Measured on the fixed render: widest primary-colour run spans
     * ~85 % of the viewport width. With the settle disabled the same
     * run collapses to ~30 %. Gate at 0.60 — ~1.4× headroom above the
     * passing value, ~2× above the failing value.
     */
    @Test
    fun `LONG preview final frame shows fully-expanded EdgeButton`() {
        val img = ImageIO.read(longPng)
        val w = img.width
        val h = img.height

        // EdgeButton Large is ~46 dp tall ≈ 92 px at 2x density. Scan the
        // bottom 120 px so the whole button (plus the round-face pill
        // curvature below it) is in range regardless of exact Material3
        // sizing changes. For each row find the widest continuous run of
        // bright pixels; the expanded button sits as one wide horizontal
        // stripe, a mid-reveal button as a short centred pill.
        val scanFromY = (h - 120).coerceAtLeast(0)
        var maxRunWidth = 0
        for (y in scanFromY until h) {
            var bestRun = 0
            var currentRun = 0
            for (x in 0 until w) {
                val argb = img.getRGB(x, y)
                val alpha = (argb ushr 24) and 0xff
                if (alpha == 0) {
                    // Pill-clip curvature: gap in the run.
                    currentRun = 0
                    continue
                }
                val r = (argb shr 16) and 0xff
                val g = (argb shr 8) and 0xff
                val b = argb and 0xff
                // Button container is Material3 primary (bright, saturated);
                // scaffold background is pure black. Sum > 120 isolates the
                // coloured button from the 0-summed background and from any
                // dark card surfaces that might stray into the band.
                if (r + g + b > 120) {
                    currentRun++
                    if (currentRun > bestRun) bestRun = currentRun
                } else {
                    currentRun = 0
                }
            }
            if (bestRun > maxRunWidth) maxRunWidth = bestRun
        }

        val extent = maxRunWidth.toDouble() / w
        assertThat(extent).isGreaterThan(0.60)
    }
}
