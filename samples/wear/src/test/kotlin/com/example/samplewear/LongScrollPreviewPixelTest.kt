package com.example.samplewear

import com.google.common.truth.Truth.assertThat
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import org.junit.Test

/**
 * End-to-end regression for `@ScrollingPreview(modes = [LONG])` on `ActivityListLongPreview`
 * (`TransformingLazyColumn` + `EdgeButton`): the stitched PNG is taller than the viewport, and its
 * top / middle / bottom thirds differ. samples/wear's `testDebugUnitTest` depends on
 * `composePreviewRenderAll`, so the PNG exists.
 */
class LongScrollPreviewPixelTest {

  private val scrollLongDir = File("build/compose-previews/data/render-scroll-long")
  private val scrollGifDir = File("build/compose-previews/data/render-scroll-gif")

  private val longPng = renderFile(scrollLongDir, "ActivityListLongPreview_Devices_Large_Round")

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

    // Dark cards on black keep mean RGB close, so the bar is low; bottom→top is the strongest
    // signal because only the bottom has the light-purple EdgeButton.
    assertThat(distance(top, bot)).isGreaterThan(5.0)
    assertThat(distance(top, mid)).isGreaterThan(0.5)
    assertThat(distance(mid, bot)).isGreaterThan(0.5)
  }

  /**
   * Regression for `@ScrollingPreview(reduceMotion = true)`: if Wear's `LocalReduceMotion` isn't
   * honoured, edge items are captured mid-scale and reappear as narrower ghost cards. All
   * `TitleCard`s are `fillMaxWidth()`, so rows in the [0.40, 0.70) width band should be rare.
   * Measured: 4.2% fixed vs 19.5% broken; gated at 10%.
   */
  @Test
  fun `LONG preview has no scaled-card ghost rows at slice seams`() {
    assertNoScaledCardGhostRows(ImageIO.read(longPng))
  }

  /**
   * Regression for LONG + GIF on one annotation (Confetti's `HomeListViewLongPreview`): the
   * renderer must force `LocalReduceMotion = true` for the LONG still regardless of flags, or the
   * same mid-scale width-band signature appears.
   */
  @Test
  fun `LONG capture always flattens motion in a multi-mode annotation`() {
    val motionLongPng =
      renderFile(scrollLongDir, "ActivityListMotionLongPreview_Devices_Large_Round", "_SCROLL_long")
    assertThat(motionLongPng.exists()).isTrue()
    val img = ImageIO.read(motionLongPng)
    // Multi-slice stitch, not a single-frame fallback.
    assertThat(img.height).isGreaterThan(454 * 2)
    assertNoScaledCardGhostRows(img)
  }

  /**
   * The GIF half of that annotation still encodes an animated scroll. Only decodability and frame
   * count are asserted; the LONG test above is the regression gate.
   */
  @Test
  fun `GIF sibling of forced-flatten LONG still animates`() {
    val gif =
      renderFile(
        scrollGifDir,
        "ActivityListMotionLongPreview_Devices_Large_Round",
        "_SCROLL_gif",
        ext = "gif",
      )
    assertThat(gif.exists()).isTrue()
    assertThat(readGifFrames(gif).size).isAtLeast(2)
  }

  /**
   * The renderer writes unverified strides/seams to `<png>.warnings.json` as `unlandedScrollSteps`
   * / `unverifiedScrollSeams`; a trustworthy stitch has neither. Applied to both TLC fixtures.
   */
  @Test
  fun `LONG captures land every stride and verify every seam`() {
    for (png in
      listOf(
        longPng,
        renderFile(
          scrollLongDir,
          "ActivityListMotionLongPreview_Devices_Large_Round",
          "_SCROLL_long",
        ),
        renderFile(scrollLongDir, "SettingsMainScreenLongPreview_Devices_Large_Round"),
      )) {
      val sidecar = File(png.parentFile, png.name + ".warnings.json")
      if (!sidecar.exists()) continue
      val json = sidecar.readText()
      assertThat(json).contains("\"unlandedScrollSteps\":[]")
      assertThat(json).contains("\"unverifiedScrollSeams\":[]")
    }
  }

  /** Reads every frame of an animated GIF with the standard `javax.imageio` reader. */
  private fun readGifFrames(file: File): List<BufferedImage> {
    val reader = ImageIO.getImageReadersByFormatName("gif").next()
    javax.imageio.stream.FileImageInputStream(file).use { input ->
      reader.input = input
      val count = reader.getNumImages(true)
      return List(count) { reader.read(it) }
    }
  }

  private fun assertNoScaledCardGhostRows(img: BufferedImage) {
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
      // [0.40, 0.70) isolates mid-scale TLC items: narrower than a full card, wider than the
      // EdgeButton band or a card's rounded-corner rows.
      if (extent >= 0.40 && extent < 0.70) scaledCardRows++
    }

    assertThat(contentRows).isGreaterThan(0)
    val scaledRatio = scaledCardRows.toDouble() / contentRows
    assertThat(scaledRatio).isLessThan(0.10)
  }

  /**
   * Regression guard for the intermittent "ghost peek pill": `ScreenScaffold` pins the EdgeButton's
   * peek state to the bottom of every intermediate slice, and if it falls in the band the stitcher
   * copies from a slice, it lands mid-stitch where the final-frame overwrite can't reach.
   *
   * Looks for a narrow, centred, dim (~90–100 px) pill anywhere above the real, bright, near
   * full-width EdgeButton band.
   */
  @Test
  fun `LONG preview has no ghost peek-pill rows above the EdgeButton`() {
    val img = ImageIO.read(longPng)
    val w = img.width
    val h = img.height

    // The real EdgeButton band: the first wide (> 40% width) run of bright purple after the list
    // content.
    val edgeButtonTop =
      (0 until h).firstOrNull { y ->
        val (extent, avg) = brightCentredRun(img, y)
        extent > w * 0.40 && avg > 400 // primary is bright on all channels
      } ?: h

    // Ghost signature above the band: 8–110 px wide, centred (± w/8), and dimmer than the real
    // button (RGB sum < 420 vs ~670) but not background black.
    val ghostRows = mutableListOf<Int>()
    for (y in 0 until edgeButtonTop) {
      val row = extractCentredPillRow(img, y)
      if (row != null) ghostRows += y
    }

    // A correct stitch has zero peek-pill ghost rows.
    assertThat(ghostRows).isEmpty()
  }

  /**
   * Regression for the EdgeButton reveal at the end of a scroll-to-end stitch: without the
   * renderer's post-scroll settle (`settlePostScrollAnimations`), the final slice shows the button
   * mid-reveal as a narrow pill. Measured widest primary run: ~85% fixed vs ~30% broken; gated at
   * 60%.
   */
  @Test
  fun `LONG preview final frame shows fully-expanded EdgeButton`() {
    val img = ImageIO.read(longPng)
    val w = img.width
    val h = img.height

    // EdgeButton Large is ~92 px tall at 2x; scan the bottom 120 px. Expanded it is one wide
    // stripe, mid-reveal a short centred pill.
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
        // Primary-coloured button vs black background (and stray dark cards).
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
   * Widest continuous run of bright visible pixels on row [y], as `(extent, averageChannelSum)`
   * with the mean `r + g + b` over the run (0..765). Transparent capsule-mask pixels break the run.
   */
  private fun brightCentredRun(img: java.awt.image.BufferedImage, y: Int): Pair<Int, Int> {
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
   * The first centred "peek pill" run on row [y] as `(x0, x1, avgRgbSum)`, or `null`. Qualifies
   * when:
   * - extent ∈ [8, 110] px;
   * - centred within ± w/8;
   * - mean `r + g + b` in `[60, 420]` (dimmer than the primary EdgeButton, brighter than
   *   background);
   * - purple cast (`B − G ≥ 5`), excluding neutral-grey chrome like `TimeText`.
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
