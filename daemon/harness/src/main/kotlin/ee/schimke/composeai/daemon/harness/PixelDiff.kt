package ee.schimke.composeai.daemon.harness

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs

/**
 * Pixel-diff with a per-pixel threshold *and* an aggregate threshold *and* an absolute cap — the
 * sweet spot from [TEST-HARNESS § 4](../../../docs/daemon/TEST-HARNESS.md#4-image-verification) and
 * [§ 11 decisions made](../../../docs/daemon/TEST-HARNESS.md#11-decisions-made):
 *
 * * **Per-pixel:** `|Δr| + |Δg| + |Δb|` ≤ 3 LSB by default. Accommodates AA / hinting jitter.
 * * **Aggregate:** ≤ 0.5% of pixels may exceed the per-pixel threshold. Lets text edges drift
 *   without letting an entire region change unnoticed.
 * * **Absolute cap:** even within the aggregate budget, no single pixel may differ by more than 50
 *   LSB total — catches whole-region colour bleed (the worst-case shape from a half-aborted
 *   `HardwareRenderer`, see DESIGN § 9).
 *
 * Per-scenario tolerance overrides via [PixelDiffTolerance]; callers stay on the defaults until a
 * real PR shows the tolerances need tightening or loosening.
 */
object PixelDiff {

  /**
   * Compares two PNGs (decoded via `javax.imageio`) and returns an [PixelDiffResult] indicating
   * whether they match within [tolerance]. Mismatched dimensions are an immediate failure with
   * `offendingPixelCount = -1` to distinguish from a per-pixel miss.
   */
  fun compare(
    actual: ByteArray,
    expected: ByteArray,
    tolerance: PixelDiffTolerance = PixelDiffTolerance.DEFAULT,
  ): PixelDiffResult {
    val actualImg = decode(actual) ?: return PixelDiffResult.failure("actual.png is undecodable")
    val expectedImg =
      decode(expected) ?: return PixelDiffResult.failure("expected.png is undecodable")
    if (actualImg.width != expectedImg.width || actualImg.height != expectedImg.height) {
      return PixelDiffResult.failure(
        "dimension mismatch: actual=${actualImg.width}x${actualImg.height}, " +
          "expected=${expectedImg.width}x${expectedImg.height}"
      )
    }
    val w = actualImg.width
    val h = actualImg.height
    var offendingCount = 0
    var maxDelta = 0
    var capExceeded = false
    for (y in 0 until h) {
      for (x in 0 until w) {
        val a = actualImg.getRGB(x, y)
        val e = expectedImg.getRGB(x, y)
        val dr = abs(((a shr 16) and 0xFF) - ((e shr 16) and 0xFF))
        val dg = abs(((a shr 8) and 0xFF) - ((e shr 8) and 0xFF))
        val db = abs((a and 0xFF) - (e and 0xFF))
        val delta = dr + dg + db
        if (delta > maxDelta) maxDelta = delta
        if (delta > tolerance.absoluteCap) capExceeded = true
        if (delta > tolerance.perPixel) offendingCount++
      }
    }
    val totalPixels = w * h
    val aggregateFraction = offendingCount.toDouble() / totalPixels.toDouble()
    val aggregateOk = aggregateFraction <= tolerance.aggregateFraction
    val capOk = !capExceeded
    val ok = aggregateOk && capOk
    val message =
      if (ok) "ok"
      else
        buildString {
          append("pixel diff failed:")
          if (!aggregateOk) {
            append(
              " offending=$offendingCount/$totalPixels " +
                "(${"%.4f".format(aggregateFraction * 100)}% > " +
                "${"%.4f".format(tolerance.aggregateFraction * 100)}%)"
            )
          }
          if (!capOk) append(" maxDelta=$maxDelta exceeds cap=${tolerance.absoluteCap}")
        }
    return PixelDiffResult(
      ok = ok,
      offendingPixelCount = offendingCount,
      totalPixels = totalPixels,
      maxDelta = maxDelta,
      message = message,
    )
  }

  /**
   * Writes diagnostic artefacts to [outDir] when a comparison fails — typically called from a
   * test's `finally`/catch after [compare] returns `!ok`. Produces:
   * * `actual.png` — bytes the daemon (or fixture) produced.
   * * `expected.png` — what the harness expected.
   * * `diff.png` — failed pixels highlighted bright red over a 50% darkened expected image, so
   *   reviewers can locate regressions visually without flipping back-and-forth between PNGs.
   *
   * Best-effort: any I/O failure is swallowed (after logging to stderr) so the test's primary
   * failure isn't masked by an artefact-writing exception.
   */
  fun writeDiffArtefacts(
    actual: ByteArray,
    expected: ByteArray,
    outDir: File,
    tolerance: PixelDiffTolerance = PixelDiffTolerance.DEFAULT,
  ) {
    try {
      outDir.mkdirs()
      File(outDir, "actual.png").writeBytes(actual)
      File(outDir, "expected.png").writeBytes(expected)
      val actualImg = decode(actual)
      val expectedImg = decode(expected)
      if (
        actualImg != null &&
          expectedImg != null &&
          actualImg.width == expectedImg.width &&
          actualImg.height == expectedImg.height
      ) {
        val w = actualImg.width
        val h = actualImg.height
        val diff = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until h) {
          for (x in 0 until w) {
            val a = actualImg.getRGB(x, y)
            val e = expectedImg.getRGB(x, y)
            val dr = abs(((a shr 16) and 0xFF) - ((e shr 16) and 0xFF))
            val dg = abs(((a shr 8) and 0xFF) - ((e shr 8) and 0xFF))
            val db = abs((a and 0xFF) - (e and 0xFF))
            val delta = dr + dg + db
            if (delta > tolerance.perPixel) {
              diff.setRGB(x, y, 0xFFFF0000.toInt())
            } else {
              // 50% darkened expected pixel as the background — keeps spatial context.
              val r = ((e shr 16) and 0xFF) / 2
              val g = ((e shr 8) and 0xFF) / 2
              val b = (e and 0xFF) / 2
              diff.setRGB(x, y, (0xFF shl 24) or (r shl 16) or (g shl 8) or b)
            }
          }
        }
        ImageIO.write(diff, "png", File(outDir, "diff.png"))
      }
    } catch (e: Throwable) {
      System.err.println("PixelDiff.writeDiffArtefacts: ${e.message}")
    }
  }

  private fun decode(bytes: ByteArray): BufferedImage? =
    try {
      ByteArrayInputStream(bytes).use { ImageIO.read(it) }
    } catch (e: Throwable) {
      System.err.println("PixelDiff.decode: ${e.message}")
      null
    }
}

/** Tolerance struct — see TEST-HARNESS § 11 for the rationale behind each default. */
data class PixelDiffTolerance(
  /** Maximum allowed `|Δr| + |Δg| + |Δb|` per pixel before it counts toward the aggregate. */
  val perPixel: Int = 3,
  /** Maximum fraction of pixels that may exceed [perPixel]. 0.5% by default. */
  val aggregateFraction: Double = 0.005,
  /** No single pixel may exceed this — catches whole-region colour bleed. */
  val absoluteCap: Int = 50,
) {
  companion object {
    val DEFAULT = PixelDiffTolerance()
  }
}

/** Outcome of a [PixelDiff.compare] call. */
data class PixelDiffResult(
  val ok: Boolean,
  val offendingPixelCount: Int,
  val totalPixels: Int,
  val maxDelta: Int,
  val message: String,
) {
  companion object {
    fun failure(message: String): PixelDiffResult =
      PixelDiffResult(
        ok = false,
        offendingPixelCount = -1,
        totalPixels = 0,
        maxDelta = -1,
        message = message,
      )
  }
}
