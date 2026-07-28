package ee.schimke.composeai.data.layoutinspector

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

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
     * Family a `<text>` gets when its captured family is null or a *sans* generic
     * (`sans-serif`/`system-ui`). Left as `sans-serif` for the vector-only export; the
     * font-embedding path sets it to the resolved default face (e.g. `Roboto`) so the emitted
     * `@font-face` matches by name. Non-sans generics (`serif`/`monospace`) keep their own identity
     * via [resolveFamily]/[embedFamily] rather than collapsing to this default.
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
    // `text-rendering="geometricPrecision"` turns off the browser's glyph grid-fitting/hinting so
    // every `<text>` rasterises at its exact subpixel metrics — matching how the Skiko render (and
    // Figma itself) place glyphs, instead of the default `auto` hinting that snaps edges to pixel
    // boundaries and leaves a constant ~2-3% edge diff against the render on text-heavy previews.
    sb.append(
      """<svg xmlns="http://www.w3.org/2000/svg" width="${model.width}" height="${model.height}" """ +
        """viewBox="0 0 ${model.width} ${model.height}" text-rendering="geometricPrecision" """ +
        """font-family="${escapeAttr(rootFamily)}">"""
    )
    sb.append('\n')
    if (fontFaces.isNotEmpty()) sb.append(fontFaceDefs(fontFaces))
    // Emit one reusable `feDropShadow` filter per distinct elevation so an elevated surface casts
    // its Material drop shadow instead of reading as a flat fill.
    val elevations = collectElevations(model.root)
    if (elevations.isNotEmpty()) sb.append(shadowFilterDefs(elevations))
    // A round Wear device screen masks the whole tree to the inscribed circle (Roborazzi's device
    // crop), so the square full-frame background doesn't paint the corners the render leaves clear.
    // A *tall* Wear scroll frame masks to a vertical stadium (capsule) instead — the circle would
    // clip the grown list to a lens. The two are mutually exclusive.
    val clip = model.roundClip
    val capsule = model.capsuleClip
    if (capsule != null) {
      sb.append(
        """<clipPath id="deviceRound"><rect x="${capsule.x}" y="${capsule.y}" """ +
          """width="${capsule.width}" height="${capsule.height}" """ +
          """rx="${capsule.rx}" ry="${capsule.rx}"/></clipPath>"""
      )
      sb.append('\n')
    } else if (clip != null) {
      sb.append(
        """<clipPath id="deviceRound"><circle cx="${clip.cx}" cy="${clip.cy}" """ +
          """r="${clip.r}"/></clipPath>"""
      )
      sb.append('\n')
    }
    // Everything is drawn in root-pixel space; a single group translate drops the tree into the
    // padded canvas, keeping child coordinates absolute (matching Figma's absolute layout on
    // import).
    val clipAttr = if (clip != null || capsule != null) """ clip-path="url(#deviceRound)"""" else ""
    sb.append("""<g transform="translate(${model.tx}, ${model.ty})"$clipAttr>""")
    sb.append('\n')
    // A device preview paints its screen background (the black watch face) behind the tree, in the
    // device-mask shape so it fills the face and the corners stay transparent — set only when a
    // device frame opted in, so component previews stay background-free.
    model.deviceBackground?.let { bg ->
      val fillOpacity = opacity("fill", bg)
      val shape =
        when {
          capsule != null ->
            """<rect x="${capsule.x}" y="${capsule.y}" width="${capsule.width}" """ +
              """height="${capsule.height}" rx="${capsule.rx}" ry="${capsule.rx}" """ +
              """fill="${bg.hex}"$fillOpacity/>"""
          clip != null ->
            """<circle cx="${clip.cx}" cy="${clip.cy}" r="${clip.r}" fill="${bg.hex}"$fillOpacity/>"""
          // A maskless `@Preview(showBackground = true, backgroundColor = …)` paints the flat
          // frame the render drew behind the composable (issue #2884) — without it the SVG was
          // transparent exactly where the PNG was opaque.
          else ->
            model.backgroundRect?.let { r ->
              """<rect x="${r.x}" y="${r.y}" width="${r.width}" height="${r.height}" """ +
                """fill="${bg.hex}"$fillOpacity/>"""
            }
        }
      if (shape != null) sb.append("  ").append(shape).append('\n')
    }
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
    inheritedOpacity: Double = 1.0,
    // Monotonic counter for curved-text `<path>` ids, threaded through the whole walk so every arc
    // gets a document-unique id even when two `CurvedLayout`s share a component name — otherwise
    // duplicate ids make a later `<textPath href>` resolve to the first path (Codex #2395).
    curveSeq: IntArray = intArrayOf(0),
  ) {
    val indent = "  ".repeat(depth)
    // An opaque layer is a leaf `<image>` — the background-free raster stands in for a subtree the
    // exporter can't vectorise. No shape/text/children; the group keeps the composable name.
    if (layer.raster != null) {
      // Raster crops come from the final composited frame, so every ancestor/local graphics-layer
      // alpha is already baked into the pixels. Reapplying it here would fade the crop twice.
      sb.append("""$indent<g id="${escapeAttr(layer.name)}">""").append('\n')
      sb.append(indent).append("  ").append(image(layer, layer.raster)).append('\n')
      sb.append("$indent</g>\n")
      return
    }
    // A captured `ImageVector` (an `Icon`/`Image`) emits as editable `<path>` layers under a
    // transform that maps the vector's own viewport onto the layer's placed box — the vector
    // alternative to the `<image>` leaf above. Also a leaf: an icon's art has no editable subtree.
    if (layer.vector != null) {
      sb.append(vectorGroup(layer, layer.vector, indent, inheritedOpacity)).append('\n')
      return
    }
    val tokenName = layer.fill?.tokenName ?: layer.stroke?.tokenName
    val dataToken =
      if (options.annotateTokens && tokenName != null) """ data-token="${escapeAttr(tokenName)}""""
      else ""
    // An elevated surface casts its Material drop shadow via a `feDropShadow` filter on its group,
    // so the shadow falls behind the whole silhouette (fill + children) exactly as the render draws
    // it. Keyed by rounded px so layers at the same elevation share one filter def.
    val filterAttr =
      if (layer.elevationPx >= 1.0) """ filter="url(#${shadowFilterId(layer.elevationPx)})""""
      else ""
    val containsRaster = layer.containsCapturedRaster()
    val outerOpacity = inheritedOpacity * layer.opacity
    val namedGroupOpacity = if (containsRaster) 1.0 else outerOpacity
    sb.append(
      """$indent<g id="${escapeAttr(layer.name)}"$dataToken$filterAttr${opacityAttr(namedGroupOpacity)}>"""
    )
    sb.append('\n')
    if (options.annotateTokens && tokenName != null) {
      sb.append("""$indent  <title>${escape(layer.name)} · ${escape(tokenName)}</title>""")
      sb.append('\n')
    }

    // A captured Canvas-draw background (`Modifier.drawBehind {…}`) paints first, beneath the
    // layer's own shape/text and its children — matching draw order, so the editable vector layers
    // sit on top of the rasterised background.
    layer.background?.let { bg ->
      sb.append(indent).append("  ").append(backgroundImage(bg)).append('\n')
    }
    if (containsRaster) {
      // A group containing a final-frame crop cannot carry inherited opacity without fading that
      // crop twice. Apply the outer alpha only to this layer's vector shape, then thread the
      // combined outer/content alpha into editable descendants; raster leaves deliberately ignore
      // it above.
      if (layer.fill != null || layer.stroke != null) {
        appendOpacityGroup(sb, indent + "  ", outerOpacity) {
          sb.append(indent).append("    ").append(shape(layer)).append('\n')
        }
      }
      val contentOpacity = outerOpacity * layer.contentOpacity
      if (layer.text != null || layer.curvedTexts.isNotEmpty()) {
        appendOpacityGroup(sb, indent + "  ", contentOpacity) {
          appendTextContent(layer, sb, options, familyOverrides, indent + "    ", curveSeq)
        }
      }
      for (child in layer.children) {
        renderLayer(
          child,
          sb,
          options,
          familyOverrides,
          depth + 1,
          inheritedOpacity = contentOpacity,
          curveSeq = curveSeq,
        )
      }
    } else {
      if (layer.fill != null || layer.stroke != null) {
        sb.append(indent).append("  ").append(shape(layer)).append('\n')
      }
      val hasInnerContent =
        layer.text != null || layer.curvedTexts.isNotEmpty() || layer.children.isNotEmpty()
      if (hasInnerContent && layer.contentOpacity < 0.999) {
        sb
          .append(indent)
          .append("  ")
          .append("""<g${opacityAttr(layer.contentOpacity)}>""")
          .append('\n')
        appendTextContent(layer, sb, options, familyOverrides, indent + "    ", curveSeq)
        for (child in layer.children) {
          renderLayer(
            child,
            sb,
            options,
            familyOverrides,
            depth + 2,
            inheritedOpacity = 1.0,
            curveSeq = curveSeq,
          )
        }
        sb.append(indent).append("  </g>\n")
      } else {
        appendTextContent(layer, sb, options, familyOverrides, indent + "  ", curveSeq)
        for (child in layer.children) {
          renderLayer(
            child,
            sb,
            options,
            familyOverrides,
            depth + 1,
            inheritedOpacity = 1.0,
            curveSeq = curveSeq,
          )
        }
      }
    }
    sb.append("$indent</g>\n")
  }

  private fun appendTextContent(
    layer: FigmaSvgLayer,
    sb: StringBuilder,
    options: Options,
    familyOverrides: Map<String, String>,
    indent: String,
    curveSeq: IntArray,
  ) {
    if (layer.text != null) {
      sb.append(indent).append(text(layer, options, familyOverrides)).append('\n')
    }
    layer.curvedTexts.forEach { ct ->
      sb.append(indent).append(curvedText(ct, "c${curveSeq[0]++}")).append('\n')
    }
  }

  private inline fun appendOpacityGroup(
    sb: StringBuilder,
    indent: String,
    opacity: Double,
    content: () -> Unit,
  ) {
    if (opacity >= 0.999) {
      content()
      return
    }
    sb.append(indent).append("""<g${opacityAttr(opacity)}>""").append('\n')
    content()
    sb.append(indent).append("</g>\n")
  }

  private fun FigmaSvgLayer.containsCapturedRaster(): Boolean =
    raster != null || background != null || children.any { it.containsCapturedRaster() }

  /**
   * A Wear curved-text run (a `TimeText` clock) as an SVG `<textPath>` on its baseline arc. The
   * baseline circle is centred at ([LayoutInspectorCurvedText.centerXPx], `centerYPx`) with radius
   * `radiusPx`; the run spans `sweepRadians` from `startAngleRadians` (screen convention: clockwise
   * from +x, so `1.5π` = top). The text is centred on the arc so it reads across the top exactly as
   * the render draws it, and stays editable rather than dropping out or baking to a raster.
   */
  private fun curvedText(ct: LayoutInspectorCurvedText, id: String): String {
    val dir = if (ct.clockwise) 1.0 else -1.0
    val a0 = ct.startAngleRadians
    val a1 = ct.startAngleRadians + dir * ct.sweepRadians
    val sx = ct.centerXPx + ct.radiusPx * cos(a0)
    val sy = ct.centerYPx + ct.radiusPx * sin(a0)
    val ex = ct.centerXPx + ct.radiusPx * cos(a1)
    val ey = ct.centerYPx + ct.radiusPx * sin(a1)
    // SVG arc sweep-flag: 1 draws in the increasing-angle (visually clockwise, y-down) direction.
    val sweepFlag = if (ct.clockwise) 1 else 0
    val largeArc = if (ct.sweepRadians > PI) 1 else 0
    val pathId = "curve-${escapeAttr(id)}"
    val r = fmt(ct.radiusPx)
    val d = "M ${fmt(sx)} ${fmt(sy)} A $r $r 0 $largeArc $sweepFlag ${fmt(ex)} ${fmt(ey)}"
    val fill = ct.colorArgb?.let { curvedColorHex(it) } ?: "#000000"
    val weight = ct.fontWeight?.let { " font-weight=\"$it\"" } ?: ""
    return "<path id=\"$pathId\" d=\"$d\" fill=\"none\"/>" +
      "<text font-size=\"${fmt(ct.fontSizePx)}\"$weight fill=\"$fill\" dominant-baseline=\"alphabetic\">" +
      "<textPath href=\"#$pathId\" startOffset=\"50%\" text-anchor=\"middle\">" +
      "${escape(ct.text)}</textPath></text>"
  }

  /** `#AARRGGBB` (or `#RRGGBB`) → `#RRGGBB` for an SVG `fill`. */
  private fun curvedColorHex(argb: String): String {
    val hex = argb.removePrefix("#")
    return if (hex.length == 8) "#${hex.substring(2)}" else "#$hex"
  }

  /** Distinct rounded-px elevations in the tree, so one `feDropShadow` def is shared per level. */
  private fun collectElevations(
    layer: FigmaSvgLayer,
    acc: MutableSet<Int> = mutableSetOf(),
  ): Set<Int> {
    if (layer.elevationPx >= 1.0) acc.add(layer.elevationPx.roundToInt())
    for (child in layer.children) collectElevations(child, acc)
    return acc
  }

  private fun shadowFilterId(elevationPx: Double): String = "shadow-${elevationPx.roundToInt()}"

  /**
   * A `feDropShadow` per elevation level, approximating Material's key shadow: the blur and
   * vertical offset scale with elevation, at a soft opacity. The filter region is expanded so a
   * large blur isn't clipped at the layer's bounds.
   */
  private fun shadowFilterDefs(elevations: Set<Int>): String {
    val sb = StringBuilder("<defs>\n")
    for (e in elevations.sorted()) {
      val dy = fmt(e * 0.5)
      val blur = fmt(e * 0.6)
      sb.append(
        """  <filter id="${shadowFilterId(e.toDouble())}" x="-50%" y="-50%" width="200%" height="200%">"""
      )
      sb.append('\n')
      sb.append(
        """    <feDropShadow dx="0" dy="$dy" stdDeviation="$blur" flood-color="#000000" flood-opacity="0.26"/>"""
      )
      sb.append('\n')
      sb.append("  </filter>\n")
    }
    sb.append("</defs>\n")
    return sb.toString()
  }

  private fun image(layer: FigmaSvgLayer, raster: FigmaSvgRaster): String =
    """<image href="${escapeAttr(raster.href)}" x="${layer.left}" y="${layer.top}" """ +
      """width="${layer.width}" height="${layer.height}"/>"""

  private fun backgroundImage(bg: FigmaSvgBackgroundRaster): String =
    """<image href="${escapeAttr(bg.href)}" x="${bg.left}" y="${bg.top}" """ +
      """width="${bg.width}" height="${bg.height}"/>"""

  /**
   * A captured icon [FigmaSvgVector] fitted in its pre-transform layout slot, then mapped through
   * the captured placed bounds. This preserves the normal aspect fit while retaining an explicit
   * nonuniform graphics-layer scale; `ContentScale.FillBounds` opts directly into a stretched fit.
   */
  private fun vectorGroup(
    layer: FigmaSvgLayer,
    vec: FigmaSvgVector,
    indent: String,
    inheritedOpacity: Double,
  ): String {
    val layoutWidth = vec.layoutWidth.takeIf { it > 0 }?.toDouble() ?: layer.width.toDouble()
    val layoutHeight = vec.layoutHeight.takeIf { it > 0 }?.toDouble() ?: layer.height.toDouble()
    val scaleX: Double
    val scaleY: Double
    if (vec.fillBounds) {
      scaleX = if (vec.viewportWidth > 0f) layer.width / vec.viewportWidth.toDouble() else 1.0
      scaleY = if (vec.viewportHeight > 0f) layer.height / vec.viewportHeight.toDouble() else 1.0
    } else {
      val layoutScale =
        minOf(
          layoutWidth / vec.viewportWidth.toDouble(),
          layoutHeight / vec.viewportHeight.toDouble(),
        )
      val placedScaleX = if (layoutWidth > 0.0) layer.width / layoutWidth else 1.0
      val placedScaleY = if (layoutHeight > 0.0) layer.height / layoutHeight else 1.0
      scaleX = layoutScale * placedScaleX
      scaleY = layoutScale * placedScaleY
    }
    val fittedWidth = vec.viewportWidth.toDouble() * scaleX
    val fittedHeight = vec.viewportHeight.toDouble() * scaleY
    val x = layer.left + (layer.width - fittedWidth) / 2.0
    val y = layer.top + (layer.height - fittedHeight) / 2.0
    val sb = StringBuilder()
    sb
      .append(
        """$indent<g id="${escapeAttr(layer.name)}"${opacityAttr(inheritedOpacity * layer.opacity)}>"""
      )
      .append('\n')
    sb
      .append(
        """$indent  <g transform="translate(${fmt(x)} ${fmt(y)}) scale(${fmt(scaleX)} ${fmt(scaleY)})"${opacityAttr(layer.contentOpacity)}>"""
      )
      .append('\n')
    for (p in vec.paths) sb.append(indent).append("    ").append(vectorPath(p)).append('\n')
    sb.append("$indent  </g>\n")
    sb.append("$indent</g>")
    return sb.toString()
  }

  private fun vectorPath(p: FigmaSvgVectorPath): String {
    val fill = paintAttr("fill", p.fillArgb, p.fillAlpha) ?: """fill="none""""
    val fillRule = if (p.evenOdd && p.fillArgb != null) """ fill-rule="evenodd"""" else ""
    val stroke =
      if (p.strokeArgb != null && p.strokeWidth > 0f) {
        paintAttr("stroke", p.strokeArgb, p.strokeAlpha)?.let {
          val cap = p.strokeCap?.let { c -> """ stroke-linecap="$c"""" } ?: ""
          val join = p.strokeJoin?.let { j -> """ stroke-linejoin="$j"""" } ?: ""
          """ $it stroke-width="${fmt(p.strokeWidth.toDouble())}"$cap$join"""
        } ?: ""
      } else ""
    return """<path d="${escapeAttr(p.pathData)}" $fill$fillRule$stroke/>"""
  }

  private fun opacityAttr(opacity: Double): String =
    if (opacity < 0.999) """ opacity="${fmt(opacity.coerceIn(0.0, 1.0))}"""" else ""

  /**
   * A captured `#AARRGGBB` paint as an SVG colour + opacity pair (`fill="#RRGGBB"
   * fill-opacity="0.5"`), folding the channel alpha and the painter's extra [extraAlpha] together.
   * Null when fully transparent or absent, so the caller can fall back to `fill="none"`.
   */
  private fun paintAttr(kind: String, argb: String?, extraAlpha: Float): String? {
    if (argb == null) return null
    val hex = argb.removePrefix("#")
    val (a, rgb) =
      if (hex.length == 8) hex.substring(0, 2).toInt(16) to hex.substring(2)
      else 255 to hex.takeLast(6)
    val op = (a / 255.0) * extraAlpha.coerceIn(0f, 1f).toDouble()
    if (op <= 0.0) return null
    val opAttr = if (op < 0.999) """ $kind-opacity="${fmt(op)}"""" else ""
    return """$kind="#$rgb"$opAttr"""
  }

  private fun shape(layer0: FigmaSvgLayer): String {
    // Compose's `Modifier.border` draws the stroke *inside* the layout bounds; SVG centers a stroke
    // on the path, so a bare rect at the bounds paints half the stroke outside the edge (the
    // "double
    // outline" an OutlinedButton/OutlinedCard shows against its render). Inset the drawn box by
    // half
    // the stroke width so the centered stroke's outer edge lands on the bound, matching the render.
    // Only when stroked — a fill-only shape keeps its exact bounds; corner radii shrink by the same
    // inset so a pill stays a pill.
    val layer =
      if (layer0.stroke != null) {
        val d = (layer0.strokeWidthPx / 2.0).roundToInt()
        layer0.copy(
          left = layer0.left + d,
          top = layer0.top + d,
          right = layer0.right - d,
          bottom = layer0.bottom - d,
          cornerRadiiPx = layer0.cornerRadiiPx?.map { (it - d).coerceAtLeast(0.0) },
        )
      } else {
        layer0
      }
    val fillAttr =
      layer.fill?.let { """fill="${it.hex}"${opacity("fill", it)}""" } ?: """fill="none""""
    val strokeAttr =
      layer.stroke?.let {
        """ stroke="${it.hex}"${opacity("stroke", it)} stroke-width="${fmt(layer.strokeWidthPx)}""""
      } ?: ""
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
    // An embedded face (via `familyOverrides`) is guaranteed present in the SVG's `@font-face`, so
    // it
    // stays a bare family name. Only the *unbacked* path — a captured face with no embedded bytes,
    // where the viewer would otherwise substitute its default serif — gets a style-correct generic
    // fallback appended.
    val familyName =
      t.fontFamily?.let { familyOverrides[it] }
        ?: withGenericFallback(resolveFamily(t.fontFamily, options.defaultFontFamily))
    val family = """ font-family="${escapeAttr(familyName)}""""
    val weight = t.fontWeight?.let { """ font-weight="$it"""" } ?: ""
    val style = if (t.italic) """ font-style="italic"""" else ""
    val fill =
      t.color?.let { """ fill="${it.hex}"${opacity("fill", it)}""" } ?: """ fill="#000000""""
    // Emit the captured tracking as SVG `letter-spacing` so the run's glyph advances match the
    // render; without it a browser uses the font's natural advances and a tracked line drifts.
    val letterSpacing =
      t.letterSpacingPx
        ?.takeIf { kotlin.math.abs(it) >= 0.01 }
        ?.let { """ letter-spacing="${fmt(it)}"""" } ?: ""
    val lines = t.lines
    if (lines != null && lines.size > 1) {
      // Wrapped text: one positioned <tspan> per line at the exact place the render wrapped it,
      // instead of collapsing the whole string onto one baseline. x/y are absolute (layer origin +
      // the captured per-line offset), so line alignment (centre/right) and the real break points
      // are preserved on Figma import.
      val tspans =
        lines.joinToString("") { line ->
          val lineStart = line.start
          val lineEnd = line.end
          val styled =
            if (lineStart != null && lineEnd != null) {
              val sourceStart = lineStart.coerceIn(0, t.content.length)
              val sourceEnd = lineEnd.coerceIn(sourceStart, t.content.length)
              val sourceLine = t.content.substring(sourceStart, sourceEnd)
              styledTspans(
                content = t.content,
                spans = t.spans,
                rangeStart = lineStart,
                rangeEnd = lineEnd,
                trailingContent =
                  if (line.content.startsWith(sourceLine)) {
                    line.content.removePrefix(sourceLine)
                  } else {
                    ""
                  },
                firstPosition =
                  """ x="${layer.left + line.left}" y="${layer.top + line.baseline}"""",
                options = options,
                familyOverrides = familyOverrides,
              )
            } else null
          styled
            ?: """<tspan x="${layer.left + line.left}" y="${layer.top + line.baseline}">${escape(line.content)}</tspan>"""
        }
      return """<text font-size="${fmt(size)}"$family$weight$style$letterSpacing$fill>$tspans</text>"""
    }
    val styled =
      styledTspans(
        content = t.content,
        spans = t.spans,
        rangeStart = 0,
        rangeEnd = t.content.length,
        firstPosition = "",
        options = options,
        familyOverrides = familyOverrides,
      )
    // Single-line text: anchor it the way the paragraph was aligned. Left/start keeps the
    // historical `x = layer.left` with no anchor attribute; centre/right/end move the anchor point
    // to the middle/right edge of the layer's own (paragraph) box and let the viewer place the run
    // around it. Without this a `TextAlign.Center` heading in a `fillMaxWidth()` box exported
    // hard against the left edge (issue #2885).
    val (anchorX, anchor) = singleLineAnchor(layer, t.textAlign, t.layoutDirection)
    return """<text x="$anchorX" y="${fmt(baseline)}" font-size="${fmt(size)}"$family$weight$style$letterSpacing$anchor$fill>""" +
      "${styled ?: escape(t.content)}</text>"
  }

  /**
   * The `x` and `text-anchor` attribute for a single-line run under [textAlign], within [layer]'s
   * paragraph box. `justify` behaves as start for a single line (there is nothing to stretch to),
   * matching how Compose lays it out; an unknown/absent alignment keeps the historical left anchor
   * so nothing that wasn't explicitly aligned moves.
   *
   * `start`/`end` are **logical**: Compose resolves `start` to the right edge and `end` to the left
   * under RTL, so they are resolved against [layoutDirection] (absent ⇒ LTR). `left`/`right` are
   * absolute and ignore it.
   */
  private fun singleLineAnchor(
    layer: FigmaSvgLayer,
    textAlign: String?,
    layoutDirection: String?,
  ): Pair<Int, String> {
    val rtl = layoutDirection?.lowercase() == "rtl"
    val resolved =
      when (textAlign?.lowercase()) {
        "start" -> if (rtl) "right" else "left"
        "end" -> if (rtl) "left" else "right"
        else -> textAlign?.lowercase()
      }
    return when (resolved) {
      "center" -> (layer.left + layer.width / 2) to """ text-anchor="middle""""
      "right" -> layer.right to """ text-anchor="end""""
      else -> layer.left to ""
    }
  }

  /** Styled `<tspan>`s for the intersections of [spans] with `[rangeStart, rangeEnd)`. */
  private fun styledTspans(
    content: String,
    spans: List<FigmaSvgTextSpan>?,
    rangeStart: Int,
    rangeEnd: Int,
    trailingContent: String = "",
    firstPosition: String,
    options: Options,
    familyOverrides: Map<String, String>,
  ): String? {
    spans ?: return null
    val start = rangeStart.coerceIn(0, content.length)
    val end = rangeEnd.coerceIn(start, content.length)
    val pieces = spans.mapNotNull { span ->
      val pieceStart = maxOf(start, span.start).coerceIn(start, end)
      val pieceEnd = minOf(end, span.end).coerceIn(pieceStart, end)
      if (pieceStart >= pieceEnd) null else Triple(pieceStart, pieceEnd, span)
    }
    if (pieces.isEmpty()) return null
    return pieces
      .mapIndexed { index, (pieceStart, pieceEnd, span) ->
        val position = if (index == 0) firstPosition else ""
        val suffix = if (index == pieces.lastIndex) trailingContent else ""
        val size = span.fontSizePx?.let { """ font-size="${fmt(it)}"""" } ?: ""
        val family =
          span.fontFamily?.let { captured ->
            val emitted =
              familyOverrides[captured]
                ?: withGenericFallback(resolveFamily(captured, options.defaultFontFamily))
            """ font-family="${escapeAttr(emitted)}""""
          } ?: ""
        val weight = span.fontWeight?.let { """ font-weight="$it"""" } ?: ""
        val style = if (span.italic) """ font-style="italic"""" else ""
        val fill = span.color?.let { """ fill="${it.hex}"${opacity("fill", it)}""" } ?: ""
        """<tspan$position$size$family$weight$style$fill>${escape(content.substring(pieceStart, pieceEnd) + suffix)}</tspan>"""
      }
      .joinToString("")
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

  /** All CSS generic families — none carries a matchable face of its own. */
  private val CSS_GENERICS =
    setOf("sans-serif", "serif", "monospace", "cursive", "fantasy", "system-ui")

  /**
   * The sans generics: they resolve to Compose's Material default typeface, which is itself the
   * default embedded face — so mapping them to [defaultFamily] is exact, not a substitution.
   */
  private val SANS_GENERICS = setOf("sans-serif", "system-ui")

  /**
   * Generics whose *style* has an embeddable Google-Fonts stand-in. A `serif` / `monospace`
   * specimen is a `GenericFontFamily`, so the capture only knows the generic name (Compose resolves
   * the concrete face inside the font engine, out of reach). Mapping it to a concrete same-style
   * family lets the embedding path reproduce a real serif / monospace instead of the sans default —
   * a far closer match than Roboto, and a designer re-picks the exact face in Figma regardless.
   */
  private val GENERIC_EMBED_FACE = mapOf("serif" to "Noto Serif", "monospace" to "Roboto Mono")

  /**
   * The family name to emit on a `<text>` when no embedded face overrides it — i.e. the vector-only
   * export or a family the embedding path couldn't resolve. A null/sans-serif capture becomes
   * [defaultFamily]; a meaningful generic (`serif`, `monospace`, `cursive`, `fantasy`) is emitted
   * **as-is** so the viewer renders a real face of that style rather than the sans default (which
   * is what lost serif/monospace specimens their identity); a real captured face keeps its name.
   */
  fun resolveFamily(captured: String?, defaultFamily: String): String {
    if (captured == null) return defaultFamily
    val generic = captured.lowercase()
    if (generic in SANS_GENERICS) return defaultFamily
    if (generic in CSS_GENERICS) return generic
    return svgFontFamily(captured)
  }

  /**
   * Appends a CSS **generic fallback** to a concrete `<text>` family so text never collapses to the
   * viewer's default *serif* when the named face is unavailable — the common case, since
   * `@font-face` embedding needs a font resolver (and Figma may drop the embedded face on import).
   * `Roboto-Regular` alone renders as Times/serif in Chromium & Figma; `Roboto-Regular, sans-serif`
   * renders in the right style. The generic is inferred from the face name (`…Mono` → `monospace`,
   * `…Serif` → `serif`, else `sans-serif`) so a serif/monospace specimen keeps its style, not just
   * sans. A name that is *already* a bare generic is returned unchanged; a multi-word face is
   * quoted so the list parses. This is presentation-only — [embedFamily]/[resolveFamily] (the
   * `@font-face` name and the override key) are untouched, so an embedded face still matches by its
   * bare name.
   */
  fun withGenericFallback(family: String): String {
    if (family.lowercase() in CSS_GENERICS) return family
    val lower = family.lowercase()
    val generic =
      when {
        "mono" in lower -> "monospace"
        "serif" in lower -> "serif"
        else -> "sans-serif"
      }
    val quoted = if (family.any { it == ' ' }) "'${cssFamily(family)}'" else family
    return "$quoted, $generic"
  }

  /**
   * The concrete family the producer should fetch + embed for a [captured] family, or null when
   * there's no embeddable face (a bare `cursive` / `fantasy`) — the `<text>` then falls back to
   * [resolveFamily] and the viewer supplies the generic. Shared with the producer so the name it
   * embeds matches what [resolveFamily] would emit for the same capture.
   */
  fun embedFamily(captured: String?, defaultFamily: String): String? {
    if (captured == null) return defaultFamily
    val generic = captured.lowercase()
    if (generic in SANS_GENERICS) return defaultFamily
    GENERIC_EMBED_FACE[generic]?.let {
      return it
    }
    if (generic in CSS_GENERICS) return null
    return embeddableFamily(captured)
  }

  /**
   * Style tokens carried separately by the face's `weight`/`italic`, so dropped from a file-derived
   * family name when deriving its embeddable *family*.
   */
  private val STYLE_TOKENS =
    setOf(
      "thin",
      "extralight",
      "ultralight",
      "light",
      "regular",
      "normal",
      "book",
      "roman",
      "medium",
      "semibold",
      "demibold",
      "bold",
      "extrabold",
      "ultrabold",
      "black",
      "heavy",
      "italic",
      "oblique",
    )

  /**
   * Normalise a concrete, file-derived face identity — `NotoSerif-Regular`, `DroidSansMono`,
   * `Roboto-Medium` — to a Google-Fonts *family* name (`Noto Serif`, `Droid Sans Mono`, `Roboto`)
   * the embedding resolver can actually fetch. A `FontListFontFamily` reports its resolved face by
   * file stem, which carries a `-Style` suffix and runs the family words together in CamelCase; the
   * resolver keys on the spaced family with weight/italic supplied separately, so split on
   * hyphen/underscore/space *and* CamelCase boundaries and drop a trailing style token. Pure string
   * work: a name Google has no family for just fails the fetch and the text keeps its vector-only
   * fallback (no worse than before), while the common bundled faces (Roboto/Noto/Droid/…) resolve.
   */
  private fun embeddableFamily(identity: String): String {
    val leaf = svgFontFamily(identity)
    val words =
      leaf
        .replace(Regex("([a-z0-9])([A-Z])"), "$1 $2")
        .split('-', '_', ' ')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
    val kept =
      if (words.size > 1 && words.last().lowercase() in STYLE_TOKENS) words.dropLast(1) else words
    return kept.joinToString(" ").ifBlank { leaf }
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
      // `<style>` content is XML character data, so a raw `&`/`<`/`>` in a family or format keyword
      // is a fatal parse error the moment the exported `.svg` is read as XML (the Figma /
      // Illustrator
      // import this export targets) — even though Chromium's lenient HTML parser tolerates it. XML-
      // escape the two free-form strings on top of the CSS-escaping; the parser decodes the entity
      // back before the CSS engine sees it, so the declared family still matches the `<text>` name.
      append("@font-face{font-family:'").append(escape(cssFamily(f.family))).append("';")
      append("font-style:").append(if (f.italic) "italic" else "normal").append(';')
      append("font-weight:").append(f.weight).append(';')
      append("src:url(data:$mime;base64,")
        .append(f.dataBase64)
        .append(") format('")
        .append(escape(f.format))
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
