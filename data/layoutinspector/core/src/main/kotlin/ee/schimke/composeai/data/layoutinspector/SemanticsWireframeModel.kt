package ee.schimke.composeai.data.layoutinspector

/**
 * Backend-agnostic geometry + style for the semantics wireframe — the single source of truth shared
 * by the SVG renderer ([SemanticsWireframeSvg]) and the per-backend raster bakers (Android
 * `android.graphics`, Desktop AWT). The model is pure data in root-pixel space; each renderer
 * applies the same translate ([WireframeModel.tx] / [WireframeModel.ty]) and reads the same colours
 * from [WireframeStyle], so the SVG and the PNG agree pixel-for-pixel on layout and encoding.
 */
data class WireframeBox(
  val left: Int,
  val top: Int,
  val right: Int,
  val bottom: Int,
  /** Nesting level from the semantics root; drives the depth-cycled stroke hue. */
  val depth: Int,
  val clickable: Boolean,
  val clearAndSet: Boolean,
  val label: String?,
) {
  val width: Int
    get() = (right - left).coerceAtLeast(0)

  val height: Int
    get() = (bottom - top).coerceAtLeast(0)
}

/**
 * The full diagram: every parseable box (pre-order) plus the padded extent. [boxes] is empty for a
 * tree with no parseable bounds — renderers emit a minimal [width]×[height] ground in that case.
 */
data class WireframeModel(
  val boxes: List<WireframeBox>,
  /** Min corner of the union of all boxes (0,0 when empty). */
  val minX: Int,
  val minY: Int,
  /** Canvas size = union extent + 2·padding (a minimal square when empty). */
  val width: Int,
  val height: Int,
  val padding: Int,
) {
  /** Translate from root-pixel space into the padded canvas. */
  val tx: Int
    get() = padding - minX

  val ty: Int
    get() = padding - minY

  companion object {
    fun from(payload: ComposeSemanticsPayload, padding: Int): WireframeModel {
      val boxes = mutableListOf<WireframeBox>()
      collect(payload.root, depth = 0, into = boxes)
      if (boxes.isEmpty()) {
        val side = padding * 2
        return WireframeModel(emptyList(), 0, 0, side, side, padding)
      }
      val minX = boxes.minOf { it.left }
      val minY = boxes.minOf { it.top }
      val maxX = boxes.maxOf { it.right }
      val maxY = boxes.maxOf { it.bottom }
      return WireframeModel(
        boxes = boxes,
        minX = minX,
        minY = minY,
        width = (maxX - minX) + padding * 2,
        height = (maxY - minY) + padding * 2,
        padding = padding,
      )
    }

    private fun collect(node: ComposeSemanticsNode, depth: Int, into: MutableList<WireframeBox>) {
      val bounds = parseBounds(node.boundsInRoot)
      if (bounds != null) {
        into.add(
          WireframeBox(
            left = bounds[0],
            top = bounds[1],
            right = bounds[2],
            bottom = bounds[3],
            depth = depth,
            clickable = node.clickable,
            clearAndSet = node.mergeMode == "clearAndSet",
            label = node.bestLabel(),
          )
        )
      }
      // Recurse even when this node's own bounds are unparseable — children carry their own
      // absolute boundsInRoot, so a degenerate container shouldn't drop its subtree.
      for (child in node.children) collect(child, depth + 1, into)
    }

    private fun ComposeSemanticsNode.bestLabel(): String? =
      label?.takeIf { it.isNotBlank() }
        ?: role?.takeIf { it.isNotBlank() }
        ?: testTag?.takeIf { it.isNotBlank() }
        ?: text?.takeIf { it.isNotBlank() }

    /** Parses `"left,top,right,bottom"` into four ints, or null if malformed. */
    private fun parseBounds(s: String?): IntArray? {
      if (s == null) return null
      val parts = s.split(",")
      if (parts.size != 4) return null
      val ints = parts.map { it.trim().toIntOrNull() ?: return null }
      return intArrayOf(ints[0], ints[1], ints[2], ints[3])
    }
  }
}

/**
 * The shared visual language — colours (as `0xRRGGBB`), stroke widths, and label fitting — that
 * both the SVG and raster renderers read so they stay identical. Kept toolkit-free (no
 * `android.graphics`, no `java.awt`) so it lives on the render-subprocess-safe core classpath.
 */
object WireframeStyle {
  /** Muted, high-contrast-on-white stroke palette cycled by nesting depth. */
  val depthStrokes =
    intArrayOf(0x5B6470, 0x2E7D6B, 0x8E6BA8, 0xB0813B, 0x3B72A8, 0xA85B6B, 0x4F8A4A, 0x7A7A33)

  /** Accent for clickable (actionable) nodes — fill + stroke. */
  const val clickAccent: Int = 0x1976D2

  /** Fill opacity (0..1) painted inside clickable boxes. */
  const val clickFillOpacity: Double = 0.08

  /** White ground so the wireframe reads the same on a dark webview / terminal. */
  const val ground: Int = 0xFFFFFF

  /** Default label font size (px). */
  const val fontSize: Int = 11

  /** Dotted/dashed stroke for `clearAndSet` boxes: on/off run lengths (px). */
  val clearAndSetDash = floatArrayOf(4f, 3f)

  fun strokeColor(box: WireframeBox): Int =
    if (box.clickable) clickAccent else depthStrokes[box.depth % depthStrokes.size]

  fun strokeWidth(box: WireframeBox): Int = if (box.clickable) 2 else 1

  /** `0xRRGGBB` → `#RRGGBB`. */
  fun hex(rgb: Int): String = "#%06X".format(rgb)

  fun red(rgb: Int): Int = (rgb shr 16) and 0xFF

  fun green(rgb: Int): Int = (rgb shr 8) and 0xFF

  fun blue(rgb: Int): Int = rgb and 0xFF

  /**
   * Truncates [text] with a trailing `…` so the rendered string fits in [maxWidthPx], estimating
   * glyph advance as 0.6·[fontSize] (a reasonable mean for sans-serif). Approximate by design — the
   * wireframe is schematic, and over-/under-fitting by a glyph is invisible at this scale. The
   * raster bakers measure precisely with their own `FontMetrics`/`Paint`, but share this for parity
   * of intent.
   */
  fun truncateToWidth(text: String, maxWidthPx: Int, fontSize: Int = this.fontSize): String {
    if (maxWidthPx <= 0) return ""
    val charWidth = fontSize * 0.6
    val maxChars = (maxWidthPx / charWidth).toInt()
    if (maxChars <= 0) return ""
    if (text.length <= maxChars) return text
    if (maxChars == 1) return "…"
    return text.take(maxChars - 1) + "…"
  }
}
