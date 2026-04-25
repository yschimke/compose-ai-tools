package ee.schimke.composeai.renderer

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.geom.GeneralPath
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Renders a multi-track value-vs-time plot of [tracks] to [outputFile].
 *
 * Each track is one animated property sampled at `frameIntervalMs` across
 * the [durationMs] window — the same surface Android Studio's animation
 * inspector exposes in its bottom panel. Track values are coerced to
 * Double via [coerceToDouble]; non-coercible properties become a labeled
 * legend entry without a line.
 *
 * Layout: 800×N px PNG with N proportional to the number of tracks
 * (header + per-track strip ~100px each). White background, black axes,
 * each track gets a distinct hue. Time axis labelled in ms; value axis
 * is normalised per-track so a Float-from-0-to-1 fade and a Color-as-hash
 * track don't squash each other.
 */
internal object AnimationCurvePlotter {
    fun plot(
        tracks: List<Track>,
        durationMs: Int,
        outputFile: File,
    ): File? {
        if (tracks.isEmpty()) return null
        val plottableTracks = tracks.map { track ->
            val coerced = track.samples.map { (t, v) -> t to coerceToDouble(v) }
            val numericSamples = coerced.mapNotNull { (t, v) -> v?.let { t to it } }
            Plottable(track.label, numericSamples)
        }

        val width = 800
        val rowHeight = 100
        val headerHeight = 40
        val legendPad = 20
        val height = headerHeight + plottableTracks.size * rowHeight + legendPad

        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.color = Color.WHITE
            g.fillRect(0, 0, width, height)

            // Header
            g.color = Color.DARK_GRAY
            g.font = Font(Font.SANS_SERIF, Font.BOLD, 14)
            g.drawString("Animation curves — ${durationMs}ms", 16, 24)

            val plotLeft = 80
            val plotRight = width - 16

            for ((i, track) in plottableTracks.withIndex()) {
                val rowTop = headerHeight + i * rowHeight
                val rowBottom = rowTop + rowHeight - 12
                val rowMid = (rowTop + rowBottom) / 2

                // Row label
                g.color = Color.BLACK
                g.font = Font(Font.SANS_SERIF, Font.PLAIN, 11)
                g.drawString(track.label.take(LABEL_MAX), 8, rowTop + 14)

                // Axes: bottom + left (y as a thin guide).
                g.color = Color.LIGHT_GRAY
                g.stroke = BasicStroke(1f)
                g.drawLine(plotLeft, rowBottom, plotRight, rowBottom)
                g.drawLine(plotLeft, rowTop + 16, plotLeft, rowBottom)

                if (track.samples.isEmpty()) {
                    g.color = Color.GRAY
                    g.font = Font(Font.SANS_SERIF, Font.ITALIC, 10)
                    g.drawString("(non-numeric — value not plotted)", plotLeft + 6, rowMid)
                    continue
                }

                val values = track.samples.map { it.second }
                val vMin = values.min()
                val vMax = values.max()
                val vRange = if (vMax - vMin < 1e-9) 1.0 else (vMax - vMin)

                // Curve
                val path = GeneralPath()
                track.samples.forEachIndexed { idx, (t, v) ->
                    val x = plotLeft +
                        (plotRight - plotLeft) * (t.toDouble() / durationMs.coerceAtLeast(1))
                    val y = rowBottom -
                        (rowBottom - (rowTop + 18)) * ((v - vMin) / vRange)
                    if (idx == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                g.color = trackColor(i)
                g.stroke = BasicStroke(1.6f)
                g.draw(path)

                // Min/max value labels
                g.color = Color.GRAY
                g.font = Font(Font.SANS_SERIF, Font.PLAIN, 9)
                g.drawString(formatValue(vMax), 50, rowTop + 22)
                g.drawString(formatValue(vMin), 50, rowBottom - 2)
            }
        } finally {
            g.dispose()
        }
        outputFile.parentFile?.mkdirs()
        ImageIO.write(img, "PNG", outputFile)
        return outputFile
    }

    /** A single animated property's time-series — the `(timeMs, value)` pairs the inspector sampled. */
    data class Track(val label: String, val samples: List<Pair<Long, Any?>>)

    private data class Plottable(val label: String, val samples: List<Pair<Long, Double>>)

    private const val LABEL_MAX = 80

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
