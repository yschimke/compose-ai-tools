package ee.schimke.composeai.renderer

import java.awt.AlphaComposite
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.Area
import java.awt.geom.Ellipse2D
import java.awt.geom.Rectangle2D
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * One captured slice — the on-disk PNG plus the cumulative scroll offset
 * (in layout pixels) the scrollable reported at the time of capture. The
 * offset is now used only as a **hint** to narrow the overlap search; the
 * actual alignment is driven by pixel content ([findOverlapShift]).
 */
internal data class SliceCapture(val scrolledLayoutPx: Float, val file: File)

/**
 * Stitches per-viewport slices (see `driveScrollByViewport`) into a single
 * tall PNG at [outputFile].
 *
 * Content-aware alignment: each slice's vertical placement is decided by
 * comparing pixel rows against the previous slice, not by trusting the
 * scroller's reported offset. We've seen `TransformingLazyColumn` and
 * spring-settled scrolls advance their semantic `ScrollAxisRange.value`
 * faster or slower than the visible content actually moves — a stitcher
 * keyed on semantics prints duplicated or dropped bands at each seam. The
 * matcher here locks onto whatever the images actually show.
 *
 * The caller should drive the scroller at less than one full viewport per
 * step (e.g. 80 %) so consecutive slice pairs have a physical overlap the
 * matcher can lock onto.
 *
 * Per slice pair `(prev, next)`:
 *  - Compute per-row luminance and per-row horizontal stddev (a cheap
 *    "interestingness" signal — a blank row has stddev ≈ 0, a row cutting
 *    through text or button edges has a large stddev).
 *  - For each candidate shift `d` in `[hint × 0.5, hint × 1.2]` (clipped to
 *    `[1, sliceH)`), score `Σ w_k · rowSAD(prev[d+k], next[k]) / Σ w_k`
 *    with `w_k = max(prevStddev[d+k], nextStddev[k])`.
 *  - Pick the `d` with the lowest score. The weighting means alignment is
 *    decided by varied rows (text, edges, icons) — vast tracts of matching
 *    blank background can't dominate the match.
 *
 * `pxPerLayoutPx` converts the scroller's layout-pixel delta to image pixels
 * to seed the search window. Robolectric's qualifier density often makes this
 * 1.0, but we compute it from slice 0 to stay honest.
 *
 * Output height = `sliceH + Σ d_i` (sum of measured per-pair shifts).
 *
 * Returns the written file, or `null` if [slices] is empty.
 */
internal fun stitchSlices(
    slices: List<SliceCapture>,
    viewportLayoutPx: Int,
    outputFile: File,
): File? {
    if (slices.isEmpty()) return null

    val stitched = buildStitchedImage(slices, viewportLayoutPx) ?: return null
    outputFile.parentFile?.mkdirs()
    ImageIO.write(stitched, "PNG", outputFile)
    return outputFile
}

/**
 * Variant of [stitchSlices] for captures whose last viewport contains
 * animations that settle *after* the scroll ends (Wear `EdgeButton` reveal,
 * Material 3 FAB appear, AnimatedVisibility fade-ins triggered by the
 * list reaching its bottom).
 *
 * Motivation: the final entry of [slices] is captured while those
 * animations are still mid-flight. In the regular [stitchSlices] path only
 * the bottom `d` rows of that slice are painted at the seam (where `d` is
 * the measured overlap shift ≈ one scroll step), so any animated element
 * taller than `d` is clipped at the top — classic symptom on Wear is two
 * half-drawn EdgeButton pills (mid-reveal from slice N-2 above a clipped
 * settled one).
 *
 * Strategy: stitch every slice as usual (including the mid-animation last
 * slice), then diff the last slice against [finalFrameFile] to find the
 * topmost row that actually changed between mid-reveal and settled.
 * Overwrite only that band (the animating tail) with the settled rows.
 * Rows above the diff band are left as the pair-wise stitch produced them
 * — that preserves all list-item positions from the during-scroll slices
 * and keeps the seam confined to content the user will read as one unit
 * (the EdgeButton / FAB / snackbar region).
 *
 * Layout shift avoidance: on Wear, `ScreenScaffold` enlarges the edge-
 * button slot once it fully reveals, which pushes list items UP relative
 * to the mid-reveal frame. A whole-viewport overwrite therefore draws the
 * settled list on top of the mid-scroll list at a slightly different y,
 * producing a ghost of each card's text. Diff-band overwrite confines the
 * replacement to rows where the two frames genuinely differ, so items in
 * the untouched upper region stay at their original slice positions.
 *
 * Falls back to writing [finalFrameFile] directly when [slices] has one
 * entry (no scroll history).
 */
internal fun stitchSlicesWithFinalFrame(
    slices: List<SliceCapture>,
    finalFrameFile: File,
    viewportLayoutPx: Int,
    outputFile: File,
    isRound: Boolean = false,
): File? {
    if (slices.isEmpty()) return null

    val finalImage = ImageIO.read(finalFrameFile)
        ?: error("Failed to read final frame PNG: $finalFrameFile")

    // Single slice: no scroll history. The settled final frame IS the
    // preview.
    if (slices.size == 1) {
        outputFile.parentFile?.mkdirs()
        ImageIO.write(finalImage, "PNG", outputFile)
        return outputFile
    }

    val content = buildStitchedContent(slices, viewportLayoutPx) ?: return null
    val topImage = content.image
    val width = topImage.width
    val topH = topImage.height
    val finalH = finalImage.height
    require(finalImage.width == width) {
        "final frame width (${finalImage.width}) differs from slice width ($width)"
    }
    require(content.lastSliceImage.width == width && content.lastSliceImage.height == finalH) {
        "last slice size (${content.lastSliceImage.width}x${content.lastSliceImage.height}) " +
            "must match final frame (${width}x$finalH)"
    }

    // Wear-specific anchor path — cuts the stitch at the last list item
    // and glues the settled EdgeButton band in after it, so nothing (ghost
    // peek pills, fading chrome, settle-animation residue) can land
    // between them. Only runs for round-device previews where the final
    // frame actually shows an edge-hugging button shape; falls through on
    // any mismatch so non-Wear and non-EdgeButton LONG captures keep the
    // established overlay path.
    if (isRound) {
        val anchored = anchorByEdgeButton(content, finalImage)
        if (anchored != null) {
            outputFile.parentFile?.mkdirs()
            ImageIO.write(anchored, "PNG", outputFile)
            return outputFile
        }
    }

    val diffStart = findFirstDifferingRow(content.lastSliceImage, finalImage)
    if (diffStart < 0) {
        // No animating tail — settled frame equals mid-reveal slice.
        // [buildStitchedContent] already painted the last-slice pinned
        // band into the reserved tail via the fallback in
        // [buildStitchedImage], so the normal stitch is already correct.
        outputFile.parentFile?.mkdirs()
        ImageIO.write(topImage, "PNG", outputFile)
        return outputFile
    }

    // Position the final-frame overlay: the last slice's pinned-bottom
    // region lives at output rows [contentYEnd..topH); anything in the
    // final frame above `pinnedBottomTop` that differs from the last
    // slice is animating tail (EdgeButton list-shift, FAB slide-in)
    // that should also be overlaid. Paint final rows [diffStart..finalH)
    // at the corresponding position relative to `contentYEnd`.
    val overwriteY = (content.contentYEnd - (content.pinnedBottomTop - diffStart))
        .coerceAtLeast(0)
    val bandHeight = finalH - diffStart

    val composed = BufferedImage(width, topH, BufferedImage.TYPE_INT_ARGB)
    val g = composed.createGraphics()
    try {
        g.drawImage(topImage, 0, 0, null)
        // Draw the animating band. Source is the diff region of the
        // final frame; destination ends at topH (the output bottom). If
        // the band starts above contentYEnd (diffStart < pinnedBottomTop),
        // rows of the animating tail above the pinned region overwrite
        // the last few painted list rows — exactly the layout-shift
        // compensation that the original implementation was doing for
        // Wear's EdgeButton-reveal list shift.
        g.drawImage(
            finalImage,
            0, overwriteY, width, overwriteY + bandHeight,
            0, diffStart, width, finalH,
            null,
        )
    } finally {
        g.dispose()
    }

    outputFile.parentFile?.mkdirs()
    ImageIO.write(composed, "PNG", outputFile)
    return outputFile
}

/**
 * Finds the topmost row where two same-sized images differ beyond an
 * anti-aliasing / rendering-noise tolerance, or `-1` if they match
 * throughout. Used to locate the start of the settle-animation band
 * between the last mid-reveal slice and the final settled frame.
 *
 * The per-row threshold `FINAL_FRAME_DIFF_ROW_THRESHOLD` is tolerant of a
 * few stray anti-aliasing differences along card edges while still
 * triggering on wholesale content shifts (EdgeButton expanding, snackbar
 * appearing, list items sliding up by a pixel).
 */
private fun findFirstDifferingRow(a: BufferedImage, b: BufferedImage): Int {
    val w = a.width
    val h = a.height
    val rgbA = IntArray(w)
    val rgbB = IntArray(w)
    for (y in 0 until h) {
        a.getRGB(0, y, w, 1, rgbA, 0, w)
        b.getRGB(0, y, w, 1, rgbB, 0, w)
        var rowDiff = 0L
        for (x in 0 until w) {
            val pa = rgbA[x]
            val pb = rgbB[x]
            if (pa == pb) continue
            val lumA = (((pa ushr 16) and 0xFF) * 299 +
                ((pa ushr 8) and 0xFF) * 587 +
                (pa and 0xFF) * 114) / 1000
            val lumB = (((pb ushr 16) and 0xFF) * 299 +
                ((pb ushr 8) and 0xFF) * 587 +
                (pb and 0xFF) * 114) / 1000
            val d = lumA - lumB
            rowDiff += if (d < 0) -d.toLong() else d.toLong()
        }
        // Threshold scaled by width so it represents "average brightness
        // delta per pixel in this row". ~2 LSBs per pixel is above the
        // noise floor of Robolectric capture + AA fringing.
        if (rowDiff > FINAL_FRAME_DIFF_ROW_THRESHOLD * w) {
            return y
        }
    }
    return -1
}

private const val FINAL_FRAME_DIFF_ROW_THRESHOLD = 2L

/**
 * Per-pixel luminance tolerance used by [bottomPinnedRowsTop] when
 * deciding whether two slices show identical content at a slice-local
 * row. Matches `FINAL_FRAME_DIFF_ROW_THRESHOLD`'s intent — it's above
 * the Robolectric capture + AA noise floor but below any real visual
 * change (the peek-EdgeButton-pill vs list-card distinction, for
 * example, comes in at ≥ 50 per pixel on luminance).
 */
private const val PINNED_ROW_DIFF_THRESHOLD = 8L

/**
 * Shared state between [buildStitchedImage] and its final-frame-aware
 * sibling — lets [stitchSlicesWithFinalFrame] paint the settled chrome
 * overlay at the correct position without re-reading slice PNGs or
 * redoing the matcher.
 */
private data class StitchedContent(
    val image: BufferedImage,
    val pinnedBottomTop: Int,
    val sliceH: Int,
    val contentYEnd: Int,
    val lastSliceImage: BufferedImage,
)

/**
 * Core stitching routine. Produces a tall image containing every
 * slice's scrollable (non-pinned) content concatenated top-to-bottom,
 * with the pinned-bottom region of each slice deliberately left
 * transparent — the final-frame overlay in [stitchSlicesWithFinalFrame]
 * paints the settled chrome there, so it appears exactly once at the
 * output tail.
 *
 * Pinned-bottom detection ([bottomPinnedRowsTop]) walks each slice pair
 * from the bottom upward and records the topmost row where the pair
 * starts differing. The median across pairs is the slice-local y below
 * which every slice carries scroll-independent chrome (Wear
 * `EdgeButton` peek pill, `ScrollIndicator`, FAB, snackbar) or blank
 * scaffold-reserved background. Because the per-pair walk stops at the
 * first divergent row, top-pinned chrome (e.g. `TimeText`) is never
 * marked — it remains visible in slice 0's contribution at the top of
 * the output.
 *
 * The matcher ([findOverlapShift]) is re-run with `rowLimit =
 * pinnedBottomTop` so the shift it picks reflects the scroll step in
 * the list region only. Without that, the pinned-bottom band (identical
 * across slices) biases the SAD score toward small shifts, which in
 * turn leaves transparent gaps when the painter later skips those rows.
 *
 * The output advances `yPrev` by the number of rows actually painted
 * (not by the nominal `d`), producing a continuous vertical strip of
 * list content with no transparent gaps between slices. When no pinned
 * region is detected (`pinnedBottomTop == sliceH`), this degrades to
 * the original full-slice paint and output height stays at
 * `sliceH + Σ d_i` — backward-compatible with existing
 * [stitchSlices] tests.
 */
private fun buildStitchedContent(
    slices: List<SliceCapture>,
    viewportLayoutPx: Int,
): StitchedContent? {
    if (slices.isEmpty()) return null

    val firstImage = ImageIO.read(slices[0].file)
        ?: error("Failed to read first slice PNG: ${slices[0].file}")
    val width = firstImage.width
    val sliceH = firstImage.height
    val pxPerLayoutPx = sliceH.toDouble() / viewportLayoutPx.toDouble()

    val images = List(slices.size) { i ->
        val img = if (i == 0) {
            firstImage
        } else {
            ImageIO.read(slices[i].file)
                ?: error("Failed to read slice PNG: ${slices[i].file}")
        }
        require(img.width == width && img.height == sliceH) {
            "Slice dimensions drifted: expected ${width}x$sliceH, got ${img.width}x${img.height} at index $i"
        }
        img
    }
    val luminance = images.map { readLuminanceRows(it) }
    val weights = luminance.map { rowStddevs(it) }

    val pinnedBottomTop = bottomPinnedRowsTop(luminance, width, sliceH)

    val shifts = IntArray(slices.size - 1)
    for (i in 1 until slices.size) {
        val reportedDelta = slices[i].scrolledLayoutPx - slices[i - 1].scrolledLayoutPx
        if (reportedDelta <= 0f) {
            shifts[i - 1] = 0
            continue
        }
        val hintPx = (reportedDelta * pxPerLayoutPx).roundToInt()
        shifts[i - 1] = findOverlapShift(
            prevLum = luminance[i - 1],
            nextLum = luminance[i],
            prevW = weights[i - 1],
            nextW = weights[i],
            sliceH = sliceH,
            hintPx = hintPx,
            rowLimit = pinnedBottomTop,
        )
    }

    // Rows painted per intermediate slice = min(d, pinnedBottomTop).
    // Anything above that is redundant (seen in a prior slice) or in
    // the pinned band (handled by the final-frame overlay).
    val rowsPainted = IntArray(shifts.size) { idx ->
        val d = shifts[idx]
        if (d <= 0) 0 else minOf(d, pinnedBottomTop)
    }
    val contentHeight = pinnedBottomTop + rowsPainted.sum()
    val tailHeight = sliceH - pinnedBottomTop
    val totalHeight = contentHeight + tailHeight

    val stitched = BufferedImage(width, totalHeight, BufferedImage.TYPE_INT_ARGB)
    val g = stitched.createGraphics()
    try {
        // First slice: paint only the list region. The pinned tail stays
        // transparent until the caller overlays the settled chrome.
        if (pinnedBottomTop > 0) {
            g.drawImage(
                images[0],
                0, 0, width, pinnedBottomTop,
                0, 0, width, pinnedBottomTop,
                null,
            )
        }
        var yPrev = pinnedBottomTop
        for (i in 1 until images.size) {
            val rows = rowsPainted[i - 1]
            if (rows <= 0) continue
            // Source rows [pinnedBottomTop - rows, pinnedBottomTop) — the
            // new content added at the bottom of the list region since
            // the previous slice. Dest rows [yPrev, yPrev + rows). Skip
            // the pinned band entirely (never painted from intermediate
            // slices, so no ghost chrome can survive into the output).
            g.drawImage(
                images[i],
                0, yPrev, width, yPrev + rows,
                0, pinnedBottomTop - rows, width, pinnedBottomTop,
                null,
            )
            yPrev += rows
        }
    } finally {
        g.dispose()
    }
    return StitchedContent(
        image = stitched,
        pinnedBottomTop = pinnedBottomTop,
        sliceH = sliceH,
        contentYEnd = contentHeight,
        lastSliceImage = images.last(),
    )
}

/** Legacy entry point preserved for [stitchSlices]. */
private fun buildStitchedImage(
    slices: List<SliceCapture>,
    viewportLayoutPx: Int,
): BufferedImage? = buildStitchedContent(slices, viewportLayoutPx)?.let { s ->
    // When no pinned chrome was detected, contentYEnd == sliceH + Σd and
    // the image is already the full stitch. When a pinned region exists
    // but the caller didn't supply a final frame, paint the last slice's
    // own pinned chrome into the reserved tail so `stitchSlices`
    // (single-mode LONG without a settle step) still produces a complete
    // image.
    if (s.pinnedBottomTop < s.sliceH) {
        val g = s.image.createGraphics()
        try {
            g.drawImage(
                s.lastSliceImage,
                0, s.contentYEnd, s.image.width, s.contentYEnd + (s.sliceH - s.pinnedBottomTop),
                0, s.pinnedBottomTop, s.image.width, s.sliceH,
                null,
            )
        } finally {
            g.dispose()
        }
    }
    s.image
}

/**
 * Walks each adjacent slice pair from the bottom upward, finding the
 * topmost slice-local row where the pair starts differing. Everything
 * at or below that row for the pair is content that didn't move
 * between slices captured at different scroll positions — pinned
 * chrome or uniformly blank scaffold-reserved background. Returns the
 * median across pairs (robust to one anomalous pair where the peek
 * chrome hasn't appeared yet or has fully transitioned), or [sliceH]
 * when there aren't enough slices to vote.
 */
private fun bottomPinnedRowsTop(
    luminance: List<Array<IntArray>>,
    width: Int,
    sliceH: Int,
): Int {
    val pairs = luminance.size - 1
    if (pairs < 1) return sliceH
    val perPairTops = IntArray(pairs)
    val threshold = PINNED_ROW_DIFF_THRESHOLD * width.toLong()
    for (i in 1..pairs) {
        val a = luminance[i - 1]
        val b = luminance[i]
        var pinnedTop = sliceH
        var y = sliceH - 1
        while (y >= 0) {
            val ar = a[y]
            val br = b[y]
            var diff = 0L
            for (x in 0 until width) {
                val d = ar[x] - br[x]
                diff += if (d < 0) -d.toLong() else d.toLong()
            }
            if (diff <= threshold) {
                pinnedTop = y
                y--
            } else {
                break
            }
        }
        perPairTops[i - 1] = pinnedTop
    }
    perPairTops.sort()
    return perPairTops[perPairTops.size / 2]
}

/**
 * Finds the vertical shift `d` that best aligns `prev[d..sliceH)` with
 * `next[0..sliceH-d)`. Uses [hintPx] (the semantic reported delta converted
 * to image pixels) to loosely narrow the search window; we keep the window
 * generous because the scroller's reported offset can be wildly inaccurate
 * (that's the bug we're fixing).
 *
 * Rows are weighted by their horizontal stddev so blank rows contribute
 * ~nothing — the alignment is decided by text, edges, and varied-colour
 * rows that can't accidentally match at the wrong offset. If the weighted
 * signal is degenerate (every candidate overlap region is uniformly blank),
 * we fall back to a plain rowSAD score so the matcher still produces a
 * sensible answer.
 */
private fun findOverlapShift(
    prevLum: Array<IntArray>,
    nextLum: Array<IntArray>,
    prevW: DoubleArray,
    nextW: DoubleArray,
    sliceH: Int,
    hintPx: Int,
    rowLimit: Int = sliceH,
): Int {
    val maxShift = sliceH - 1
    if (maxShift < 1) return 0

    val lo: Int
    val hi: Int
    if (hintPx > 0) {
        lo = (hintPx / 3).coerceIn(1, maxShift)
        hi = (hintPx * 3).coerceIn(lo, maxShift)
    } else {
        lo = 1
        hi = maxShift
    }

    var bestWeightedD = -1
    var bestWeightedScore = Double.POSITIVE_INFINITY
    var bestPlainD = hintPx.coerceIn(lo, hi)
    var bestPlainScore = Double.POSITIVE_INFINITY

    for (d in lo..hi) {
        // `rowLimit` clips both axes so only rows inside the list region
        // (above any pinned-bottom chrome) contribute to the score. Without
        // it, identical pinned rows skew the matcher toward small shifts.
        val nFullOverlap = sliceH - d
        if (nFullOverlap <= 0) break
        val n = minOf(nFullOverlap, maxOf(0, rowLimit - d))
        if (n <= 0) continue
        var weightSum = 0.0
        var weightedCost = 0.0
        var plainCost = 0.0
        for (k in 0 until n) {
            val w = max(prevW[d + k], nextW[k])
            val sad = rowSad(prevLum[d + k], nextLum[k])
            plainCost += sad
            if (w > 0.0) {
                weightedCost += w * sad
                weightSum += w
            }
        }
        val plainScore = plainCost / n
        if (plainScore < bestPlainScore) {
            bestPlainScore = plainScore
            bestPlainD = d
        }
        if (weightSum > 0.0) {
            val score = weightedCost / weightSum
            if (score < bestWeightedScore) {
                bestWeightedScore = score
                bestWeightedD = d
            }
        }
    }

    return if (bestWeightedD >= 0) bestWeightedD else bestPlainD
}

/**
 * Converts each row of [img] into an `IntArray` of 0..255 luminance values
 * (Rec. 601 weighting). Luminance — rather than raw ARGB — makes the row
 * SAD dominated by perceived brightness differences, which lines up with
 * "interesting rows" as a human reads them.
 */
private fun readLuminanceRows(img: BufferedImage): Array<IntArray> {
    val w = img.width
    val h = img.height
    val rgb = IntArray(w)
    return Array(h) { y ->
        img.getRGB(0, y, w, 1, rgb, 0, w)
        IntArray(w) { x ->
            val p = rgb[x]
            val r = (p ushr 16) and 0xFF
            val gg = (p ushr 8) and 0xFF
            val b = p and 0xFF
            (r * 299 + gg * 587 + b * 114) / 1000
        }
    }
}

/**
 * Horizontal standard deviation of each row — the per-row "interestingness"
 * weight. A blank row (black/white/single-colour background) scores ≈ 0;
 * a row cutting through text, a chip border, or an icon scores high.
 */
private fun rowStddevs(rows: Array<IntArray>): DoubleArray {
    return DoubleArray(rows.size) { y ->
        val row = rows[y]
        if (row.isEmpty()) {
            return@DoubleArray 0.0
        }
        var sum = 0L
        for (v in row) sum += v
        val mean = sum.toDouble() / row.size
        var varSum = 0.0
        for (v in row) {
            val d = v - mean
            varSum += d * d
        }
        sqrt(varSum / row.size)
    }
}

/** Sum of absolute per-pixel luminance differences between two rows. */
private fun rowSad(a: IntArray, b: IntArray): Long {
    val w = min(a.size, b.size)
    var sum = 0L
    for (i in 0 until w) {
        val d = a[i] - b[i]
        sum += if (d < 0) -d.toLong() else d.toLong()
    }
    return sum
}

/**
 * Height of the anchor band (in slice-local pixels) used by
 * [anchorByEdgeButton] to locate the last list item in the stitched
 * content. 48 px covers roughly one Wear `TitleCard` including its
 * bottom rounded-corner rows — enough signal to uniquely match, narrow
 * enough to fit above the EdgeButton on a 454-px viewport.
 */
private const val ANCHOR_BAND_ROWS = 48

/**
 * Minimum anchor band height, used when `edgeButtonTop` is near the
 * viewport top and we have to shrink the band. Below this the match
 * becomes ambiguous (too few distinguishing rows).
 */
private const val MIN_ANCHOR_BAND = 16

/**
 * Minimum summed per-row stddev across the anchor band. Blank regions
 * (uniform background, padding between cards) have near-zero stddev and
 * match anywhere in the stitched content — bailing keeps the heuristic
 * from cutting the output at a meaningless seam.
 */
private const val MIN_ANCHOR_VARIATION = 200.0

/**
 * Per-pixel SAD threshold (on 0–255 luminance) for an anchor match to
 * count. Real list content at the right position SADs to ~0; a genuine
 * mis-match (list item vs background vs peek pill) SADs to 20–60+.
 */
private const val ANCHOR_MATCH_MAX_SAD_PER_PIXEL = 8.0

/**
 * Minimum extent (as a fraction of image width) for a row to qualify as
 * "inside" the Wear `EdgeButton` band. The button is roughly the width
 * of the round viewport at its widest point; scan-line sampling hits
 * 40–70 % of the width depending on where on the button the row cuts.
 */
private const val EDGE_BUTTON_MIN_EXTENT_FRAC = 0.30

/**
 * Minimum mean channel sum (r + g + b, 0–765) for a run of pixels to
 * count as Wear Material3 primary. The full `EdgeButton` at primary-
 * container lands at ~670; cards / background sit well below 300.
 */
private const val EDGE_BUTTON_MIN_BRIGHTNESS_SUM = 500

/**
 * Minimum blue − green bias (on 0–255 channels, averaged across the
 * run) — Wear Material3 primary has a distinct purple cast. Gates out
 * neutral-grey chrome that happens to be wide and bright (dialogs,
 * white snackbars) without being an EdgeButton.
 */
private const val EDGE_BUTTON_MIN_PURPLE_CAST = 10.0

/**
 * Wear-specific re-stitch: when the settled final frame actually shows
 * an edge-hugging button, find the last list item that sits immediately
 * above that button, locate the same item in the top-down stitched
 * content, and glue the button region on directly after it. Nothing
 * (ghost peek pills, fading chrome, settle-animation residue) can land
 * between them because we don't paint the rows between in the first
 * place.
 *
 * Returns `null` when the heuristic can't apply cleanly — no button
 * shape in the final frame, anchor band would be too thin, anchor band
 * is blank / insufficiently varied, or no acceptable match is found in
 * the stitched content. Callers fall back to the established
 * final-frame overlay path in that case.
 */
private fun anchorByEdgeButton(
    content: StitchedContent,
    finalImage: BufferedImage,
): BufferedImage? {
    val width = content.image.width
    val finalH = finalImage.height

    val edgeButtonTop = detectWearEdgeButtonTop(finalImage) ?: return null
    val anchorK = ANCHOR_BAND_ROWS.coerceAtMost(edgeButtonTop)
    if (anchorK < MIN_ANCHOR_BAND) return null
    val anchorTop = edgeButtonTop - anchorK

    val anchorLum = readLuminanceRowsOfRegion(finalImage, anchorTop, anchorK)
    val anchorVariation = rowStddevs(anchorLum).sum()
    if (anchorVariation < MIN_ANCHOR_VARIATION) return null

    // Only search the painted content region. When `buildStitchedContent`
    // detected a pinned-bottom band, `contentYEnd < image.height`; the
    // reserved tail is transparent and the anchor would never match
    // there. When no pinned region was detected, contentYEnd ==
    // image.height and the whole image is fair game.
    val searchEnd = content.contentYEnd
    if (searchEnd < anchorK) return null
    val topLum = readLuminanceRowsOfRegion(content.image, 0, searchEnd)

    val matchEndY = findBestAnchorMatch(topLum, width, anchorLum) ?: return null

    val prefixHeight = matchEndY
    val tailHeight = finalH - edgeButtonTop
    val totalH = prefixHeight + tailHeight
    val out = BufferedImage(width, totalH, BufferedImage.TYPE_INT_ARGB)
    val g = out.createGraphics()
    try {
        g.drawImage(
            content.image,
            0, 0, width, prefixHeight,
            0, 0, width, prefixHeight,
            null,
        )
        g.drawImage(
            finalImage,
            0, prefixHeight, width, prefixHeight + tailHeight,
            0, edgeButtonTop, width, finalH,
            null,
        )
    } finally {
        g.dispose()
    }
    return out
}

/**
 * Scans [img] from its vertical midpoint down to the bottom for the
 * first row that looks like the top edge of a Wear Material3
 * `EdgeButton` — wide, bright, with a purple cast. Returns the row
 * index (`y` in slice-local coordinates), or `null` if no such row is
 * found. Restricted to the bottom half because `EdgeButton` hugs the
 * bottom of the round viewport by definition.
 */
private fun detectWearEdgeButtonTop(img: BufferedImage): Int? {
    val w = img.width
    val h = img.height
    if (w <= 0 || h <= 0) return null
    val startY = h / 2
    val minExtent = (w * EDGE_BUTTON_MIN_EXTENT_FRAC).toInt()
    val rgb = IntArray(w)
    for (y in startY until h) {
        img.getRGB(0, y, w, 1, rgb, 0, w)
        var first = -1
        var last = -1
        var sumR = 0L
        var sumG = 0L
        var sumB = 0L
        var count = 0
        for (x in 0 until w) {
            val p = rgb[x]
            val alpha = (p ushr 24) and 0xFF
            if (alpha == 0) continue
            val r = (p ushr 16) and 0xFF
            val gg = (p ushr 8) and 0xFF
            val b = p and 0xFF
            val s = r + gg + b
            if (s > EDGE_BUTTON_MIN_BRIGHTNESS_SUM) {
                if (first < 0) first = x
                last = x
                sumR += r
                sumG += gg
                sumB += b
                count++
            }
        }
        if (first < 0 || count == 0) continue
        val extent = last - first + 1
        if (extent < minExtent) continue
        val purpleCast = (sumB - sumG).toDouble() / count
        if (purpleCast < EDGE_BUTTON_MIN_PURPLE_CAST) continue
        return y
    }
    return null
}

/**
 * Reads `[y0, y0 + h)` of [img] as a matrix of per-row luminance values.
 * Shared shape with [readLuminanceRows] — uses Rec. 601 weighting —
 * but avoids the full-image allocation cost when we only need a narrow
 * band (anchor) or a prefix (painted-content region).
 */
private fun readLuminanceRowsOfRegion(
    img: BufferedImage,
    y0: Int,
    h: Int,
): Array<IntArray> {
    val w = img.width
    val rgb = IntArray(w)
    return Array(h) { k ->
        img.getRGB(0, y0 + k, w, 1, rgb, 0, w)
        IntArray(w) { x ->
            val p = rgb[x]
            val r = (p ushr 16) and 0xFF
            val gg = (p ushr 8) and 0xFF
            val b = p and 0xFF
            (r * 299 + gg * 587 + b * 114) / 1000
        }
    }
}

/**
 * Scans `topLum` for the bottommost position whose `anchor.size` rows
 * match `anchor` below the per-pixel SAD threshold
 * [ANCHOR_MATCH_MAX_SAD_PER_PIXEL]. Returns the row *after* the matched
 * band — i.e. anchor matched at `topLum[y − anchor.size, y)` —
 * suitable for use as a prefix-end cut point, or `null` if no position
 * hits the threshold.
 *
 * Bottommost-acceptable rather than global-min: once the EdgeButton
 * reveals, the last list item appears near the bottom of the stitched
 * strip. A global-min search can prefer an earlier similar-content
 * scroll position (real Wear previews repeat card templates; synthetic
 * jitter patterns repeat by construction) — which would glue the
 * EdgeButton on far above the true scroll end and throw away the tail
 * of the stitched content. Walking bottom-up and accepting the first
 * match locks onto the most recent occurrence of the last-item
 * signature.
 */
private fun findBestAnchorMatch(
    topLum: Array<IntArray>,
    width: Int,
    anchor: Array<IntArray>,
): Int? {
    val k = anchor.size
    val h = topLum.size
    if (k <= 0 || h < k) return null

    val perPixelCutoff = (ANCHOR_MATCH_MAX_SAD_PER_PIXEL * k * width).toLong()

    for (y in h downTo k) {
        var sad = 0L
        for (kk in 0 until k) {
            sad += rowSad(anchor[kk], topLum[y - k + kk])
            if (sad > perPixelCutoff) break
        }
        if (sad <= perPixelCutoff) return y
    }
    return null
}

/**
 * Clips [file]'s image into a pill/stadium shape: half-circle at the top,
 * rectangular middle, half-circle at the bottom. Width determines the circle
 * diameter. Pixels outside the shape become transparent.
 *
 * Used on stitched `@ScrollingPreview(LONG)` outputs for round Wear devices,
 * so the rendered scroll visually preserves the round screen edge at the top
 * of the first frame and the bottom of the last frame.
 */
internal fun applyWearPillClip(file: File) {
    val src = ImageIO.read(file) ?: return
    val w = src.width
    val h = src.height
    if (h <= 0 || w <= 0) return

    val radius = w / 2.0

    // Union of: top half-circle (centred at y=r), middle rectangle, bottom
    // half-circle (centred at y=h-r). For h < 2r (too short to be a proper
    // pill), fall back to a single ellipse.
    val pill: Area = if (h >= 2 * radius) {
        Area(Ellipse2D.Double(0.0, 0.0, w.toDouble(), 2 * radius)).apply {
            add(Area(Rectangle2D.Double(0.0, radius, w.toDouble(), h - 2 * radius)))
            add(Area(Ellipse2D.Double(0.0, h - 2 * radius, w.toDouble(), 2 * radius)))
        }
    } else {
        Area(Ellipse2D.Double(0.0, 0.0, w.toDouble(), min(h.toDouble(), 2 * radius)))
    }

    val out = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
    val g = out.createGraphics()
    try {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.composite = AlphaComposite.Src
        g.color = Color(0, 0, 0, 0)
        g.fillRect(0, 0, w, h)
        g.clip = pill
        g.drawImage(src, 0, 0, null)
    } finally {
        g.dispose()
    }
    ImageIO.write(out, "PNG", file)
}
