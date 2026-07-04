package ee.schimke.composeai.data.layoutinspector

/**
 * Bakes a [FigmaSvgModel] into a **layered, editable SVG** designed to round-trip cleanly through
 * Figma's SVG import (and Sketch / Penpot / Illustrator).
 *
 * How the emitted document maps to Figma layers:
 * - **Every layer is a `<g id="…">`** whose `id` is the composable name — so a `Button`, a `Card`,
 *   a whole screen each land as a *named* group/frame, nested exactly as the composables nest. This
 *   is the difference from [SemanticsWireframeSvg], which flattens the tree into anonymous boxes.
 * - **Container tokens become real vector shapes**: a layer with a background token is a filled
 *   `<rect>` (or a rounded-corner `<path>` when the corners aren't uniform), a border token adds a
 *   stroke, and the resolved corner radius imports as Figma's editable corner radius.
 * - **Text is editable `<text>`**, not outlines — with the captured family/size/weight/colour — so a
 *   designer edits the string in place and the type stays live.
 * - **Named theme colours ride along**: when a fill resolves to a theme role, the role name is
 *   emitted in a `<title>` and a `data-token` attribute, so the paired `figma-variables.json` (and a
 *   future live-variable import) can bind the fill to a variable instead of a raw literal.
 *
 * Pure and deterministic: model in, SVG string out — no graphics toolkit, no IO — so it lives on the
 * render-subprocess-safe core classpath next to [SemanticsWireframeSvg].
 */
object FigmaLayeredSvg {

  data class Options(
    /** Emit the `<title>`/`data-token` theme-role annotations on named-colour shapes. */
    val annotateTokens: Boolean = true,
    /** Fallback text size (px) when a text node didn't resolve one. */
    val defaultFontSizePx: Double = 14.0,
  )

  fun render(model: FigmaSvgModel, options: Options = Options()): String {
    val sb = StringBuilder()
    sb.append(
      """<svg xmlns="http://www.w3.org/2000/svg" width="${model.width}" height="${model.height}" """ +
        """viewBox="0 0 ${model.width} ${model.height}" font-family="sans-serif">"""
    )
    sb.append('\n')
    // Everything is drawn in root-pixel space; a single group translate drops the tree into the
    // padded canvas, keeping child coordinates absolute (matching Figma's absolute layout on import).
    sb.append("""<g transform="translate(${model.tx}, ${model.ty})">""")
    sb.append('\n')
    renderLayer(model.root, sb, options, depth = 1)
    sb.append("</g>\n")
    sb.append("</svg>\n")
    return sb.toString()
  }

  private fun renderLayer(layer: FigmaSvgLayer, sb: StringBuilder, options: Options, depth: Int) {
    val indent = "  ".repeat(depth)
    val tokenName = layer.fill?.tokenName ?: layer.stroke?.tokenName
    val dataToken =
      if (options.annotateTokens && tokenName != null)
        """ data-token="${escapeAttr(tokenName)}""""
      else ""
    sb.append("""$indent<g id="${escapeAttr(layer.name)}"$dataToken>""")
    sb.append('\n')
    if (options.annotateTokens && tokenName != null) {
      sb.append("""$indent  <title>${escape(layer.name)} · ${escape(tokenName)}</title>""")
      sb.append('\n')
    }

    if (layer.fill != null || layer.stroke != null) {
      sb.append(indent).append("  ").append(shape(layer)).append('\n')
    }
    if (layer.text != null) {
      sb.append(indent).append("  ").append(text(layer, options)).append('\n')
    }
    for (child in layer.children) renderLayer(child, sb, options, depth + 1)
    sb.append("$indent</g>\n")
  }

  private fun shape(layer: FigmaSvgLayer): String {
    val fillAttr =
      layer.fill?.let { """fill="${it.hex}"${opacity("fill", it)}""" } ?: """fill="none""""
    val strokeAttr =
      layer.stroke?.let { """ stroke="${it.hex}"${opacity("stroke", it)} stroke-width="1"""" } ?: ""
    val radii = effectiveRadii(layer)
    return if (radii == null) {
      """<rect x="${layer.left}" y="${layer.top}" width="${layer.width}" height="${layer.height}" """ +
        """$fillAttr$strokeAttr/>"""
    } else if (radii.distinct().size == 1) {
      val r = fmt(radii[0])
      """<rect x="${layer.left}" y="${layer.top}" width="${layer.width}" height="${layer.height}" """ +
        """rx="$r" ry="$r" $fillAttr$strokeAttr/>"""
    } else {
      """<path d="${roundedRectPath(layer, radii)}" $fillAttr$strokeAttr/>"""
    }
  }

  /** Resolves the four px corner radii to draw with, honouring [FigmaSvgLayer.circle]. */
  private fun effectiveRadii(layer: FigmaSvgLayer): List<Double>? {
    if (layer.circle) {
      val r = minOf(layer.width, layer.height) / 2.0
      return listOf(r, r, r, r)
    }
    return layer.cornerRadiiPx
  }

  /**
   * A rounded rectangle path with independent corner radii (top-left, top-right, bottom-right,
   * bottom-left), each clamped to half the shorter side so overlapping radii don't invert the path.
   */
  private fun roundedRectPath(layer: FigmaSvgLayer, radii: List<Double>): String {
    val x = layer.left.toDouble()
    val y = layer.top.toDouble()
    val w = layer.width.toDouble()
    val h = layer.height.toDouble()
    val cap = minOf(w, h) / 2.0
    val c = radii.map { it.coerceIn(0.0, cap) }
    val (tl, tr, br, bl) = Quad(c[0], c[1], c[2], c[3])
    return buildString {
      append("M${fmt(x + tl)},${fmt(y)} ")
      append("H${fmt(x + w - tr)} ")
      if (tr > 0) append("A${fmt(tr)},${fmt(tr)} 0 0 1 ${fmt(x + w)},${fmt(y + tr)} ")
      append("V${fmt(y + h - br)} ")
      if (br > 0) append("A${fmt(br)},${fmt(br)} 0 0 1 ${fmt(x + w - br)},${fmt(y + h)} ")
      append("H${fmt(x + bl)} ")
      if (bl > 0) append("A${fmt(bl)},${fmt(bl)} 0 0 1 ${fmt(x)},${fmt(y + h - bl)} ")
      append("V${fmt(y + tl)} ")
      if (tl > 0) append("A${fmt(tl)},${fmt(tl)} 0 0 1 ${fmt(x + tl)},${fmt(y)} ")
      append("Z")
    }
  }

  private data class Quad(val a: Double, val b: Double, val c: Double, val d: Double)

  private fun text(layer: FigmaSvgLayer, options: Options): String {
    val t = layer.text!!
    val size = t.fontSizePx ?: options.defaultFontSizePx
    // Baseline: place the text near the top of its box, offset by the cap so it sits inside — a
    // designer repositions it in Figma anyway; this only needs to land it in the right neighbourhood.
    val baseline = layer.top + size * 0.8
    val family = t.fontFamily?.let { """ font-family="${escapeAttr(svgFontFamily(it))}"""" } ?: ""
    val weight = t.fontWeight?.let { """ font-weight="$it"""" } ?: ""
    val style = if (t.italic) """ font-style="italic"""" else ""
    val fill =
      t.color?.let { """ fill="${it.hex}"${opacity("fill", it)}""" } ?: """ fill="#000000""""
    return """<text x="${layer.left}" y="${fmt(baseline)}" font-size="${fmt(size)}"$family$weight$style$fill>""" +
      "${escape(t.content)}</text>"
  }

  /**
   * Compose reports a `FontListFontFamily` as a resolved face identity (a file path or `res/font/id`)
   * rather than a display name. Strip it to a last path segment so the SVG carries something a font
   * picker can match; a generic name (`sans-serif`) passes through unchanged.
   */
  private fun svgFontFamily(identity: String): String {
    if (!identity.contains('/') && !identity.contains('\\')) return identity
    val leaf = identity.substringAfterLast('/').substringAfterLast('\\')
    return leaf.substringBeforeLast('.').ifBlank { identity }
  }

  private fun opacity(kind: String, color: FigmaSvgColor): String =
    if (color.opacity < 1.0) """ $kind-opacity="${fmt(color.opacity)}"""" else ""

  /** Trim trailing zeros so `12.0` → `12` and `10.5` stays `10.5`, keeping the SVG compact. */
  private fun fmt(v: Double): String {
    if (v == v.toLong().toDouble()) return v.toLong().toString()
    return ((v * 100).toLong() / 100.0).toString()
  }

  private fun escape(s: String): String =
    buildString(s.length) {
      for (c in s) {
        when (c) {
          '&' -> append("&amp;")
          '<' -> append("&lt;")
          '>' -> append("&gt;")
          else -> append(c)
        }
      }
    }

  private fun escapeAttr(s: String): String =
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
