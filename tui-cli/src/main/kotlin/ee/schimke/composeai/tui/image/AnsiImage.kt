package ee.schimke.composeai.tui.image

import java.io.File
import javax.imageio.ImageIO

/**
 * Tiny PNG → ANSI half-block renderer. One terminal cell encodes two image pixels stacked
 * vertically: the `▀` glyph's foreground colour paints the top half and its background colour
 * paints the bottom half. That doubles the effective vertical resolution for free.
 *
 * ## Why half-blocks rather than sixel / Kitty graphics / iTerm2 inline images
 *
 * Sixel, Kitty graphics, iTerm2 OSC 1337, and the like would all give a sharper preview, but they
 * all require **out-of-band escape sequences that Mosaic's renderer can't currently emit safely** —
 * Mosaic positions content based on string width, and an escape sequence that draws a 200x300px
 * raster into a single Text cell would desync its layout cache. See `tui-cli/LIMITATIONS.md` for
 * the patch we'd want in the Mosaic fork.
 *
 * Half-blocks travel as ordinary printable characters with ANSI SGR colour escapes inline — each
 * row has the same visual width as character width, so Mosaic's width tracking stays honest. The
 * tradeoff is resolution (a 480×800 preview compresses to 80×200 cells, ~10× loss each axis), but
 * at typical preview sizes that's still legible enough to spot the kind of "the button moved to the
 * wrong corner" / "the background colour broke" regression most agents are looking for. Sharper
 * rendering is a follow-up.
 */
object AnsiImage {
  /**
   * Render [file] at most [maxCols] cells wide and [maxRows] cells tall (one cell == 1 char == 2
   * image pixels vertically). Preserves aspect ratio — the returned strings are exactly the
   * scaled-down image, no padding.
   *
   * The result is a list of pre-coloured row strings; the caller is responsible for emitting them
   * one per line (typically as separate `Text(...)` composables — see [AsciiBlock] below).
   *
   * Returns an empty list when the file can't be decoded, so the caller can show a placeholder
   * without a try/catch.
   */
  fun render(file: File, maxCols: Int, maxRows: Int): List<String> {
    if (maxCols <= 0 || maxRows <= 0) return emptyList()
    val img =
      try {
        ImageIO.read(file) ?: return emptyList()
      } catch (_: Throwable) {
        return emptyList()
      }

    // Each terminal row is 2 image rows. Compute the largest scale that fits both axes.
    val srcW = img.width
    val srcH = img.height
    if (srcW <= 0 || srcH <= 0) return emptyList()
    val cellPxX = (srcW + maxCols - 1) / maxCols
    val cellPxY = (srcH + (maxRows * 2) - 1) / (maxRows * 2)
    val px = maxOf(cellPxX, cellPxY, 1)
    val cols = (srcW + px - 1) / px
    val rows = (srcH + (px * 2) - 1) / (px * 2)

    val out = ArrayList<String>(rows)
    val builder = StringBuilder()
    for (cy in 0 until rows) {
      builder.setLength(0)
      var prevFg = -1
      var prevBg = -1
      for (cx in 0 until cols) {
        val topY = cy * 2 * px
        val botY = (cy * 2 + 1) * px
        val srcX = cx * px
        val top = averageBlock(img, srcX, topY, px, px, srcW, srcH)
        val bot = averageBlock(img, srcX, botY, px, px, srcW, srcH)
        if (top != prevFg) {
          builder
            .append(SGR_FG_RGB)
            .append(rgbR(top))
            .append(';')
            .append(rgbG(top))
            .append(';')
            .append(rgbB(top))
            .append('m')
          prevFg = top
        }
        if (bot != prevBg) {
          builder
            .append(SGR_BG_RGB)
            .append(rgbR(bot))
            .append(';')
            .append(rgbG(bot))
            .append(';')
            .append(rgbB(bot))
            .append('m')
          prevBg = bot
        }
        builder.append('▀') // ▀ UPPER HALF BLOCK
      }
      builder.append(SGR_RESET)
      out += builder.toString()
    }
    return out
  }

  /**
   * Grayscale ASCII fallback for terminals that strip 24-bit colour or for the kind of
   * Mosaic-driven layouts where embedded SGR escapes can confuse width tracking. Same shape as
   * [render] — a list of pre-formatted rows — but each character is a luminance step in the classic
   * "@ # 8 & % $ * + ; : , ." ramp. Cheap and safe.
   *
   * The TUI uses this in narrow-tab mode where the data pane is hidden — that mode is already
   * width-constrained and the colour escapes add up to a measurable redraw cost on slow PTYs
   * (Cygwin, conpty over SSH).
   */
  fun renderAscii(file: File, maxCols: Int, maxRows: Int): List<String> {
    if (maxCols <= 0 || maxRows <= 0) return emptyList()
    val img =
      try {
        ImageIO.read(file) ?: return emptyList()
      } catch (_: Throwable) {
        return emptyList()
      }
    val ramp = ASCII_RAMP
    val srcW = img.width
    val srcH = img.height
    if (srcW <= 0 || srcH <= 0) return emptyList()
    // Terminal cells are roughly 2× taller than wide — counted in `px` we treat one row as
    // 2 pixels tall to keep aspect ratio honest.
    val cellPxX = (srcW + maxCols - 1) / maxCols
    val cellPxY = (srcH + (maxRows * 2) - 1) / (maxRows * 2)
    val px = maxOf(cellPxX, cellPxY, 1)
    val cols = (srcW + px - 1) / px
    val rows = (srcH + (px * 2) - 1) / (px * 2)

    val out = ArrayList<String>(rows)
    val builder = StringBuilder()
    for (cy in 0 until rows) {
      builder.setLength(0)
      for (cx in 0 until cols) {
        val srcX = cx * px
        val srcY = cy * 2 * px
        val rgb = averageBlock(img, srcX, srcY, px, px * 2, srcW, srcH)
        val lum =
          (0.299 * rgbR(rgb) + 0.587 * rgbG(rgb) + 0.114 * rgbB(rgb)).toInt().coerceIn(0, 255)
        builder.append(ramp[(lum * (ramp.length - 1)) / 255])
      }
      out += builder.toString()
    }
    return out
  }

  private fun averageBlock(
    img: java.awt.image.BufferedImage,
    startX: Int,
    startY: Int,
    w: Int,
    h: Int,
    srcW: Int,
    srcH: Int,
  ): Int {
    var rSum = 0
    var gSum = 0
    var bSum = 0
    var count = 0
    val endX = minOf(startX + w, srcW)
    val endY = minOf(startY + h, srcH)
    var y = startY
    while (y < endY) {
      var x = startX
      while (x < endX) {
        val argb = img.getRGB(x, y)
        rSum += (argb shr 16) and 0xff
        gSum += (argb shr 8) and 0xff
        bSum += argb and 0xff
        count++
        x++
      }
      y++
    }
    if (count == 0) return 0
    return ((rSum / count) shl 16) or ((gSum / count) shl 8) or (bSum / count)
  }

  private fun rgbR(c: Int) = (c shr 16) and 0xff

  private fun rgbG(c: Int) = (c shr 8) and 0xff

  private fun rgbB(c: Int) = c and 0xff

  private const val SGR_FG_RGB = "[38;2;"
  private const val SGR_BG_RGB = "[48;2;"
  private const val SGR_RESET = "[0m"
  private const val ASCII_RAMP = " .,:;+*?%S#@"
}
