package ee.schimke.composeai.daemon.history

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.math.max

/**
 * Pixel + structural image diff for `history/diff mode = "pixel"` (H5, issue #1873).
 *
 * Pure-JVM (`java.awt` / `javax.imageio`), self-contained in `:daemon:core` — the harness's
 * `PixelDiff` is a test fixture with a different, tolerance-gated contract (pass/fail for golden
 * comparisons) and lives in a test-scope module, so it isn't reused here. This produces *metrics*
 * for two archived history PNGs plus a reviewer-facing marked-diff image:
 *
 * - **[Result.diffPx]** — count of pixels whose RGB differs at all between the two frames. A blunt,
 *   deterministic "how many pixels moved" number; perceptual closeness is [Result.ssim]'s job.
 * - **[Result.ssim]** — mean structural-similarity index (Wang et al. 2004) over 8×8 luma windows,
 *   in `[-1, 1]` (1.0 ⇒ identical). Captures "did the structure change" rather than raw pixel
 *   count, so anti-aliasing jitter that bumps [diffPx] barely dents `ssim`.
 * - **[Result.markedPng]** — the `to` frame at 50% brightness with every differing pixel painted
 *   bright red, encoded as PNG bytes, so a reviewer can locate the change without flipping between
 *   two images. Null when the two frames have mismatched dimensions (no meaningful overlay).
 *
 * **Dimension mismatch** (same preview rendered at two different sizes) is reported, not errored:
 * `diffPx = max(areaFrom, areaTo)`, `ssim = 0.0`, `markedPng = null`. The caller still gets a
 * usable "everything changed" signal.
 */
object HistoryImageDiff {

  /** Result of [diff]. [markedPng] is null on dimension mismatch or PNG-encode failure. */
  data class Result(val diffPx: Long, val ssim: Double, val markedPng: ByteArray?) {
    // ByteArray needs structural equals/hashCode for the data class to behave in tests/maps.
    override fun equals(other: Any?): Boolean {
      if (this === other) return true
      if (other !is Result) return false
      return diffPx == other.diffPx &&
        ssim == other.ssim &&
        (markedPng?.contentEquals(other.markedPng ?: ByteArray(0)) ?: (other.markedPng == null))
    }

    override fun hashCode(): Int {
      var result = diffPx.hashCode()
      result = 31 * result + ssim.hashCode()
      result = 31 * result + (markedPng?.contentHashCode() ?: 0)
      return result
    }
  }

  /** Thrown when a frame's bytes can't be decoded as an image. */
  class UndecodableImageException(message: String) : Exception(message)

  /**
   * Diffs [fromPng] against [toPng]. Throws [UndecodableImageException] if either side can't be
   * decoded — the caller maps that onto a structured RPC error.
   */
  fun diff(fromPng: ByteArray, toPng: ByteArray): Result {
    val a =
      decode(fromPng) ?: throw UndecodableImageException("from frame is not a decodable image")
    val b = decode(toPng) ?: throw UndecodableImageException("to frame is not a decodable image")
    if (a.width != b.width || a.height != b.height) {
      val area = max(a.width.toLong() * a.height, b.width.toLong() * b.height)
      return Result(diffPx = area, ssim = 0.0, markedPng = null)
    }
    val w = a.width
    val h = b.height
    var diffPx = 0L
    val marked = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
    for (y in 0 until h) {
      for (x in 0 until w) {
        val pa = a.getRGB(x, y)
        val pb = b.getRGB(x, y)
        if ((pa and 0xFFFFFF) != (pb and 0xFFFFFF)) {
          diffPx++
          marked.setRGB(x, y, 0xFFFF0000.toInt()) // opaque red
        } else {
          // 50% darkened `to` pixel keeps spatial context behind the red marks.
          val r = ((pb shr 16) and 0xFF) / 2
          val g = ((pb shr 8) and 0xFF) / 2
          val bl = (pb and 0xFF) / 2
          marked.setRGB(x, y, (0xFF shl 24) or (r shl 16) or (g shl 8) or bl)
        }
      }
    }
    return Result(diffPx = diffPx, ssim = ssim(a, b), markedPng = encodePng(marked))
  }

  /**
   * Mean SSIM over non-overlapping 8×8 luma windows. Edge windows smaller than 8 px use their
   * actual size. Non-overlapping (vs. a sliding Gaussian window) keeps it cheap and fully
   * deterministic — adequate for "did this render's structure change" without the cost of the
   * reference filter.
   */
  private fun ssim(a: BufferedImage, b: BufferedImage): Double {
    val w = a.width
    val h = a.height
    val la = luma(a)
    val lb = luma(b)
    val c1 = (0.01 * 255.0) * (0.01 * 255.0)
    val c2 = (0.03 * 255.0) * (0.03 * 255.0)
    val win = 8
    var sum = 0.0
    var windows = 0
    var by = 0
    while (by < h) {
      var bx = 0
      while (bx < w) {
        val xe = minOf(bx + win, w)
        val ye = minOf(by + win, h)
        var meanA = 0.0
        var meanB = 0.0
        var count = 0
        for (y in by until ye) for (x in bx until xe) {
          meanA += la[y * w + x]
          meanB += lb[y * w + x]
          count++
        }
        meanA /= count
        meanB /= count
        var varA = 0.0
        var varB = 0.0
        var cov = 0.0
        for (y in by until ye) for (x in bx until xe) {
          val dA = la[y * w + x] - meanA
          val dB = lb[y * w + x] - meanB
          varA += dA * dA
          varB += dB * dB
          cov += dA * dB
        }
        varA /= count
        varB /= count
        cov /= count
        val s =
          ((2 * meanA * meanB + c1) * (2 * cov + c2)) /
            ((meanA * meanA + meanB * meanB + c1) * (varA + varB + c2))
        sum += s
        windows++
        bx += win
      }
      by += win
    }
    return if (windows == 0) 1.0 else sum / windows
  }

  private fun luma(img: BufferedImage): DoubleArray {
    val w = img.width
    val h = img.height
    val out = DoubleArray(w * h)
    for (y in 0 until h) {
      for (x in 0 until w) {
        val p = img.getRGB(x, y)
        val r = (p shr 16) and 0xFF
        val g = (p shr 8) and 0xFF
        val bl = p and 0xFF
        out[y * w + x] = 0.299 * r + 0.587 * g + 0.114 * bl
      }
    }
    return out
  }

  private fun decode(bytes: ByteArray): BufferedImage? =
    try {
      ByteArrayInputStream(bytes).use { ImageIO.read(it) }
    } catch (_: Throwable) {
      null
    }

  private fun encodePng(img: BufferedImage): ByteArray? =
    try {
      ByteArrayOutputStream().use { out ->
        ImageIO.write(img, "png", out)
        out.toByteArray()
      }
    } catch (_: Throwable) {
      null
    }
}
