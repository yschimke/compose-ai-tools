package ee.schimke.composeai.scroll

import ee.schimke.composeai.io.SystemFileSystem
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
import okio.FileSystem
import okio.Path.Companion.toPath

/**
 * One captured slice — the on-disk PNG plus the cumulative scroll offset (in layout pixels) the
 * scrollable reported at the time of capture. The offset is used as a **hint** to narrow the
 * overlap search; the actual alignment is driven by pixel content ([findOverlapShift]).
 *
 * [measured] says the offset was read off the content itself (the driver measured how far the
 * scrollable's descendants moved, see `driveScrollByViewport`) rather than off the scroller's
 * `ScrollAxisRange`, which on a lazy list is not a pixel position at all. A measured hint is
 * trusted to within [MEASURED_HINT_SLACK_FRAC] of the viewport; a reported one gets the generous
 * window the scrollers' known lies require.
 */
data class SliceCapture(
  val scrolledLayoutPx: Float,
  val file: File,
  val measured: Boolean = false,
)

/**
 * How one seam between consecutive slices was cut, for the caller's diagnostics.
 *
 * [shiftPx] is the vertical offset the matcher settled on (0 when the pair showed no motion), over
 * [overlapRows] rows of shared content. [weightedSadPerPixel] is the match residual at that shift —
 * per-pixel luminance difference across the overlap, weighted towards varied rows — and [verified]
 * is whether it sits under [SEAM_MAX_WEIGHTED_SAD_PER_PIXEL]. An unverified seam is one where no
 * candidate shift made the two slices agree: the scroller landed somewhere the content does not
 * corroborate, chrome moved between captures, or the overlap was too thin. The stitcher still
 * paints its best guess; the flag is what lets a consumer refuse to trust it.
 */
data class ScrollSeam(
  val index: Int,
  val hintPx: Int,
  val shiftPx: Int,
  val overlapRows: Int,
  /**
   * Rows in the overlap that carry signal — horizontal luminance stddev at or above
   * [INFORMATIVE_ROW_MIN_STDDEV] in either slice. Black background, the body of a button between
   * its text lines, a blank spacer: none of those count.
   */
  val informativeRows: Int,
  /**
   * The summed stddev of those rows — how much the overlap had to align on. The alignment was
   * decided by this signal alone, and a seam under [MIN_OVERLAP_SIGNAL] is reported as
   * [Verdict.LOW_SIGNAL] rather than trusted.
   */
  val signal: Double,
  val weightedSadPerPixel: Double,
  val plainSadPerPixel: Double,
  /** Whether [hintPx] was measured off the content by the driver ([SliceCapture.measured]). */
  val measuredHint: Boolean = false,
) {
  enum class Verdict {
    /** Enough signal in the overlap and the two slices agree at [shiftPx]. */
    VERIFIED,
    /**
     * The overlap had too little varied content for the pixels to decide an alignment, but the
     * driver measured the stride off the content's semantics bounds and the seam sits on that.
     * Trusted — a blank spacer between two sections is a legitimate thing to scroll past.
     */
    MEASURED,
    /**
     * The overlap had too little varied content to decide an alignment with, and the hint was only
     * what the scroller reported. The seam sits on that report.
     */
    LOW_SIGNAL,
    /** No candidate shift made the two slices agree; the seam is a guess. */
    MISMATCH,
  }

  val verdict: Verdict
    get() =
      when {
        shiftPx == 0 -> Verdict.VERIFIED
        signal < MIN_OVERLAP_SIGNAL -> if (measuredHint) Verdict.MEASURED else Verdict.LOW_SIGNAL
        weightedSadPerPixel > SEAM_MAX_WEIGHTED_SAD_PER_PIXEL -> Verdict.MISMATCH
        else -> Verdict.VERIFIED
      }

  val verified: Boolean
    get() = verdict == Verdict.VERIFIED || verdict == Verdict.MEASURED

  /** One-line, human-readable summary for logs and the warnings sidecar. */
  fun describe(): String =
    "seam $index: shift ${shiftPx}px (hint ${hintPx}px) over $overlapRows rows " +
      "($informativeRows with signal, ${"%.0f".format(java.util.Locale.ROOT, signal)} total), " +
      "residual ${"%.1f".format(java.util.Locale.ROOT, weightedSadPerPixel)}/px" +
      when (verdict) {
        Verdict.VERIFIED -> ""
        Verdict.MEASURED -> " (no signal in the overlap; placed by the measured stride)"
        Verdict.LOW_SIGNAL ->
          " — LOW SIGNAL (under ${"%.0f".format(java.util.Locale.ROOT, MIN_OVERLAP_SIGNAL)} of varied rows to align on)"
        Verdict.MISMATCH -> " — MISMATCH (residual above $SEAM_MAX_WEIGHTED_SAD_PER_PIXEL/px)"
      }
}

/**
 * Stitches per-viewport slices (see `driveScrollByViewport`) into a single tall PNG at
 * [outputFile].
 *
 * Content-aware alignment: each slice's vertical placement is decided by comparing pixel rows
 * against the previous slice, not by trusting the scroller's reported offset. We've seen
 * `TransformingLazyColumn` and spring-settled scrolls advance their semantic
 * `ScrollAxisRange.value` faster or slower than the visible content actually moves — a stitcher
 * keyed on semantics prints duplicated or dropped bands at each seam. The matcher here locks onto
 * whatever the images actually show.
 *
 * The caller should drive the scroller at less than one full viewport per step (e.g. 80 %) so
 * consecutive slice pairs have a physical overlap the matcher can lock onto.
 *
 * Per slice pair `(prev, next)`:
 * - Compute per-row luminance and per-row horizontal stddev (a cheap "interestingness" signal — a
 *   blank row has stddev ≈ 0, a row cutting through text or button edges has a large stddev).
 * - For each candidate shift `d` in `[hint × 0.5, hint × 1.2]` (clipped to `[1, sliceH)`), score `Σ
 *   w_k · rowSAD(prev[d+k], next[k]) / Σ w_k` with `w_k = max(prevStddev[d+k], nextStddev[k])`.
 * - Pick the `d` with the lowest score. The weighting means alignment is decided by varied rows
 *   (text, edges, icons) — vast tracts of matching blank background can't dominate the match.
 *
 * `pxPerLayoutPx` converts the scroller's layout-pixel delta to image pixels to seed the search
 * window. Robolectric's qualifier density often makes this 1.0, but we compute it from slice 0 to
 * stay honest.
 *
 * Output height = `sliceH + Σ d_i` (sum of measured per-pair shifts).
 *
 * Returns the written file, or `null` if [slices] is empty.
 */
fun stitchSlices(
  slices: List<SliceCapture>,
  viewportLayoutPx: Int,
  outputFile: File,
  fileSystem: FileSystem = SystemFileSystem,
  onSeam: ((ScrollSeam) -> Unit)? = null,
): File? {
  if (slices.isEmpty()) return null

  val stitched = buildStitchedImage(slices, viewportLayoutPx, fileSystem, onSeam) ?: return null
  outputFile.parentFile?.mkdirs()
  fileSystem.write(outputFile.path.toPath()) { ImageIO.write(stitched, "PNG", outputStream()) }
  return outputFile
}

/**
 * Variant of [stitchSlices] for captures whose last viewport contains animations that settle
 * *after* the scroll ends (Wear `EdgeButton` reveal, Material 3 FAB appear, AnimatedVisibility
 * fade-ins triggered by the list reaching its bottom).
 *
 * Motivation: the final entry of [slices] is captured while those animations are still mid-flight.
 * In the regular [stitchSlices] path only the bottom `d` rows of that slice are painted at the seam
 * (where `d` is the measured overlap shift ≈ one scroll step), so any animated element taller than
 * `d` is clipped at the top — classic symptom on Wear is two half-drawn EdgeButton pills
 * (mid-reveal from slice N-2 above a clipped settled one).
 *
 * Strategy: stitch every slice as usual (including the mid-animation last slice), then diff the
 * last slice against [finalFrameFile] to find the topmost row that actually changed between
 * mid-reveal and settled. Overwrite only that band (the animating tail) with the settled rows. Rows
 * above the diff band are left as the pair-wise stitch produced them — that preserves all list-item
 * positions from the during-scroll slices and keeps the seam confined to content the user will read
 * as one unit (the EdgeButton / FAB / snackbar region).
 *
 * Layout shift avoidance: on Wear, `ScreenScaffold` enlarges the edge- button slot once it fully
 * reveals, which pushes list items UP relative to the mid-reveal frame. A whole-viewport overwrite
 * therefore draws the settled list on top of the mid-scroll list at a slightly different y,
 * producing a ghost of each card's text. Diff-band overwrite confines the replacement to rows where
 * the two frames genuinely differ, so items in the untouched upper region stay at their original
 * slice positions.
 *
 * Falls back to writing [finalFrameFile] directly when [slices] has one entry (no scroll history).
 */
fun stitchSlicesWithFinalFrame(
  slices: List<SliceCapture>,
  finalFrameFile: File,
  viewportLayoutPx: Int,
  outputFile: File,
  isRound: Boolean = false,
  fileSystem: FileSystem = SystemFileSystem,
  onSeam: ((ScrollSeam) -> Unit)? = null,
): File? {
  if (slices.isEmpty()) return null

  val finalImage =
    ImageIO.read(fileSystem.read(finalFrameFile.path.toPath()) { readByteArray() }.inputStream())
      ?: error("Failed to read final frame PNG: $finalFrameFile")

  // Single slice: no scroll history. The settled final frame IS the
  // preview.
  if (slices.size == 1) {
    outputFile.parentFile?.mkdirs()
    fileSystem.write(outputFile.path.toPath()) { ImageIO.write(finalImage, "PNG", outputStream()) }
    return outputFile
  }

  val content =
    buildStitchedContent(
      slices,
      viewportLayoutPx,
      detectPinnedBottom = true,
      fileSystem = fileSystem,
      onSeam = onSeam,
      deferSeams = true,
    ) ?: return null
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
      // Only the seams whose painted band survived the cut are in the output. The last one or
      // two are usually below it — the EdgeButton reveal shifts the tail cards while they are
      // captured, so those seams score as mismatches, and none of their rows made it into the PNG.
      onSeam?.let { report ->
        content.seams.forEach { (seam, bandStart) -> if (bandStart < anchored.cutY) report(seam) }
      }
      outputFile.parentFile?.mkdirs()
      fileSystem.write(outputFile.path.toPath()) {
        ImageIO.write(anchored.image, "PNG", outputStream())
      }
      return outputFile
    }
  }
  onSeam?.let { report -> content.seams.forEach { (seam, _) -> report(seam) } }

  val diffStart = findFirstDifferingRow(content.lastSliceImage, finalImage)
  if (diffStart < 0) {
    // No animating tail — settled frame equals mid-reveal slice.
    // [buildStitchedContent] already painted the last-slice pinned
    // band into the reserved tail via the fallback in
    // [buildStitchedImage], so the normal stitch is already correct.
    outputFile.parentFile?.mkdirs()
    fileSystem.write(outputFile.path.toPath()) { ImageIO.write(topImage, "PNG", outputStream()) }
    return outputFile
  }

  // Position the final-frame overlay: the last slice's pinned-bottom
  // region lives at output rows [contentYEnd..topH); anything in the
  // final frame above `pinnedBottomTop` that differs from the last
  // slice is animating tail (EdgeButton list-shift, FAB slide-in)
  // that should also be overlaid. Paint final rows [diffStart..finalH)
  // at the corresponding position relative to `contentYEnd`.
  val overwriteY = (content.contentYEnd - (content.pinnedBottomTop - diffStart)).coerceAtLeast(0)
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
      0,
      overwriteY,
      width,
      overwriteY + bandHeight,
      0,
      diffStart,
      width,
      finalH,
      null,
    )
  } finally {
    g.dispose()
  }

  outputFile.parentFile?.mkdirs()
  fileSystem.write(outputFile.path.toPath()) { ImageIO.write(composed, "PNG", outputStream()) }
  return outputFile
}

/**
 * Finds the topmost row where two same-sized images differ beyond an anti-aliasing /
 * rendering-noise tolerance, or `-1` if they match throughout. Used to locate the start of the
 * settle-animation band between the last mid-reveal slice and the final settled frame.
 *
 * The per-row threshold `FINAL_FRAME_DIFF_ROW_THRESHOLD` is tolerant of a few stray anti-aliasing
 * differences along card edges while still triggering on wholesale content shifts (EdgeButton
 * expanding, snackbar appearing, list items sliding up by a pixel).
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
      val lumA =
        (((pa ushr 16) and 0xFF) * 299 + ((pa ushr 8) and 0xFF) * 587 + (pa and 0xFF) * 114) / 1000
      val lumB =
        (((pb ushr 16) and 0xFF) * 299 + ((pb ushr 8) and 0xFF) * 587 + (pb and 0xFF) * 114) / 1000
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
 * Per-pixel luminance tolerance used by [bottomPinnedRowsTop] when deciding whether two slices show
 * identical content at a slice-local row. Matches `FINAL_FRAME_DIFF_ROW_THRESHOLD`'s intent — it's
 * above the Robolectric capture + AA noise floor but below any real visual change (the
 * peek-EdgeButton-pill vs list-card distinction, for example, comes in at ≥ 50 per pixel on
 * luminance).
 */
private const val PINNED_ROW_DIFF_THRESHOLD = 8L

/**
 * Shared state between [buildStitchedImage] and its final-frame-aware sibling — lets
 * [stitchSlicesWithFinalFrame] paint the settled chrome overlay at the correct position without
 * re-reading slice PNGs or redoing the matcher.
 */
private data class StitchedContent(
  val image: BufferedImage,
  val pinnedBottomTop: Int,
  val sliceH: Int,
  val contentYEnd: Int,
  val lastSliceImage: BufferedImage,
  /** Every seam the matcher cut, in order, with the output row its painted band starts at. */
  val seams: List<Pair<ScrollSeam, Int>> = emptyList(),
)

/**
 * Core stitching routine. Produces a tall image containing every slice's scrollable (non-pinned)
 * content concatenated top-to-bottom, with the pinned-bottom region of each slice deliberately left
 * transparent — the final-frame overlay in [stitchSlicesWithFinalFrame] paints the settled chrome
 * there, so it appears exactly once at the output tail.
 *
 * Pinned-bottom detection ([bottomPinnedRowsTop]) walks each slice pair from the bottom upward and
 * records the topmost row where the pair starts differing. The median across pairs is the
 * slice-local y below which every slice carries scroll-independent chrome (Wear `EdgeButton` peek
 * pill, `ScrollIndicator`, FAB, snackbar) or blank scaffold-reserved background. Because the
 * per-pair walk stops at the first divergent row, top-pinned chrome (e.g. `TimeText`) is never
 * marked — it remains visible in slice 0's contribution at the top of the output.
 *
 * The matcher ([findOverlapShift]) is re-run with `rowLimit = pinnedBottomTop` so the shift it
 * picks reflects the scroll step in the list region only. Without that, the pinned-bottom band
 * (identical across slices) biases the SAD score toward small shifts, which in turn leaves
 * transparent gaps when the painter later skips those rows.
 *
 * The output advances `yPrev` by the number of rows actually painted (not by the nominal `d`),
 * producing a continuous vertical strip of list content with no transparent gaps between slices.
 * When no pinned region is detected (`pinnedBottomTop == sliceH`), this degrades to the original
 * full-slice paint and output height stays at `sliceH + Σ d_i` — backward-compatible with existing
 * [stitchSlices] tests.
 */
private fun buildStitchedContent(
  slices: List<SliceCapture>,
  viewportLayoutPx: Int,
  detectPinnedBottom: Boolean = false,
  fileSystem: FileSystem = SystemFileSystem,
  onSeam: ((ScrollSeam) -> Unit)? = null,
  deferSeams: Boolean = false,
): StitchedContent? {
  if (slices.isEmpty()) return null

  val firstImage =
    ImageIO.read(fileSystem.read(slices[0].file.path.toPath()) { readByteArray() }.inputStream())
      ?: error("Failed to read first slice PNG: ${slices[0].file}")
  val width = firstImage.width
  val sliceH = firstImage.height
  val pxPerLayoutPx = sliceH.toDouble() / viewportLayoutPx.toDouble()

  val images =
    List(slices.size) { i ->
      val img =
        if (i == 0) {
          firstImage
        } else {
          ImageIO.read(
            fileSystem.read(slices[i].file.path.toPath()) { readByteArray() }.inputStream()
          ) ?: error("Failed to read slice PNG: ${slices[i].file}")
        }
      require(img.width == width && img.height == sliceH) {
        "Slice dimensions drifted: expected ${width}x$sliceH, got ${img.width}x${img.height} at index $i"
      }
      img
    }
  val luminance = images.map { readLuminanceRows(it) }
  val weights = luminance.map { rowStddevs(it) }

  // Pinned-bottom detection is only meaningful for captures that have a
  // settled-frame overlay (Wear EdgeButton path) — that flow needs the
  // pinned region masked off intermediate slices so peek-pill ghosts don't
  // leak into the stitched output. The legacy [stitchSlices] path has no
  // settled frame and no pinned chrome, so leaving it always-on caused
  // false positives on synthetic test fixtures whose grey backgrounds
  // happened to match across slices and shrank the matchable list region.
  val pinnedBottomTop =
    if (detectPinnedBottom) {
      bottomPinnedRowsTop(luminance, width, sliceH)
    } else {
      sliceH
    }

  val shifts = IntArray(slices.size - 1)
  val seams = mutableListOf<ScrollSeam>()
  for (i in 1 until slices.size) {
    val reportedDelta = slices[i].scrolledLayoutPx - slices[i - 1].scrolledLayoutPx
    val measured = slices[i].measured && slices[i - 1].measured
    if (reportedDelta <= 0f) {
      shifts[i - 1] = 0
      seams += ScrollSeam(i - 1, 0, 0, 0, 0, 0.0, 0.0, 0.0, measured)
      continue
    }
    val hintPx = (reportedDelta * pxPerLayoutPx).roundToInt()
    val match =
      findOverlapShift(
        prevLum = luminance[i - 1],
        nextLum = luminance[i],
        prevW = weights[i - 1],
        nextW = weights[i],
        sliceH = sliceH,
        hintPx = hintPx,
        rowLimit = pinnedBottomTop,
        measuredHint = measured,
      )
    shifts[i - 1] = match.shift
    seams +=
      ScrollSeam(
        index = i - 1,
        hintPx = hintPx,
        shiftPx = match.shift,
        overlapRows = match.overlapRows,
        informativeRows = match.informativeRows,
        signal = match.signal,
        weightedSadPerPixel = match.weightedSadPerPixel,
        plainSadPerPixel = match.plainSadPerPixel,
        measuredHint = measured,
      )
  }

  // Rows painted per intermediate slice = min(d, pinnedBottomTop).
  // Anything above that is redundant (seen in a prior slice) or in
  // the pinned band (handled by the final-frame overlay).
  val rowsPainted =
    IntArray(shifts.size) { idx ->
      val d = shifts[idx]
      if (d <= 0) 0 else minOf(d, pinnedBottomTop)
    }
  val contentHeight = pinnedBottomTop + rowsPainted.sum()
  val tailHeight = sliceH - pinnedBottomTop
  val totalHeight = contentHeight + tailHeight

  val stitched = BufferedImage(width, totalHeight, BufferedImage.TYPE_INT_ARGB)
  val seamBands = mutableListOf<Pair<ScrollSeam, Int>>()
  val g = stitched.createGraphics()
  try {
    // First slice: paint only the list region. The pinned tail stays
    // transparent until the caller overlays the settled chrome.
    if (pinnedBottomTop > 0) {
      g.drawImage(images[0], 0, 0, width, pinnedBottomTop, 0, 0, width, pinnedBottomTop, null)
    }
    var yPrev = pinnedBottomTop
    for (i in 1 until images.size) {
      val rows = rowsPainted[i - 1]
      seamBands += seams[i - 1] to yPrev
      if (rows <= 0) continue
      // Source rows [pinnedBottomTop - rows, pinnedBottomTop) — the
      // new content added at the bottom of the list region since
      // the previous slice. Dest rows [yPrev, yPrev + rows). Skip
      // the pinned band entirely (never painted from intermediate
      // slices, so no ghost chrome can survive into the output).
      g.drawImage(
        images[i],
        0,
        yPrev,
        width,
        yPrev + rows,
        0,
        pinnedBottomTop - rows,
        width,
        pinnedBottomTop,
        null,
      )
      yPrev += rows
    }
  } finally {
    g.dispose()
  }
  // The plain (no final frame) callers keep every seam; [stitchSlicesWithFinalFrame] filters by
  // what its anchored cut actually kept before it reports.
  if (onSeam != null && !deferSeams) seamBands.forEach { (seam, _) -> onSeam(seam) }
  return StitchedContent(
    image = stitched,
    pinnedBottomTop = pinnedBottomTop,
    sliceH = sliceH,
    contentYEnd = contentHeight,
    lastSliceImage = images.last(),
    seams = seamBands,
  )
}

/** Legacy entry point preserved for [stitchSlices]. */
private fun buildStitchedImage(
  slices: List<SliceCapture>,
  viewportLayoutPx: Int,
  fileSystem: FileSystem = SystemFileSystem,
  onSeam: ((ScrollSeam) -> Unit)? = null,
): BufferedImage? =
  buildStitchedContent(slices, viewportLayoutPx, fileSystem = fileSystem, onSeam = onSeam)?.let { s
    ->
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
          0,
          s.contentYEnd,
          s.image.width,
          s.contentYEnd + (s.sliceH - s.pinnedBottomTop),
          0,
          s.pinnedBottomTop,
          s.image.width,
          s.sliceH,
          null,
        )
      } finally {
        g.dispose()
      }
    }
    s.image
  }

/**
 * Walks each adjacent slice pair from the bottom upward, finding the topmost slice-local row where
 * the pair starts differing. Everything at or below that row for the pair is content that didn't
 * move between slices captured at different scroll positions — pinned chrome or uniformly blank
 * scaffold-reserved background. Returns the median across pairs (robust to one anomalous pair where
 * the peek chrome hasn't appeared yet or has fully transitioned), or [sliceH] when there aren't
 * enough slices to vote.
 */
private fun bottomPinnedRowsTop(luminance: List<Array<IntArray>>, width: Int, sliceH: Int): Int {
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

/** The matcher's answer for one slice pair — see [findOverlapShift]. */
private data class ShiftMatch(
  val shift: Int,
  val overlapRows: Int,
  val informativeRows: Int,
  val signal: Double,
  val weightedSadPerPixel: Double,
  val plainSadPerPixel: Double,
)

/**
 * Finds the vertical shift `d` that best aligns `prev[d..sliceH)` with `next[0..sliceH-d)`. Uses
 * [hintPx] (the reported delta converted to image pixels) to narrow the search window: a *measured*
 * hint ([measuredHint], the driver read the content's own displacement) is trusted to within
 * [MEASURED_HINT_SLACK_FRAC] of the viewport, a reported one only loosely — the scroller's offset
 * can be wildly inaccurate (that's the bug we're fixing).
 *
 * Rows are weighted by their horizontal stddev so blank rows contribute ~nothing — the alignment is
 * decided by text, edges, and varied-colour rows that can't accidentally match at the wrong offset.
 * If the weighted signal is degenerate (every candidate overlap region is uniformly blank), we fall
 * back to a plain rowSAD score so the matcher still produces a sensible answer.
 *
 * **A candidate is only eligible when its overlap carries signal.** Two floors: it has to overlap
 * by at least [minOverlapRows] rows (default [MIN_OVERLAP_FRAC] of the viewport), and the
 * *informative* rows among them — horizontal stddev at or above [INFORMATIVE_ROW_MIN_STDDEV] in
 * either slice — have to sum to at least [MIN_OVERLAP_SIGNAL]. Black background scores 0; the body
 * of a button between its text lines scores a little for its edges; a row through text or an icon
 * scores high. So one line of text, or half a dozen rows of chip edges, is enough to decide on, and
 * a dozen black rows with two rows of anti-aliased glyph tops is not. Without the floors the window
 * reaches shifts that leave two or three rows in common, and a handful of blank rows at one slice's
 * foot and the next slice's head score a near-perfect match — better than the true shift, whose
 * seventy-odd rows of shared content carry sub-pixel anti-aliasing and whatever chrome is drawn
 * over the top of the viewport (a Wear `TimeText` sits on top of the scrolling list, so the head of
 * every slice differs from the same content seen lower down in the previous one). Measured on
 * horologist's sectioned-list previews: the true 307 px shift on a 384 px viewport scored 8.5/px; a
 * 372 px shift over 12 black rows scored 0.3/px and won, painting the next slice from its top row —
 * `TimeText` and all — sixty rows too high, so the seam repeated a header and stamped `10:10` into
 * the middle of the list.
 *
 * **Ties go to the hint.** Repeating templates — a column of identical chips, a list of cards that
 * differ only in their text — match equally well one item-pitch apart when the overlap happens to
 * cut through their bodies. Candidates within [TIE_MARGIN_PER_PIXEL] of the best score are a tie,
 * and the one nearest [hintPx] wins, so an ambiguous overlap defers to what the scroller said
 * instead of to whichever repeat the search visited first.
 *
 * **A reported hint bounds the search above, not below.** A scroller clamps at its content end, so
 * the last stride of a walk can move the content anywhere from nothing to the full step while the
 * driver credits the whole step (a lazy list's `ScrollAxisRange.value` says nothing about pixels).
 * The window for a reported hint therefore runs from 1 to `3 × hint`; the old `hint / 3` floor put
 * an 82 px end-of-list landing outside a 363 px hint's window and forced a mismatch.
 */
private fun findOverlapShift(
  prevLum: Array<IntArray>,
  nextLum: Array<IntArray>,
  prevW: DoubleArray,
  nextW: DoubleArray,
  sliceH: Int,
  hintPx: Int,
  rowLimit: Int = sliceH,
  measuredHint: Boolean = false,
  minOverlapRows: Int = minOverlapRowsFor(sliceH),
): ShiftMatch {
  val maxShift = sliceH - 1
  if (maxShift < 1) return ShiftMatch(0, 0, 0, 0.0, 0.0, 0.0)

  // End-of-scroll overreport guard (issue #2299) — score a ZERO shift now, decide after the search.
  // A scroller can keep reporting a growing offset after the visible content has already reached
  // its
  // end (a `LazyList`/`TransformingLazyColumn` whose `maxValue` overshoots pins the last item at
  // the
  // bottom), so consecutive slices are essentially identical. The hint-narrowed window below starts
  // at `hintPx / 3`, so it can't see the true ~0 shift and is forced to a spurious large one —
  // which
  // re-paints the last item's tail lower down as a ghost band. `zeroWeightedMean` is the weighted
  // per-row SAD at no offset; the actual no-move decision is made at the return below, gated on it
  // being both near-identical in absolute terms AND no worse than the best real shift (a genuine
  // move always matches strictly better at its true offset, so this can't drop real content).
  val rowWidth = prevLum.firstOrNull()?.size ?: 0
  var zeroWeightedMean = Double.POSITIVE_INFINITY
  var zeroPlainMean = Double.POSITIVE_INFINITY
  if (rowWidth > 0) {
    val n = minOf(sliceH, rowLimit)
    if (n > 0) {
      var weightSum = 0.0
      var weightedCost = 0.0
      var plainCost = 0.0
      for (k in 0 until n) {
        val sad = rowSad(prevLum[k], nextLum[k])
        plainCost += sad
        val w = max(prevW[k], nextW[k])
        if (w > 0.0) {
          weightedCost += w * sad
          weightSum += w
        }
      }
      zeroPlainMean = plainCost / n
      if (weightSum > 0.0) zeroWeightedMean = weightedCost / weightSum
    }
  }

  // The largest shift that still leaves `minOverlapRows` rows of shared, matchable content —
  // shared in the list region when a pinned band caps `rowLimit`.
  val overlapCeiling = (minOf(sliceH, rowLimit) - minOverlapRows).coerceIn(1, maxShift)
  var lo: Int
  var hi: Int
  if (hintPx > 0 && measuredHint) {
    val slack = max(MEASURED_HINT_MIN_SLACK_PX, (sliceH * MEASURED_HINT_SLACK_FRAC).roundToInt())
    lo = (hintPx - slack).coerceIn(1, maxShift)
    hi = (hintPx + slack).coerceIn(lo, maxShift)
  } else if (hintPx > 0) {
    lo = 1
    hi = (hintPx * 3).coerceIn(lo, maxShift)
  } else {
    lo = 1
    hi = maxShift
  }
  // Apply the overlap floor, unless it would empty the window (a viewport too short for the floor
  // to mean anything — keep the historical search rather than answer nothing).
  if (overlapCeiling >= lo) hi = minOf(hi, overlapCeiling)

  // Per-candidate scores, kept so ties can be broken by hint proximity after the search.
  val count = (hi - lo + 1).coerceAtLeast(0)
  val weightedScores = DoubleArray(count) { Double.POSITIVE_INFINITY }
  val plainScores = DoubleArray(count) { Double.POSITIVE_INFINITY }
  val overlapRows = IntArray(count)
  val informativeRows = IntArray(count)
  val signals = DoubleArray(count)
  var bestWeightedScore = Double.POSITIVE_INFINITY
  var bestPlainScore = Double.POSITIVE_INFINITY
  // The plain fallback ignores the signal floor (it exists for overlaps with no signal at all) but
  // still honours the overlap floor through `hi`.
  var bestPlainD = hintPx.coerceIn(lo, hi)
  var bestPlainN = 0

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
    var informative = 0
    var signal = 0.0
    for (k in 0 until n) {
      val w = max(prevW[d + k], nextW[k])
      val sad = rowSad(prevLum[d + k], nextLum[k])
      plainCost += sad
      if (w > 0.0) {
        weightedCost += w * sad
        weightSum += w
      }
      if (w >= INFORMATIVE_ROW_MIN_STDDEV) {
        informative++
        signal += w
      }
    }
    val idx = d - lo
    overlapRows[idx] = n
    informativeRows[idx] = informative
    signals[idx] = signal
    val plainScore = plainCost / n
    plainScores[idx] = plainScore
    if (plainScore < bestPlainScore) {
      bestPlainScore = plainScore
      bestPlainD = d
      bestPlainN = n
    }
    if (weightSum > 0.0 && signal >= MIN_OVERLAP_SIGNAL) {
      val score = weightedCost / weightSum
      weightedScores[idx] = score
      if (score < bestWeightedScore) bestWeightedScore = score
    }
  }

  // Tie-break: among candidates within the margin of the best weighted score, the one nearest the
  // hint. Periodic content (identical chip bodies one pitch apart) is the case this decides.
  var bestWeightedD = -1
  if (bestWeightedScore.isFinite()) {
    val tieCutoff = bestWeightedScore + TIE_MARGIN_PER_PIXEL * max(rowWidth, 1)
    var bestDistance = Int.MAX_VALUE
    for (idx in 0 until count) {
      if (weightedScores[idx] > tieCutoff) continue
      val d = lo + idx
      val distance = kotlin.math.abs(d - hintPx)
      if (distance < bestDistance) {
        bestDistance = distance
        bestWeightedD = d
      }
    }
  }

  // No-move decision (issue #2299): the zero-offset match is near-identical in absolute terms AND
  // no
  // worse than the best in-window shift. A real move matches strictly better at its true offset, so
  // the zero-offset score only ties/wins when nothing actually scrolled — then report 0 so the
  // caller paints no seam band (no duplicated tail). Compare against the PLAIN (unweighted) score,
  // not the weighted one: on solid/blank overlaps the weighted matcher finds no candidate
  // (`bestWeightedScore == +∞`), which would make a weighted `<=` vacuously true and let a static
  // header at zero offset mask a real scroll step (Codex review, PR #2629). Plain SAD weights every
  // row equally, so a small static header can't hide scrolled content below it.
  if (
    rowWidth > 0 &&
      bestPlainScore.isFinite() &&
      zeroPlainMean <= bestPlainScore &&
      zeroWeightedMean / rowWidth <= NO_MOVE_MAX_SAD_PER_PIXEL
  ) {
    val n = minOf(sliceH, rowLimit)
    return ShiftMatch(
      0,
      n,
      n,
      MIN_OVERLAP_SIGNAL,
      zeroWeightedMean / rowWidth,
      zeroPlainMean / rowWidth,
    )
  }

  val perPixel = if (rowWidth > 0) rowWidth.toDouble() else 1.0
  return if (bestWeightedD >= 0) {
    val idx = bestWeightedD - lo
    ShiftMatch(
      shift = bestWeightedD,
      overlapRows = overlapRows[idx],
      informativeRows = informativeRows[idx],
      signal = signals[idx],
      weightedSadPerPixel = weightedScores[idx] / perPixel,
      plainSadPerPixel = plainScores[idx] / perPixel,
    )
  } else {
    // No candidate cleared the signal floor: nothing varied enough to align on. Fall back to the
    // plain score so the caller still gets a placement, and report the (low) signal honestly so the
    // seam is flagged rather than trusted.
    val idx = (bestPlainD - lo).coerceIn(0, (count - 1).coerceAtLeast(0))
    ShiftMatch(
      shift = bestPlainD,
      overlapRows = bestPlainN,
      informativeRows = if (count > 0) informativeRows[idx] else 0,
      signal = if (count > 0) signals[idx] else 0.0,
      weightedSadPerPixel = bestPlainScore / perPixel,
      plainSadPerPixel = bestPlainScore / perPixel,
    )
  }
}

/** The overlap floor for a [sliceH]-row viewport — see [findOverlapShift]. */
private fun minOverlapRowsFor(sliceH: Int): Int =
  max(MIN_OVERLAP_ROWS_FLOOR, (sliceH * MIN_OVERLAP_FRAC).roundToInt())

/**
 * Converts each row of [img] into an `IntArray` of 0..255 luminance values (Rec. 601 weighting).
 * Luminance — rather than raw ARGB — makes the row SAD dominated by perceived brightness
 * differences, which lines up with "interesting rows" as a human reads them.
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
 * Horizontal standard deviation of each row — the per-row "interestingness" weight. A blank row
 * (black/white/single-colour background) scores ≈ 0; a row cutting through text, a chip border, or
 * an icon scores high.
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
 * Height of the anchor band (in slice-local pixels) used by [anchorByEdgeButton] to locate the last
 * list item in the stitched content. 48 px covers roughly one Wear `TitleCard` including its bottom
 * rounded-corner rows — enough signal to uniquely match, narrow enough to fit above the EdgeButton
 * on a 454-px viewport.
 */
private const val ANCHOR_BAND_ROWS = 48

/**
 * Minimum anchor band height, used when `edgeButtonTop` is near the viewport top and we have to
 * shrink the band. Below this the match becomes ambiguous (too few distinguishing rows).
 */
private const val MIN_ANCHOR_BAND = 16

/**
 * Minimum summed per-row stddev across the anchor band. Blank regions (uniform background, padding
 * between cards) have near-zero stddev and match anywhere in the stitched content — bailing keeps
 * the heuristic from cutting the output at a meaningless seam.
 */
private const val MIN_ANCHOR_VARIATION = 200.0

/**
 * Per-pixel SAD threshold (on 0–255 luminance) for an anchor match to count. Real list content at
 * the right position SADs to ~0; a genuine mis-match (list item vs background vs peek pill) SADs to
 * 20–60+.
 */
private const val ANCHOR_MATCH_MAX_SAD_PER_PIXEL = 8.0

/**
 * Per-pixel weighted-SAD ceiling under which two consecutive slices count as "no visible motion" —
 * the scroller reported an advance but the content stayed put (end-of-scroll `maxValue` overshoot),
 * so the pair reveals no new rows. A genuine mid-scroll pair (the driver strides ~80% of the
 * viewport) shifts content far and scores well above this at zero offset; an end-of-scroll pair
 * that only jitters the pinned last item scores far below it. Measured on the issue-#2299 fixture:
 * the redundant tail pair scored ~13 vs ~34 for a real move, so 20 sits with margin on both sides.
 * See the guard in [findOverlapShift].
 */
private const val NO_MOVE_MAX_SAD_PER_PIXEL = 20.0

/**
 * Smallest overlap, as a fraction of the viewport, a candidate shift may leave and still be scored.
 * The LONG driver strides 80 % of the viewport, so the planned overlap is 20 %; a tenth leaves the
 * matcher room for a scroller that travelled further than asked while excluding the two-row
 * "matches" that undid horologist's sectioned lists (see [findOverlapShift]).
 */
private const val MIN_OVERLAP_FRAC = 0.10

/** Absolute floor under [MIN_OVERLAP_FRAC], for tiny synthetic viewports. */
private const val MIN_OVERLAP_ROWS_FLOOR = 8

/**
 * Horizontal luminance stddev at or above which a row counts as carrying signal for alignment. A
 * black or single-colour row is 0; a row cutting a dark Wear chip body with no text on black lands
 * around 15–20 (the edges alone); a row through text, an icon or a bright button sits at 40 and up.
 * 10 admits chip edges — a body-only overlap still says *something* about where the item sits —
 * while excluding anti-aliasing haze and the near-flat gradient of a dialog scrim.
 */
private const val INFORMATIVE_ROW_MIN_STDDEV = 10.0

/**
 * Summed stddev of a candidate overlap's informative rows before its score can win, and before a
 * seam counts as verified. 30 is a single row of real text (a Wear body-text row scores ~40, a
 * chequered synthetic one ~38 at its weakest), or three rows of bare chip edges (~10–15 each) — a
 * feature, not a stray edge. The 12-row black overlap that undid horologist's lists scored 0 (no
 * informative rows at all); the one-row black overlap at the far end of the window, 21.
 */
const val MIN_OVERLAP_SIGNAL: Double = 30.0

/**
 * Per-pixel weighted-SAD margin within which two candidate shifts are a tie (the nearer to the hint
 * wins). A true alignment and a one-pitch-off repeat of the same chip body both score under 2/px;
 * the same alignment one row off scores 4/px and more, so it never ties.
 */
private const val TIE_MARGIN_PER_PIXEL = 1.5

/**
 * Half-width of the search window around a *measured* hint (see [SliceCapture.measured]), as a
 * fraction of the viewport. The driver reads the displacement off semantics bounds in layout
 * pixels; the slack covers the layout→image rounding and a sub-pixel landing, nothing more.
 */
private const val MEASURED_HINT_SLACK_FRAC = 0.02

/** Absolute floor under [MEASURED_HINT_SLACK_FRAC]. */
private const val MEASURED_HINT_MIN_SLACK_PX = 4

/**
 * Per-pixel weighted-SAD ceiling under which a seam counts as verified ([ScrollSeam.verified]). A
 * true alignment of real Wear content scores 1–9/px (sub-pixel anti-aliasing plus a `TimeText`
 * drawn over the head of each slice); the runner-up candidate one row off scores 9–15/px and an
 * unrelated shift 25/px and up. 20 sits between "right, with chrome on top" and "one row off".
 */
const val SEAM_MAX_WEIGHTED_SAD_PER_PIXEL: Double = 20.0

/**
 * Minimum extent (as a fraction of image width) for a row to qualify as "inside" the Wear
 * `EdgeButton` band. The button is roughly the width of the round viewport at its widest point;
 * scan-line sampling hits 40–70 % of the width depending on where on the button the row cuts.
 */
private const val EDGE_BUTTON_MIN_EXTENT_FRAC = 0.30

/**
 * Minimum mean channel sum (r + g + b, 0–765) for a run of pixels to count as Wear Material3
 * primary. The full `EdgeButton` at primary- container lands at ~670; cards / background sit well
 * below 300.
 */
private const val EDGE_BUTTON_MIN_BRIGHTNESS_SUM = 500

/**
 * Minimum blue − green bias (on 0–255 channels, averaged across the run) — Wear Material3 primary
 * has a distinct purple cast. Gates out neutral-grey chrome that happens to be wide and bright
 * (dialogs, white snackbars) without being an EdgeButton.
 */
private const val EDGE_BUTTON_MIN_PURPLE_CAST = 10.0

/**
 * Wear-specific re-stitch: when the settled final frame actually shows an edge-hugging button, find
 * the last list item that sits immediately above that button, locate the same item in the top-down
 * stitched content, and glue the button region on directly after it. Nothing (ghost peek pills,
 * fading chrome, settle-animation residue) can land between them because we don't paint the rows
 * between in the first place.
 *
 * Returns `null` when the heuristic can't apply cleanly — no button shape in the final frame,
 * anchor band would be too thin, anchor band is blank / insufficiently varied, or no acceptable
 * match is found in the stitched content. Callers fall back to the established final-frame overlay
 * path in that case.
 */
/** [anchorByEdgeButton]'s output and the stitched row it cut the prefix at. */
private data class Anchored(val image: BufferedImage, val cutY: Int)

private fun anchorByEdgeButton(content: StitchedContent, finalImage: BufferedImage): Anchored? {
  val width = content.image.width
  val finalH = finalImage.height

  val edgeButtonTop = detectWearEdgeButtonTop(finalImage) ?: return null
  // Anchor against the bottom edge of the last list card, NOT the top
  // of the EdgeButton. `ScreenScaffold` leaves a blank spacer between
  // list content and the settled button; sampling 48 rows immediately
  // above `edgeButtonTop` mixes real card rows with ~30 rows of
  // empty-background, and the SAD matcher (bottom-up / first-below-
  // threshold) then accepts positions 1–30 rows inside any peek-pill
  // residue that's still in the stitched content — the fading-pill
  // region looks enough like the blank band to match. Walking up from
  // `edgeButtonTop` to the first row with real content puts the anchor
  // on rows that can only match where the last list item genuinely
  // sits in the stitch.
  val anchorBottomExclusive = lastContentRowBottomUp(finalImage, edgeButtonTop) ?: return null
  val anchorK = ANCHOR_BAND_ROWS.coerceAtMost(anchorBottomExclusive)
  if (anchorK < MIN_ANCHOR_BAND) return null
  val anchorTop = anchorBottomExclusive - anchorK

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

  // Output = stitched[0..matchEndY) + finalImage[anchorBottomExclusive..h).
  // The `[anchorBottomExclusive..edgeButtonTop)` span in the final
  // frame is the settled blank band between last card and button —
  // keeping it preserves the real spacing and makes the output always
  // transition "last card → blank gap → EdgeButton", matching the
  // settled Wear layout.
  val prefixHeight = matchEndY
  val tailStart = anchorBottomExclusive
  val tailHeight = finalH - tailStart
  val totalH = prefixHeight + tailHeight
  val out = BufferedImage(width, totalH, BufferedImage.TYPE_INT_ARGB)
  val g = out.createGraphics()
  try {
    g.drawImage(content.image, 0, 0, width, prefixHeight, 0, 0, width, prefixHeight, null)
    g.drawImage(
      finalImage,
      0,
      prefixHeight,
      width,
      prefixHeight + tailHeight,
      0,
      tailStart,
      width,
      finalH,
      null,
    )
  } finally {
    g.dispose()
  }
  return Anchored(out, prefixHeight)
}

/**
 * Walks [img] from `edgeButtonTop − 1` upward, returning the exclusive row index immediately below
 * the last row with non-trivial visible content — i.e. the smallest `y` such that every row in
 * `[y..edgeButtonTop)` is empty background. Returns `null` if the whole region above the button is
 * empty (no list content to anchor on).
 *
 * Used by [anchorByEdgeButton] to place the anchor band on the actual last list card instead of on
 * the scaffold-reserved blank spacer below it. "Content" is any opaque pixel with `r + g + b ≥
 * [CONTENT_MIN_BRIGHTNESS_SUM]` — low enough to catch card surfaces on dark Wear themes, high
 * enough to ignore Robolectric's AA haze.
 */
private fun lastContentRowBottomUp(img: BufferedImage, edgeButtonTop: Int): Int? {
  val w = img.width
  val rgb = IntArray(w)
  for (y in edgeButtonTop - 1 downTo 0) {
    img.getRGB(0, y, w, 1, rgb, 0, w)
    for (x in 0 until w) {
      val p = rgb[x]
      val alpha = (p ushr 24) and 0xFF
      if (alpha == 0) continue
      val r = (p ushr 16) and 0xFF
      val gg = (p ushr 8) and 0xFF
      val b = p and 0xFF
      if (r + gg + b >= CONTENT_MIN_BRIGHTNESS_SUM) return y + 1
    }
  }
  return null
}

/**
 * Per-pixel brightness floor for a row to count as "real content" in [lastContentRowBottomUp]. 120
 * lands above dark-theme card surfaces (Wear `TitleCard` on black reads ~r+g+b = 150) but below the
 * kind of AA haze that stray compositor transparency leaves above the EdgeButton spacer.
 */
private const val CONTENT_MIN_BRIGHTNESS_SUM = 120

/**
 * Scans [img] from its vertical midpoint down to the bottom for the first row that looks like the
 * top edge of a Wear Material3 `EdgeButton` — wide, bright, with a purple cast. Returns the row
 * index (`y` in slice-local coordinates), or `null` if no such row is found. Restricted to the
 * bottom half because `EdgeButton` hugs the bottom of the round viewport by definition.
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
 * Reads `[y0, y0 + h)` of [img] as a matrix of per-row luminance values. Shared shape with
 * [readLuminanceRows] — uses Rec. 601 weighting — but avoids the full-image allocation cost when we
 * only need a narrow band (anchor) or a prefix (painted-content region).
 */
private fun readLuminanceRowsOfRegion(img: BufferedImage, y0: Int, h: Int): Array<IntArray> {
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
 * Scans `topLum` for the position whose `anchor.size` rows best match `anchor`, among those under
 * the per-pixel SAD threshold [ANCHOR_MATCH_MAX_SAD_PER_PIXEL]. Returns the row *after* the matched
 * band — i.e. anchor matched at `topLum[y − anchor.size, y)` — suitable for use as a prefix-end cut
 * point, or `null` if no position hits the threshold.
 *
 * Biased toward the bottom, but only among near-equal scores: once the EdgeButton reveals, the last
 * list item appears near the bottom of the stitched strip, and a plain global-min search can prefer
 * an earlier similar-content scroll position (real Wear previews repeat card templates; synthetic
 * jitter patterns repeat by construction) — which would glue the EdgeButton on far above the true
 * scroll end and throw away the tail of the stitched content. So we keep the "most recent
 * occurrence" intent by walking bottom-up, but require a lower candidate to score within
 * [ANCHOR_MATCH_TIE_MARGIN_PER_PIXEL] of the best one before it wins.
 *
 * Why not simply take the bottommost *acceptable* match (the original behaviour):
 * [ANCHOR_MATCH_MAX_SAD_PER_PIXEL] is a fixed cutoff, so on a list of visually-similar rows a wrong
 * position one row-pitch below the true one can also land under it — its score sits near the cutoff
 * rather than near zero. Whether it did was decided by sub-pixel text rendering, so the same commit
 * stitched two different images on different runs: the taller one repeated the final list row, and
 * the output grew by exactly one row height. Scoring every candidate and demanding near-parity with
 * the best makes the choice depend on how well the rows actually match rather than on which side of
 * a fixed threshold noise pushed them.
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

  // Bottom-up, so `candidates` is already ordered bottommost-first.
  val candidates = mutableListOf<Pair<Int, Long>>()
  var bestSad = Long.MAX_VALUE
  for (y in h downTo k) {
    var sad = 0L
    var overCutoff = false
    for (kk in 0 until k) {
      sad += rowSad(anchor[kk], topLum[y - k + kk])
      if (sad > perPixelCutoff) {
        overCutoff = true
        break
      }
    }
    if (overCutoff) continue
    candidates += y to sad
    if (sad < bestSad) bestSad = sad
  }
  if (candidates.isEmpty()) return null

  val tieCutoff = bestSad + (ANCHOR_MATCH_TIE_MARGIN_PER_PIXEL * k * width).toLong()
  return candidates.first { (_, sad) -> sad <= tieCutoff }.first
}

/**
 * How much worse (in per-pixel 0–255 luminance SAD) than the best match a *lower* anchor position
 * may score and still be preferred for its position. The true last-item position SADs to ~0; a
 * genuine repeat of the same card template elsewhere in the strip also SADs to ~0, so near-parity
 * is the right test for "these are the same content, take the later one". A row-pitch-off mismatch
 * scores well above 1 per pixel even when it squeaks under [ANCHOR_MATCH_MAX_SAD_PER_PIXEL], so it
 * can no longer win on position alone.
 */
private const val ANCHOR_MATCH_TIE_MARGIN_PER_PIXEL = 1.0

/**
 * Clips [file]'s image into a pill/stadium shape: half-circle at the top, rectangular middle,
 * half-circle at the bottom. Width determines the circle diameter. Pixels outside the shape become
 * transparent.
 *
 * Used on stitched `@ScrollingPreview(LONG)` outputs for round Wear devices, so the rendered scroll
 * visually preserves the round screen edge at the top of the first frame and the bottom of the last
 * frame.
 */
fun applyWearPillClip(file: File, fileSystem: FileSystem = SystemFileSystem) {
  val src =
    ImageIO.read(fileSystem.read(file.path.toPath()) { readByteArray() }.inputStream()) ?: return
  val w = src.width
  val h = src.height
  if (h <= 0 || w <= 0) return

  val radius = w / 2.0

  // Union of: top half-circle (centred at y=r), middle rectangle, bottom
  // half-circle (centred at y=h-r). For h < 2r (too short to be a proper
  // pill), fall back to a single ellipse.
  val pill: Area =
    if (h >= 2 * radius) {
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
  fileSystem.write(file.path.toPath()) { ImageIO.write(out, "PNG", outputStream()) }
}
