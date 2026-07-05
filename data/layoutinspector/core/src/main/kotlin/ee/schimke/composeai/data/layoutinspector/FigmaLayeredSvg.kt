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
 * - **Text is editable `<text>`**, not outlines — with the captured family/size/weight/colour — so
 *   a designer edits the string in place and the type stays live.
 * - **Named theme colours ride along**: when a fill resolves to a theme role, the role name is
 *   emitted in a `<title>` and a `data-token` attribute, so the paired `figma-variables.json` (and
 *   a future live-variable import) can bind the fill to a variable instead of a raw literal.
 *
 * Pure and deterministic: model in, SVG string out — no graphics toolkit, no IO — so it lives on
 * the render-subprocess-safe core classpath next to [SemanticsWireframeSvg].
 */
object FigmaLayeredSvg {

  data class Options(
    /** Emit the `<title>`/`data-token` theme-role annotations on named-colour shapes. */
    val annotateTokens: Boolean = true,
    /** Fallback text size (px) when a text node didn't resolve one. */
    val defaultFontSizePx: Double = 14.0,
    /**
     * Family a `<text>` gets when its captured family is null or a generic (`sans-serif`/`serif`/
     * `monospace`). Left as `sans-serif` for the vector-only export; the font-embedding path sets
     * it to the resolved default face (e.g. `Roboto`) so the emitted `@font-face` matches by name.
     */
    val defaultFontFamily: String = "sans-serif",
  )

  /**
   * @param fontFaces faces to embed as `@font-face` (via `<defs><style>`) so the text renders with
   *   the real typeface in Chromium/Figma instead of a substituted `sans-serif`. Empty (default)
   *   keeps the export vector-only with no embedded fonts.
   * @param familyOverrides maps a text node's *captured* [FigmaSvgText.fontFamily] (e.g. the
   *   absolute path of the font file the render loaded) to the family name to emit on the `<text>`
   *   — so it matches the `@font-face` the producer embedded for that file. Unmapped families fall
   *   back to [resolveFamily].
   */
  fun render(
    model: FigmaSvgModel,
    options: Options = Options(),
    fontFaces: List<FigmaSvgFontFace> = emptyList(),
    familyOverrides: Map<String, String> = emptyMap(),
  ): String {
    val sb = StringBuilder()
    val rootFamily = if (fontFaces.isNotEmpty()) options.defaultFontFamily else "sans-serif"
    sb.append(
      """<svg xmlns="http://www.w3.org/2000/svg" width="${model.width}" height="${model.height}" """ +
        """viewBox="0 0 ${model.width} ${model.height}" font-family="${escapeAttr(rootFamily)}">"""
    )
    sb.append('\n')
    if (fontFaces.isNotEmpty()) sb.append(fontFaceDefs(fontFaces))
    // Everything is drawn in root-pixel space; a single group translate drops the tree into the
    // padded canvas, keeping child coordinates absolute (matching Figma's absolute layout on
    // import).
    sb.append("""<g transform="translate(${model.tx}, ${model.ty})">""")
    sb.append('\n')
    renderLayer(model.root, sb, options, familyOverrides, depth = 1)
    sb.append("</g>\n")
    sb.append("</svg>\n")
    return sb.toString()
  }

  private fun renderLayer(
    layer: FigmaSvgLayer,
    sb: StringBuilder,
    options: Options,
    familyOverrides: Map<String, String>,
    depth: Int,
  ) {
    val indent = "  ".repeat(depth)
    // An opaque layer is a leaf `<image>` — the background-free raster stands in for a subtree the
    // exporter can't vectorise. No shape/text/children; the group keeps the composable name.
    if (layer.raster != null) {
      sb.append("""$indent<g id="${escapeAttr(layer.name)}">""").append('\n')
      sb.append(indent).append("  ").append(image(layer, layer.raster)).append('\n')
      sb.append("$indent</g>\n")
      return
    }
    val tokenName = layer.fill?.tokenName ?: layer.stroke?.tokenName
    val dataToken =
      if (options.annotateTokens && tokenName != null) """ data-token="${escapeAttr(tokenName)}""""
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
      sb.append(indent).append("  ").append(text(layer, options, familyOverrides)).append('\n')
    }
    for (child in layer.children) renderLayer(child, sb, options, familyOverrides, depth + 1)
    sb.append("$indent</g>\n")
  }

  private fun image(layer: FigmaSvgLayer, raster: FigmaSvgRaster): String =
    """<image href="${escapeAttr(raster.href)}" x="${layer.left}" y="${layer.top}" """ +
      """width="${layer.width}" height="${layer.height}"/>"""

  private fun shape(layer: FigmaSvgLayer): String {
    val fillAttr =
      layer.fill?.let { """fill="${it.hex}"${opacity("fill", it)}""" } ?: """fill="none""""
    val strokeAttr =
      layer.stroke?.let { """ stroke="${it.hex}"${opacity("stroke", it)} stroke-width="1"""" } ?: ""
    val radii = effectiveRadii(layer)
    return if (radii == null) {
      """<rect x="${layer.left}" y="${layer.top}" width="${layer.width}" height="${layer.height}" """ +
        """$fillAttr$strokeAttr/>"""
    } else if (layer.cut) {
      // A cut/chamfered corner can't be expressed as a `<rect rx>` — always a path with straight
      // corner segments, uniform or not.
      """<path d="${cornerRectPath(layer, radii, cut = true)}" $fillAttr$strokeAttr/>"""
    } else if (radii.distinct().size == 1) {
      val r = fmt(radii[0])
      """<rect x="${layer.left}" y="${layer.top}" width="${layer.width}" height="${layer.height}" """ +
        """rx="$r" ry="$r" $fillAttr$strokeAttr/>"""
    } else {
      """<path d="${cornerRectPath(layer, radii, cut = false)}" $fillAttr$strokeAttr/>"""
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
   * A rectangle path with independent corner sizes (top-left, top-right, bottom-right,
   * bottom-left), each clamped to half the shorter side so overlapping corners don't invert the
   * path. Each corner is an arc (rounded) or — when [cut] — a straight chamfer segment between the
   * same two points, so a `CutCornerShape` bevels where a `RoundedCornerShape` rounds.
   */
  private fun cornerRectPath(layer: FigmaSvgLayer, radii: List<Double>, cut: Boolean): String {
    val x = layer.left.toDouble()
    val y = layer.top.toDouble()
    val w = layer.width.toDouble()
    val h = layer.height.toDouble()
    val cap = minOf(w, h) / 2.0
    val c = radii.map { it.coerceIn(0.0, cap) }
    val (tl, tr, br, bl) = Quad(c[0], c[1], c[2], c[3])
    // A rounded corner is an arc to (ex,ey); a cut corner is a straight line to the same point.
    fun corner(r: Double, ex: Double, ey: Double): String =
      when {
        r <= 0.0 -> ""
        cut -> "L${fmt(ex)},${fmt(ey)} "
        else -> "A${fmt(r)},${fmt(r)} 0 0 1 ${fmt(ex)},${fmt(ey)} "
      }
    return buildString {
      append("M${fmt(x + tl)},${fmt(y)} ")
      append("H${fmt(x + w - tr)} ")
      append(corner(tr, x + w, y + tr))
      append("V${fmt(y + h - br)} ")
      append(corner(br, x + w - br, y + h))
      append("H${fmt(x + bl)} ")
      append(corner(bl, x, y + h - bl))
      append("V${fmt(y + tl)} ")
      append(corner(tl, x + tl, y))
      append("Z")
    }
  }

  private data class Quad(val a: Double, val b: Double, val c: Double, val d: Double)

  private fun text(
    layer: FigmaSvgLayer,
    options: Options,
    familyOverrides: Map<String, String>,
  ): String {
    val t = layer.text!!
    val size = t.fontSizePx ?: options.defaultFontSizePx
    val baseline = layer.top + baselineOffset(t, size, (layer.bottom - layer.top).toDouble())
    val familyName =
      t.fontFamily?.let { familyOverrides[it] }
        ?: resolveFamily(t.fontFamily, options.defaultFontFamily)
    val family = """ font-family="${escapeAttr(familyName)}""""
    val weight = t.fontWeight?.let { """ font-weight="$it"""" } ?: ""
    val style = if (t.italic) """ font-style="italic"""" else ""
    val fill =
      t.color?.let { """ fill="${it.hex}"${opacity("fill", it)}""" } ?: """ fill="#000000""""
    return """<text x="${layer.left}" y="${fmt(baseline)}" font-size="${fmt(size)}"$family$weight$style$fill>""" +
      "${escape(t.content)}</text>"
  }

  // Typical UI-font metrics as a fraction of the em (font size). Compose lays a line out as the
  // font
  // box (ascent + descent ≈ [FONT_BOX]·em) with any extra line-height leading split above and below
  // it; the baseline then sits [ASCENT]·em below the top of that font box. Approximations, not the
  // exact resolved face metrics — but close enough that the SVG text lands within a pixel of the
  // render (the fidelity harness confirms it), and a designer nudges it in Figma regardless.
  private const val ASCENT_EM = 0.93
  private const val FONT_BOX_EM = 1.17

  /**
   * The first-line baseline offset from the layer's top, given the font [size] (px) and the layer's
   * measured [boxHeight] (px). Uses the resolved line height when captured, else the measured box
   * when it looks single-line, else a 1.2·em default; the leading beyond the font box is split so
   * the baseline drops below a bare ascent-from-top — which is where Compose actually draws it.
   */
  private fun baselineOffset(t: FigmaSvgText, size: Double, boxHeight: Double): Double {
    val lineHeight =
      t.lineHeightPx ?: boxHeight.takeIf { it in (size * 0.9)..(size * 2.2) } ?: (size * 1.2)
    val halfLeading = ((lineHeight - size * FONT_BOX_EM) / 2).coerceAtLeast(0.0)
    return halfLeading + size * ASCENT_EM
  }

  /** CSS generic families that carry no real face — resolved to the default embedded family. */
  private val GENERIC_FAMILIES =
    setOf("sans-serif", "serif", "monospace", "cursive", "fantasy", "system-ui")

  /**
   * The family name to emit for a `<text>` — the captured face, or [defaultFamily] when the capture
   * was null or a CSS generic (a bare `sans-serif` carries no real face to match). Shared with the
   * producer so the name it emits matches the `@font-face` family it embeds.
   */
  fun resolveFamily(captured: String?, defaultFamily: String): String {
    if (captured == null || captured.lowercase() in GENERIC_FAMILIES) return defaultFamily
    return svgFontFamily(captured)
  }

  /** `<defs><style>` with one `@font-face` per embedded face, its bytes as a base64 data URI. */
  private fun fontFaceDefs(faces: List<FigmaSvgFontFace>): String = buildString {
    append("<defs><style>")
    for (f in faces) {
      val mime =
        when (f.format) {
          "truetype" -> "font/ttf"
          "opentype" -> "font/otf"
          else -> "font/woff2"
        }
      append("@font-face{font-family:'").append(cssFamily(f.family)).append("';")
      append("font-style:").append(if (f.italic) "italic" else "normal").append(';')
      append("font-weight:").append(f.weight).append(';')
      append("src:url(data:$mime;base64,")
        .append(f.dataBase64)
        .append(") format('")
        .append(f.format)
        .append("');}")
    }
    append("</style></defs>\n")
  }

  private fun cssFamily(s: String): String = s.replace("\\", "\\\\").replace("'", "\\'")

  /**
   * Compose reports a `FontListFontFamily` as a resolved face identity (a file path or
   * `res/font/id`) rather than a display name. Strip it to a last path segment so the SVG carries
   * something a font picker can match; a generic name (`sans-serif`) passes through unchanged.
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
