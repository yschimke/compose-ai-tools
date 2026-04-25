package ee.schimke.composeai.renderer

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.geom.GeneralPath
import java.awt.image.BufferedImage

/**
 * Renders animation-curve panels.
 *
 * The renderer uses two entry points:
 *
 *   - [renderCurvePanel] — the curve strip: one stacked row per
 *     animated property, time on the x-axis, value on the y-axis. When
 *     called with a [currentTimeMs] it also draws a moving dot on each
 *     curve at that time. This is the per-frame artifact the combined
 *     GIF stitches under each screenshot.
 *   - [composeFrameWithCurves] — composes one screenshot atop one
 *     curve panel into a single `BufferedImage`. The screenshot keeps
 *     its native size; the curve panel is rendered at the screenshot's
 *     width so the two stack cleanly.
 *
 * Track values are coerced to Double via [coerceToDouble]; non-coercible
 * properties become a labeled legend entry without a line.
 */
internal object AnimationCurvePlotter {

    /** A single animated property's time-series — `(timeMs, value)` pairs. */
    data class Track(val label: String, val samples: List<Pair<Long, Any?>>)

    /**
     * Renders the curve panel at [width] × computed-height. Each track
     * gets a fixed-height strip; when [currentTimeMs] is non-negative
     * a vertical line + dot mark the current frame's position.
     */
    fun renderCurvePanel(
        tracks: List<Track>,
        durationMs: Int,
        width: Int,
        currentTimeMs: Long = -1,
    ): BufferedImage {
        val plottableTracks = tracks.map { track ->
            val coerced = track.samples.map { (t, v) -> t to coerceToDouble(v) }
            val numericSamples = coerced.mapNotNull { (t, v) -> v?.let { t to it } }
            Plottable(track.label, numericSamples)
        }
        val height = HEADER_HEIGHT + plottableTracks.size * ROW_HEIGHT + FOOTER_PAD

        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.color = Color.WHITE
            g.fillRect(0, 0, width, height)

            g.color = HEADER_COLOR
            g.font = HEADER_FONT
            val headerLabel = if (currentTimeMs >= 0) {
                "Animation curves — t=${currentTimeMs}ms / ${durationMs}ms"
            } else {
                "Animation curves — ${durationMs}ms"
            }
            g.drawString(headerLabel, 16, 24)

            val plotLeft = 80
            val plotRight = width - 16

            for ((i, track) in plottableTracks.withIndex()) {
                val rowTop = HEADER_HEIGHT + i * ROW_HEIGHT
                val rowBottom = rowTop + ROW_HEIGHT - 12
                val rowMid = (rowTop + rowBottom) / 2

                g.color = Color.BLACK
                g.font = LABEL_FONT
                g.drawString(track.label.take(LABEL_MAX), 8, rowTop + 14)

                g.color = AXIS_COLOR
                g.stroke = BasicStroke(1f)
                g.drawLine(plotLeft, rowBottom, plotRight, rowBottom)
                g.drawLine(plotLeft, rowTop + 16, plotLeft, rowBottom)

                if (track.samples.isEmpty()) {
                    g.color = Color.GRAY
                    g.font = ITALIC_FONT
                    g.drawString("(non-numeric — value not plotted)", plotLeft + 6, rowMid)
                    continue
                }

                val values = track.samples.map { it.second }
                val vMin = values.min()
                val vMax = values.max()
                val vRange = if (vMax - vMin < 1e-9) 1.0 else (vMax - vMin)

                val plotWidth = (plotRight - plotLeft).toDouble()
                val plotInnerHeight = rowBottom - (rowTop + 18).toDouble()
                fun timeToX(t: Long): Double = plotLeft + plotWidth * (t.toDouble() / durationMs.coerceAtLeast(1))
                fun valueToY(v: Double): Double = rowBottom - plotInnerHeight * ((v - vMin) / vRange)

                val path = GeneralPath()
                track.samples.forEachIndexed { idx, (t, v) ->
                    val x = timeToX(t)
                    val y = valueToY(v)
                    if (idx == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                val color = trackColor(i)
                g.color = color
                g.stroke = BasicStroke(1.6f)
                g.draw(path)

                // Moving-dot indicator: vertical line + filled circle on
                // the curve at currentTimeMs, locating the running
                // frame's position on the timeline.
                if (currentTimeMs >= 0) {
                    val cursorX = timeToX(currentTimeMs).coerceIn(plotLeft.toDouble(), plotRight.toDouble())
                    g.color = CURSOR_LINE_COLOR
                    g.stroke = BasicStroke(0.8f)
                    g.drawLine(cursorX.toInt(), rowTop + 16, cursorX.toInt(), rowBottom)
                    val curveY = interpolateValue(track.samples, currentTimeMs)
                    if (curveY != null) {
                        val cy = valueToY(curveY)
                        g.color = color
                        val r = 4.0
                        g.fill(Ellipse2D.Double(cursorX - r, cy - r, 2 * r, 2 * r))
                        g.color = Color.WHITE
                        g.stroke = BasicStroke(1.2f)
                        g.draw(Ellipse2D.Double(cursorX - r, cy - r, 2 * r, 2 * r))
                    }
                }

                g.color = Color.GRAY
                g.font = SMALL_FONT
                g.drawString(formatValue(vMax), 50, rowTop + 22)
                g.drawString(formatValue(vMin), 50, rowBottom - 2)
            }
        } finally {
            g.dispose()
        }
        return img
    }

    /**
     * Stacks [screenshot] above a curve panel (rendered at the
     * screenshot's width) into a single image. Used by the renderer to
     * produce one frame of the combined GIF.
     */
    fun composeFrameWithCurves(
        screenshot: BufferedImage,
        tracks: List<Track>,
        durationMs: Int,
        currentTimeMs: Long,
    ): BufferedImage {
        val panel = renderCurvePanel(tracks, durationMs, screenshot.width, currentTimeMs)
        val composite = BufferedImage(
            screenshot.width,
            screenshot.height + panel.height,
            BufferedImage.TYPE_INT_ARGB,
        )
        val g = composite.createGraphics()
        try {
            g.color = Color.WHITE
            g.fillRect(0, 0, composite.width, composite.height)
            g.drawImage(screenshot, 0, 0, null)
            g.drawImage(panel, 0, screenshot.height, null)
        } finally {
            g.dispose()
        }
        return composite
    }

    private data class Plottable(val label: String, val samples: List<Pair<Long, Double>>)

    /**
     * Linearly interpolates between two adjacent `(timeMs, value)`
     * samples to find the curve's value at exactly [currentTimeMs].
     * Returns `null` if no numeric samples exist.
     */
    private fun interpolateValue(samples: List<Pair<Long, Any?>>, currentTimeMs: Long): Double? {
        val numeric = samples.mapNotNull { (t, v) -> coerceToDouble(v)?.let { t to it } }
        if (numeric.isEmpty()) return null
        if (currentTimeMs <= numeric.first().first) return numeric.first().second
        if (currentTimeMs >= numeric.last().first) return numeric.last().second
        for (i in 1 until numeric.size) {
            val (t1, v1) = numeric[i]
            if (currentTimeMs <= t1) {
                val (t0, v0) = numeric[i - 1]
                val span = (t1 - t0).coerceAtLeast(1)
                val frac = (currentTimeMs - t0).toDouble() / span
                return v0 + (v1 - v0) * frac
            }
        }
        return numeric.last().second
    }

    private const val HEADER_HEIGHT = 36
    private const val ROW_HEIGHT = 80
    private const val FOOTER_PAD = 16
    private const val LABEL_MAX = 80
    private val HEADER_COLOR = Color.DARK_GRAY
    private val AXIS_COLOR = Color.LIGHT_GRAY
    private val CURSOR_LINE_COLOR = Color(0x80, 0x80, 0x80, 0xC0)
    private val HEADER_FONT = Font(Font.SANS_SERIF, Font.BOLD, 13)
    private val LABEL_FONT = Font(Font.SANS_SERIF, Font.PLAIN, 11)
    private val ITALIC_FONT = Font(Font.SANS_SERIF, Font.ITALIC, 10)
    private val SMALL_FONT = Font(Font.SANS_SERIF, Font.PLAIN, 9)

    private fun formatValue(v: Double): String =
        if (v.isFinite() && (v == v.toLong().toDouble())) v.toLong().toString()
        else "%.3g".format(v)

    private val PALETTE = arrayOf(
        Color(0x1F77B4), Color(0xFF7F0E), Color(0x2CA02C), Color(0xD62728),
        Color(0x9467BD), Color(0x8C564B), Color(0xE377C2), Color(0x7F7F7F),
        Color(0xBCBD22), Color(0x17BECF),
    )

    private fun trackColor(i: Int): Color = PALETTE[i % PALETTE.size]
}
