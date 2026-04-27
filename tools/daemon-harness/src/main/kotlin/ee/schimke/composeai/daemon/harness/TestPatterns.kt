package ee.schimke.composeai.daemon.harness

import java.awt.Color
import java.awt.Font
import java.awt.GradientPaint
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import javax.imageio.metadata.IIOMetadataNode
import javax.imageio.stream.MemoryCacheImageOutputStream

/**
 * Deterministic PNG fixture generator for the daemon test harness — see docs/daemon/TEST-HARNESS.md
 * § 4 (image baselines, "generated test patterns" strategy) and § 11 (decisions made: same
 * generator produces fixture and baseline so nothing is checked in for v0+v1).
 *
 * Each function is a pure function of its inputs: identical inputs always produce identical bytes.
 * Two pieces of pinning enable this:
 *
 * * `ImageIO`'s default PNG writer does not insert a `tIME` chunk or any timestamp metadata, so we
 *   just call `ImageIO.write` (or, for non-default `ImageWriteParam`, the long form below) without
 *   supplying metadata. We deliberately do **not** call `getDefaultMetadata()` because that *can*
 *   leak environment-derived attributes on some JREs (e.g. an `iTXt` "Software" comment containing
 *   the JDK build).
 * * Drawing uses `RenderingHints.VALUE_TEXT_ANTIALIAS_ON` and a logical Java font
 *   (`Font.SANS_SERIF`), both of which are consistent across the JRE versions we run on. AA noise
 *   around glyph edges is the dominant remaining source of cross-platform jitter; the harness's
 *   `PixelDiff` tolerances (per-pixel 3 LSB, aggregate 0.5%) absorb that.
 *
 * Aesthetics: old TV test-signal — solid colours, gradient strips, alignment grids, text labels.
 * Useful debugging artefacts when something blows up; legible at thumbnail size when CI surfaces
 * `actual.png` / `expected.png` / `diff.png` on failure.
 */
object TestPatterns {

  /**
   * Solid-colour fill at [width] × [height]. [rgb] is a packed `0xAARRGGBB` int (alpha defaults to
   * fully opaque if you pass `0xRRGGBB`).
   */
  fun solidColour(width: Int, height: Int, rgb: Int): ByteArray {
    val img = newImage(width, height)
    val g = img.createGraphics()
    try {
      g.color = Color(rgb, hasAlpha(rgb))
      g.fillRect(0, 0, width, height)
    } finally {
      g.dispose()
    }
    return encodePng(img)
  }

  /**
   * Centre-aligned [text] over a flat [bg] background with [fg] glyphs. Defaults: white-on-black,
   * matching the TV-test-signal aesthetic.
   */
  fun textBox(
    width: Int,
    height: Int,
    text: String,
    fg: Int = 0xFFFFFFFF.toInt(),
    bg: Int = 0xFF000000.toInt(),
  ): ByteArray {
    val img = newImage(width, height)
    val g = img.createGraphics()
    try {
      g.color = Color(bg, hasAlpha(bg))
      g.fillRect(0, 0, width, height)
      g.color = Color(fg, hasAlpha(fg))
      g.setRenderingHint(
        RenderingHints.KEY_TEXT_ANTIALIASING,
        RenderingHints.VALUE_TEXT_ANTIALIAS_ON,
      )
      // Logical font: Java guarantees a sans-serif binding on every JRE.
      val font = Font(Font.SANS_SERIF, Font.PLAIN, (height / 6).coerceAtLeast(10))
      g.font = font
      val fm = g.fontMetrics
      val textW = fm.stringWidth(text)
      val x = (width - textW) / 2
      val y = (height - fm.height) / 2 + fm.ascent
      g.drawString(text, x, y)
    } finally {
      g.dispose()
    }
    return encodePng(img)
  }

  /**
   * Linear gradient from [fromRgb] at top-left to [toRgb] at bottom-right (or top→bottom if
   * [vertical]).
   */
  fun gradient(
    width: Int,
    height: Int,
    fromRgb: Int,
    toRgb: Int,
    vertical: Boolean = false,
  ): ByteArray {
    val img = newImage(width, height)
    val g = img.createGraphics()
    try {
      val (x2, y2) = if (vertical) 0 to height else width to 0
      g.paint =
        GradientPaint(
          0f,
          0f,
          Color(fromRgb, hasAlpha(fromRgb)),
          x2.toFloat(),
          y2.toFloat(),
          Color(toRgb, hasAlpha(toRgb)),
        )
      g.fillRect(0, 0, width, height)
    } finally {
      g.dispose()
    }
    return encodePng(img)
  }

  /**
   * Alternating black/white cells at [cellPx] per cell. Useful as an alignment / off-by-one grid.
   */
  fun alignmentGrid(width: Int, height: Int, cellPx: Int = 32): ByteArray {
    require(cellPx > 0) { "cellPx must be positive" }
    val img = newImage(width, height)
    val g = img.createGraphics()
    try {
      val cellsX = (width + cellPx - 1) / cellPx
      val cellsY = (height + cellPx - 1) / cellPx
      for (cy in 0 until cellsY) {
        for (cx in 0 until cellsX) {
          val odd = (cx + cy) and 1 == 1
          g.color = if (odd) Color.WHITE else Color.BLACK
          g.fillRect(cx * cellPx, cy * cellPx, cellPx, cellPx)
        }
      }
    } finally {
      g.dispose()
    }
    return encodePng(img)
  }

  // -------------------------------------------------------------------------
  // Internals
  // -------------------------------------------------------------------------

  private fun newImage(width: Int, height: Int): BufferedImage {
    require(width > 0 && height > 0) { "image dimensions must be positive" }
    return BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
  }

  private fun hasAlpha(packed: Int): Boolean = (packed ushr 24) != 0xFF && (packed ushr 24) != 0

  /**
   * Encodes [img] as PNG bytes deterministically. We write through the long-form `ImageWriter` path
   * (rather than the convenience `ImageIO.write`) so we can pass an explicit empty metadata tree —
   * guaranteeing no `tIME` chunk, no `iTXt`/`tEXt` "Software" comment, and no environment-derived
   * attributes that some JREs add when given the writer's default metadata.
   */
  private fun encodePng(img: BufferedImage): ByteArray {
    val writer =
      ImageIO.getImageWritersByFormatName("png").next()
        ?: error("no PNG ImageWriter available — JRE missing javax.imageio PNG plugin?")
    val out = ByteArrayOutputStream(8 * 1024)
    MemoryCacheImageOutputStream(out).use { ios ->
      writer.output = ios
      val typeSpec = javax.imageio.ImageTypeSpecifier.createFromRenderedImage(img)
      val writeParam: ImageWriteParam = writer.defaultWriteParam
      // Empty metadata tree (just the required PNG root) — no tIME, no tEXt, no iTXt.
      val md =
        writer.getDefaultImageMetadata(typeSpec, writeParam).also {
          it.setFromTree("javax_imageio_png_1.0", IIOMetadataNode("javax_imageio_png_1.0"))
        }
      writer.write(null, IIOImage(img, null, md), writeParam)
    }
    writer.dispose()
    return out.toByteArray()
  }
}
