package ee.schimke.composeai.renderer

import java.awt.Color
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Pure-JVM regression for the "ghost overlay pill" flakiness observed on
 * `@ScrollingPreview(LONG)` outputs (e.g. Wear's `ActivityListLongPreview` —
 * the small grey `EdgeButton` "peek" ellipsis showing up as a phantom pill
 * above the real EdgeButton in a subset of stitched renders).
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
 * The 40-px height difference between good and bad is exactly the 33 pill
 * rows + 7 gap rows inserted above the final EdgeButton.
 *
 * Mechanism: `ScreenScaffold` pins the peek pill to the bottom of every
 * intermediate slice. Under the pre-fix stitcher, each slice's painted
 * bottom band dragged the pill into the scrolling-content region of the
 * stitch, where the last-viewport-sized final-frame overwrite could not
 * reach it. The fix (approach B in [buildStitchedContent]) detects the
 * pinned-bottom region per slice pair via [bottomPinnedRowsTop] and skips
 * those rows in every intermediate slice's contribution; the settled
 * final-frame overlay paints the real chrome once at the output tail.
 *
 * This test deterministically reproduces the pre-fix shape with synthetic
 * slices (list content + pinned peek pill) and asserts that the stitched
 * output is clean — no ghost pill above the real EdgeButton.
 */
class LongScrollGhostOverlayTest {
    @get:Rule
    val tmp: TemporaryFolder = TemporaryFolder()

    /**
     * Four-slice fixture that mirrors the Wear `LongActivityListScreen`
     * layout: the top `LIST_BOTTOM = 140` rows are scrolling list content
     * (unique colour per source-y), the bottom 60 rows are black
     * scaffold-reserved background with a peek pill pinned at rows
     * `155..175`. The final frame replaces the peek with a full-width
     * `EdgeButton` at rows `160..199`.
     *
     * With the pinned-bottom-masking stitcher the output is a continuous
     * list strip followed by exactly one EdgeButton band — no peek ghosts
     * at slice seams.
     */
    @Test
    fun `stitched output has no ghost peek pill above the EdgeButton`() {
        val viewport = 200
        val width = 120
        // Step < LIST_BOTTOM so consecutive slices share real list-region
        // overlap the matcher can lock onto (same invariant as real Wear:
        // scroll step ≈ 80 % of the list-content height, not the viewport).
        val step = 100
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

        // Sanity: the final EdgeButton occupies the very bottom.
        assertTrue(
            "expected EdgeButton-primary pixels near the very bottom",
            rowIsPredominantly(stitched, y = stitched.height - 3, rgb = EDGE_BUTTON_PRIMARY_RGB),
        )

        // Ghost-pill signature: narrow centred runs of peek-grey pixels
        // anywhere above the settled EdgeButton band. With approach B,
        // pinned rows are masked out of every intermediate slice's
        // contribution, so the output is free of peek ghosts.
        val edgeButtonTop = firstRowWithPrimaryWideRun(stitched)
        val ghostRows = (0 until edgeButtonTop).filter { y ->
            rowHasNarrowCentredRun(stitched, y, rgb = PEEK_PILL_GREY_RGB, minWidth = 8, maxWidth = 60)
        }
        assertEquals(
            "expected zero ghost peek-pill rows above the EdgeButton; got $ghostRows",
            0,
            ghostRows.size,
        )
    }

    /**
     * Wear anchor path: when `isRound = true` and the final frame
     * carries an edge-hugging button shape, the stitcher locates the
     * last-list-item "anchor" above the button in the settled frame
     * and re-glues the EdgeButton immediately after the same anchor in
     * the top-down stitched content. Guarantees that no intermediate
     * chrome (fading peek pills, settle-animation residue) can land
     * between the last list item and the settled button.
     *
     * This test uses a position-jittered peek pill (1 px down per
     * slice) so that pair-wise row diffs would trip the
     * [bottomPinnedRowsTop] threshold — i.e. approach B alone would
     * fall through and ghost the pill. The anchor path ignores the
     * pinned-detection decision entirely and operates on the final-
     * frame geometry, so this fixture exercises the full Wear code
     * path instead of just the pinned-masking fast path.
     */
    @Test
    fun `Wear anchor path cuts at last list item regardless of pill jitter`() {
        val viewport = 200
        val width = 120
        val step = 100
        val slices = (0..3).map { i ->
            val file = tmp.newFile("jitter_slice_$i.png")
            ImageIO.write(
                buildPeekSlice(width, viewport, scrollOffset = i * step, pillYShift = i),
                "PNG",
                file,
            )
            SliceCapture((i * step).toFloat(), file)
        }
        val finalFile = tmp.newFile("jitter_final.png")
        ImageIO.write(buildFinalFrame(width, viewport, scrollOffset = 3 * step), "PNG", finalFile)

        val out = tmp.newFile("stitched_jitter.png")
        stitchSlicesWithFinalFrame(
            slices = slices,
            finalFrameFile = finalFile,
            viewportLayoutPx = viewport,
            outputFile = out,
            isRound = true,
        ) ?: error("stitcher returned null")
        val stitched = ImageIO.read(out)

        val edgeButtonTop = firstRowWithPrimaryWideRun(stitched)
        assertTrue(
            "expected EdgeButton-primary band near the output bottom; none found",
            edgeButtonTop < stitched.height,
        )
        // No peek-grey ghost rows anywhere above the button.
        val ghostRows = (0 until edgeButtonTop).filter { y ->
            rowHasNarrowCentredRun(stitched, y, rgb = PEEK_PILL_GREY_RGB, minWidth = 8, maxWidth = 60)
        }
        assertEquals(
            "expected zero ghost peek-pill rows above the EdgeButton; got $ghostRows",
            0,
            ghostRows.size,
        )
        // The row immediately above the EdgeButton band must be list content
        // matching the settled scroll position (source-y at scroll end). The
        // anchor heuristic guarantees this by construction — a broken
        // algorithm could produce empty rows or content from earlier scroll
        // positions between the last item and the button.
        val rowAboveButton = edgeButtonTop - 1
        val expectedSourceY = (3 * step) + (viewport - FINAL_EDGE_BUTTON_HEIGHT - 1)
        val expected = expectedListColour(expectedSourceY)
        assertTrue(
            "row above EdgeButton (y=$rowAboveButton) should match source-y " +
                "$expectedSourceY; got ARGB 0x${Integer.toHexString(stitched.getRGB(width / 2, rowAboveButton))}",
            rowMatchesColour(stitched, rowAboveButton, expected, tolerance = 1200),
        )
    }

    /**
     * With `isRound = true` but no edge-hugging button in the final frame,
     * [detectWearEdgeButtonTop] returns null, the anchor path bails, and
     * the established overlay path handles the capture. Guards against
     * the Wear-specific branch taking over non-Wear captures that happen
     * to be rendered on round hardware (Tile previews, widget previews).
     */
    @Test
    fun `Wear anchor path falls through when the final frame has no edge button`() {
        val viewport = 200
        val width = 120
        val step = 100
        val slices = (0..3).map { i ->
            val file = tmp.newFile("no_edge_slice_$i.png")
            ImageIO.write(buildPeekSlice(width, viewport, scrollOffset = i * step), "PNG", file)
            SliceCapture((i * step).toFloat(), file)
        }
        // Final frame without an EdgeButton — keeps the peek pill in
        // place. `detectWearEdgeButtonTop` should return null.
        val finalFile = tmp.newFile("no_edge_final.png")
        ImageIO.write(buildPeekSlice(width, viewport, scrollOffset = 3 * step), "PNG", finalFile)

        val out = tmp.newFile("stitched_no_edge.png")
        val result = stitchSlicesWithFinalFrame(
            slices = slices,
            finalFrameFile = finalFile,
            viewportLayoutPx = viewport,
            outputFile = out,
            isRound = true,
        )
        assertTrue("stitcher returned null", result != null)
        // The fallback path produces a valid PNG; we don't assert pixel-
        // perfection here because the established behaviour is tested
        // separately. Just confirming the round-device branch doesn't
        // destroy the output or throw.
        val stitched = ImageIO.read(out)
        assertTrue("output height should be > viewport", stitched.height > viewport)
    }

    /**
     * Content-continuity check: the stitched list region should read out
     * the same pseudo-random colour per source-y that the fixture
     * generates — i.e. no gaps, no duplicated bands. Exercises approach
     * B's "advance by rows painted, not nominal `d`" behaviour.
     */
    @Test
    fun `stitched output is a continuous list strip with no transparent gaps`() {
        val viewport = 200
        val width = 120
        val step = 100
        val slices = (0..3).map { i ->
            val file = tmp.newFile("cont_slice_$i.png")
            ImageIO.write(buildPeekSlice(width, viewport, scrollOffset = i * step), "PNG", file)
            SliceCapture((i * step).toFloat(), file)
        }
        val finalFile = tmp.newFile("cont_final.png")
        ImageIO.write(buildFinalFrame(width, viewport, scrollOffset = 3 * step), "PNG", finalFile)

        val out = tmp.newFile("stitched_cont.png")
        stitchSlicesWithFinalFrame(slices, finalFile, viewport, out)
            ?: error("stitcher returned null")
        val stitched = ImageIO.read(out)

        // The list region occupies rows 0 until the start of the EdgeButton.
        // Every row in that range should be fully opaque (no transparent
        // slice-boundary gaps) AND match the hash colour for that source-y.
        val edgeButtonTop = firstRowWithPrimaryWideRun(stitched)
        val mismatches = mutableListOf<Int>()
        for (y in 0 until edgeButtonTop) {
            val centre = stitched.getRGB(width / 2, y)
            val alpha = (centre ushr 24) and 0xFF
            if (alpha == 0) {
                mismatches += y
                continue
            }
            val expected = expectedListColour(y)
            val r = (centre shr 16) and 0xFF
            val g = (centre shr 8) and 0xFF
            val b = centre and 0xFF
            val dr = r - ((expected shr 16) and 0xFF)
            val dg = g - ((expected shr 8) and 0xFF)
            val db = b - (expected and 0xFF)
            if (dr * dr + dg * dg + db * db > 1200) mismatches += y
        }
        assertEquals(
            "expected every list row to be opaque and match its source-y hash; mismatches: $mismatches",
            0,
            mismatches.size,
        )
    }

    // ------------------------------------------------------------------
    // Fixture builders — minimal stand-ins for a Wear-style layout.
    // ------------------------------------------------------------------

    /**
     * An intermediate slice during scroll. Rows `[0..LIST_BOTTOM)` are
     * scrolling list content with a unique colour per `sourceY = y +
     * scrollOffset`. Rows `[LIST_BOTTOM..viewport)` are black scaffold-
     * reserved background, with a grey peek-pill ellipse at
     * `[PEEK_TOP..PEEK_BOT]` centred horizontally. [pillYShift] lets a
     * caller simulate the real-world spring-settle wobble where the
     * peek pill moves by 1–3 px between consecutive slices — enough to
     * defeat [bottomPinnedRowsTop]'s stable-position assumption.
     */
    private fun buildPeekSlice(
        width: Int,
        height: Int,
        scrollOffset: Int,
        pillYShift: Int = 0,
    ): BufferedImage {
        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        try {
            g.color = Color.BLACK
            g.fillRect(0, 0, width, height)
            paintListBand(g, width, scrollOffset)
            paintPeekPill(g, width, pillYShift)
        } finally {
            g.dispose()
        }
        return img
    }

    /**
     * The settled frame captured after `settlePostScrollAnimations`:
     * same list-at-bottom as the last slice, but the scaffold has grown
     * the `EdgeButton` slot and the peek pill has been replaced by a
     * full-width button.
     */
    private fun buildFinalFrame(width: Int, height: Int, scrollOffset: Int): BufferedImage {
        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        try {
            g.color = Color.BLACK
            g.fillRect(0, 0, width, height)
            paintListBand(g, width, scrollOffset)
            val top = height - FINAL_EDGE_BUTTON_HEIGHT
            g.color = Color(
                (EDGE_BUTTON_PRIMARY_RGB shr 16) and 0xFF,
                (EDGE_BUTTON_PRIMARY_RGB shr 8) and 0xFF,
                EDGE_BUTTON_PRIMARY_RGB and 0xFF,
            )
            g.fillRect(4, top, width - 8, FINAL_EDGE_BUTTON_HEIGHT)
        } finally {
            g.dispose()
        }
        return img
    }

    private fun paintListBand(g: java.awt.Graphics2D, width: Int, scrollOffset: Int) {
        for (y in 0 until LIST_BOTTOM) {
            val sourceY = y + scrollOffset
            val rgb = expectedListColour(sourceY)
            g.color = Color((rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF)
            g.drawLine(0, y, width - 1, y)
        }
    }

    /**
     * Deterministic colour per source-y. Non-periodic so the row matcher
     * can't collapse the search window onto an accidental alignment.
     */
    private fun expectedListColour(sourceY: Int): Int {
        var h = sourceY.toLong() * 2654435761L xor (sourceY.toLong() shl 16)
        h = h xor (h ushr 29)
        val r = ((h ushr 16) and 0xFFL).toInt()
        val g = ((h ushr 8) and 0xFFL).toInt()
        val b = (h and 0xFFL).toInt()
        return (r shl 16) or (g shl 8) or b
    }

    private fun paintPeekPill(g: java.awt.Graphics2D, width: Int, yShift: Int = 0) {
        g.color = Color(
            (PEEK_PILL_GREY_RGB shr 16) and 0xFF,
            (PEEK_PILL_GREY_RGB shr 8) and 0xFF,
            PEEK_PILL_GREY_RGB and 0xFF,
        )
        val pillW = 30
        val pillH = PEEK_BOT - PEEK_TOP + 1
        g.fillOval((width - pillW) / 2, PEEK_TOP + yShift, pillW, pillH)
    }

    // ------------------------------------------------------------------
    // Pixel probes.
    // ------------------------------------------------------------------

    private fun firstRowWithPrimaryWideRun(img: BufferedImage): Int {
        val w = img.width
        val targetR = (EDGE_BUTTON_PRIMARY_RGB shr 16) and 0xFF
        val targetG = (EDGE_BUTTON_PRIMARY_RGB shr 8) and 0xFF
        val targetB = EDGE_BUTTON_PRIMARY_RGB and 0xFF
        for (y in 0 until img.height) {
            var run = 0
            var maxRun = 0
            for (x in 0 until w) {
                val p = img.getRGB(x, y)
                if ((p ushr 24) and 0xFF == 0) { run = 0; continue }
                val dr = ((p shr 16) and 0xFF) - targetR
                val dg = ((p shr 8) and 0xFF) - targetG
                val db = (p and 0xFF) - targetB
                if (dr * dr + dg * dg + db * db < 1200) {
                    run++
                    if (run > maxRun) maxRun = run
                } else {
                    run = 0
                }
            }
            if (maxRun > w / 2) return y
        }
        return img.height
    }

    private fun rowMatchesColour(
        img: BufferedImage,
        y: Int,
        expectedRgb: Int,
        tolerance: Int,
    ): Boolean {
        if (y < 0 || y >= img.height) return false
        val p = img.getRGB(img.width / 2, y)
        if ((p ushr 24) and 0xFF == 0) return false
        val dr = ((p shr 16) and 0xFF) - ((expectedRgb shr 16) and 0xFF)
        val dg = ((p shr 8) and 0xFF) - ((expectedRgb shr 8) and 0xFF)
        val db = (p and 0xFF) - (expectedRgb and 0xFF)
        return dr * dr + dg * dg + db * db <= tolerance
    }

    private fun rowIsPredominantly(img: BufferedImage, y: Int, rgb: Int): Boolean {
        val w = img.width
        val targetR = (rgb shr 16) and 0xFF
        val targetG = (rgb shr 8) and 0xFF
        val targetB = rgb and 0xFF
        var hits = 0
        for (x in 0 until w) {
            val p = img.getRGB(x, y)
            val dr = ((p shr 16) and 0xFF) - targetR
            val dg = ((p shr 8) and 0xFF) - targetG
            val db = (p and 0xFF) - targetB
            if (dr * dr + dg * dg + db * db < 1200) hits++
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
        val targetR = (rgb shr 16) and 0xFF
        val targetG = (rgb shr 8) and 0xFF
        val targetB = rgb and 0xFF
        var first = -1
        var last = -1
        for (x in 0 until w) {
            val p = img.getRGB(x, y)
            if ((p ushr 24) and 0xFF == 0) continue
            val dr = ((p shr 16) and 0xFF) - targetR
            val dg = ((p shr 8) and 0xFF) - targetG
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
        // Slice-local layout: list on top, scaffold-reserved band below.
        private const val LIST_BOTTOM = 140
        private const val PEEK_TOP = 155
        private const val PEEK_BOT = 175
        // Muted purple-grey — the peek colour observed in the real bug.
        private const val PEEK_PILL_GREY_RGB = 0x72_6C_7E
        // Wear Material3 primary-ish — the full "Start workout" colour.
        private const val EDGE_BUTTON_PRIMARY_RGB = 0xE6_DA_FC
        // Full EdgeButton height within the settled viewport.
        private const val FINAL_EDGE_BUTTON_HEIGHT = 40
    }
}
