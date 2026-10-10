package ee.schimke.composeai.guidelines

import ee.schimke.composeai.guidelines.protocol.GuidelineVerdictV1
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.math.roundToInt

/**
 * Draws a preview's findings over its render: an outline on every node a `fail` names (from the
 * accessibility bounds, which are pixels of the same render) and a dashed outline on every region,
 * each with a numbered badge. Plain AWT, so it runs headless in CI.
 *
 * Nothing is filled over the content: the finding is usually about exactly what is under the mark
 * (text too small, a clipped line), so the outline is drawn around it, as a bright stroke over a
 * dark halo that reads on light and dark renders alike. Rule ids are not written on the picture —
 * at the width a PR comment shows it they were illegible and overlapped — the badge carries a
 * number, and the report lists the findings under the same numbers.
 *
 * Numbering follows the findings as given — the CLI passes `failures()`, by descending confidence,
 * which is the order `guidelines-report.py` lists them in — the first finding with something to
 * draw is 1, the next is 2, and a finding with nothing on this picture gets no number. Every mark
 * of one finding carries its number.
 */
public object GuidelineAnnotator {
  /**
   * [png] with [failures] drawn on it — their nodes, and the regions they point at
   * ([GuidelineVerdictV1.regions]) that belong to [subjectId] — the input unchanged when it cannot
   * be read.
   */
  public fun annotate(
    png: ByteArray,
    nodes: List<PreviewNode>,
    failures: List<GuidelineVerdictV1>,
    subjectId: String? = null,
  ): ByteArray {
    val source = runCatching { ImageIO.read(ByteArrayInputStream(png)) }.getOrNull() ?: return png
    val marks = marks(nodes, failures, subjectId, source.width, source.height)
    val style = Style.forWidth(source.width)
    val image = BufferedImage(source.width, source.height, BufferedImage.TYPE_INT_ARGB)
    val g = image.createGraphics()
    try {
      g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
      g.setRenderingHint(
        RenderingHints.KEY_TEXT_ANTIALIASING,
        RenderingHints.VALUE_TEXT_ANTIALIAS_ON,
      )
      g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
      g.drawImage(source, 0, 0, null)
      marks.forEach { outline(g, it, style) }
      val badges = placeBadges(marks, source.width, source.height, style.badge, style.outset)
      marks.zip(badges).forEach { (mark, centre) -> badge(g, mark.number, centre, style) }
    } finally {
      g.dispose()
    }
    return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
  }

  /** One thing drawn on the picture: a node's bounds or a region, numbered by its finding. */
  internal data class Mark(
    val number: Int,
    val ruleId: String,
    val bounds: Rectangle,
    val region: Boolean,
  )

  /**
   * What [annotate] draws, in drawing order: per finding in the given order, its nodes the render
   * has, then its regions on this subject. Findings with nothing to draw take no number.
   */
  internal fun marks(
    nodes: List<PreviewNode>,
    failures: List<GuidelineVerdictV1>,
    subjectId: String?,
    width: Int,
    height: Int,
  ): List<Mark> {
    val byId = nodes.associateBy { it.id }
    var next = 1
    return failures.flatMap { verdict ->
      val rects =
        verdict.nodeIds.mapNotNull(byId::get).map {
          Rectangle(it.left, it.top, it.right - it.left, it.bottom - it.top) to false
        } +
          verdict.regions
            .filter { subjectId == null || it.subjectId == null || it.subjectId == subjectId }
            .map {
              Rectangle(
                (it.x * width).roundToInt(),
                (it.y * height).roundToInt(),
                (it.width * width).roundToInt().coerceAtLeast(1),
                (it.height * height).roundToInt().coerceAtLeast(1),
              ) to true
            }
      if (rects.isEmpty()) emptyList()
      else {
        val number = next++
        rects.map { (rect, region) -> Mark(number, verdict.ruleId, rect, region) }
      }
    }
  }

  /**
   * A centre for each mark's badge, in [marks] order: diameter [size], wholly inside the
   * [width]×[height] picture, overlapping no earlier badge where any spot allows it, and preferring
   * the spot that covers the least of the marked boxes — just outside a corner of its own box, so
   * the badge sits beside what the finding is about rather than on it.
   */
  internal fun placeBadges(
    marks: List<Mark>,
    width: Int,
    height: Int,
    size: Int,
    outset: Int = 0,
  ): List<Point> {
    val r = size / 2.0
    val gap = (size / 8.0).coerceAtLeast(2.0)
    val placed = mutableListOf<Point>()
    marks.forEach { mark ->
      val b = Rectangle(mark.bounds).apply { grow(outset, outset) }
      val left = b.x + r
      val right = b.x + b.width - r
      val above = b.y - r - gap
      val below = b.y + b.height + r + gap
      val top = b.y + r
      val bottom = b.y + b.height - r
      val candidates =
        listOf(
            Point(left, above),
            Point(right, above),
            Point(left, below),
            Point(right, below),
            Point(b.x - r - gap, top),
            Point(b.x + b.width + r + gap, top),
            Point(b.x - r - gap, bottom),
            Point(b.x + b.width + r + gap, bottom),
            Point(left, top),
            Point(right, top),
          )
          .map { it.clamp(r, width - r, height - r) }
      val free = candidates.filter { c -> placed.none { it.distance(c) < size + gap } }
      val chosen =
        free.minByOrNull { c -> marks.sumOf { coverage(c, r, it.bounds) } }
          ?: slide(candidates.first(), placed, size + gap, r, width, height)
      placed += chosen
    }
    return placed
  }

  internal data class Point(val x: Double, val y: Double) {
    fun distance(other: Point): Double = Math.hypot(x - other.x, y - other.y)

    fun clamp(min: Double, maxX: Double, maxY: Double): Point =
      Point(x.coerceIn(min, maxX.coerceAtLeast(min)), y.coerceIn(min, maxY.coerceAtLeast(min)))
  }

  /** How much of [box] a badge of radius [r] at [c] would cover, as the overlap of its square. */
  private fun coverage(c: Point, r: Double, box: Rectangle): Double {
    val w = minOf(c.x + r, box.x + box.width.toDouble()) - maxOf(c.x - r, box.x.toDouble())
    val h = minOf(c.y + r, box.y + box.height.toDouble()) - maxOf(c.y - r, box.y.toDouble())
    return if (w > 0 && h > 0) w * h else 0.0
  }

  /** Every spot was taken: step down (then up) from [start] until clear of [placed]. */
  private fun slide(
    start: Point,
    placed: List<Point>,
    spacing: Double,
    r: Double,
    width: Int,
    height: Int,
  ): Point {
    val steps = (height / spacing).toInt() + 1
    for (i in 1..steps) {
      for (dy in listOf(i * spacing, -i * spacing)) {
        val c = Point(start.x, start.y + dy).clamp(r, width - r, height - r)
        if (placed.none { it.distance(c) < spacing }) return c
      }
    }
    return start
  }

  /** Drawn just outside the box, so the stroke never covers the edge of what it marks. */
  private fun outline(g: Graphics2D, mark: Mark, style: Style) {
    val b = Rectangle(mark.bounds).apply { grow(style.outset, style.outset) }
    g.color = HALO
    g.stroke =
      BasicStroke(style.stroke + 2 * style.halo, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
    g.drawRect(b.x, b.y, b.width, b.height)
    g.color = MARK
    g.stroke =
      if (mark.region)
        BasicStroke(
          style.stroke,
          BasicStroke.CAP_BUTT,
          BasicStroke.JOIN_MITER,
          10f,
          floatArrayOf(style.stroke * 3, style.stroke * 2),
          0f,
        )
      else BasicStroke(style.stroke)
    g.drawRect(b.x, b.y, b.width, b.height)
  }

  private fun badge(g: Graphics2D, number: Int, centre: Point, style: Style) {
    val d = style.badge
    val x = (centre.x - d / 2.0).roundToInt()
    val y = (centre.y - d / 2.0).roundToInt()
    g.color = HALO
    val halo = style.halo.roundToInt()
    g.fillOval(x - halo, y - halo, d + 2 * halo, d + 2 * halo)
    g.color = MARK
    g.fillOval(x, y, d, d)
    val text = number.toString()
    var size = (d * 0.66f)
    g.font = Font(Font.SANS_SERIF, Font.BOLD, size.roundToInt())
    while (g.fontMetrics.stringWidth(text) > d * 0.78 && size > 6f) {
      size -= 1f
      g.font = g.font.deriveFont(size)
    }
    val bounds = g.font.createGlyphVector(g.fontRenderContext, text).visualBounds
    g.color = HALO
    g.drawString(
      text,
      (centre.x - bounds.x - bounds.width / 2.0).toFloat(),
      (centre.y - bounds.y - bounds.height / 2.0).toFloat(),
    )
  }

  /**
   * Sizes relative to the picture's width, so a mark reads the same however wide the picture is
   * shown: a badge about 7.5% of the width (24px when a 408px Wear render is shown 320px wide).
   */
  private class Style(val stroke: Float, val halo: Float, val badge: Int) {
    /** How far outside a box its outline is drawn: the stroke and the halo's inner half. */
    val outset: Int = (stroke / 2 + halo).roundToInt()

    companion object {
      fun forWidth(width: Int): Style {
        val stroke = (width / 130f).coerceAtLeast(2.5f)
        val badge = (width * 0.075f).roundToInt().coerceAtLeast(24)
        return Style(stroke, (stroke * 0.6f).coerceAtLeast(1.5f), badge)
      }
    }
  }

  /** Bright amber over near-black: legible on dark and light renders alike. */
  private val MARK = Color(0xFF, 0xC4, 0x00)
  private val HALO = Color(0x10, 0x10, 0x10)
}
