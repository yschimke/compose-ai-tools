package ee.schimke.composeai.daemon

import ee.schimke.composeai.cli.AccessibilityFinding
import ee.schimke.composeai.cli.AccessibilityNode
import ee.schimke.composeai.io.SystemFileSystem
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.FontMetrics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.geom.Line2D
import java.awt.geom.Rectangle2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.max
import okio.FileSystem
import okio.Path.Companion.toPath

/**
 * AWT port of `:data-a11y-core`'s `AccessibilityOverlay` for the desktop daemon.
 *
 * Same Paparazzi-style visual language: screenshot on the left with translucent pastel fills over
 * each accessibility-relevant node, legend panel on the right with a swatched row per node (label /
 * role / states), merged children inlined under their parent. All visual constants (panel width,
 * palette, alphas, swatch sizes, padding, upscale threshold) are kept byte-identical to the Android
 * source so the two backends produce visually matching overlays.
 *
 * **What's cut vs. the Android source:** the circular-clip path (`isRound`) — desktop has no round
 * watch-face previews, and `ImageComposeScene` never clips. The findings/badge layer is kept but
 * always receives an empty list (ATF is Android-only), so no badges are ever drawn; the code stays
 * so the layout maths matches.
 *
 * **AWT API mapping vs. android.graphics:**
 * - `Paint.measureText` → [FontMetrics.stringWidth].
 * - Baseline centring: AWT `FontMetrics.getAscent()` is **positive** (android's
 *   `fontMetrics.ascent` is negative), so vertical centring is `cy + (ascent - descent) / 2f`.
 * - `Typeface.BOLD` → `Font(Font.SANS_SERIF, Font.BOLD, size)`.
 * - `drawRect` / `drawRoundRect` / `drawLine` / `drawCircle` → `Graphics2D.fill` / `draw` of
 *   `Rectangle2D` / `RoundRectangle2D` / `Line2D` / `Ellipse2D`.
 * - Translucent fills → `Color(r, g, b, alpha)` with alpha 0–255.
 * - `BitmapFactory.decodeFile` → [ImageIO.read]; `Bitmap.compress(PNG)` → `ImageIO.write(..,
 *   "png")`.
 * - Upscale → `drawImage` with `INTERPOLATION_BILINEAR` + antialiasing / text-antialiasing enabled.
 */
object DesktopAccessibilityOverlay {

  /** Width of the legend panel beside the screenshot. */
  private const val LEGEND_WIDTH = 540

  /** Vertical padding between legend rows. */
  private const val ROW_PADDING = 10

  /** Outer margin inside the legend panel. */
  private const val LEGEND_MARGIN = 24

  /** Badge radius (px) for finding numbers (findings are always empty on desktop). */
  private const val BADGE_RADIUS = 22f

  /** Side of the colour swatch drawn next to each node legend row. */
  private const val SWATCH_SIDE = 28f

  /** Side of the inline-child swatch drawn under a merged parent row. */
  private const val MINI_SWATCH_SIDE = 18f

  /**
   * Alpha (0–255) for the translucent fill over each merged node — ~10% so the screenshot reads.
   */
  private const val NODE_FILL_ALPHA = 24

  /** Dot on/off pattern (px) for unmerged-node borders. */
  private val UNMERGED_DASH_INTERVAL = floatArrayOf(2f, 4f)

  /** Sources upscale to this short side so the legend doesn't dwarf them. */
  private const val MIN_SCREENSHOT_DIM = 400

  /** Pastel palette for nodes. Cycled by index — adjacent hues for neighbouring nodes. */
  private val NODE_PALETTE =
    intArrayOf(
      rgb(0xF8, 0xBB, 0xD0), // pink
      rgb(0xB3, 0xE5, 0xFC), // light blue
      rgb(0xFF, 0xE0, 0xB2), // peach
      rgb(0xC8, 0xE6, 0xC9), // mint
      rgb(0xE1, 0xBE, 0xE7), // lavender
      rgb(0xFF, 0xF5, 0x9D), // pale yellow
      rgb(0xFF, 0xCC, 0xBC), // coral
      rgb(0xB2, 0xEB, 0xF2), // cyan
    )

  /**
   * Writes the annotated PNG to [destPng] (creating parent directories as needed). Returns the
   * destination [File] when written, `null` when [nodes] is empty (nothing to draw) or [sourcePng]
   * is missing / undecodable. Wrapped so a draw failure never strands the primary PNG — the caller
   * logs and continues.
   */
  fun generate(
    sourcePng: File,
    nodes: List<AccessibilityNode>,
    destPng: File,
    fileSystem: FileSystem = SystemFileSystem,
  ): File? {
    if (nodes.isEmpty()) return null
    if (!sourcePng.exists()) {
      System.err.println(
        "[compose-a11y] overlay skipped: source PNG missing at ${sourcePng.absolutePath}"
      )
      return null
    }
    return try {
      generateInternal(sourcePng, nodes, destPng, fileSystem)
    } catch (t: Throwable) {
      // Without this catch a BufferedImage / Graphics2D blow-up would propagate through
      // writeArtifacts and skip the JSON sidecars too — masking the failure as "no a11y data".
      System.err.println(
        "[compose-a11y] overlay failed for ${sourcePng.name}: " +
          "${t.javaClass.simpleName}: ${t.message}"
      )
      t.printStackTrace(System.err)
      null
    }
  }

  private fun generateInternal(
    sourcePng: File,
    nodes: List<AccessibilityNode>,
    destPng: File,
    fileSystem: FileSystem,
  ): File? {
    val source =
      ImageIO.read(fileSystem.read(sourcePng.path.toPath()) { readByteArray() }.inputStream())
    if (source == null) {
      System.err.println(
        "[compose-a11y] overlay skipped: ImageIO could not decode " +
          "${sourcePng.absolutePath} (size=${sourcePng.length()} bytes)"
      )
      return null
    }
    val composite = compose(source, emptyList(), nodes)
    destPng.parentFile?.mkdirs()
    fileSystem.write(destPng.path.toPath()) { ImageIO.write(composite, "png", outputStream()) }
    return destPng
  }

  /**
   * Side-by-side composer: screenshot on the left at its native (or upscaled) size, legend panel on
   * the right at [LEGEND_WIDTH]. Canvas height is the larger of the screenshot height and the
   * legend's content height.
   */
  private fun compose(
    source: BufferedImage,
    findings: List<AccessibilityFinding>,
    nodes: List<AccessibilityNode>,
  ): BufferedImage {
    val scale = screenshotScale(source)
    val drawnW = (source.width * scale).toInt()
    val drawnH = (source.height * scale).toInt()

    val nodeColors = IntArray(nodes.size) { NODE_PALETTE[it % NODE_PALETTE.size] }
    val groups = groupNodes(nodes, nodeColors)

    val findingsBlock = measureFindingsBlock(findings, LEGEND_WIDTH)
    val nodesBlock = measureNodesBlock(groups, LEGEND_WIDTH)
    val headerBlock = 28 + ROW_PADDING + 6
    val legendMin = LEGEND_MARGIN + headerBlock + findingsBlock + nodesBlock + LEGEND_MARGIN
    val canvasHeight = max(drawnH, legendMin)

    val composite = BufferedImage(drawnW + LEGEND_WIDTH, canvasHeight, BufferedImage.TYPE_INT_ARGB)
    val g = composite.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
    g.setRenderingHint(
      RenderingHints.KEY_INTERPOLATION,
      RenderingHints.VALUE_INTERPOLATION_BILINEAR,
    )
    g.color = Color.WHITE
    g.fillRect(0, 0, composite.width, composite.height)

    val imageTopOffset = (canvasHeight - drawnH) / 2

    // Translucent pastel fills first so finding outlines would layer on top.
    drawNodeFills(g, nodes, nodeColors, scale, offsetX = 0f, offsetY = imageTopOffset.toFloat())
    g.drawImage(
      source,
      0,
      imageTopOffset,
      drawnW,
      imageTopOffset + drawnH,
      0,
      0,
      source.width,
      source.height,
      null,
    )
    // Re-draw fills over the bitmap + a thin border so each region reads as a region.
    drawNodeFills(g, nodes, nodeColors, scale, offsetX = 0f, offsetY = imageTopOffset.toFloat())
    drawNodeBorders(g, nodes, nodeColors, scale, offsetX = 0f, offsetY = imageTopOffset.toFloat())
    findings.forEachIndexed { i, f ->
      drawFindingBadge(g, i + 1, f, scale, offsetX = 0f, offsetY = imageTopOffset.toFloat())
    }

    val legendX = drawnW.toFloat()
    drawLegendBackground(g, legendX, 0f, LEGEND_WIDTH, canvasHeight)
    var y = LEGEND_MARGIN.toFloat()
    y = drawHeader(g, findings.size, nodes.size, legendX + LEGEND_MARGIN, y)
    y = drawFindingsRows(g, findings, legendX, y, LEGEND_WIDTH)
    drawNodeGroups(g, groups, legendX, y, LEGEND_WIDTH)

    g.dispose()
    return composite
  }

  private fun screenshotScale(source: BufferedImage): Float {
    if (source.width >= MIN_SCREENSHOT_DIM || source.height >= MIN_SCREENSHOT_DIM) return 1f
    return MIN_SCREENSHOT_DIM.toFloat() / max(source.width, source.height)
  }

  // ---------- screenshot layer ----------

  private fun drawNodeFills(
    g: Graphics2D,
    nodes: List<AccessibilityNode>,
    nodeColors: IntArray,
    scale: Float,
    offsetX: Float,
    offsetY: Float,
  ) {
    nodes.forEachIndexed { i, node ->
      // Unmerged descendants are line-only (drawn by drawNodeBorders).
      if (!node.merged) return@forEachIndexed
      val r = parseBounds(node.boundsInScreen) ?: return@forEachIndexed
      g.color = withAlpha(nodeColors[i], NODE_FILL_ALPHA)
      g.fill(
        Rectangle2D.Float(
          offsetX + r.left * scale,
          offsetY + r.top * scale,
          (r.right - r.left) * scale,
          (r.bottom - r.top) * scale,
        )
      )
    }
  }

  private fun drawNodeBorders(
    g: Graphics2D,
    nodes: List<AccessibilityNode>,
    nodeColors: IntArray,
    scale: Float,
    offsetX: Float,
    offsetY: Float,
  ) {
    val solid = BasicStroke(1.5f)
    val dashed =
      BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 1f, UNMERGED_DASH_INTERVAL, 0f)
    nodes.forEachIndexed { i, node ->
      val r = parseBounds(node.boundsInScreen) ?: return@forEachIndexed
      g.stroke = if (node.merged) solid else dashed
      g.color = withAlpha(nodeColors[i], if (node.merged) 200 else 140)
      val x = offsetX + r.left * scale + 0.5f
      val yTop = offsetY + r.top * scale + 0.5f
      g.draw(
        Rectangle2D.Float(x, yTop, (r.right - r.left) * scale - 1f, (r.bottom - r.top) * scale - 1f)
      )
    }
    g.stroke = BasicStroke(1f)
  }

  private fun drawFindingBadge(
    g: Graphics2D,
    number: Int,
    finding: AccessibilityFinding,
    scale: Float,
    offsetX: Float,
    offsetY: Float,
  ) {
    val r = parseBounds(finding.boundsInScreen) ?: return
    val color = withAlpha(levelColor(finding.level), 255)
    g.stroke = BasicStroke(2f)
    g.color = withAlpha(levelColor(finding.level), 150)
    val inset = 1f
    g.draw(
      Rectangle2D.Float(
        offsetX + r.left * scale + inset,
        offsetY + r.top * scale + inset,
        (r.right - r.left) * scale - 2 * inset,
        (r.bottom - r.top) * scale - 2 * inset,
      )
    )
    g.stroke = BasicStroke(1f)
    val cx = offsetX + max(r.left * scale, BADGE_RADIUS)
    val cy = offsetY + max(r.top * scale, BADGE_RADIUS)
    g.color = color
    fillCircle(g, cx, cy, BADGE_RADIUS)
    g.color = Color.WHITE
    g.font = bold(24)
    drawCentredText(g, number.toString(), cx, cy)
  }

  // ---------- legend layer ----------

  private fun drawLegendBackground(g: Graphics2D, x: Float, y: Float, w: Int, h: Int) {
    g.color = rgbColor(0xFA, 0xFA, 0xFC)
    g.fill(Rectangle2D.Float(x, y, w.toFloat(), h.toFloat()))
    g.color = rgbColor(0xE3, 0xE3, 0xE8)
    g.stroke = BasicStroke(1f)
    g.draw(Line2D.Float(x, y, x, y + h))
  }

  private fun drawHeader(
    g: Graphics2D,
    findingCount: Int,
    nodeCount: Int,
    x: Float,
    y: Float,
  ): Float {
    g.color = Color.BLACK
    g.font = bold(28)
    val baseline = y + 28f
    val title = buildString {
      append("Accessibility")
      val parts = mutableListOf<String>()
      if (findingCount > 0) parts += "$findingCount finding${if (findingCount == 1) "" else "s"}"
      if (nodeCount > 0) parts += "$nodeCount element${if (nodeCount == 1) "" else "s"}"
      if (parts.isNotEmpty()) append(" · ").append(parts.joinToString(", "))
    }
    g.drawString(title, x, baseline)
    return baseline + ROW_PADDING + 6f
  }

  private fun drawFindingsRows(
    g: Graphics2D,
    findings: List<AccessibilityFinding>,
    originX: Float,
    top: Float,
    panelWidth: Int,
  ): Float {
    var y = top
    findings.forEachIndexed { i, f -> y = drawFindingRow(g, i + 1, f, originX, y, panelWidth) }
    return y
  }

  private fun drawFindingRow(
    g: Graphics2D,
    number: Int,
    finding: AccessibilityFinding,
    originX: Float,
    top: Float,
    panelWidth: Int,
  ): Float {
    val color = withAlpha(levelColor(finding.level), 255)
    val badgeX = originX + LEGEND_MARGIN + BADGE_RADIUS
    val badgeY = top + BADGE_RADIUS
    g.color = color
    fillCircle(g, badgeX, badgeY, BADGE_RADIUS)
    g.color = Color.WHITE
    g.font = bold(24)
    drawCentredText(g, number.toString(), badgeX, badgeY)

    g.color = Color.BLACK
    g.font = bold(22)
    val textX = badgeX + BADGE_RADIUS + 14f
    val titleY = top + 24f
    g.drawString("${finding.level} · ${finding.type}", textX, titleY)

    g.color = rgbColor(0x30, 0x30, 0x33)
    g.font = plain(20)
    val rightMargin = LEGEND_MARGIN
    val textWidth = (panelWidth - (textX - originX) - rightMargin).toInt()
    val lines = wrap(finding.message, g.getFontMetrics(plain(20)), textWidth)
    var lineY = titleY + 28f
    for (line in lines) {
      g.drawString(line, textX, lineY)
      lineY += 26f
    }
    return lineY + ROW_PADDING.toFloat()
  }

  private data class NodeGroup(
    val parent: AccessibilityNode,
    val parentColor: Int,
    val children: List<Pair<AccessibilityNode, Int>>,
  )

  /**
   * Walks the node list once, attaching each run of `merged=false` nodes to the most recent
   * `merged=true` node. Relies on the extractor's pre-order traversal putting parent before its
   * descendants — same contract Android's `allViews` ordering provides.
   */
  private fun groupNodes(nodes: List<AccessibilityNode>, colors: IntArray): List<NodeGroup> {
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
    g: Graphics2D,
    groups: List<NodeGroup>,
    originX: Float,
    top: Float,
    panelWidth: Int,
  ): Float {
    var y = top
    for (grp in groups) y = drawNodeGroup(g, grp, originX, y, panelWidth)
    return y
  }

  private fun drawNodeGroup(
    g: Graphics2D,
    group: NodeGroup,
    originX: Float,
    top: Float,
    panelWidth: Int,
  ): Float {
    val swatchX = originX + LEGEND_MARGIN
    val swatchY = top + 4f
    drawSwatch(g, swatchX, swatchY, SWATCH_SIDE, group.parentColor)

    val textX = swatchX + SWATCH_SIDE + 14f
    g.color = Color.BLACK
    g.font = bold(20)
    val labelFm = g.getFontMetrics(bold(20))
    val rightMargin = LEGEND_MARGIN
    val textWidth = (panelWidth - (textX - originX) - rightMargin).toInt()
    val baseLabel = group.parent.label.ifEmpty { group.parent.role ?: "(unlabelled)" }
    val labelText = if (group.parent.merged) baseLabel else "↳ $baseLabel"
    val labelLines = wrap(labelText, labelFm, textWidth)
    var y = top + 22f
    for (line in labelLines) {
      g.drawString(line, textX, y)
      y += 24f
    }

    val subtitleParts = buildList {
      group.parent.role?.let { add(it) }
      addAll(group.parent.states)
    }
    if (subtitleParts.isNotEmpty()) {
      g.color = rgbColor(0x60, 0x60, 0x66)
      g.font = plain(17)
      g.drawString(subtitleParts.joinToString(" · "), textX, y)
      y += 20f
    }

    if (group.children.isNotEmpty()) {
      y += 4f
      y = drawInlineChildren(g, group.children, textX, y, textWidth)
    }
    return y + ROW_PADDING.toFloat()
  }

  private fun drawInlineChildren(
    g: Graphics2D,
    children: List<Pair<AccessibilityNode, Int>>,
    leftX: Float,
    top: Float,
    maxWidth: Int,
  ): Float {
    val labelFont = plain(18)
    val labelFm = g.getFontMetrics(labelFont)
    val sepText = "·"
    val sepWidth = labelFm.stringWidth(sepText).toFloat()
    val sepGap = 8f
    val miniGap = 6f
    val lineHeight = 26f
    val maxX = leftX + maxWidth

    var x = leftX
    var y = top + 16f
    for ((idx, pair) in children.withIndex()) {
      val (child, color) = pair
      val rawLabel = child.label.ifEmpty { child.role ?: "(unlabelled)" }
      val labelWidth = labelFm.stringWidth(rawLabel).toFloat()
      val itemWidth = MINI_SWATCH_SIDE + miniGap + labelWidth
      val sepNeeded = idx > 0
      val advance = if (sepNeeded) sepGap + sepWidth + sepGap + itemWidth else itemWidth
      if (sepNeeded && x + advance > maxX) {
        x = leftX
        y += lineHeight
      } else if (sepNeeded) {
        x += sepGap
        g.color = rgbColor(0x80, 0x80, 0x88)
        g.font = labelFont
        g.drawString(sepText, x, y)
        x += sepWidth + sepGap
      }
      val miniTop = y - MINI_SWATCH_SIDE + 4f
      drawSwatch(g, x, miniTop, MINI_SWATCH_SIDE, color)
      x += MINI_SWATCH_SIDE + miniGap
      g.color = rgbColor(0x20, 0x20, 0x24)
      g.font = labelFont
      val drawn =
        if (x + labelWidth <= maxX) {
          g.drawString(rawLabel, x, y)
          labelWidth
        } else {
          val fitted = ellipsize(rawLabel, labelFm, (maxX - x).toInt())
          g.drawString(fitted, x, y)
          labelFm.stringWidth(fitted).toFloat()
        }
      x += drawn
    }
    return y + 4f
  }

  private fun drawSwatch(g: Graphics2D, x: Float, y: Float, side: Float, color: Int) {
    g.color = withAlpha(color, 255)
    g.fill(RoundRectangle2D.Float(x, y, side, side, 6f, 6f))
    g.color = withAlpha(rgb(0x60, 0x60, 0x66), 120)
    g.stroke = BasicStroke(1f)
    g.draw(RoundRectangle2D.Float(x, y, side, side, 6f, 6f))
  }

  // ---------- measurement ----------

  private fun measureFindingsBlock(findings: List<AccessibilityFinding>, panelWidth: Int): Int {
    if (findings.isEmpty()) return 0
    val fm = sharedFontMetrics(plain(20))
    val textWidth = panelWidth - LEGEND_MARGIN * 2 - (BADGE_RADIUS * 2 + 14f).toInt()
    var total = 0
    for (f in findings) {
      val lines = wrap(f.message, fm, textWidth).size.coerceAtLeast(1)
      total += 24 + 28 + 26 * lines + ROW_PADDING
    }
    return total
  }

  private fun measureNodesBlock(groups: List<NodeGroup>, panelWidth: Int): Int {
    if (groups.isEmpty()) return 0
    val labelFm = sharedFontMetrics(plain(20))
    val childFm = sharedFontMetrics(plain(18))
    val textWidth = panelWidth - LEGEND_MARGIN * 2 - (SWATCH_SIDE + 14f).toInt()
    var total = 0
    for (grp in groups) {
      val baseLabel = grp.parent.label.ifEmpty { grp.parent.role ?: "(unlabelled)" }
      val labelText = if (grp.parent.merged) baseLabel else "↳ $baseLabel"
      val labelLines = wrap(labelText, labelFm, textWidth).size.coerceAtLeast(1)
      val hasSubtitle = grp.parent.role != null || grp.parent.states.isNotEmpty()
      var rowHeight = 22 + 24 * labelLines + (if (hasSubtitle) 20 else 0)
      if (grp.children.isNotEmpty()) {
        rowHeight += 4 + measureChildLines(grp.children, childFm, textWidth) * 26 + 4
      }
      total += rowHeight + ROW_PADDING
    }
    return total
  }

  private fun measureChildLines(
    children: List<Pair<AccessibilityNode, Int>>,
    labelFm: FontMetrics,
    maxWidth: Int,
  ): Int {
    val sepGap = 8f
    val sepWidth = labelFm.stringWidth("·").toFloat()
    val miniGap = 6f
    var x = 0f
    var lines = 1
    for ((idx, pair) in children.withIndex()) {
      val rawLabel = pair.first.label.ifEmpty { pair.first.role ?: "(unlabelled)" }
      val itemWidth = MINI_SWATCH_SIDE + miniGap + labelFm.stringWidth(rawLabel)
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

  private data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int)

  private fun levelColor(level: String): Int =
    when (level) {
      "ERROR" -> rgb(0xD3, 0x2F, 0x2F)
      "WARNING" -> rgb(0xF5, 0x7C, 0x00)
      "INFO" -> rgb(0x19, 0x76, 0xD2)
      else -> rgb(0x75, 0x75, 0x75)
    }

  private fun parseBounds(s: String?): Bounds? {
    if (s == null) return null
    val parts = s.split(",").mapNotNull { it.trim().toIntOrNull() }
    if (parts.size != 4) return null
    return Bounds(parts[0], parts[1], parts[2], parts[3])
  }

  /** Greedy word-wrap fitting [text] into [maxWidth] px using [fm]'s metrics. */
  private fun wrap(text: String, fm: FontMetrics, maxWidth: Int): List<String> {
    if (text.isEmpty()) return listOf("")
    val words = text.split(' ')
    val out = mutableListOf<String>()
    var current = StringBuilder()
    for (word in words) {
      val candidate = if (current.isEmpty()) word else "$current $word"
      if (fm.stringWidth(candidate) <= maxWidth) {
        current = StringBuilder(candidate)
      } else {
        if (current.isNotEmpty()) out.add(current.toString())
        current = StringBuilder(word)
      }
    }
    if (current.isNotEmpty()) out.add(current.toString())
    return out
  }

  private fun ellipsize(text: String, fm: FontMetrics, maxWidth: Int): String {
    val ellipsis = "…"
    if (fm.stringWidth(text) <= maxWidth) return text
    val ellipsisWidth = fm.stringWidth(ellipsis)
    if (ellipsisWidth >= maxWidth) return ""
    var end = text.length
    while (end > 0 && fm.stringWidth(text.substring(0, end)) + ellipsisWidth > maxWidth) end--
    return text.substring(0, end) + ellipsis
  }

  /**
   * Draws [text] horizontally centred on [cx] and vertically centred on [cy]. AWT
   * [FontMetrics.getAscent] is positive (the opposite sign of android's `fontMetrics.ascent`), so
   * the baseline offset for vertical centring is `cy + (ascent - descent) / 2`.
   */
  private fun drawCentredText(g: Graphics2D, text: String, cx: Float, cy: Float) {
    val fm = g.fontMetrics
    val tx = cx - fm.stringWidth(text) / 2f
    val ty = cy + (fm.ascent - fm.descent) / 2f
    g.drawString(text, tx, ty)
  }

  private fun fillCircle(g: Graphics2D, cx: Float, cy: Float, radius: Float) {
    g.fill(Ellipse2D.Float(cx - radius, cy - radius, radius * 2, radius * 2))
  }

  private fun bold(size: Int): Font = Font(Font.SANS_SERIF, Font.BOLD, size)

  private fun plain(size: Int): Font = Font(Font.SANS_SERIF, Font.PLAIN, size)

  // Offscreen graphics for measurement-only FontMetrics (no Graphics2D in scope yet).
  private val measureImage = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)

  private fun sharedFontMetrics(font: Font): FontMetrics {
    val g = measureImage.createGraphics()
    return try {
      g.getFontMetrics(font)
    } finally {
      g.dispose()
    }
  }

  private fun rgb(r: Int, gr: Int, b: Int): Int = (0xFF shl 24) or (r shl 16) or (gr shl 8) or b

  private fun rgbColor(r: Int, gr: Int, b: Int): Color = Color(r, gr, b)

  private fun withAlpha(argb: Int, alpha: Int): Color =
    Color((argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF, alpha)
}
