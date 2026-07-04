package ee.schimke.composeai.daemon

import java.awt.AlphaComposite
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage

/**
 * Structural **fidelity diff** between a rendered preview and its `compose/figma-svg` export: given
 * the real render and a rasterisation of the exported SVG at the same size, it scores how
 * faithfully the layered SVG reproduces the render and produces a side-by-side + diff-overlay image
 * to look at.
 *
 * This is the measurement half of the fidelity harness (the SVG rasterisation — Skia
 * [`loadSvgPainter`][androidx.compose.ui.res.loadSvgPainter] — lives in the desktop renderer, the
 * only backend with an SVG decoder). It is deliberately **pure** (`BufferedImage` in, score + image
 * out — no IO, no toolkit beyond `java.awt`) so it unit-tests without a device and drives the
 * vector-vs-raster split (`FigmaSvgModel.DEFAULT_RASTER_COMPONENTS`) and the structural fixes (text
 * baselines, clip-to-rounded-parent, resolved `Surface`/`Card` fills) from evidence rather than
 * per-component guessing.
 *
 * The score is a per-pixel agreement fraction: both images are flattened onto a common opaque
 * background (so the SVG's transparent gaps compare against the render's actual pixels, not against
 * "undefined"), then a pixel counts as matching when every channel is within [Options.tolerance].
 * It is intentionally a *structural* metric — antialiasing and sub-pixel text differ between Skia's
 * render and its SVG re-rasterisation, so a tolerance absorbs that while still catching a missing
 * shape, a wrong fill, or misplaced text.
 */
object FigmaFidelity {

  data class Options(
    /** Per-channel absolute difference (0..255) under which a pixel counts as matching. */
    val tolerance: Int = 24,
    /**
     * Neighbourhood radius (px) the match is allowed to shift within. A pixel counts as matching
     * when *some* pixel of the other image within this radius is close — so a ≤`spatialRadius`-px
     * shift of text baselines or shape edges (all but unavoidable between the render and its SVG
     * re-rasterisation) reads as a match, not a structural defect. `0` restores an exact
     * position-locked compare; `1` (default) absorbs the sub-pixel drift that otherwise makes text
     * dominate the score.
     */
    val spatialRadius: Int = 1,
    /** Opaque background both images are flattened onto before comparison (ARGB). */
    val background: Int = 0xFFFFFFFF.toInt(),
    /** Gutter (px) between the panels of the side-by-side composite. */
    val gutter: Int = 12,
  )

  data class Result(
    /** Fraction of pixels (0.0..1.0) matching within tolerance — the headline fidelity score. */
    val score: Double,
    /** Mean per-channel absolute error (0.0..255.0) across all pixels — a finer-grained signal. */
    val meanAbsError: Double,
    val width: Int,
    val height: Int,
    /** `render | svg | diff` panels in one image, labelled — the artifact a reviewer looks at. */
    val composite: BufferedImage,
  ) {
    val scorePercent: Double
      get() = score * 100.0
  }

  /**
   * Compares [render] against [svgRaster] (a rasterisation of the same preview's
   * `compose-figma.svg`). [svgRaster] is scaled to [render]'s dimensions first, so the caller only
   * has to land it in the same coordinate frame (crop the export's padding), not match the pixel
   * size exactly.
   */
  fun compare(
    render: BufferedImage,
    svgRaster: BufferedImage,
    options: Options = Options(),
  ): Result {
    val w = render.width
    val h = render.height
    val a = flatten(render, options.background)
    val b =
      flatten(
        svgRaster.let { if (it.width == w && it.height == h) it else resize(it, w, h) },
        options.background,
      )

    val pxa = IntArray(w * h)
    val pxb = IntArray(w * h)
    a.getRGB(0, 0, w, h, pxa, 0, w)
    b.getRGB(0, 0, w, h, pxb, 0, w)
    val diff = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
    var matched = 0L
    var errorSum = 0.0
    val total = w.toLong() * h.toLong()
    val r = options.spatialRadius.coerceAtLeast(0)
    for (y in 0 until h) {
      for (x in 0 until w) {
        val pa = pxa[y * w + x]
        val ar = (pa shr 16) and 0xFF
        val ag = (pa shr 8) and 0xFF
        val ab = pa and 0xFF
        // Structural match: score this pixel against the *closest* pixel of b within `r`px, so a
        // sub-pixel shift of an edge/glyph finds its match instead of reading as a mismatch. The
        // (0,0) neighbour is always in bounds, so `bestMax` is always set.
        var bestMax = Int.MAX_VALUE
        var bestSum = 0
        var dy = -r
        while (dy <= r) {
          val ny = y + dy
          if (ny in 0 until h) {
            var dx = -r
            while (dx <= r) {
              val nx = x + dx
              if (nx in 0 until w) {
                val pb = pxb[ny * w + nx]
                val dr = Math.abs(ar - ((pb shr 16) and 0xFF))
                val dg = Math.abs(ag - ((pb shr 8) and 0xFF))
                val db = Math.abs(ab - (pb and 0xFF))
                val mx = maxOf(dr, dg, db)
                if (mx < bestMax) {
                  bestMax = mx
                  bestSum = dr + dg + db
                }
              }
              dx++
            }
          }
          dy++
        }
        errorSum += bestSum
        if (bestMax <= options.tolerance) {
          matched++
          // Matching pixels: a dimmed grayscale of the render, so mismatches pop.
          val gray = (ar + ag + ab) / 3
          val dim = (gray * 3 / 5) + 100
          diff.setRGB(x, y, (dim shl 16) or (dim shl 8) or dim)
        } else {
          diff.setRGB(x, y, 0xE53935) // red — a structural mismatch
        }
      }
    }
    val score = if (total == 0L) 1.0 else matched.toDouble() / total.toDouble()
    val meanAbsError = if (total == 0L) 0.0 else errorSum / (total.toDouble() * 3.0)
    val composite = sideBySide(a, b, diff, score, options)
    return Result(score, meanAbsError, w, h, composite)
  }

  /** Draws [src] over an opaque [background] so transparent regions become concrete pixels. */
  private fun flatten(src: BufferedImage, background: Int): BufferedImage {
    val out = BufferedImage(src.width, src.height, BufferedImage.TYPE_INT_RGB)
    val g = out.createGraphics()
    g.color = Color(background, true)
    g.fillRect(0, 0, out.width, out.height)
    g.composite = AlphaComposite.SrcOver
    g.drawImage(src, 0, 0, null)
    g.dispose()
    return out
  }

  private fun resize(src: BufferedImage, w: Int, h: Int): BufferedImage {
    val out = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
    val g = out.createGraphics()
    g.setRenderingHint(
      RenderingHints.KEY_INTERPOLATION,
      RenderingHints.VALUE_INTERPOLATION_BILINEAR,
    )
    g.drawImage(src, 0, 0, w, h, null)
    g.dispose()
    return out
  }

  private fun sideBySide(
    render: BufferedImage,
    svg: BufferedImage,
    diff: BufferedImage,
    score: Double,
    options: Options,
  ): BufferedImage {
    val w = render.width
    val h = render.height
    val labelH = 22
    val gutter = options.gutter
    val outW = w * 3 + gutter * 2
    val outH = h + labelH
    val out = BufferedImage(outW, outH, BufferedImage.TYPE_INT_RGB)
    val g = out.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
    g.color = Color(0xF3F4F6)
    g.fillRect(0, 0, outW, outH)
    val panels =
      listOf(
        "render" to render,
        "figma-svg" to svg,
        // Locale.ROOT keeps the label `.`-separated regardless of the host locale.
        "diff ${String.format(java.util.Locale.ROOT, "%.1f", score * 100)}%" to diff,
      )
    g.font = Font(Font.SANS_SERIF, Font.BOLD, 12)
    for ((i, panel) in panels.withIndex()) {
      val x = i * (w + gutter)
      g.color = Color(0x374151)
      g.drawString(panel.first, x + 2, 15)
      g.drawImage(panel.second, x, labelH, null)
    }
    g.dispose()
    return out
  }
}
