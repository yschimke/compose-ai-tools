package ee.schimke.composeai.daemon

import ee.schimke.composeai.cli.AccessibilityNode
import ee.schimke.composeai.cli.TalkBackTraversal
import ee.schimke.composeai.cli.TalkBackUtterance
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.math.max
import kotlin.math.min

/**
 * Desktop (AWT / `Graphics2D`) twin of the Android `TalkBackFocusOverlay` (issue #1956). It draws
 * the identical TalkBack focus visualization — a green focus rectangle around the focused stop,
 * faint traversal-order badges on every stop, and a caption card with the composed announcement —
 * but onto a `BufferedImage` via `java.awt.Graphics2D`, the way [DesktopAccessibilityOverlay] and
 * [DesktopSemanticsWireframe] composite onto captured frames (the Compose-Multiplatform desktop
 * recording path has no `android.graphics`).
 *
 * The shared, backend-agnostic logic lives in `:data-a11y-core`: [TalkBackTraversal.focusStops]
 * picks the stops, [TalkBackUtterance.compose] writes the caption. Only the drawing is duplicated
 * per graphics stack, so the desktop and Android overlays stay visually in step by construction.
 *
 * Operates on the natural-size frame (node `boundsInScreen` are in source-bitmap pixels), so
 * callers composite **before** any scale-down.
 */
object DesktopTalkBackFocusOverlay {

  // Visual constants mirror the Android renderer so the two backends look the same.
  private val FOCUS_GREEN = Color(0x00, 0xC8, 0x53)
  private const val FOCUS_STROKE_PX = 6f
  private const val FOCUS_INSET_PX = 3f
  private const val NUMBER_RADIUS_PX = 18
  private const val NUMBER_TEXT_PX = 22
  private const val CAPTION_MARGIN_PX = 16
  private const val CAPTION_PADDING_PX = 18
  private const val CAPTION_TEXT_PX = 28
  private const val CAPTION_LINE_PX = 36
  private const val CAPTION_CORNER_PX = 16f
  private val CAPTION_BG = Color(0x10, 0x10, 0x12, 0xE6)
  private val NUMBER_BG_IDLE = Color(0x33, 0x33, 0x38, 0xB0)

  /**
   * Composites the focus overlay for [focusedStop] onto the PNG-encoded frame [pngBytes], returning
   * re-encoded PNG bytes. Returns `null` (caller should write the frame untouched) when [nodes] has
   * no focus stops or the bytes can't be decoded. Out-of-range [focusedStop] is clamped.
   */
  fun overlayPngBytes(
    pngBytes: ByteArray,
    nodes: List<AccessibilityNode>,
    focusedStop: Int,
  ): ByteArray? {
    val stops = TalkBackTraversal.focusStops(nodes)
    if (stops.isEmpty()) return null
    val source =
      try {
        ImageIO.read(ByteArrayInputStream(pngBytes))
      } catch (t: Throwable) {
        System.err.println(
          "[compose-a11y] desktop talkback overlay decode failed: ${t.javaClass.simpleName}: ${t.message}"
        )
        null
      } ?: return null
    val composite = compose(source, stops, focusedStop.coerceIn(0, stops.size - 1))
    return ByteArrayOutputStream().use { out ->
      ImageIO.write(composite, "png", out)
      out.toByteArray()
    }
  }

  /**
   * Pure AWT compositing — exposed for unit tests. [stops] are the focus stops; [focusedStop]
   * indexes them.
   */
  internal fun compose(
    source: BufferedImage,
    stops: List<AccessibilityNode>,
    focusedStop: Int,
  ): BufferedImage {
    val out = BufferedImage(source.width, source.height, BufferedImage.TYPE_INT_ARGB)
    val g = out.createGraphics()
    try {
      g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
      g.setRenderingHint(
        RenderingHints.KEY_TEXT_ANTIALIASING,
        RenderingHints.VALUE_TEXT_ANTIALIAS_ON,
      )
      g.drawImage(source, 0, 0, null)
      drawTraversalNumbers(g, stops, focusedStop)
      drawFocusRect(g, stops[focusedStop])
      drawCaption(g, out.width, out.height, TalkBackUtterance.compose(stops[focusedStop]))
    } finally {
      g.dispose()
    }
    return out
  }

  private fun drawFocusRect(g: Graphics2D, node: AccessibilityNode) {
    val r = parseBounds(node.boundsInScreen) ?: return
    g.color = FOCUS_GREEN
    g.stroke = BasicStroke(FOCUS_STROKE_PX)
    val o = (FOCUS_INSET_PX + FOCUS_STROKE_PX / 2f)
    val x = r[0] - o
    val y = r[1] - o
    val w = (r[2] - r[0]) + 2 * o
    val h = (r[3] - r[1]) + 2 * o
    g.draw(java.awt.geom.Rectangle2D.Float(x, y, w, h))
  }

  private fun drawTraversalNumbers(
    g: Graphics2D,
    stops: List<AccessibilityNode>,
    focusedStop: Int,
  ) {
    g.font = Font(Font.SANS_SERIF, Font.BOLD, NUMBER_TEXT_PX)
    val fm = g.fontMetrics
    stops.forEachIndexed { i, node ->
      val r = parseBounds(node.boundsInScreen) ?: return@forEachIndexed
      val cx = max(r[0], NUMBER_RADIUS_PX.toFloat())
      val cy = max(r[1], NUMBER_RADIUS_PX.toFloat())
      g.color = if (i == focusedStop) FOCUS_GREEN else NUMBER_BG_IDLE
      g.fillOval(
        (cx - NUMBER_RADIUS_PX).toInt(),
        (cy - NUMBER_RADIUS_PX).toInt(),
        NUMBER_RADIUS_PX * 2,
        NUMBER_RADIUS_PX * 2,
      )
      g.color = Color.WHITE
      val label = (i + 1).toString()
      g.drawString(label, cx - fm.stringWidth(label) / 2f, cy + (fm.ascent - fm.descent) / 2f)
    }
  }

  private fun drawCaption(g: Graphics2D, width: Int, height: Int, utterance: String) {
    if (utterance.isEmpty()) return
    g.font = Font(Font.SANS_SERIF, Font.PLAIN, CAPTION_TEXT_PX)
    val fm = g.fontMetrics
    val maxTextWidth = width - 2 * CAPTION_MARGIN_PX - 2 * CAPTION_PADDING_PX
    val lines = wrap(utterance, fm, maxTextWidth)
    val cardHeight = 2 * CAPTION_PADDING_PX + lines.size * CAPTION_LINE_PX
    val cardTop = height - CAPTION_MARGIN_PX - cardHeight
    g.color = CAPTION_BG
    g.fill(
      RoundRectangle2D.Float(
        CAPTION_MARGIN_PX.toFloat(),
        cardTop.toFloat(),
        (width - 2 * CAPTION_MARGIN_PX).toFloat(),
        cardHeight.toFloat(),
        CAPTION_CORNER_PX,
        CAPTION_CORNER_PX,
      )
    )
    // Green accent bar down the left edge, tying the caption to the focus rectangle's colour.
    g.color = FOCUS_GREEN
    g.fill(
      RoundRectangle2D.Float(
        CAPTION_MARGIN_PX.toFloat(),
        cardTop.toFloat(),
        6f,
        cardHeight.toFloat(),
        CAPTION_CORNER_PX,
        CAPTION_CORNER_PX,
      )
    )
    g.color = Color.WHITE
    var baseline = cardTop + CAPTION_PADDING_PX + CAPTION_TEXT_PX
    val textX = (CAPTION_MARGIN_PX + CAPTION_PADDING_PX).toFloat()
    for (line in lines) {
      g.drawString(line, textX, baseline.toFloat())
      baseline += CAPTION_LINE_PX
    }
  }

  /** `l,t,r,b` integer pixels → `[left, top, right, bottom]` floats, normalised. */
  private fun parseBounds(s: String?): FloatArray? {
    if (s == null) return null
    val p = s.split(",").mapNotNull { it.trim().toFloatOrNull() }
    if (p.size != 4) return null
    return floatArrayOf(min(p[0], p[2]), min(p[1], p[3]), max(p[0], p[2]), max(p[1], p[3]))
  }

  private fun wrap(text: String, fm: java.awt.FontMetrics, maxWidth: Int): List<String> {
    if (text.isEmpty()) return emptyList()
    val out = mutableListOf<String>()
    var current = StringBuilder()
    for (word in text.split(' ')) {
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
}
