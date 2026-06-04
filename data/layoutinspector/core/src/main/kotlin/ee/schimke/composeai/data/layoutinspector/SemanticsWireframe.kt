package ee.schimke.composeai.data.layoutinspector

/**
 * Bakes a [ComposeSemanticsPayload] into a standalone **2D wireframe SVG** — the schematic an
 * agent, MCP client, CLI report, or PR diff can consume as a single self-contained artifact,
 * without the interactive VS Code box-overlay and without the a11y/ATF data the
 * `AccessibilityOverlay` PNG requires.
 *
 * Visual language (deliberately a *skeleton*, not a screenshot composite):
 * - Every semantic node with parseable [ComposeSemanticsNode.boundsInRoot] becomes a stroked
 *   `<rect>` in the shared root-pixel coordinate space (bounds are already absolute-to-root, so no
 *   transform accumulation is needed — only a single translate to the padded viewBox origin).
 * - **Depth reads through stroke hue**: nesting level cycles a muted palette so a deep tree stays
 *   legible without fills fighting each other. Children are emitted after parents (pre-order), so
 *   they layer on top.
 * - **Clickable nodes are the actionable stops** — they get a translucent accent fill + a thicker
 *   accent stroke, the same "this is where focus lands" cue the a11y overlay paints louder.
 * - **Merge mode** distinguishes structure: `mergeDescendants` keeps the solid stroke;
 *   `clearAndSet` switches to a dashed stroke (its descendants' semantics are replaced, so the box
 *   is a semantic boundary, not a container you read into).
 * - Each box is labelled top-left with its [ComposeSemanticsNode.label] ?: role ?: testTag ?: text,
 *   truncated to the box width so labels never spill past their region.
 *
 * Pure and deterministic: input model in, SVG string out, no Android `Canvas`/`Bitmap`, no IO. That
 * keeps it on the render-subprocess-safe core classpath, unit-testable without Robolectric, and
 * reusable by the later 2.5D/3D box view (which reuses the same box extraction with depth as Z).
 */
object SemanticsWireframeSvg {

  /** Tunables for the bake; defaults are chosen to read at a glance on a phone-sized root. */
  data class Options(
    /** Transparent margin (px) around the diagram extent. */
    val padding: Int = 16,
    /** Draw the top-left label on each box. */
    val showLabels: Boolean = true,
    /** Label font size (px). */
    val fontSize: Int = 11,
  )

  /** Muted, high-contrast-on-white stroke palette cycled by nesting depth. */
  private val DEPTH_STROKES =
    listOf("#5B6470", "#2E7D6B", "#8E6BA8", "#B0813B", "#3B72A8", "#A85B6B", "#4F8A4A", "#7A7A33")

  /** Accent for clickable (actionable) nodes — fill + stroke. */
  private const val CLICK_STROKE = "#1976D2"
  private const val CLICK_FILL = "#1976D2"

  /** Writes the wireframe SVG for [payload]. */
  fun render(payload: ComposeSemanticsPayload, options: Options = Options()): String {
    val boxes = mutableListOf<Box>()
    collect(payload.root, depth = 0, into = boxes)

    // Diagram extent = union of every parseable box (the merged root sometimes reports (0,0,0,0),
    // so trusting root bounds alone would clip the whole tree). Empty tree → a minimal valid SVG.
    if (boxes.isEmpty()) {
      return emptySvg(options)
    }
    val minX = boxes.minOf { it.left }
    val minY = boxes.minOf { it.top }
    val maxX = boxes.maxOf { it.right }
    val maxY = boxes.maxOf { it.bottom }
    val tx = options.padding - minX
    val ty = options.padding - minY
    val width = (maxX - minX) + options.padding * 2
    val height = (maxY - minY) + options.padding * 2

    val sb = StringBuilder()
    sb.append(
      """<svg xmlns="http://www.w3.org/2000/svg" width="$width" height="$height" """ +
        """viewBox="0 0 $width $height" font-family="sans-serif">"""
    )
    sb.append("\n")
    // White ground so the wireframe reads the same in a dark webview / terminal preview.
    sb.append("""<rect x="0" y="0" width="$width" height="$height" fill="#FFFFFF"/>""")
    sb.append("\n")

    for (box in boxes) {
      val x = box.left + tx
      val y = box.top + ty
      val w = (box.right - box.left).coerceAtLeast(0)
      val h = (box.bottom - box.top).coerceAtLeast(0)
      val stroke =
        if (box.clickable) CLICK_STROKE else DEPTH_STROKES[box.depth % DEPTH_STROKES.size]
      val strokeWidth = if (box.clickable) 2 else 1
      val dash = if (box.clearAndSet) """ stroke-dasharray="4 3"""" else ""
      val fill =
        if (box.clickable) """ fill="$CLICK_FILL" fill-opacity="0.08"""" else """ fill="none""""
      sb.append(
        """<rect x="$x" y="$y" width="$w" height="$h"$fill stroke="$stroke" """ +
          """stroke-width="$strokeWidth"$dash/>"""
      )
      sb.append("\n")

      if (options.showLabels) {
        val label = box.label
        if (label != null && w > options.fontSize) {
          val text = truncateToWidth(label, w - 4, options.fontSize)
          if (text.isNotEmpty()) {
            val ty2 = y + options.fontSize + 1
            sb.append(
              """<text x="${x + 2}" y="$ty2" font-size="${options.fontSize}" """ +
                """fill="$stroke">${escape(text)}</text>"""
            )
            sb.append("\n")
          }
        }
      }
    }
    sb.append("</svg>")
    sb.append("\n")
    return sb.toString()
  }

  private fun emptySvg(options: Options): String {
    val side = options.padding * 2
    return """<svg xmlns="http://www.w3.org/2000/svg" width="$side" height="$side" """ +
      """viewBox="0 0 $side $side" font-family="sans-serif">""" +
      """<rect x="0" y="0" width="$side" height="$side" fill="#FFFFFF"/></svg>""" +
      "\n"
  }

  private data class Box(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val depth: Int,
    val clickable: Boolean,
    val clearAndSet: Boolean,
    val label: String?,
  )

  private fun collect(node: ComposeSemanticsNode, depth: Int, into: MutableList<Box>) {
    val bounds = parseBounds(node.boundsInRoot)
    if (bounds != null) {
      into.add(
        Box(
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
    // Recurse even when this node's own bounds are unparseable — children carry their own absolute
    // boundsInRoot, so a degenerate container shouldn't drop its subtree from the diagram.
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

  /**
   * Truncates [text] with a trailing `…` so the rendered string fits in [maxWidthPx], estimating
   * glyph advance as 0.6·[fontSize] (a reasonable mean for sans-serif). Approximate by design — the
   * wireframe is schematic, and over-/under-fitting by a glyph is invisible at this scale.
   */
  private fun truncateToWidth(text: String, maxWidthPx: Int, fontSize: Int): String {
    if (maxWidthPx <= 0) return ""
    val charWidth = fontSize * 0.6
    val maxChars = (maxWidthPx / charWidth).toInt()
    if (maxChars <= 0) return ""
    if (text.length <= maxChars) return text
    if (maxChars == 1) return "…"
    return text.take(maxChars - 1) + "…"
  }

  private fun escape(s: String): String =
    buildString(s.length) {
      for (c in s) {
        when (c) {
          '&' -> append("&amp;")
          '<' -> append("&lt;")
          '>' -> append("&gt;")
          '"' -> append("&quot;")
          '\'' -> append("&apos;")
          else -> append(c)
        }
      }
    }
}
