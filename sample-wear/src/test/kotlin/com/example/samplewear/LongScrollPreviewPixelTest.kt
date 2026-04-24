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
     * Regression guard for the intermittent "ghost peek pill" bug
     * (investigated alongside #170). In flaky runs the stitched output
     * contains one or more narrow muted grey/purple pills — the peek
     * state of `EdgeButton` that `ScreenScaffold` pins to the bottom of
     * every intermediate slice — painted into the scrolling-content
     * region of the stitch, ABOVE the fully-revealed "Start workout"
     * EdgeButton at the true bottom.
     *
     * Mechanism: each intermediate slice has the peek pill at the same
     * `y` within the slice (it is pinned to the viewport, not scrolled
     * with the list). The stitcher paints only the bottom `d` rows of
     * every slice ≥ 1 at a shifted destination `y`, so if the peek pill
     * falls inside that bottom band the pill lands in the middle of the
     * stitched output. `stitchSlicesWithFinalFrame`'s final-frame
     * overwrite only covers the last `finalH` rows, so ghosts painted
     * from earlier slices are never reached.
     *
     * This test looks for the signature: a narrow, centred run of
     * muted-grey pixels anywhere ABOVE the real EdgeButton band. The
     * real EdgeButton is Wear Material3 primary (bright, saturated,
     * spans most of the viewport width); the peek pill is a dim
     * ~90–100 px wide oval, centred horizontally, with mean luminance
     * well below the primary colour.
     */
    @Test
    fun `LONG preview has no ghost peek-pill rows above the EdgeButton`() {
        val img = ImageIO.read(longPng)
        val w = img.width
        val h = img.height

        // Locate the topmost row of the real EdgeButton band. The band is
        // the first wide (> 40 % width) run of bright/saturated-purple
        // pixels scanning from top to bottom after we've already cleared
        // the list content region.
        val edgeButtonTop = (0 until h).firstOrNull { y ->
            val (extent, avg) = brightCentredRun(img, y)
            extent > w * 0.40 && avg > 400 // primary is bright on all channels
        } ?: h

        // Scan everything strictly above the EdgeButton band for the ghost
        // signature: narrow (8–110 px), centred (± w/8 of centre), with a
        // mean colour that is content-ish but distinctly DIMMER than the
        // real EdgeButton (sum of RGB channels < 420 — well below the
        // ~670 of Wear Material3 primary, above the ~0 of pure black
        // background).
        val ghostRows = mutableListOf<Int>()
        for (y in 0 until edgeButtonTop) {
            val row = extractCentredPillRow(img, y)
            if (row != null) ghostRows += y
        }

        // Allow a tiny budget for AA fringing / unrelated chrome at
        // extreme top (TimeText curvature), but the Wear long preview
        // should produce zero peek-pill ghost rows in a correct stitch.
        assertThat(ghostRows).isEmpty()
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

    /**
     * Widest continuous horizontal run of bright visible pixels on row [y],
     * returned as `(extent, averageChannelSum)` where `averageChannelSum`
     * is the mean of `r + g + b` across the run (0..765). Transparent pill-
     * clip pixels (alpha = 0) break the run, matching the shape of a
     * Wear round-device capsule mask. Used to locate the real EdgeButton
     * band in the stitched output.
     */
    private fun brightCentredRun(
        img: java.awt.image.BufferedImage,
        y: Int,
    ): Pair<Int, Int> {
        val w = img.width
        var bestExtent = 0
        var bestSum = 0L
        var run = 0
        var runSum = 0L
        for (x in 0 until w) {
            val argb = img.getRGB(x, y)
            val alpha = (argb ushr 24) and 0xff
            if (alpha == 0) {
                run = 0
                runSum = 0L
                continue
            }
            val r = (argb shr 16) and 0xff
            val g = (argb shr 8) and 0xff
            val b = argb and 0xff
            val s = r + g + b
            // Only consider pixels that plausibly belong to a coloured
            // element (not the black scaffold background).
            if (s > 150) {
                run++
                runSum += s
                if (run > bestExtent) {
                    bestExtent = run
                    bestSum = runSum
                }
            } else {
                run = 0
                runSum = 0L
            }
        }
        val avg = if (bestExtent == 0) 0 else (bestSum / bestExtent).toInt()
        return bestExtent to avg
    }

    /**
     * Returns the first (x0, x1, avgRgbSum) tuple describing a centred
     * "peek pill" run on row [y], or `null` if none qualifies. A peek
     * pill of `EdgeButton` — the ghost-flake signature — has:
     *  - extent ∈ [8, 110] px (too narrow to be a card, too wide to be
     *    AA on a single icon edge);
     *  - centred within ± w/8 of the image centre;
     *  - mean `r + g + b` in `[60, 420]` — darker than the Wear
     *    Material3 primary-coloured EdgeButton (~670) and brighter than
     *    pure background (0);
     *  - a distinct purple cast (`B − G ≥ 5`). This filters out
     *    neutral-grey chrome like `TimeText` (`B == G == R`) that can
     *    also present as a narrow centred run near the top of the
     *    stitched image but is not the flake.
     */
    private fun extractCentredPillRow(
        img: java.awt.image.BufferedImage,
        y: Int,
    ): Triple<Int, Int, Int>? {
        val w = img.width
        var first = -1
        var last = -1
        var sumS = 0L
        var sumG = 0L
        var sumB = 0L
        var count = 0
        for (x in 0 until w) {
            val argb = img.getRGB(x, y)
            val alpha = (argb ushr 24) and 0xff
            if (alpha == 0) continue
            val r = (argb shr 16) and 0xff
            val g = (argb shr 8) and 0xff
            val b = argb and 0xff
            val s = r + g + b
            if (s > 150) {
                if (first < 0) first = x
                last = x
                sumS += s
                sumG += g
                sumB += b
                count++
            }
        }
        if (first < 0 || count == 0) return null
        val extent = last - first + 1
        if (extent !in 8..110) return null
        val centre = (first + last) / 2
        if (Math.abs(centre - w / 2) > w / 8) return null
        val avg = (sumS / count).toInt()
        if (avg !in 60..420) return null
        val purpleCast = (sumB - sumG) / count.toDouble()
        if (purpleCast < 5.0) return null
        return Triple(first, last, avg)
    }
}
