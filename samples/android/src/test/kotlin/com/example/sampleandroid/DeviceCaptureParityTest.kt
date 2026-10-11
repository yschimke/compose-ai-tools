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
 * `samples/android/design/home-screen.uibuilder.json` still look like the app it was authored from?
 *
 * The reference is the real app: a `kind=ACTIVITY` preview launches `MainActivity` and captures
 * `renders/activity__MainActivity.png` (docs/APP_TOURS.md). The candidate is
 * [HomeScreenDesignPreview], the design rendered through the preview lane.
 *
 * Report-only: writes `build/device-capture-parity/report.json` and asserts only that both inputs
 * are comparable. A threshold should be chosen from measured history, not invented up front.
 *
 * The known floor is two thin row bands (status-bar glyphs and nav pill: the design preview draws
 * system chrome, the ACTIVITY capture doesn't); `differingRowBands` makes that readable, and
 * anything inside the content area is real drift — usually the design being out of date, sometimes
 * a gap in what the builder can express.
 */
class DeviceCaptureParityTest {

  private val rendersDir = File("build/compose-previews/renders")

  /**
   * Locale-independent fixed-point, so a comma-decimal default locale can't produce invalid JSON.
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
   * [Scores.meanAbsoluteDifference] is the mean per-channel difference (0.0–1.0);
   * [Scores.differingFraction] the fraction of pixels differing by more than [tolerance] on any
   * channel. Compared over the overlap so a size mismatch degrades (reported as `sameSize`).
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
    // A `@Preview` render is `<function>_<previewName>-<8 hex digest>.png`
    // (docs/RENDER_FILENAMES.md), but an ACTIVITY capture is the bare `activity__<SimpleName>.png`,
    // so resolve it directly.
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
