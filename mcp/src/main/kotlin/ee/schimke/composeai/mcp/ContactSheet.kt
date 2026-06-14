package ee.schimke.composeai.mcp

import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * Stitches the per-cell PNGs of a render matrix into a single labelled grid PNG — the "contact
 * sheet" the `render_matrix` surfaces return as an optional, non-default output so an agent can
 * eyeball "does this survive small screen + RTL + large font?" in one image instead of reading N
 * PNGs (issue #1788). Pure: PNG bytes + captions in, one stitched PNG out.
 */
object ContactSheet {
  /** One labelled tile: [png] is a rendered cell's PNG bytes, [label] is its caption-strip text. */
  class Cell(val label: String, val png: ByteArray)

  private const val PADDING = 12
  private const val CAPTION_HEIGHT = 22
  private const val PLACEHOLDER_EDGE = 96

  /**
   * Lay the [cells] out in a near-square grid (or [columns] when given), each tile sized to the
   * largest cell image plus a caption strip, on a white background. Returns null when [cells] is
   * empty. A cell whose bytes don't decode renders as a captioned placeholder so the grid stays
   * aligned.
   */
  fun stitch(cells: List<Cell>, columns: Int? = null): ByteArray? {
    if (cells.isEmpty()) return null
    val images = cells.map { runCatching { ImageIO.read(it.png.inputStream()) }.getOrNull() }

    val cols = (columns ?: ceil(sqrt(cells.size.toDouble())).toInt()).coerceAtLeast(1)
    val rows = ceil(cells.size.toDouble() / cols).toInt()
    val tileW =
      (images.mapNotNull { it?.width }.maxOrNull() ?: PLACEHOLDER_EDGE).coerceAtLeast(
        PLACEHOLDER_EDGE
      )
    val tileH =
      (images.mapNotNull { it?.height }.maxOrNull() ?: PLACEHOLDER_EDGE).coerceAtLeast(
        PLACEHOLDER_EDGE
      )

    val cellW = tileW + PADDING * 2
    val cellH = tileH + CAPTION_HEIGHT + PADDING * 2
    val sheetW = cellW * cols
    val sheetH = cellH * rows

    val sheet = BufferedImage(sheetW, sheetH, BufferedImage.TYPE_INT_ARGB)
    val g = sheet.createGraphics()
    try {
      g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
      g.setRenderingHint(
        RenderingHints.KEY_TEXT_ANTIALIASING,
        RenderingHints.VALUE_TEXT_ANTIALIAS_ON,
      )
      g.color = Color.WHITE
      g.fillRect(0, 0, sheetW, sheetH)
      g.font = Font(Font.SANS_SERIF, Font.PLAIN, 12)

      for ((i, cell) in cells.withIndex()) {
        val col = i % cols
        val row = i / cols
        val originX = col * cellW
        val originY = row * cellH
        val imgAreaX = originX + PADDING
        val imgAreaY = originY + PADDING

        val img = images[i]
        if (img != null) {
          // Centre the cell image within its tile so mixed-size cells stay aligned.
          val dx = imgAreaX + (tileW - img.width) / 2
          val dy = imgAreaY + (tileH - img.height) / 2
          g.drawImage(img, dx, dy, null)
        } else {
          g.color = Color(0xEE, 0xEE, 0xEE)
          g.fillRect(imgAreaX, imgAreaY, tileW, tileH)
          g.color = Color.GRAY
          g.drawRect(imgAreaX, imgAreaY, tileW - 1, tileH - 1)
          g.drawString("(no render)", imgAreaX + 8, imgAreaY + 20)
        }

        g.color = Color.DARK_GRAY
        val caption = ellipsize(cell.label, g, cellW - PADDING * 2)
        g.drawString(caption, originX + PADDING, originY + PADDING + tileH + CAPTION_HEIGHT - 6)
      }
    } finally {
      g.dispose()
    }

    val out = ByteArrayOutputStream()
    ImageIO.write(sheet, "png", out)
    return out.toByteArray()
  }

  /** Trim [text] with a trailing ellipsis so it fits within [maxWidth] px in the graphics font. */
  private fun ellipsize(text: String, g: Graphics2D, maxWidth: Int): String {
    val fm = g.fontMetrics
    if (fm.stringWidth(text) <= maxWidth) return text
    var s = text
    while (s.isNotEmpty() && fm.stringWidth("$s…") > maxWidth) s = s.dropLast(1)
    return "$s…"
  }
}
