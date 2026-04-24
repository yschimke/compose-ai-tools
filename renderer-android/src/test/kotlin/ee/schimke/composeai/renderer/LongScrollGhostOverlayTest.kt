package ee.schimke.composeai.renderer

import java.awt.Color
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Pure-JVM regression for the "ghost overlay pill" flakiness that has shown
 * up intermittently on `@ScrollingPreview(LONG)` outputs (e.g. Wear's
 * `ActivityListLongPreview` — the small grey EdgeButton "peek" ellipsis
 * showing up as a phantom pill above the real EdgeButton in a subset of
 * stitched renders).
 *
 * Diagnosis from a diff of a known-bad vs known-good render:
 *
 * ```
 * before.png (bad):
 *   rows 2100–2195  card body "Sleep day 15"
 *   rows 2196–2227  background gap
 *   rows 2228–2261  GHOST PILL (EdgeButton peek, ~94×34 px, centred)
 *   rows 2262–2267  background gap
 *   rows 2268+      full "Start workout" EdgeButton
 * after.png (good):
 *   rows 2100–2195  card body "Sleep day 15"
 *   rows 2196–2227  background gap
 *   rows 2228+      full "Start workout" EdgeButton (no ghost)
 * ```
 *
 * The 40-px height difference between good and bad output is exactly the
 * 33 pill rows + 7 gap rows inserted above the final EdgeButton.
 *
 * Mechanism: `ScreenScaffold` keeps an EdgeButton "peek pill" pinned to the
 * bottom of the viewport while the list is mid-scroll; at scroll-end the
 * scaffold grows that slot into a full-width button. The pill sits at the
 * SAME `y` within every intermediate slice (it's pinned, not scrolled).
 * When the stitcher paints slice N's bottom `d_N` rows at a shifted
 * destination `y`, a pill captured in the bottom of that slice's painted
 * band lands inside the scrolling-content region of the final stitch — it
 * is not overwritten by the next slice (whose own pinned pill is painted
 * lower) and it is not overwritten by `stitchSlicesWithFinalFrame`'s
 * final-frame band (which only covers the last-viewport region of the
 * output, ~topH − finalH..topH).
 *
 * This test reproduces that exact shape with synthetic slices so the bug
 * is deterministically demonstrable without Robolectric / Wear Compose.
 * Today it documents the broken state by asserting ghost rows are
 * observed; when the fix lands the stitcher will produce zero ghost rows
 * and the implementer should flip the assertion to the regression-guard
 * form (`assertThat(ghostRows).isEmpty()`).
 *
 * Fix avenues (for whoever picks this up):
 *  1. In [buildStitchedImage], before painting each slice `i ≥ 1`, detect
 *     the pinned-bottom region by comparing slice `i` against slice
 *     `i − 1` at identical `y` (rows that match despite different scroll
 *     positions are scroll-independent chrome). Mask those rows to
 *     transparent in the copy used for painting — the final-frame
 *     overlay restores the real chrome at the bottom.
 *  2. Alternatively, in [stitchSlicesWithFinalFrame], extend the
 *     final-frame band upwards to cover every slice boundary's tail, not
 *     only the last viewport.
 *  3. On the sample side: hide `EdgeButton`'s peek state during stitched
 *     captures by reading `LocalScrollCaptureInProgress` in the
 *     `edgeButton` slot, the same pattern already used for the scroll
 *     indicator. That avoids the bug without a stitcher change but
 *     requires every sample / consumer of `@ScrollingPreview(LONG)` on
 *     Wear to opt in.
 */
class LongScrollGhostOverlayTest {
    @get:Rule
    val tmp: TemporaryFolder = TemporaryFolder()

    /**
     * Reproduces the ghost-pill signature deterministically.
     *
     * Scene:
     *  - 4 slices, viewport = 200 px, 80 %-viewport scroll step (160 px per step).
     *  - Each slice is `background` + a "list band" that scrolls + a pinned
     *    "peek pill" at `y = 175..184` (10 px tall) in slice-local coords.
     *  - Final frame: same list-at-bottom + a "full EdgeButton" rectangle at
     *    `y = 130..194` (65 px tall) instead of the peek pill.
     *
     * Expected clean output (no ghost):
     *  - The final EdgeButton lives only in the very bottom of the stitched
     *    image (rows `topH − 65 .. topH`).
     *  - Rows above the EdgeButton should be either list content or pure
     *    background — never the peek-pill grey.
     *
     * Observed broken output (current stitcher):
     *  - One or more ghost peek pills appear in the mid-to-upper rows of the
     *    stitch, at positions `yPrev_i + 175 − (sliceH − d_i)` where the
     *    matched shift `d_i` is small enough that the peek pill row lands
     *    in the painted band.
     */
    @Test
    fun `ghost peek pill survives stitch when pinned-bottom chrome differs from final`() {
        val viewport = 200
        val width = 120
        val step = 160 // 80% viewport
        // 4 slices, cumulative scroll 0 / 160 / 320 / 480.
        val slices = (0..3).map { i ->
            val file = tmp.newFile("peek_slice_$i.png")
            ImageIO.write(buildPeekSlice(width, viewport, scrollOffset = i * step), "PNG", file)
            SliceCapture((i * step).toFloat(), file)
        }
        val finalFile = tmp.newFile("final.png")
        ImageIO.write(buildFinalFrame(width, viewport, scrollOffset = 3 * step), "PNG", finalFile)

        val out = tmp.newFile("stitched_peek.png")
        stitchSlicesWithFinalFrame(slices, finalFile, viewport, out)
            ?: error("stitcher returned null")
        val stitched = ImageIO.read(out)

        // Sanity: the final EdgeButton (bright primary, rows bottom-65 of final)
        // is present at the very bottom of the output.
        val finalEdgeButtonTop = stitched.height - FINAL_EDGE_BUTTON_HEIGHT
        assertTrue(
            "expected a row of EdgeButton-primary pixels at the very bottom",
            rowIsPredominantly(stitched, y = stitched.height - 2, rgb = EDGE_BUTTON_PRIMARY_RGB),
        )

        // The ghost-pill signature: ANY row above the final EdgeButton with
        // a narrow centred run of peek-grey pixels is a ghost. Each
        // intermediate slice's painted band contains the pinned peek pill
        // at a shifted destination `y` in the stitched output, and those
        // positions are all above `finalEdgeButtonTop` — they are not
        // reached by the last-viewport-sized final-frame overwrite.
        val ghostRows = (0 until finalEdgeButtonTop).filter { y ->
            rowHasNarrowCentredRun(stitched, y, rgb = PEEK_PILL_GREY_RGB, minWidth = 8, maxWidth = 60)
        }

        // CURRENT (broken) stitcher: with a 4-slice × 80% step fixture,
        // one ghost pill band appears per slice i whose painted band
        // `[yPrev_i, yPrev_i + d_i]` covers the slice-local pill row
        // (175..184). That's 4 bands × ~10 rows/band ≈ ≥ 30 leaked rows.
        // The assertion below locks in the bug; flip it to
        // `assertTrue(..., ghostRows.isEmpty())` once the stitcher stops
        // propagating pinned-bottom chrome from intermediate slices (the
        // documented fix avenues: mask the pinned-bottom region off each
        // slice before painting, or extend the final-frame overwrite to
        // every slice boundary).
        System.err.println(
            "Ghost pill rows observed above EdgeButton: ${ghostRows.size} " +
                "(first 10: ${ghostRows.take(10)})",
        )
        assertTrue(
            "reproducer expected to observe leaked peek-pill rows with current stitcher — " +
                "if you're seeing this, the fix may have landed; flip the assertion",
            ghostRows.isNotEmpty(),
        )
    }

    /**
     * Guard that the input fixture is actually exercising the bug path:
     * pinned chrome sits in the bottom `d` rows of intermediate slices, so
     * the stitcher's `drawImage` of those rows paints the pill into the
     * output. If this precondition ever stops holding (e.g. the fixture
     * drifts so the pill no longer falls inside the painted band), the
     * main test above is a no-op.
     */
    @Test
    fun `fixture precondition - peek pill falls inside painted band of each slice`() {
        val viewport = 200
        val step = 160
        val paintedBandStart = viewport - step // = 40 → source rows 40..199
        // Peek pill rows 175..184 — well inside 40..199.
        assertTrue(PEEK_PILL_TOP >= paintedBandStart)
        assertTrue(PEEK_PILL_BOTTOM < viewport)
    }

    // ------------------------------------------------------------------
    // Fixture builders — minimal stand-ins for a Wear-style layout.
    // ------------------------------------------------------------------

    /**
     * An intermediate slice during scroll: black background, list cards that
     * move with [scrollOffset], plus a centred peek-pill ellipse pinned at
     * the bottom of the viewport. The list cards give the matcher an
     * unambiguous overlap signal; the peek pill is the scroll-independent
     * chrome whose leakage we're catching.
     */
    private fun buildPeekSlice(width: Int, height: Int, scrollOffset: Int): BufferedImage {
        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        try {
            g.color = Color.BLACK
            g.fillRect(0, 0, width, height)
            paintListBand(g, width, height, scrollOffset)
            paintPeekPill(g, width)
        } finally {
            g.dispose()
        }
        return img
    }

    /**
     * The settled frame captured after `settlePostScrollAnimations`: same
     * scroll position as the last slice, but the scaffold has grown the
     * EdgeButton slot and the peek pill is gone — replaced by a full
     * primary-coloured button.
     */
    private fun buildFinalFrame(width: Int, height: Int, scrollOffset: Int): BufferedImage {
        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        try {
            g.color = Color.BLACK
            g.fillRect(0, 0, width, height)
            paintListBand(g, width, height, scrollOffset)
            // Full EdgeButton: wider + taller, replacing the peek region.
            val top = height - FINAL_EDGE_BUTTON_HEIGHT
            g.color = Color(
                EDGE_BUTTON_PRIMARY_RGB shr 16 and 0xFF,
                EDGE_BUTTON_PRIMARY_RGB shr 8 and 0xFF,
                EDGE_BUTTON_PRIMARY_RGB and 0xFF,
            )
            g.fillRect(4, top, width - 8, FINAL_EDGE_BUTTON_HEIGHT)
        } finally {
            g.dispose()
        }
        return img
    }

    /**
     * Paints a band of scrollable "card" stripes — the content the matcher
     * locks onto. Pattern is deterministic in `(x, y + scrollOffset)` so
     * sliced windows reconstruct identically when stitched at the correct
     * shift.
     */
    private fun paintListBand(
        g: java.awt.Graphics2D,
        width: Int,
        height: Int,
        scrollOffset: Int,
    ) {
        for (y in 0 until height) {
            val sourceY = y + scrollOffset
            // 40-px-tall stripes, alternating colours — gives the weighted
            // row matcher plenty of variety to align on.
            val band = (sourceY / 40) % 4
            val c = when (band) {
                0 -> Color(0x20, 0x55, 0x99)
                1 -> Color(0x99, 0x30, 0x30)
                2 -> Color(0x30, 0x99, 0x30)
                else -> Color(0x99, 0x99, 0x30)
            }
            g.color = c
            g.drawLine(0, y, width - 1, y)
        }
    }

    private fun paintPeekPill(g: java.awt.Graphics2D, width: Int) {
        g.color = Color(
            PEEK_PILL_GREY_RGB shr 16 and 0xFF,
            PEEK_PILL_GREY_RGB shr 8 and 0xFF,
            PEEK_PILL_GREY_RGB and 0xFF,
        )
        val pillW = 30
        g.fillOval((width - pillW) / 2, PEEK_PILL_TOP, pillW, PEEK_PILL_BOTTOM - PEEK_PILL_TOP + 1)
    }

    // ------------------------------------------------------------------
    // Pixel-probe helpers.
    // ------------------------------------------------------------------

    private fun rowIsPredominantly(img: BufferedImage, y: Int, rgb: Int): Boolean {
        val w = img.width
        var hits = 0
        val targetR = rgb shr 16 and 0xFF
        val targetG = rgb shr 8 and 0xFF
        val targetB = rgb and 0xFF
        for (x in 0 until w) {
            val p = img.getRGB(x, y)
            val dr = (p shr 16 and 0xFF) - targetR
            val dg = (p shr 8 and 0xFF) - targetG
            val db = (p and 0xFF) - targetB
            if (dr * dr + dg * dg + db * db < 600) hits++
        }
        return hits > w / 2
    }

    private fun rowHasNarrowCentredRun(
        img: BufferedImage,
        y: Int,
        rgb: Int,
        minWidth: Int,
        maxWidth: Int,
    ): Boolean {
        val w = img.width
        val targetR = rgb shr 16 and 0xFF
        val targetG = rgb shr 8 and 0xFF
        val targetB = rgb and 0xFF
        var first = -1
        var last = -1
        for (x in 0 until w) {
            val p = img.getRGB(x, y)
            val dr = (p shr 16 and 0xFF) - targetR
            val dg = (p shr 8 and 0xFF) - targetG
            val db = (p and 0xFF) - targetB
            if (dr * dr + dg * dg + db * db < 400) {
                if (first < 0) first = x
                last = x
            }
        }
        if (first < 0) return false
        val extent = last - first + 1
        if (extent !in minWidth..maxWidth) return false
        val centre = (first + last) / 2
        return Math.abs(centre - w / 2) <= w / 8
    }

    companion object {
        // Rows (0-indexed, within a slice) where the peek pill sits.
        private const val PEEK_PILL_TOP = 175
        private const val PEEK_PILL_BOTTOM = 184
        // Muted purple-grey — the peek colour observed in the real bug.
        private const val PEEK_PILL_GREY_RGB = 0x72_6C_7E
        // Wear Material3 primary-ish — the full "Start workout" colour.
        private const val EDGE_BUTTON_PRIMARY_RGB = 0xE6_DA_FC
        // Full EdgeButton height within the settled viewport.
        private const val FINAL_EDGE_BUTTON_HEIGHT = 65
    }
}
