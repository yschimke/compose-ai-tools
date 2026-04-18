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

    val firstImage = ImageIO.read(slices[0].file)
        ?: error("Failed to read first slice PNG: ${slices[0].file}")
    val width = firstImage.width
    val sliceH = firstImage.height
    val pxPerLayoutPx = sliceH.toDouble() / viewportLayoutPx.toDouble()

    // Load every slice up-front — we need each pixel set twice (alignment +
    // blit) and the slice count is one per viewport (handful per preview).
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

    // Measured pixel shift for each transition i-1 -> i.
    val shifts = IntArray(slices.size - 1)
    for (i in 1 until slices.size) {
        val reportedDelta = slices[i].scrolledLayoutPx - slices[i - 1].scrolledLayoutPx
        if (reportedDelta <= 0f) {
            // Framework reported no motion — treat as a duplicate capture
            // and skip it; keeps parity with the old behaviour.
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
        )
    }

    val totalHeight = sliceH + shifts.sum()
    val stitched = BufferedImage(width, totalHeight, BufferedImage.TYPE_INT_ARGB)
    val g = stitched.createGraphics()
    try {
        g.drawImage(images[0], 0, 0, null)
        var yPrev = sliceH
        for (i in 1 until images.size) {
            val d = shifts[i - 1]
            if (d <= 0) continue
            // Copy the bottom `d` rows of the next slice directly beneath the
            // previously written content — the top `sliceH - d` rows already
            // overlap with what was drawn for the previous slice.
            g.drawImage(
                images[i],
                0, yPrev, width, yPrev + d,
                0, sliceH - d, width, sliceH,
                null,
            )
            yPrev += d
        }
    } finally {
        g.dispose()
    }

    outputFile.parentFile?.mkdirs()
    ImageIO.write(stitched, "PNG", outputFile)
    return outputFile
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
): Int {
    val maxShift = sliceH - 1
    if (maxShift < 1) return 0

    // Generous window around the hint — the framework can under- or over-
    // report motion by a wide margin (e.g. TransformingLazyColumn), and
    // search cost scales with sliceH × w, which is cheap.
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
    // Fallback: plain (unweighted) SAD per row, in case every candidate
    // overlap region is uniformly blank (all per-row stddevs = 0) so the
    // weighted score is undefined for every d.
    var bestPlainD = hintPx.coerceIn(lo, hi)
    var bestPlainScore = Double.POSITIVE_INFINITY

    for (d in lo..hi) {
        val n = sliceH - d
        if (n <= 0) break
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
