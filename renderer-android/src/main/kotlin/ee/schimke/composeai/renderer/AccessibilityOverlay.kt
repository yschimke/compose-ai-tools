package ee.schimke.composeai.renderer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import java.io.File

/**
 * Generates an annotated screenshot alongside each preview when accessibility
 * checks produce findings.
 *
 * The input PNG is left untouched — the overlay is written as a sibling
 * `<id>.a11y.png` so downstream tools can show either the clean render or the
 * annotated one. The composite has the original screenshot on the left with
 * numbered badges + coloured outlines at each finding's `boundsInScreen`, and
 * a legend panel on the right listing rule + message for each badge.
 *
 * Drawn with native Canvas instead of a second Compose render pass because
 * the annotation is pure data — boxes and text over an existing bitmap. A
 * Compose pass would need another `ActivityScenario`, double the capture
 * latency, and deal with inspection-mode quirks; Canvas sidesteps all of it.
 */
internal object AccessibilityOverlay {

    /** Width of the legend panel when laid out beside a portrait screenshot. */
    private const val LEGEND_WIDTH = 520

    /** Vertical padding between legend rows. */
    private const val ROW_PADDING = 12

    /** Outer margin inside the legend panel. */
    private const val LEGEND_MARGIN = 24

    /** Badge radius (px). Small enough not to obscure the element, big enough to read. */
    private const val BADGE_RADIUS = 22f

    /**
     * Outline stroke width (px). The whole point of the overlay is to draw
     * attention to the offending element without smothering the UI it sits
     * on top of, so we keep the stroke thin and translucent
     * ([OUTLINE_ALPHA]) and let the badge carry the colour weight.
     */
    private const val OUTLINE_STROKE = 2f

    /**
     * Outline stroke alpha (0–255). Picks up the level colour but at ~60%
     * opacity so backgrounds, text, and small targets behind the stroke
     * still read clearly. Earlier full-opacity 4f borders crowded out the
     * UI on tiny Wear bounds.
     */
    private const val OUTLINE_ALPHA = 150

    /**
     * Minimum side length (px) for the screenshot panel. Sources smaller
     * than this on both axes — Wear small/large round at 192–227 — are
     * upscaled (preserving aspect) so they don't look dwarfed next to the
     * fixed-width legend. Phones / tablets / desktop already comfortably
     * exceed this on at least one axis and pass through unchanged.
     */
    private const val MIN_SCREENSHOT_DIM = 400

    /**
     * Writes the annotated PNG next to [sourcePng]. If [findings] is empty,
     * does nothing (keeps the build cache tidy — zero findings means nothing
     * to show, and the CLI/VSCode treat the absence of the file accordingly).
     *
     * Layout matches Paparazzi's a11y snapshots: screenshot on the left,
     * fixed-width legend on the right — for every device. Wear previews
     * end up looking proportionally narrow next to the legend, but a
     * consistent shape makes batches of reports easier to skim than a
     * Wear-specific stacked layout would.
     *
     * @return the destination [File] when written, `null` otherwise.
     */
    fun generate(sourcePng: File, findings: List<AccessibilityFinding>): File? {
        if (findings.isEmpty()) return null
        if (!sourcePng.exists()) {
            // The render pipeline is supposed to write outputFile before
            // calling us, so a missing source means the wiring shifted —
            // worth surfacing rather than silently dropping the overlay.
            System.err.println(
                "[compose-a11y] overlay skipped: source PNG missing at ${sourcePng.absolutePath}",
            )
            return null
        }
        return try {
            generateInternal(sourcePng, findings)
        } catch (t: Throwable) {
            // Without this catch, an exception inside Canvas / Bitmap.createBitmap
            // would propagate through writePerPreviewReport and skip the JSON
            // report too — masking the original failure as "no a11y data".
            // Logging the stack here keeps the report intact and tells CI logs
            // exactly what to fix.
            System.err.println(
                "[compose-a11y] overlay failed for ${sourcePng.name}: " +
                    "${t.javaClass.simpleName}: ${t.message}",
            )
            t.printStackTrace(System.err)
            null
        }
    }

    private fun generateInternal(sourcePng: File, findings: List<AccessibilityFinding>): File? {
        val source = BitmapFactory.decodeFile(sourcePng.absolutePath)
        if (source == null) {
            System.err.println(
                "[compose-a11y] overlay skipped: BitmapFactory could not decode " +
                    "${sourcePng.absolutePath} (size=${sourcePng.length()} bytes)",
            )
            return null
        }

        val composite = drawSideBySide(source, findings)

        val dest = File(sourcePng.parentFile, "${sourcePng.nameWithoutExtension}.a11y.png")
        dest.outputStream().use { composite.compress(Bitmap.CompressFormat.PNG, 100, it) }
        source.recycle()
        composite.recycle()
        return dest
    }

    /**
     * Side-by-side composition: screenshot on the left, fixed-width legend
     * on the right. The canvas height is `max(screenshotHeight, legendHeight)`
     * so both panes fit without cropping; the screenshot is centred
     * vertically in its column when the legend is taller, and Wear-sized
     * sources are upscaled to [MIN_SCREENSHOT_DIM] so they don't look
     * dwarfed next to the legend.
     */
    private fun drawSideBySide(source: Bitmap, findings: List<AccessibilityFinding>): Bitmap {
        val scale = screenshotScale(source)
        val drawn = if (scale > 1f) {
            // ARGB_8888 + filter=true so the upscale stays smooth on the
            // round-clipped Wear edges — nearest-neighbour produced
            // distracting jaggies on the alpha boundary.
            Bitmap.createScaledBitmap(
                source,
                (source.width * scale).toInt(),
                (source.height * scale).toInt(),
                true,
            )
        } else source

        val legendHeight = estimateLegendHeight(findings, LEGEND_WIDTH)
        val canvasHeight = maxOf(drawn.height, legendHeight)
        val composite = Bitmap.createBitmap(
            drawn.width + LEGEND_WIDTH,
            canvasHeight,
            Bitmap.Config.ARGB_8888,
        )
        val canvas = Canvas(composite)
        canvas.drawColor(Color.WHITE)

        val imageTop = (canvasHeight - drawn.height) / 2
        canvas.drawBitmap(drawn, 0f, imageTop.toFloat(), null)
        findings.forEachIndexed { i, f ->
            drawBadgeAndOutline(canvas, i + 1, f, 0f, imageTop.toFloat(), scale)
        }

        drawLegend(
            canvas = canvas,
            findings = findings,
            originX = drawn.width.toFloat(),
            originY = 0f,
            width = LEGEND_WIDTH,
            height = canvasHeight,
        )
        // [drawn] is either [source] (scale = 1) or a fresh upscaled bitmap.
        // The caller recycles [source] separately; only the scaled copy
        // needs releasing here.
        if (drawn !== source) drawn.recycle()
        return composite
    }

    /**
     * Returns the upscale factor for [source] when both dimensions are
     * smaller than [MIN_SCREENSHOT_DIM] (i.e. Wear). Larger sources get
     * `1f` and pass through Bitmap.createScaledBitmap unchanged.
     */
    private fun screenshotScale(source: Bitmap): Float {
        if (source.width >= MIN_SCREENSHOT_DIM || source.height >= MIN_SCREENSHOT_DIM) {
            return 1f
        }
        // Use the larger dimension so the screenshot exactly hits MIN on
        // its long side and stays inside it on the short side — preserves
        // the round/square shape Wear ships with.
        return MIN_SCREENSHOT_DIM.toFloat() / maxOf(source.width, source.height)
    }

    private fun drawBadgeAndOutline(
        canvas: Canvas,
        number: Int,
        finding: AccessibilityFinding,
        offsetX: Float,
        offsetY: Float,
        scale: Float,
    ) {
        val bounds = parseBounds(finding.boundsInScreen) ?: return
        val color = levelColor(finding.level)
        // boundsInScreen are in the source bitmap's pixel space; when we
        // upscale Wear screenshots the badge / outline have to follow.
        val left = bounds.left * scale
        val top = bounds.top * scale
        val right = bounds.right * scale
        val bottom = bounds.bottom * scale

        // Outline around the finding. Inset by half [OUTLINE_STROKE] so the
        // stroke sits *inside* the element's touch bounds — useful for tiny
        // targets where a full-thickness outline would otherwise render
        // entirely outside the visible box. Translucent so the original UI
        // still reads through; the badge carries the loud colour.
        val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = OUTLINE_STROKE
            this.color = color
            alpha = OUTLINE_ALPHA
        }
        val inset = OUTLINE_STROKE / 2f
        canvas.drawRect(
            RectF(
                offsetX + left + inset,
                offsetY + top + inset,
                offsetX + right - inset,
                offsetY + bottom - inset,
            ),
            outline,
        )

        // Badge anchored at the top-left corner of the element so it stays
        // next to the offending control even when bounds clip the edge of
        // the image.
        val cx = offsetX + left.coerceAtLeast(BADGE_RADIUS)
        val cy = offsetY + top.coerceAtLeast(BADGE_RADIUS)

        val badgeBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            this.color = color
        }
        canvas.drawCircle(cx, cy, BADGE_RADIUS, badgeBg)

        val badgeText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.WHITE
            textSize = 24f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val fm = badgeText.fontMetrics
        canvas.drawText(
            number.toString(),
            cx,
            cy - (fm.ascent + fm.descent) / 2f,
            badgeText,
        )
    }

    private fun drawLegend(
        canvas: Canvas,
        findings: List<AccessibilityFinding>,
        originX: Float,
        originY: Float,
        width: Int,
        height: Int,
    ) {
        val bg = Paint().apply { color = Color.rgb(0xF6, 0xF6, 0xF8); style = Paint.Style.FILL }
        canvas.drawRect(originX, originY, originX + width, originY + height, bg)

        // Header
        val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 28f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val headerY = originY + LEGEND_MARGIN + headerPaint.textSize
        canvas.drawText(
            "Accessibility (${findings.size})",
            originX + LEGEND_MARGIN,
            headerY,
            headerPaint,
        )

        // Rows
        var y = headerY + ROW_PADDING + 20f
        findings.forEachIndexed { index, finding ->
            y = drawLegendRow(canvas, index + 1, finding, originX, y, width)
        }
    }

    /** Draws a single legend row; returns the next row's top Y. */
    private fun drawLegendRow(
        canvas: Canvas,
        number: Int,
        finding: AccessibilityFinding,
        offsetX: Float,
        top: Float,
        panelWidth: Int,
    ): Float {
        val color = levelColor(finding.level)

        // Badge — same visual language as the overlay so the mapping is obvious.
        val badgeX = offsetX + LEGEND_MARGIN + BADGE_RADIUS
        val badgeY = top + BADGE_RADIUS
        val badgeBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; this.color = color }
        canvas.drawCircle(badgeX, badgeY, BADGE_RADIUS, badgeBg)
        val badgeText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.WHITE
            textSize = 24f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val bfm = badgeText.fontMetrics
        canvas.drawText(number.toString(), badgeX, badgeY - (bfm.ascent + bfm.descent) / 2f, badgeText)

        // Rule name (bold)
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.BLACK
            textSize = 22f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val titleX = badgeX + BADGE_RADIUS + 14f
        val titleY = top + 24f
        canvas.drawText("${finding.level} · ${finding.type}", titleX, titleY, titlePaint)

        // Message (wrapped)
        val messagePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.rgb(0x30, 0x30, 0x33)
            textSize = 20f
        }
        val textWidth = (panelWidth - LEGEND_MARGIN - (BADGE_RADIUS * 2 + 14f) - LEGEND_MARGIN).toInt()
        val lines = wrap(finding.message, messagePaint, textWidth)
        var lineY = titleY + 28f
        for (line in lines) {
            canvas.drawText(line, titleX, lineY, messagePaint)
            lineY += 26f
        }

        // Element description (small, muted)
        val elementDesc = finding.viewDescription
        if (!elementDesc.isNullOrBlank()) {
            val descPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = Color.rgb(0x70, 0x70, 0x76)
                textSize = 18f
                isAntiAlias = true
            }
            val truncated = if (elementDesc.length > 64) elementDesc.take(61) + "…" else elementDesc
            canvas.drawText(truncated, titleX, lineY + 4f, descPaint)
            lineY += 24f
        }

        return lineY + ROW_PADDING.toFloat()
    }

    private fun estimateLegendHeight(findings: List<AccessibilityFinding>, width: Int): Int {
        // Rough upper-bound estimate. A wider panel wraps long messages into
        // fewer lines, so we linearly scale the assumed "lines per row" by
        // the panel width relative to the default (narrow) LEGEND_WIDTH.
        // Overshooting here is safe — the composite sizes to this estimate
        // and the final draw just paints within it. Undershooting clips
        // the last row.
        val headerHeight = LEGEND_MARGIN * 2 + 28
        val assumedMessageLines = if (width >= LEGEND_WIDTH * 2) 1 else 2
        val rowHeight = 24 + 28 + 26 * assumedMessageLines + 24 + ROW_PADDING
        return headerHeight + findings.size * rowHeight + LEGEND_MARGIN
    }

    private fun levelColor(level: String): Int = when (level) {
        "ERROR" -> Color.rgb(0xD3, 0x2F, 0x2F)
        "WARNING" -> Color.rgb(0xF5, 0x7C, 0x00)
        "INFO" -> Color.rgb(0x19, 0x76, 0xD2)
        else -> Color.rgb(0x75, 0x75, 0x75)
    }

    private fun parseBounds(s: String?): Rect? {
        if (s == null) return null
        val parts = s.split(",").mapNotNull { it.trim().toIntOrNull() }
        if (parts.size != 4) return null
        return Rect(parts[0], parts[1], parts[2], parts[3])
    }

    /** Greedy word-wrap fitting [text] into [maxWidth] pixels using [paint]'s metrics. */
    private fun wrap(text: String, paint: Paint, maxWidth: Int): List<String> {
        val words = text.split(' ')
        val out = mutableListOf<String>()
        var current = StringBuilder()
        for (word in words) {
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (paint.measureText(candidate) <= maxWidth) {
                current = StringBuilder(candidate)
            } else {
                if (current.isNotEmpty()) out.add(current.toString())
                current = StringBuilder(word)
            }
        }
        if (current.isNotEmpty()) out.add(current.toString())
        return out
    }
}
