package com.example.sampleandroid

import java.awt.image.BufferedImage
import java.io.File
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.math.abs
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Device-capture parity: does the UI-builder design in
 * `samples/android/design/home-screen.uibuilder.json` still look like the app it was authored
 * against?
 *
 * The **reference** is the real app. `kind=ACTIVITY` previews launch `MainActivity` for real, with
 * its full lifecycle, its own `setContent` and its own theme, and capture the resumed screen to
 * `renders/activity__MainActivity.png` (see docs/APP_TOURS.md). That is the extraction half of
 * `docs/design/DEVICE_CAPTURE.md` already built and shipping; this test consumes it rather than
 * adding a second capture path.
 *
 * The **candidate** is [HomeScreenDesignPreview], the design rendered through the ordinary preview
 * lane.
 *
 * ## Report-only, on purpose
 *
 * This test never fails on a difference. It writes `build/device-capture-parity/report.json` and
 * asserts only that both inputs exist and are comparable. Two reasons, and neither is temporary
 * timidity:
 *
 * 1. **Nobody has measured the real drift yet.** A threshold invented before the first run is a
 *    number someone made up, and the usual fate of a gate like that is to be muted. The daily job
 *    exists to produce the measurement a threshold could later be chosen from.
 * 2. It matches how this repository already treats parity findings — advisory, severity being the
 *    agent's signal, per `site/reference/a11y.md`.
 *
 * Turning it into a gate is a deliberate follow-up, once `report.json` has a few weeks of history.
 *
 * ## The floor, measured
 *
 * At the commit that added this lane the two frames were **pixel-identical over 99.79% of the
 * frame**: `meanAbsoluteDifference` 0.002183, `differingPixelFraction` 0.0021, and every differing
 * pixel inside rows `18-43` and `2069-2079`. Those two bands are the status-bar glyphs and the
 * navigation pill: the design preview draws system chrome, and the ACTIVITY capture does not, *even
 * though both previews declare `showSystemUi = true`*. The content itself does not differ by a
 * single pixel.
 *
 * So a non-zero score is expected, and `differingRowBands` is the field that makes it readable —
 * two thin bands at the extremes are the known floor, anything inside the content area is drift.
 * That is why the bands are reported rather than just a number: a score alone cannot tell the two
 * apart, and a reviewer should not have to open the PNGs to find out.
 *
 * ## What a difference here means
 *
 * The app is the reference, so a difference is the *design* being out of date — someone changed
 * `HomeScreen` and the design was not re-authored. It can also be a real fidelity gap in what the
 * builder can express, which is the more interesting case and the reason the score is recorded
 * rather than just eyeballed.
 */
class DeviceCaptureParityTest {

  private val rendersDir = File("build/compose-previews/renders")

  /**
   * Locale-independent fixed-point. `"%.6f".format(x)` uses the default locale, which in a
   * comma-decimal locale writes `0,123456` — invalid JSON, and a CI runner is exactly where an
   * unexpected default locale shows up.
   */
  private fun fixed(value: Double): String = String.format(Locale.ROOT, "%.6f", value)

  private val reportDir = File("build/device-capture-parity")

  /** Both scores, plus where the differences are, from one pass over the overlapping area. */
  private data class Scores(
    val meanAbsoluteDifference: Double,
    val differingFraction: Double,
    /** Contiguous runs of rows containing at least one differing pixel, as `first..last`. */
    val differingRowBands: List<IntRange>,
  )

  /**
   * [Scores.meanAbsoluteDifference] is the mean per-channel difference, 0.0 (identical) to 1.0.
   * [Scores.differingFraction] is the fraction of pixels differing by more than [tolerance] on any
   * channel — the two answer different questions, and a design that is right but shifted by one
   * pixel scores very differently on them.
   *
   * Compared over the overlap so a size mismatch degrades rather than throws; `sameSize` in the
   * report says whether that happened, because a score over an overlap is not comparable to one
   * over the whole frame.
   */
  private fun score(a: BufferedImage, b: BufferedImage, tolerance: Int = 8): Scores {
    val w = minOf(a.width, b.width)
    val h = minOf(a.height, b.height)
    if (w == 0 || h == 0) return Scores(1.0, 1.0, emptyList())
    var total = 0L
    var differing = 0L
    val dirtyRows = mutableListOf<Int>()
    for (y in 0 until h) {
      var rowDirty = false
      for (x in 0 until w) {
        val pa = a.getRGB(x, y)
        val pb = b.getRGB(x, y)
        val dr = abs(((pa shr 16) and 0xff) - ((pb shr 16) and 0xff))
        val dg = abs(((pa shr 8) and 0xff) - ((pb shr 8) and 0xff))
        val db = abs((pa and 0xff) - (pb and 0xff))
        total += (dr + dg + db).toLong()
        if (dr > tolerance || dg > tolerance || db > tolerance) {
          differing++
          rowDirty = true
        }
      }
      if (rowDirty) dirtyRows += y
    }
    val pixels = w.toDouble() * h.toDouble()
    return Scores(
      total.toDouble() / (pixels * 3.0 * 255.0),
      differing.toDouble() / pixels,
      contiguousBands(dirtyRows),
    )
  }

  /** `[3, 4, 5, 9, 10]` → `[3..5, 9..10]`. */
  private fun contiguousBands(rows: List<Int>): List<IntRange> {
    if (rows.isEmpty()) return emptyList()
    val bands = mutableListOf<IntRange>()
    var start = rows.first()
    var previous = rows.first()
    for (row in rows.drop(1)) {
      if (row != previous + 1) {
        bands += start..previous
        start = row
      }
      previous = row
    }
    bands += start..previous
    return bands
  }

  @Test
  fun designMatchesCapturedApp() {
    // Two different filename conventions meet here, and neither is guesswork:
    //   * a `@Preview` render is `<function>_<previewName>-<8 hex digest>.png`
    //     (docs/RENDER_FILENAMES.md), which is what `renderFile` matches;
    //   * a synthetic ACTIVITY capture is the bare `activity__<SimpleName>.png` —
    //     `AppTourDiscovery` sets `renderOutput` to exactly that, with no digest.
    // `renderFile` happens to fall back to the bare name when its pattern misses, but relying on a
    // fallback for the primary input would be luck, so the activity is resolved directly.
    val captured =
      File(rendersDir, "activity__MainActivity.png").takeIf { it.exists() }
        ?: renderFile(rendersDir, "activity__MainActivity")
    val design = renderFile(rendersDir, "HomeScreenDesignPreview_Design")

    // The activity capture is `optional` for every activity but the launcher, and a module that
    // rendered no activity at all should not turn this into a red herring in an unrelated failure.
    // Skip loudly rather than fail: the daily job's log says which input was missing.
    assumeTrue(
      "no activity capture at ${captured.path} — is ACTIVITY discovery on for this module?",
      captured.exists(),
    )
    assumeTrue("no design render at ${design.path}", design.exists())

    val capturedImage = ImageIO.read(captured)
    val designImage = ImageIO.read(design)

    val scores = score(capturedImage, designImage)
    val mad = scores.meanAbsoluteDifference
    val differing = scores.differingFraction
    val bands = scores.differingRowBands
    val sameSize =
      capturedImage.width == designImage.width && capturedImage.height == designImage.height

    reportDir.mkdirs()
    File(reportDir, "report.json")
      .writeText(
        buildString {
          appendLine("{")
          appendLine("  \"schema\": \"device-capture-parity/v1\",")
          appendLine("  \"design\": \"samples/android/design/home-screen.uibuilder.json\",")
          appendLine("  \"reference\": {")
          appendLine("    \"source\": \"activity-capture\",")
          appendLine("    \"file\": \"${captured.name}\",")
          appendLine("    \"widthPx\": ${capturedImage.width},")
          appendLine("    \"heightPx\": ${capturedImage.height}")
          appendLine("  },")
          appendLine("  \"candidate\": {")
          appendLine("    \"source\": \"design-preview\",")
          appendLine("    \"file\": \"${design.name}\",")
          appendLine("    \"widthPx\": ${designImage.width},")
          appendLine("    \"heightPx\": ${designImage.height}")
          appendLine("  },")
          appendLine("  \"sameSize\": $sameSize,")
          appendLine("  \"meanAbsoluteDifference\": ${fixed(mad)},")
          appendLine("  \"differingPixelFraction\": ${fixed(differing)},")
          appendLine(
            "  \"differingRowBands\": [" +
              bands.joinToString(", ") { "\"${it.first}-${it.last}\"" } +
              "],"
          )
          appendLine("  \"gate\": \"report-only\"")
          appendLine("}")
        }
      )

    println(
      "device-capture parity: mad=${fixed(mad)} " +
        "differing=${fixed(differing * 100)}% sameSize=$sameSize " +
        "bands=${bands.joinToString(",") { "${it.first}-${it.last}" }} " +
        "(${capturedImage.width}x${capturedImage.height} vs " +
        "${designImage.width}x${designImage.height})"
    )

    // The only assertions: both images decoded and overlap. A score of any value is a result, not
    // a failure — see the class doc.
    check(capturedImage.width > 0 && capturedImage.height > 0) { "captured image is empty" }
    check(designImage.width > 0 && designImage.height > 0) { "design image is empty" }
  }
}
