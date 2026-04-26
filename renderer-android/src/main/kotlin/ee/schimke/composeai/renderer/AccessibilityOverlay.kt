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
 * Layout: screenshot on the left, legend panel on the right. Merged children
 * (the inner `Text`s of a clickable container) are not given their own row —
 * they're inlined under their merged parent's row with mini-swatches that
 * match the per-child screenshot fills, so the legend stays short and the
 * parent/child grouping reads at a glance.
 */
internal object AccessibilityOverlay {

    /** Width of the legend panel beside the screenshot. */
    private const val LEGEND_WIDTH = 540

    /** Vertical padding between legend rows. */
    private const val ROW_PADDING = 10

    /** Outer margin inside the legend panel. */
    private const val LEGEND_MARGIN = 24

    /** Badge radius (px) for finding numbers. */
    private const val BADGE_RADIUS = 22f

    /** Side of the colour swatch drawn next to each ANI legend row. */
    private const val SWATCH_SIDE = 28f

    /** Side of the inline-child swatch drawn under a merged parent row. */
    private const val MINI_SWATCH_SIDE = 18f

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

    /**
     * Side-by-side composer: screenshot on the left at its native (or
     * upscaled-Wear) size, legend panel on the right at [LEGEND_WIDTH]. The
     * canvas height is the larger of the screenshot height and the legend's
     * content height, so a tall phone preview won't pad the legend and a
     * short Wear preview won't truncate it.
     */
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
        val groups = groupNodes(nodes, nodeColors)

        val findingsBlock = measureFindingsBlock(findings, LEGEND_WIDTH)
        val nodesBlock = measureNodesBlock(groups, LEGEND_WIDTH)
        val headerBlock = 28 + ROW_PADDING + 6
        val legendMin = LEGEND_MARGIN + headerBlock + findingsBlock + nodesBlock + LEGEND_MARGIN
        val canvasHeight = max(drawn.height, legendMin)
        val composite = Bitmap.createBitmap(
            drawn.width + LEGEND_WIDTH,
            canvasHeight,
            Bitmap.Config.ARGB_8888,
        )
        val canvas = Canvas(composite).apply { drawColor(Color.WHITE) }
        val imageTopOffset = (canvasHeight - drawn.height) / 2

        // Translucent pastel fills first so finding outlines layer on top.
        drawNodeFills(canvas, nodes, nodeColors, offsetX = 0f, offsetY = imageTopOffset.toFloat())
        canvas.drawBitmap(drawn, 0f, imageTopOffset.toFloat(), null)
        // Re-draw fills *over* the bitmap with their full stroke — the bitmap
        // we just drew covers the pre-fill, so we paint the fill again and
        // add a thin border so each region reads as a region, not a tint.
        drawNodeFills(canvas, nodes, nodeColors, offsetX = 0f, offsetY = imageTopOffset.toFloat())
        drawNodeBorders(canvas, nodes, nodeColors, offsetX = 0f, offsetY = imageTopOffset.toFloat())
        findings.forEachIndexed { i, f ->
            drawFindingBadge(canvas, i + 1, f, offsetX = 0f, offsetY = imageTopOffset.toFloat())
        }

        val legendX = drawn.width.toFloat()
        drawLegendBackground(canvas, legendX, 0f, LEGEND_WIDTH, canvasHeight)
        var y = LEGEND_MARGIN.toFloat()
        y = drawHeader(canvas, findings.size, nodes.size, legendX + LEGEND_MARGIN, y)
        y = drawFindingsRows(canvas, findings, legendX, y, LEGEND_WIDTH)
        drawNodeGroups(canvas, groups, legendX, y, LEGEND_WIDTH)
        if (drawn !== source) drawn.recycle()
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
     * Pairs a merged parent node with the merged children that follow it in
     * the extraction order. Orphan unmerged nodes (no preceding focusable
     * parent in the list) get rendered as standalone groups with an empty
     * [children] list and the dashed-border + `↳ ` legend treatment.
     */
    private data class NodeGroup(
        val parent: AccessibilityNode,
        val parentColor: Int,
        val children: List<Pair<AccessibilityNode, Int>>,
    )

    /**
     * Walks the node list once, attaching each run of `merged=false` nodes
     * to the most recent `merged=true` node. Relies on ATF's depth-first
     * `allViews` iteration putting parent before its descendants — the same
     * ordering [AccessibilityChecker.extractNodes] preserves.
     */
    private fun groupNodes(
        nodes: List<AccessibilityNode>,
        colors: IntArray,
    ): List<NodeGroup> {
        val groups = mutableListOf<NodeGroup>()
        var i = 0
        while (i < nodes.size) {
            val node = nodes[i]
            if (node.merged) {
                val children = mutableListOf<Pair<AccessibilityNode, Int>>()
                var j = i + 1
                while (j < nodes.size && !nodes[j].merged) {
                    children += nodes[j] to colors[j]
                    j++
                }
                groups += NodeGroup(node, colors[i], children)
                i = j
            } else {
                groups += NodeGroup(node, colors[i], emptyList())
                i++
            }
        }
        return groups
    }

    private fun drawNodeGroups(
        canvas: Canvas,
        groups: List<NodeGroup>,
        originX: Float,
        top: Float,
        panelWidth: Int,
    ): Float {
        var y = top
        for (g in groups) y = drawNodeGroup(canvas, g, originX, y, panelWidth)
        return y
    }

    /**
     * One legend block: parent swatch + label + role/states subtitle, then
     * (if any merged children) an inline child line indented under the
     * label, where each child is a mini-swatch + label pair separated by
     * `·`. Returns the next row's top Y.
     */
    private fun drawNodeGroup(
        canvas: Canvas,
        group: NodeGroup,
        originX: Float,
        top: Float,
        panelWidth: Int,
    ): Float {
        val swatchX = originX + LEGEND_MARGIN
        val swatchY = top + 4f
        drawSwatch(canvas, swatchX, swatchY, SWATCH_SIDE, group.parentColor)

        val textX = swatchX + SWATCH_SIDE + 14f
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.BLACK
            textSize = 20f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val rightMargin = LEGEND_MARGIN
        val textWidth = (panelWidth - (textX - originX) - rightMargin).toInt()
        val baseLabel = group.parent.label.ifEmpty { group.parent.role ?: "(unlabelled)" }
        // ↳ marks orphan rows whose semantics merge into a screen-reader-
        // focusable ancestor we couldn't pair up — kept as a standalone
        // legend row, distinguishable from a true focus stop. Inline
        // children under a real parent don't need this prefix.
        val labelText = if (group.parent.merged) baseLabel else "↳ $baseLabel"
        val labelLines = wrap(labelText, labelPaint, textWidth)
        var y = top + 22f
        for (line in labelLines) {
            canvas.drawText(line, textX, y, labelPaint)
            y += 24f
        }

        // Subtitle: role + states separated by ' · ', skipped when both
        // empty (purely-textual nodes don't need a subtitle line).
        val subtitleParts = buildList {
            group.parent.role?.let { add(it) }
            addAll(group.parent.states)
        }
        if (subtitleParts.isNotEmpty()) {
            val sub = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = Color.rgb(0x60, 0x60, 0x66)
                textSize = 17f
            }
            canvas.drawText(subtitleParts.joinToString(" · "), textX, y, sub)
            y += 20f
        }

        if (group.children.isNotEmpty()) {
            y += 4f
            y = drawInlineChildren(
                canvas = canvas,
                children = group.children,
                leftX = textX,
                top = y,
                maxWidth = textWidth,
            )
        }
        return y + ROW_PADDING.toFloat()
    }

    /**
     * Lays out merged children horizontally under their parent's label,
     * each as a `[mini-swatch] label` pair separated by `·`. Wraps to a new
     * line when the next pair would overflow [maxWidth]. Returns the y of
     * the last drawn line's baseline.
     */
    private fun drawInlineChildren(
        canvas: Canvas,
        children: List<Pair<AccessibilityNode, Int>>,
        leftX: Float,
        top: Float,
        maxWidth: Int,
    ): Float {
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.rgb(0x20, 0x20, 0x24)
            textSize = 18f
        }
        val sepPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.rgb(0x80, 0x80, 0x88)
            textSize = 18f
        }
        val sepText = "·"
        val sepWidth = sepPaint.measureText(sepText)
        val sepGap = 8f
        val miniGap = 6f
        val lineHeight = 26f
        val maxX = leftX + maxWidth

        var x = leftX
        var y = top + 16f
        for ((idx, pair) in children.withIndex()) {
            val (child, color) = pair
            val rawLabel = child.label.ifEmpty { child.role ?: "(unlabelled)" }
            val labelWidth = labelPaint.measureText(rawLabel)
            val itemWidth = MINI_SWATCH_SIDE + miniGap + labelWidth
            val sepNeeded = idx > 0
            val advance = if (sepNeeded) sepGap + sepWidth + sepGap + itemWidth else itemWidth
            if (sepNeeded && x + advance > maxX) {
                x = leftX
                y += lineHeight
            } else if (sepNeeded) {
                x += sepGap
                canvas.drawText(sepText, x, y, sepPaint)
                x += sepWidth + sepGap
            }
            // Mini swatch — vertically centred on the label baseline.
            val miniTop = y - MINI_SWATCH_SIDE + 4f
            drawSwatch(canvas, x, miniTop, MINI_SWATCH_SIDE, color)
            x += MINI_SWATCH_SIDE + miniGap
            // If the label alone overflows the line, wrap-fit the trailing
            // text instead of spilling into the legend background. Rare —
            // child labels are short — but keeps Wear-narrow panels safe.
            val drawn = if (x + labelWidth <= maxX) {
                canvas.drawText(rawLabel, x, y, labelPaint)
                labelWidth
            } else {
                val fitted = ellipsize(rawLabel, labelPaint, (maxX - x).toInt())
                canvas.drawText(fitted, x, y, labelPaint)
                labelPaint.measureText(fitted)
            }
            x += drawn
        }
        return y + 4f
    }

    private fun drawSwatch(
        canvas: Canvas,
        x: Float,
        y: Float,
        side: Float,
        color: Int,
    ) {
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            this.color = color
        }
        canvas.drawRoundRect(RectF(x, y, x + side, y + side), 6f, 6f, fill)
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1f
            this.color = Color.rgb(0x60, 0x60, 0x66)
            alpha = 120
        }
        canvas.drawRoundRect(RectF(x, y, x + side, y + side), 6f, 6f, border)
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
     * Sum of grouped-row heights — parent label + optional subtitle +
     * optional inline-children block (with wrap simulation so we reserve
     * the right number of lines for narrow panels).
     */
    private fun measureNodesBlock(groups: List<NodeGroup>, panelWidth: Int): Int {
        if (groups.isEmpty()) return 0
        val labelPaint = Paint().apply { textSize = 20f }
        val childPaint = Paint().apply { textSize = 18f }
        val textWidth = panelWidth - LEGEND_MARGIN * 2 - (SWATCH_SIDE + 14f).toInt()
        var total = 0
        for (g in groups) {
            val baseLabel = g.parent.label.ifEmpty { g.parent.role ?: "(unlabelled)" }
            val labelText = if (g.parent.merged) baseLabel else "↳ $baseLabel"
            val labelLines = wrap(labelText, labelPaint, textWidth).size.coerceAtLeast(1)
            val hasSubtitle = g.parent.role != null || g.parent.states.isNotEmpty()
            var rowHeight = 22 + 24 * labelLines + (if (hasSubtitle) 20 else 0)
            if (g.children.isNotEmpty()) {
                rowHeight += 4 + measureChildLines(g.children, childPaint, textWidth) * 26 + 4
            }
            total += rowHeight + ROW_PADDING
        }
        return total
    }

    private fun measureChildLines(
        children: List<Pair<AccessibilityNode, Int>>,
        labelPaint: Paint,
        maxWidth: Int,
    ): Int {
        val sepGap = 8f
        val sepWidth = labelPaint.measureText("·")
        val miniGap = 6f
        var x = 0f
        var lines = 1
        for ((idx, pair) in children.withIndex()) {
            val rawLabel = pair.first.label.ifEmpty { pair.first.role ?: "(unlabelled)" }
            val itemWidth = MINI_SWATCH_SIDE + miniGap + labelPaint.measureText(rawLabel)
            val advance = if (idx > 0) sepGap + sepWidth + sepGap + itemWidth else itemWidth
            if (idx > 0 && x + advance > maxWidth) {
                lines++
                x = itemWidth
            } else {
                x += advance
            }
        }
        return lines
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

    /**
     * Truncate [text] with a trailing `…` so the result fits in [maxWidth]
     * pixels. Used when a single inline-child label would otherwise spill
     * past the legend's right edge — rare, but Wear's narrow rendering
     * makes it possible enough to handle defensively.
     */
    private fun ellipsize(text: String, paint: Paint, maxWidth: Int): String {
        val ellipsis = "…"
        if (paint.measureText(text) <= maxWidth) return text
        val ellipsisWidth = paint.measureText(ellipsis)
        if (ellipsisWidth >= maxWidth) return ""
        var end = text.length
        while (end > 0 && paint.measureText(text, 0, end) + ellipsisWidth > maxWidth) end--
        return text.substring(0, end) + ellipsis
    }
}
