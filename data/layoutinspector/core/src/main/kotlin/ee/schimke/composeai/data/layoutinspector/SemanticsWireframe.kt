package ee.schimke.composeai.data.layoutinspector

/**
 * Bakes a [ComposeSemanticsPayload] into a standalone **2D wireframe SVG** — the schematic an
 * agent, MCP client, CLI report, or PR diff can consume as a single self-contained artifact,
 * without the interactive VS Code box-overlay and without the a11y/ATF data the
 * `AccessibilityOverlay` PNG requires.
 *
 * Visual language (deliberately a *skeleton*, not a screenshot composite) is defined once in
 * [WireframeStyle] and shared with the raster bakers:
 * - Every semantic node with parseable [ComposeSemanticsNode.boundsInRoot] becomes a stroked
 *   `<rect>` in the shared root-pixel coordinate space (bounds are already absolute-to-root, so no
 *   transform accumulation is needed — only a single translate to the padded viewBox origin).
 * - **Depth reads through stroke hue**: nesting level cycles a muted palette so a deep tree stays
 *   legible without fills fighting each other. Children are emitted after parents (pre-order), so
 *   they layer on top.
 * - **Clickable nodes are the actionable stops** — translucent accent fill + thicker accent stroke.
 * - **Merge mode** distinguishes structure: `mergeDescendants` keeps the solid stroke;
 *   `clearAndSet` switches to a dashed stroke (its descendants' semantics are replaced).
 * - Each box is labelled top-left with its label → role → testTag → text, truncated to box width.
 *
 * Pure and deterministic: input model in, SVG string out, no graphics toolkit, no IO.
 */
object SemanticsWireframeSvg {

  /** Tunables for the bake; defaults are chosen to read at a glance on a phone-sized root. */
  data class Options(
    /** Transparent margin (px) around the diagram extent. */
    val padding: Int = 16,
    /** Draw the top-left label on each box. */
    val showLabels: Boolean = true,
    /** Label font size (px). */
    val fontSize: Int = WireframeStyle.fontSize,
  )

  /** Writes the wireframe SVG for [payload]. */
  fun render(payload: ComposeSemanticsPayload, options: Options = Options()): String {
    val model = WireframeModel.from(payload, options.padding)
    return render(model, options)
  }

  /** Writes the wireframe SVG for an already-built [model]. */
  fun render(model: WireframeModel, options: Options = Options()): String {
    val sb = StringBuilder()
    sb.append(
      """<svg xmlns="http://www.w3.org/2000/svg" width="${model.width}" height="${model.height}" """ +
        """viewBox="0 0 ${model.width} ${model.height}" font-family="sans-serif">"""
    )
    sb.append("\n")
    sb.append(
      """<rect x="0" y="0" width="${model.width}" height="${model.height}" """ +
        """fill="${WireframeStyle.hex(WireframeStyle.ground)}"/>"""
    )
    sb.append("\n")

    for (box in model.boxes) {
      val x = box.left + model.tx
      val y = box.top + model.ty
      val stroke = WireframeStyle.hex(WireframeStyle.strokeColor(box))
      val strokeWidth = WireframeStyle.strokeWidth(box)
      val dash = if (box.clearAndSet) """ stroke-dasharray="4 3"""" else ""
      val fill =
        if (box.clickable)
          """ fill="${WireframeStyle.hex(WireframeStyle.clickAccent)}" """ +
            """fill-opacity="${WireframeStyle.clickFillOpacity}""""
        else """ fill="none""""
      sb.append(
        """<rect x="$x" y="$y" width="${box.width}" height="${box.height}"$fill stroke="$stroke" """ +
          """stroke-width="$strokeWidth"$dash/>"""
      )
      sb.append("\n")

      if (options.showLabels) {
        val label = box.label
        if (label != null && box.width > options.fontSize) {
          val text = WireframeStyle.truncateToWidth(label, box.width - 4, options.fontSize)
          if (text.isNotEmpty()) {
            val baseline = y + options.fontSize + 1
            sb.append(
              """<text x="${x + 2}" y="$baseline" font-size="${options.fontSize}" """ +
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
