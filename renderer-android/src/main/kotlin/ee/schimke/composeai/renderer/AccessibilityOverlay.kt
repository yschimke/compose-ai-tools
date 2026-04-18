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
     * Aspect threshold (width / height) at which the layout flips from
     * side-by-side to stacked. Portrait phones (0.5) stay side-by-side;
     * Wear rounds/squares (1.0) and desktop/tablet landscapes (≥1.3) stack
     * so the legend can use the full screenshot width without looking
     * cramped next to a tiny round preview.
     */
    private const val STACK_ASPECT_THRESHOLD = 0.8f

    /**
     * Writes the annotated PNG next to [sourcePng]. If [findings] is empty,
     * does nothing (keeps the build cache tidy — zero findings means nothing
     * to show, and the CLI/VSCode treat the absence of the file accordingly).
     *
     * Layout is adaptive: portrait screenshots get a right-side legend
     * ([LEGEND_WIDTH] wide), everything squarer (Wear, landscape, desktop)
     * gets the legend *below* the screenshot so the legend panel can take
     * the full screenshot width. See [STACK_ASPECT_THRESHOLD].
     *
     * @return the destination [File] when written, `null` otherwise.
     */
    fun generate(sourcePng: File, findings: List<AccessibilityFinding>): File? {
        if (findings.isEmpty() || !sourcePng.exists()) return null

        val source = BitmapFactory.decodeFile(sourcePng.absolutePath)
            ?: return null

        val aspect = source.width.toFloat() / source.height.toFloat()
        val stacked = aspect >= STACK_ASPECT_THRESHOLD

        val composite = if (stacked) {
            drawStacked(source, findings)
        } else {
            drawSideBySide(source, findings)
        }

        val dest = File(sourcePng.parentFile, "${sourcePng.nameWithoutExtension}.a11y.png")
        dest.outputStream().use { composite.compress(Bitmap.CompressFormat.PNG, 100, it) }
        source.recycle()
        composite.recycle()
        return dest
    }

    /**
     * Portrait (tall) composition: screenshot on the left, fixed-width legend
     * on the right. The canvas height is `max(screenshotHeight, legendHeight)`
     * so both panes fit without cropping.
     */
    private fun drawSideBySide(source: Bitmap, findings: List<AccessibilityFinding>): Bitmap {
        val legendHeight = estimateLegendHeight(findings, LEGEND_WIDTH)
        val canvasHeight = maxOf(source.height, legendHeight)
        val composite = Bitmap.createBitmap(
            source.width + LEGEND_WIDTH,
            canvasHeight,
            Bitmap.Config.ARGB_8888,
        )
        val canvas = Canvas(composite)
        canvas.drawColor(Color.WHITE)

        val imageTop = (canvasHeight - source.height) / 2
        canvas.drawBitmap(source, 0f, imageTop.toFloat(), null)
        findings.forEachIndexed { i, f -> drawBadgeAndOutline(canvas, i + 1, f, 0f, imageTop.toFloat()) }

        drawLegend(
            canvas = canvas,
            findings = findings,
            originX = source.width.toFloat(),
            originY = 0f,
            width = LEGEND_WIDTH,
            height = canvasHeight,
        )
        return composite
    }

    /**
     * Square-ish composition (Wear, landscape, desktop): screenshot on top,
     * legend stretched across the full width below. Avoids the awkward
     * "tall narrow legend beside a tiny round preview" look on Wear.
     */
    private fun drawStacked(source: Bitmap, findings: List<AccessibilityFinding>): Bitmap {
        // Legend stretches to the screenshot width, but clamp to a sensible
        // minimum so a 200dp Wear round doesn't produce a painfully narrow
        // legend column. Wear previews at 227 are a common case.
        val legendWidth = maxOf(source.width, LEGEND_WIDTH)
        val legendHeight = estimateLegendHeight(findings, legendWidth)
        val composite = Bitmap.createBitmap(
            legendWidth,
            source.height + legendHeight,
            Bitmap.Config.ARGB_8888,
        )
        val canvas = Canvas(composite)
        canvas.drawColor(Color.WHITE)

        // Centre the screenshot horizontally when the legend is wider than it.
        val imageLeft = (legendWidth - source.width) / 2
        canvas.drawBitmap(source, imageLeft.toFloat(), 0f, null)
        findings.forEachIndexed { i, f -> drawBadgeAndOutline(canvas, i + 1, f, imageLeft.toFloat(), 0f) }

        drawLegend(
            canvas = canvas,
            findings = findings,
            originX = 0f,
            originY = source.height.toFloat(),
            width = legendWidth,
            height = legendHeight,
        )
        return composite
    }

    private fun drawBadgeAndOutline(
        canvas: Canvas,
        number: Int,
        finding: AccessibilityFinding,
        offsetX: Float,
        offsetY: Float,
    ) {
        val bounds = parseBounds(finding.boundsInScreen) ?: return
        val color = levelColor(finding.level)

        // Outline around the finding. Inset so the stroke sits *inside* the
        // element's touch bounds — useful for tiny targets where a
        // full-stroke outline would render entirely outside the visible box.
        val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 4f
            this.color = color
        }
        canvas.drawRect(
            RectF(
                offsetX + bounds.left.toFloat() + 2f,
                offsetY + bounds.top.toFloat() + 2f,
                offsetX + bounds.right.toFloat() - 2f,
                offsetY + bounds.bottom.toFloat() - 2f,
            ),
            outline,
        )

        // Badge anchored at the top-left corner of the element so it stays
        // next to the offending control even when bounds clip the edge of
        // the image.
        val cx = offsetX + bounds.left.toFloat().coerceAtLeast(BADGE_RADIUS)
        val cy = offsetY + bounds.top.toFloat().coerceAtLeast(BADGE_RADIUS)

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
