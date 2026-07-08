package ee.schimke.composeai.renderer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * Renders the TalkBack focus visualization for one frame — issue #1956, Phase 1.
 *
 * Where [AccessibilityOverlay] paints a *static* Paparazzi-style legend of every node, this draws
 * what TalkBack *does*: a single green focus rectangle around the node TalkBack is currently
 * stopped on, the spoken announcement for that node in a caption card (so a silent capture still
 * conveys "what TalkBack says"), and faint traversal-order numbers on every focus stop so the path
 * through the screen reads at a glance.
 *
 * It composites onto the source screenshot at its native size (no side panel) so the output frame
 * is the same dimensions as the input — drop one of these per captured frame, advance [focusedStop]
 * as the walk progresses ([TalkBackOverlayFrames] maps frame index → stop), and the APNG / MP4 /
 * GIF encoder turns the sequence into an animated focus walk for free. The focus stops are the
 * merged nodes ([TalkBackTraversal.focusStops]); the caption text is [TalkBackUtterance.compose] of
 * the focused stop, so the rectangle, the number, and the words always agree.
 *
 * Android-only (uses `Bitmap` / `Canvas`), same as [AccessibilityOverlay]. A desktop Skia variant
 * could mirror it the day live mode lands there.
 */
object TalkBackFocusOverlay {

  /** TalkBack's focus rectangle is a thick green box; this matches its recognisable look. */
  private val FOCUS_GREEN: Int = Color.rgb(0x00, 0xC8, 0x53)
  private const val FOCUS_STROKE_PX: Float = 6f

  /** Inset so the stroke sits just outside the node bounds rather than biting into the control. */
  private const val FOCUS_INSET_PX: Float = 3f

  /** Traversal-order badge sizing for the un-focused stops. */
  private const val NUMBER_RADIUS_PX: Float = 18f
  private const val NUMBER_TEXT_PX: Float = 22f

  /** Caption card chrome. */
  private const val CAPTION_MARGIN_PX: Float = 16f
  private const val CAPTION_PADDING_PX: Float = 18f
  private const val CAPTION_TEXT_PX: Float = 28f
  private const val CAPTION_LINE_PX: Float = 36f
  private const val CAPTION_CORNER_PX: Float = 16f
  private val CAPTION_BG: Int = Color.argb(0xE6, 0x10, 0x10, 0x12)
  private val CAPTION_TEXT: Int = Color.WHITE
  private val CAPTION_ACCENT: Int = FOCUS_GREEN

  /**
   * Writes the focus-overlay frame for the stop at [focusedStop] (an index into the focus-stop
   * sub-list of [nodes]) to [destPng]. No-op (returns `null`) when there are no focus stops or the
   * source can't be decoded. Out-of-range [focusedStop] is clamped into range.
   */
  fun generate(
    sourcePng: File,
    nodes: List<AccessibilityNode>,
    focusedStop: Int,
    destPng: File,
  ): File? {
    val stops = TalkBackTraversal.focusStops(nodes)
    if (stops.isEmpty()) return null
    if (!sourcePng.exists()) {
      System.err.println(
        "[compose-a11y] talkback overlay skipped: source PNG missing at ${sourcePng.absolutePath}"
      )
      return null
    }
    return try {
      val source = BitmapFactory.decodeFile(sourcePng.absolutePath) ?: return null
      val composite = compose(source, stops, focusedStop.coerceIn(0, stops.size - 1))
      destPng.parentFile?.mkdirs()
      destPng.outputStream().use { composite.compress(Bitmap.CompressFormat.PNG, 100, it) }
      source.recycle()
      composite.recycle()
      destPng
    } catch (t: Throwable) {
      System.err.println(
        "[compose-a11y] talkback overlay failed for ${sourcePng.name}: " +
          "${t.javaClass.simpleName}: ${t.message}"
      )
      t.printStackTrace(System.err)
      null
    }
  }

  /** In-memory variant for tests / live drawing — returns the composited bitmap. */
  fun compose(source: Bitmap, stops: List<AccessibilityNode>, focusedStop: Int): Bitmap {
    val out = source.copy(Bitmap.Config.ARGB_8888, /* isMutable= */ true)
    val canvas = Canvas(out)
    drawTraversalNumbers(canvas, stops, focusedStop)
    drawFocusRect(canvas, stops[focusedStop])
    drawCaption(canvas, out.width, out.height, TalkBackUtterance.compose(stops[focusedStop]))
    return out
  }

  private fun drawFocusRect(canvas: Canvas, node: AccessibilityNode) {
    val r = parseBounds(node.boundsInScreen) ?: return
    val stroke =
      Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = FOCUS_STROKE_PX
        color = FOCUS_GREEN
      }
    val o = FOCUS_INSET_PX + FOCUS_STROKE_PX / 2f
    canvas.drawRect(r.left - o, r.top - o, r.right + o, r.bottom + o, stroke)
  }

  private fun drawTraversalNumbers(
    canvas: Canvas,
    stops: List<AccessibilityNode>,
    focusedStop: Int,
  ) {
    val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    val text =
      Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = NUMBER_TEXT_PX
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
      }
    val fm = text.fontMetrics
    stops.forEachIndexed { i, node ->
      val r = parseBounds(node.boundsInScreen) ?: return@forEachIndexed
      // Badge at the node's top-left corner, nudged inside the frame so edge nodes stay visible.
      val cx = r.left.coerceAtLeast(NUMBER_RADIUS_PX)
      val cy = r.top.coerceAtLeast(NUMBER_RADIUS_PX)
      // The focused stop gets the bright green badge; the rest are translucent grey so the path is
      // legible without competing with the focus rectangle.
      bg.color = if (i == focusedStop) FOCUS_GREEN else Color.argb(0xB0, 0x33, 0x33, 0x38)
      canvas.drawCircle(cx, cy, NUMBER_RADIUS_PX, bg)
      canvas.drawText((i + 1).toString(), cx, cy - (fm.ascent + fm.descent) / 2f, text)
    }
  }

  private fun drawCaption(canvas: Canvas, width: Int, height: Int, utterance: String) {
    if (utterance.isEmpty()) return
    val text =
      Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = CAPTION_TEXT
        textSize = CAPTION_TEXT_PX
      }
    val maxTextWidth = (width - 2 * CAPTION_MARGIN_PX - 2 * CAPTION_PADDING_PX).toInt()
    val lines = wrap(utterance, text, maxTextWidth)
    val cardHeight = 2 * CAPTION_PADDING_PX + lines.size * CAPTION_LINE_PX
    val cardTop = height - CAPTION_MARGIN_PX - cardHeight
    val cardRect =
      RectF(CAPTION_MARGIN_PX, cardTop, width - CAPTION_MARGIN_PX, height - CAPTION_MARGIN_PX)
    val bg =
      Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = CAPTION_BG
      }
    canvas.drawRoundRect(cardRect, CAPTION_CORNER_PX, CAPTION_CORNER_PX, bg)
    // A green accent bar down the left edge ties the caption to the focus rectangle's colour.
    val accent =
      Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = CAPTION_ACCENT
      }
    canvas.drawRoundRect(
      RectF(cardRect.left, cardRect.top, cardRect.left + 6f, cardRect.bottom),
      CAPTION_CORNER_PX,
      CAPTION_CORNER_PX,
      accent,
    )
    var baseline = cardTop + CAPTION_PADDING_PX + CAPTION_TEXT_PX
    val textX = CAPTION_MARGIN_PX + CAPTION_PADDING_PX
    for (line in lines) {
      canvas.drawText(line, textX, baseline, text)
      baseline += CAPTION_LINE_PX
    }
  }

  private fun parseBounds(s: String?): RectF? {
    if (s == null) return null
    val parts = s.split(",").mapNotNull { it.trim().toFloatOrNull() }
    if (parts.size != 4) return null
    return RectF(
      min(parts[0], parts[2]),
      min(parts[1], parts[3]),
      max(parts[0], parts[2]),
      max(parts[1], parts[3]),
    )
  }

  /** Greedy word-wrap fitting [text] into [maxWidth] px using [paint]'s metrics. */
  private fun wrap(text: String, paint: Paint, maxWidth: Int): List<String> {
    if (text.isEmpty()) return emptyList()
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
