package ee.schimke.composeai.renderer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import java.io.File
import kotlin.math.max

/**
 * Generates an annotated screenshot for each preview that has either ATF
 * findings or accessibility-relevant nodes.
 *
 * Visual language matches Cash App's Paparazzi a11y snapshots: every node
 * ATF tracks (label / role / state) gets a translucent pastel fill on the
 * screenshot and a swatched legend row in matching colour, so reviewers can
 * map "what TalkBack sees" without counting overlays. ATF findings layer on
 * top with the louder red/orange/blue numbered badges they've always had.
 *
 * Layout adapts to aspect ratio:
 *  - **Tall** previews (h/w ≥ [TALL_ASPECT]) keep the side-by-side shape —
 *    screenshot left, legend right, ANI rows y-aligned to their element's
 *    top edge so the eye can scan straight across.
 *  - **Non-tall** (Wear, landscape) stack the legend vertically: findings
 *    above the screenshot, ANI rows below it, so the small round Wear face
 *    isn't squeezed by a fixed-width side panel.
 */
internal object AccessibilityOverlay {

    /** Width of the legend panel when laid out beside a tall screenshot. */
    private const val LEGEND_WIDTH = 540

    /** Vertical padding between legend rows. */
    private const val ROW_PADDING = 10

    /** Outer margin inside the legend panel. */
    private const val LEGEND_MARGIN = 24

    /** Badge radius (px) for finding numbers. */
    private const val BADGE_RADIUS = 22f

    /** Side of the colour swatch drawn next to each ANI legend row. */
    private const val SWATCH_SIDE = 28f

    /** Outline stroke width (px) on a finding's element bounds. */
    private const val OUTLINE_STROKE = 2f

    /** Outline alpha (0–255). Translucent so UI behind still reads. */
    private const val OUTLINE_ALPHA = 150

    /**
     * Alpha (0–255) for the translucent fill drawn over each ANI element.
     * ~30% opacity — enough to identify the region in the legend mapping,
     * not enough to obscure the underlying control. Findings still get the
     * full-strength outline + badge on top.
     */
    private const val NODE_FILL_ALPHA = 80

    /**
     * Fill alpha for unmerged descendants (the inner `Text` of a `Button`
     * whose semantics merge into the button). Roughly half [NODE_FILL_ALPHA]
     * so reviewers eyeball "this is structure underneath a real focus
     * stop, not its own TalkBack stop".
     */
    private const val UNMERGED_NODE_FILL_ALPHA = 40

    /** Dash on/off pattern (px) for unmerged-node borders. */
    private val UNMERGED_DASH_INTERVAL = floatArrayOf(6f, 4f)

    /** Aspect at which we switch from stacked-legend to side-by-side. */
    private const val TALL_ASPECT = 1.3f

    /** Wear sources upscale to this short side so the legend doesn't dwarf them. */
    private const val MIN_SCREENSHOT_DIM = 400

    /**
     * Pastel palette for ANI nodes. Cycled by index, so consecutive nodes get
     * adjacent hues — easier to disambiguate two close-together elements than
     * a random palette would be. Saturated enough that the swatch reads on a
     * white legend background; light enough that the [NODE_FILL_ALPHA] fill
     * doesn't darken the screenshot much.
     */
    private val NODE_PALETTE = intArrayOf(
        Color.rgb(0xF8, 0xBB, 0xD0), // pink
        Color.rgb(0xB3, 0xE5, 0xFC), // light blue
        Color.rgb(0xFF, 0xE0, 0xB2), // peach
        Color.rgb(0xC8, 0xE6, 0xC9), // mint
        Color.rgb(0xE1, 0xBE, 0xE7), // lavender
        Color.rgb(0xFF, 0xF5, 0x9D), // pale yellow
        Color.rgb(0xFF, 0xCC, 0xBC), // coral
        Color.rgb(0xB2, 0xEB, 0xF2), // cyan
    )

    /**
     * Writes the annotated PNG next to [sourcePng]. No-op when [findings] and
     * [nodes] are both empty. Returns the destination [File] when written.
     */
    fun generate(
        sourcePng: File,
        findings: List<AccessibilityFinding>,
        nodes: List<AccessibilityNode>,
    ): File? {
        if (findings.isEmpty() && nodes.isEmpty()) return null
        if (!sourcePng.exists()) {
            // The render pipeline writes outputFile before calling us; a
            // missing source means the wiring shifted — surface it rather
            // than silently dropping the overlay.
            System.err.println(
                "[compose-a11y] overlay skipped: source PNG missing at ${sourcePng.absolutePath}",
            )
            return null
        }
        return try {
            generateInternal(sourcePng, findings, nodes)
        } catch (t: Throwable) {
            // Without this catch, a Canvas / Bitmap.createBitmap blow-up
            // would propagate through writePerPreviewReport and skip the
            // JSON report too — masking the original failure as "no a11y
            // data". Logging the stack here keeps the report intact.
            System.err.println(
                "[compose-a11y] overlay failed for ${sourcePng.name}: " +
                    "${t.javaClass.simpleName}: ${t.message}",
            )
            t.printStackTrace(System.err)
            null
        }
    }

    private fun generateInternal(
        sourcePng: File,
        findings: List<AccessibilityFinding>,
        nodes: List<AccessibilityNode>,
    ): File? {
        val source = BitmapFactory.decodeFile(sourcePng.absolutePath)
        if (source == null) {
            System.err.println(
                "[compose-a11y] overlay skipped: BitmapFactory could not decode " +
                    "${sourcePng.absolutePath} (size=${sourcePng.length()} bytes)",
            )
            return null
        }
        val composite = compose(source, findings, nodes)
        val dest = File(sourcePng.parentFile, "${sourcePng.nameWithoutExtension}.a11y.png")
        dest.outputStream().use { composite.compress(Bitmap.CompressFormat.PNG, 100, it) }
        source.recycle()
        composite.recycle()
        return dest
    }

    private fun compose(
        source: Bitmap,
        findings: List<AccessibilityFinding>,
        nodes: List<AccessibilityNode>,
    ): Bitmap {
        val scale = screenshotScale(source)
        val drawn = if (scale > 1f) {
            Bitmap.createScaledBitmap(
                source,
                (source.width * scale).toInt(),
                (source.height * scale).toInt(),
                /* filter = */ true,
            )
        } else source
        // Stable per-node colour assignment; both the screenshot fill and the
        // legend swatch read the same index, so they always match.
        val nodeColors = IntArray(nodes.size) { NODE_PALETTE[it % NODE_PALETTE.size] }
        val isTall = drawn.height.toFloat() / drawn.width.toFloat() >= TALL_ASPECT
        val composite = if (isTall) {
            composeTall(drawn, findings, nodes, nodeColors)
        } else {
            composeStacked(drawn, findings, nodes, nodeColors)
        }
        if (drawn !== source) drawn.recycle()
        return composite
    }

    /**
     * Tall layout: screenshot on the left, legend on the right. Findings are
     * stacked at the top of the legend; ANI rows below them, each y-anchored
     * at the element's `bounds.top` (with overlap prevention so adjacent
     * elements don't trample each other).
     */
    private fun composeTall(
        screenshot: Bitmap,
        findings: List<AccessibilityFinding>,
        nodes: List<AccessibilityNode>,
        nodeColors: IntArray,
    ): Bitmap {
        val findingsBlock = measureFindingsBlock(findings, LEGEND_WIDTH)
        val nodesBlockMin = measureNodesBlockMin(nodes)
        val legendMin = LEGEND_MARGIN + findingsBlock + nodesBlockMin + LEGEND_MARGIN
        val canvasHeight = max(screenshot.height, legendMin)
        val composite = Bitmap.createBitmap(
            screenshot.width + LEGEND_WIDTH,
            canvasHeight,
            Bitmap.Config.ARGB_8888,
        )
        val canvas = Canvas(composite).apply { drawColor(Color.WHITE) }
        val imageTopOffset = (canvasHeight - screenshot.height) / 2

        // Translucent pastel fills first so finding outlines layer on top.
        drawNodeFills(canvas, nodes, nodeColors, offsetX = 0f, offsetY = imageTopOffset.toFloat())
        canvas.drawBitmap(screenshot, 0f, imageTopOffset.toFloat(), null)
        // Re-draw fills *over* the bitmap with their full stroke — the bitmap
        // we just drew covers the pre-fill, so we paint the fill again and
        // add a thin border so each region reads as a region, not a tint.
        drawNodeFills(canvas, nodes, nodeColors, offsetX = 0f, offsetY = imageTopOffset.toFloat())
        drawNodeBorders(canvas, nodes, nodeColors, offsetX = 0f, offsetY = imageTopOffset.toFloat())
        findings.forEachIndexed { i, f ->
            drawFindingBadge(canvas, i + 1, f, offsetX = 0f, offsetY = imageTopOffset.toFloat())
        }

        val legendX = screenshot.width.toFloat()
        drawLegendBackground(canvas, legendX, 0f, LEGEND_WIDTH, canvasHeight)
        var y = LEGEND_MARGIN.toFloat()
        y = drawHeader(canvas, findings.size, nodes.size, legendX + LEGEND_MARGIN, y)
        y = drawFindingsRows(canvas, findings, legendX, y, LEGEND_WIDTH)
        drawNodeRowsAligned(
            canvas = canvas,
            nodes = nodes,
            nodeColors = nodeColors,
            originX = legendX,
            top = y,
            bottom = canvasHeight - LEGEND_MARGIN.toFloat(),
            panelWidth = LEGEND_WIDTH,
            imageTopOffset = imageTopOffset.toFloat(),
        )
        return composite
    }

    /**
     * Non-tall layout (Wear, landscape): findings stacked above the
     * screenshot, ANI rows stacked below it. Screenshot is centred
     * horizontally inside the wider of the two rails.
     */
    private fun composeStacked(
        screenshot: Bitmap,
        findings: List<AccessibilityFinding>,
        nodes: List<AccessibilityNode>,
        nodeColors: IntArray,
    ): Bitmap {
        // Stacked legend wants more elbow room than the side-by-side one —
        // a Wear screenshot is ~400px wide, so we let rows fill that plus a
        // margin instead of clipping to LEGEND_WIDTH.
        val panelWidth = max(screenshot.width, LEGEND_WIDTH) + LEGEND_MARGIN * 2
        val findingsBlock = if (findings.isEmpty()) 0 else
            measureFindingsBlock(findings, panelWidth) + LEGEND_MARGIN
        val nodesBlock = if (nodes.isEmpty()) 0 else
            measureNodesStackedBlock(nodes, panelWidth) + LEGEND_MARGIN
        val headerBlock = LEGEND_MARGIN + 28 + ROW_PADDING + 16
        val canvasWidth = panelWidth
        val canvasHeight = headerBlock + findingsBlock + screenshot.height + nodesBlock + LEGEND_MARGIN
        val composite = Bitmap.createBitmap(canvasWidth, canvasHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(composite).apply { drawColor(Color.WHITE) }

        var y = LEGEND_MARGIN.toFloat()
        y = drawHeader(canvas, findings.size, nodes.size, LEGEND_MARGIN.toFloat(), y)
        if (findings.isNotEmpty()) {
            y = drawFindingsRows(canvas, findings, originX = 0f, top = y, panelWidth = canvasWidth)
            y += LEGEND_MARGIN
        }
        // Centre the screenshot horizontally; record its origin so finding /
        // node draws line up with the same coordinates.
        val imageX = ((canvasWidth - screenshot.width) / 2).toFloat()
        val imageY = y
        drawNodeFills(canvas, nodes, nodeColors, imageX, imageY)
        canvas.drawBitmap(screenshot, imageX, imageY, null)
        drawNodeFills(canvas, nodes, nodeColors, imageX, imageY)
        drawNodeBorders(canvas, nodes, nodeColors, imageX, imageY)
        findings.forEachIndexed { i, f ->
            drawFindingBadge(canvas, i + 1, f, imageX, imageY)
        }
        y = imageY + screenshot.height
        if (nodes.isNotEmpty()) {
            y += LEGEND_MARGIN
            drawNodeRowsStacked(canvas, nodes, nodeColors, originX = 0f, top = y, panelWidth = canvasWidth)
        }
        return composite
    }

    private fun screenshotScale(source: Bitmap): Float {
        if (source.width >= MIN_SCREENSHOT_DIM || source.height >= MIN_SCREENSHOT_DIM) return 1f
        return MIN_SCREENSHOT_DIM.toFloat() / max(source.width, source.height)
    }

    // ---------- screenshot layer ----------

    private fun drawNodeFills(
        canvas: Canvas,
        nodes: List<AccessibilityNode>,
        nodeColors: IntArray,
        offsetX: Float,
        offsetY: Float,
    ) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        nodes.forEachIndexed { i, node ->
            val r = parseBounds(node.boundsInScreen) ?: return@forEachIndexed
            paint.color = nodeColors[i]
            paint.alpha = if (node.merged) NODE_FILL_ALPHA else UNMERGED_NODE_FILL_ALPHA
            canvas.drawRect(
                offsetX + r.left, offsetY + r.top, offsetX + r.right, offsetY + r.bottom,
                paint,
            )
        }
    }

    private fun drawNodeBorders(
        canvas: Canvas,
        nodes: List<AccessibilityNode>,
        nodeColors: IntArray,
        offsetX: Float,
        offsetY: Float,
    ) {
        // Two paint instances so the cached `pathEffect` doesn't leak across
        // merged borders (Paint mutates its effect ref on assignment).
        val solid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.5f
        }
        val dashed = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1f
            pathEffect = DashPathEffect(UNMERGED_DASH_INTERVAL, 0f)
        }
        nodes.forEachIndexed { i, node ->
            val r = parseBounds(node.boundsInScreen) ?: return@forEachIndexed
            val paint = if (node.merged) solid else dashed
            paint.color = nodeColors[i]
            paint.alpha = if (node.merged) 200 else 140
            canvas.drawRect(
                offsetX + r.left + 0.5f, offsetY + r.top + 0.5f,
                offsetX + r.right - 0.5f, offsetY + r.bottom - 0.5f,
                paint,
            )
        }
    }

    private fun drawFindingBadge(
        canvas: Canvas,
        number: Int,
        finding: AccessibilityFinding,
        offsetX: Float,
        offsetY: Float,
    ) {
        val r = parseBounds(finding.boundsInScreen) ?: return
        val color = levelColor(finding.level)
        val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = OUTLINE_STROKE
            this.color = color
            alpha = OUTLINE_ALPHA
        }
        val inset = OUTLINE_STROKE / 2f
        canvas.drawRect(
            RectF(
                offsetX + r.left + inset, offsetY + r.top + inset,
                offsetX + r.right - inset, offsetY + r.bottom - inset,
            ),
            outline,
        )
        // Badge anchored at the top-left so it stays next to the offending
        // control even when bounds clip the edge of the image.
        val cx = offsetX + r.left.toFloat().coerceAtLeast(BADGE_RADIUS)
        val cy = offsetY + r.top.toFloat().coerceAtLeast(BADGE_RADIUS)
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; this.color = color }
        canvas.drawCircle(cx, cy, BADGE_RADIUS, bg)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.WHITE
            textSize = 24f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val fm = text.fontMetrics
        canvas.drawText(number.toString(), cx, cy - (fm.ascent + fm.descent) / 2f, text)
    }

    // ---------- legend layer ----------

    private fun drawLegendBackground(
        canvas: Canvas,
        x: Float, y: Float, w: Int, h: Int,
    ) {
        val bg = Paint().apply { color = Color.rgb(0xFA, 0xFA, 0xFC); style = Paint.Style.FILL }
        canvas.drawRect(x, y, x + w, y + h, bg)
        val divider = Paint().apply { color = Color.rgb(0xE3, 0xE3, 0xE8); strokeWidth = 1f }
        canvas.drawLine(x, y, x, y + h, divider)
    }

    private fun drawHeader(
        canvas: Canvas,
        findingCount: Int,
        nodeCount: Int,
        x: Float,
        y: Float,
    ): Float {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 28f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val baseline = y + paint.textSize
        val title = buildString {
            append("Accessibility")
            val parts = mutableListOf<String>()
            if (findingCount > 0) parts += "$findingCount finding${if (findingCount == 1) "" else "s"}"
            if (nodeCount > 0) parts += "$nodeCount element${if (nodeCount == 1) "" else "s"}"
            if (parts.isNotEmpty()) append(" · ").append(parts.joinToString(", "))
        }
        canvas.drawText(title, x, baseline, paint)
        return baseline + ROW_PADDING + 6f
    }

    private fun drawFindingsRows(
        canvas: Canvas,
        findings: List<AccessibilityFinding>,
        originX: Float,
        top: Float,
        panelWidth: Int,
    ): Float {
        var y = top
        findings.forEachIndexed { i, f ->
            y = drawFindingRow(canvas, i + 1, f, originX, y, panelWidth)
        }
        return y
    }

    private fun drawFindingRow(
        canvas: Canvas,
        number: Int,
        finding: AccessibilityFinding,
        originX: Float,
        top: Float,
        panelWidth: Int,
    ): Float {
        val color = levelColor(finding.level)
        val badgeX = originX + LEGEND_MARGIN + BADGE_RADIUS
        val badgeY = top + BADGE_RADIUS
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; this.color = color }
        canvas.drawCircle(badgeX, badgeY, BADGE_RADIUS, bg)
        val badgeText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.WHITE
            textSize = 24f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val bfm = badgeText.fontMetrics
        canvas.drawText(number.toString(), badgeX, badgeY - (bfm.ascent + bfm.descent) / 2f, badgeText)

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.BLACK
            textSize = 22f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val textX = badgeX + BADGE_RADIUS + 14f
        val titleY = top + 24f
        canvas.drawText("${finding.level} · ${finding.type}", textX, titleY, titlePaint)

        val msgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.rgb(0x30, 0x30, 0x33)
            textSize = 20f
        }
        val rightMargin = LEGEND_MARGIN
        val textWidth = (panelWidth - (textX - originX) - rightMargin).toInt()
        val lines = wrap(finding.message, msgPaint, textWidth)
        var lineY = titleY + 28f
        for (line in lines) {
            canvas.drawText(line, textX, lineY, msgPaint)
            lineY += 26f
        }
        return lineY + ROW_PADDING.toFloat()
    }

    /**
     * Y-aligned ANI rows for the tall layout. Each row's preferred top is the
     * element's `bounds.top` in canvas space; we walk top-down and push any
     * row that would collide with the previous one downward by a row
     * height + padding, so the relative ordering matches the screenshot
     * even if some elements are stacked too tightly to align exactly.
     */
    private fun drawNodeRowsAligned(
        canvas: Canvas,
        nodes: List<AccessibilityNode>,
        nodeColors: IntArray,
        originX: Float,
        top: Float,
        bottom: Float,
        panelWidth: Int,
        imageTopOffset: Float,
    ) {
        if (nodes.isEmpty()) return
        // Index-color pairs sorted by anchor Y. Sorting is stable on tied
        // anchors so left-to-right reading order survives.
        data class Anchored(val node: AccessibilityNode, val color: Int, val anchor: Float)
        val anchored = nodes.mapIndexedNotNull { i, n ->
            val r = parseBounds(n.boundsInScreen) ?: return@mapIndexedNotNull null
            Anchored(n, nodeColors[i], imageTopOffset + r.top)
        }.sortedBy { it.anchor }

        var prevBottom = top
        for (a in anchored) {
            val rowTop = max(a.anchor, prevBottom + ROW_PADDING)
            val rowBottom = drawNodeRow(canvas, a.node, a.color, originX, rowTop, panelWidth)
            if (rowBottom > bottom) break
            prevBottom = rowBottom
        }
    }

    private fun drawNodeRowsStacked(
        canvas: Canvas,
        nodes: List<AccessibilityNode>,
        nodeColors: IntArray,
        originX: Float,
        top: Float,
        panelWidth: Int,
    ): Float {
        var y = top
        nodes.forEachIndexed { i, n ->
            y = drawNodeRow(canvas, n, nodeColors[i], originX, y, panelWidth)
        }
        return y
    }

    /**
     * One ANI legend row: solid swatch (matching the screenshot fill) +
     * label, with role / states as a smaller second line. Returns the next
     * row's top Y.
     */
    private fun drawNodeRow(
        canvas: Canvas,
        node: AccessibilityNode,
        color: Int,
        originX: Float,
        top: Float,
        panelWidth: Int,
    ): Float {
        // Swatch — full opacity in the legend so the colour is unambiguous,
        // even though its on-screenshot counterpart is translucent.
        val swatchX = originX + LEGEND_MARGIN
        val swatchY = top + 4f
        val swatchPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; this.color = color }
        canvas.drawRoundRect(
            RectF(swatchX, swatchY, swatchX + SWATCH_SIDE, swatchY + SWATCH_SIDE),
            6f, 6f, swatchPaint,
        )
        val swatchBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1f
            this.color = Color.rgb(0x60, 0x60, 0x66)
            alpha = 120
        }
        canvas.drawRoundRect(
            RectF(swatchX, swatchY, swatchX + SWATCH_SIDE, swatchY + SWATCH_SIDE),
            6f, 6f, swatchBorder,
        )

        val textX = swatchX + SWATCH_SIDE + 14f
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.BLACK
            textSize = 20f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val rightMargin = LEGEND_MARGIN
        val textWidth = (panelWidth - (textX - originX) - rightMargin).toInt()
        val baseLabel = node.label.ifEmpty { node.role ?: "(unlabelled)" }
        // ↳ marks rows whose semantics merge into a screen-reader-focusable
        // ancestor, so reviewers can tell "extra structure underneath a
        // focus stop" from "a separate TalkBack stop". Kept on the same
        // line as the label rather than as a state chip — the prefix is
        // structural, not a state.
        val labelText = if (node.merged) baseLabel else "↳ $baseLabel"
        val labelLines = wrap(labelText, labelPaint, textWidth)
        var y = top + 22f
        for (line in labelLines) {
            canvas.drawText(line, textX, y, labelPaint)
            y += 24f
        }

        // Subtitle: role + states separated by ' · ', skipped when both
        // empty (purely-textual nodes don't need a subtitle line).
        val subtitleParts = buildList {
            node.role?.let { add(it) }
            addAll(node.states)
        }
        if (subtitleParts.isNotEmpty()) {
            val sub = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = Color.rgb(0x60, 0x60, 0x66)
                textSize = 17f
            }
            canvas.drawText(subtitleParts.joinToString(" · "), textX, y, sub)
            y += 20f
        }
        return y + ROW_PADDING.toFloat()
    }

    // ---------- measurement ----------

    /** Total height a findings block (rows only, no header) consumes. */
    private fun measureFindingsBlock(findings: List<AccessibilityFinding>, panelWidth: Int): Int {
        if (findings.isEmpty()) return 0
        val msgPaint = Paint().apply { textSize = 20f }
        val textWidth = panelWidth - LEGEND_MARGIN * 2 - (BADGE_RADIUS * 2 + 14f).toInt()
        var total = 0
        for (f in findings) {
            val lines = wrap(f.message, msgPaint, textWidth).size.coerceAtLeast(1)
            total += 24 + 28 + 26 * lines + ROW_PADDING
        }
        return total
    }

    /**
     * Lower bound on tall-layout ANI block height — assumes each row is one
     * line of label + one subtitle line. Y-alignment may push some rows
     * further down, but those overflow into the canvas's bottom margin
     * rather than getting clipped.
     */
    private fun measureNodesBlockMin(nodes: List<AccessibilityNode>): Int {
        if (nodes.isEmpty()) return 0
        return nodes.size * (22 + 24 + 20 + ROW_PADDING)
    }

    private fun measureNodesStackedBlock(nodes: List<AccessibilityNode>, panelWidth: Int): Int {
        if (nodes.isEmpty()) return 0
        val labelPaint = Paint().apply { textSize = 20f }
        val textWidth = panelWidth - LEGEND_MARGIN * 2 - (SWATCH_SIDE + 14f).toInt()
        var total = 0
        for (n in nodes) {
            val baseLabel = n.label.ifEmpty { n.role ?: "(unlabelled)" }
            val labelText = if (n.merged) baseLabel else "↳ $baseLabel"
            val lines = wrap(labelText, labelPaint, textWidth).size.coerceAtLeast(1)
            val hasSubtitle = n.role != null || n.states.isNotEmpty()
            total += 22 + 24 * lines + (if (hasSubtitle) 20 else 0) + ROW_PADDING
        }
        return total
    }

    // ---------- shared helpers ----------

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
        if (text.isEmpty()) return listOf("")
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
