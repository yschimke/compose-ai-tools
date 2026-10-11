package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.data.layoutinspector.ComposeSemanticsInsets
import ee.schimke.composeai.data.layoutinspector.ComposeSemanticsNode
import ee.schimke.composeai.data.layoutinspector.ComposeSemanticsPayload
import ee.schimke.composeai.data.layoutinspector.ComposeSemanticsTokens
import ee.schimke.composeai.data.layoutinspector.ComposeSemanticsTypography
import ee.schimke.composeai.data.layoutinspector.LayoutInspectorBounds
import ee.schimke.composeai.data.layoutinspector.LayoutInspectorGradient
import ee.schimke.composeai.data.layoutinspector.LayoutInspectorNode
import ee.schimke.composeai.data.layoutinspector.LayoutInspectorPayload
import ee.schimke.composeai.data.layoutinspector.SlotBounds
import ee.schimke.composeai.data.theme.ThemePayload

/**
 * Derive the viewer's typography, theme and layout inspection layers from a render's own capture,
 * as [DesignAnnotation]s — the code-side counterpart of producer-authored annotations
 * ([ServeAnnotationStore]), drawn with the same box + legend idiom.
 *
 * Typography comes from `compose/semantics` ([ComposeSemanticsNode.typography], with Material roles
 * from [ThemePayload.consumers]). Container layers come from `layout/inspector`, the canonical home
 * of [ComposeSemanticsTokens], because it covers every `LayoutNode` — a padded `Column` declares no
 * semantics at all. Without a layout tree they fall back to the semantics tree's mirrored tokens.
 *
 * The layout layer is the code-side counterpart of [AnnotationKind.LAYOUT] (size, padding per edge,
 * arrangement gap, min-size floor), matching the published layout wireframe. Nothing is re-measured
 * or inferred; nodes that resolved nothing contribute nothing.
 */
public object ServeDesignAnnotations {
  /** The OpenType registered axes whose default is the same in every face. */
  private val AXIS_DEFAULTS =
    mapOf("wdth" to 100f, "slnt" to 0f, "ital" to 0f, "GRAD" to 0f, "ROND" to 0f)

  /**
   * The annotations for one render, in depth-first (legend) order.
   *
   * Bounds are absolute render pixels (semantics `boundsInRoot`, [LayoutInspectorNode.bounds], or
   * the captured paint box), the served PNG's space. Malformed or zero-area bounds are skipped.
   * `enclosing` carries the nearest annotated layout box, so stacked wrappers reproducing one box
   * collapse to one rectangle.
   */
  public fun annotations(
    payload: ComposeSemanticsPayload,
    theme: ThemePayload? = null,
    layout: LayoutInspectorPayload? = null,
  ): List<DesignAnnotation> {
    val out = mutableListOf<DesignAnnotation>()
    val typographyTokensByNode = theme.typographyTokensByNode()
    // Containers come from the semantics walk only when there's no layout tree, so no node is
    // described twice.
    val containersFromSemantics = layout == null
    fun walkSemantics(node: ComposeSemanticsNode, enclosing: AnnotationBounds?) {
      // Unplaced nodes (measured but never positioned, e.g. a subcomposed trial copy) report the
      // origin as their bounds; skip the whole subtree.
      if (!node.placed) return
      val bounds = SlotBounds.parse(node.boundsInRoot)?.takeIf { it.hasArea() }
      var nextEnclosing = enclosing
      if (bounds != null) {
        typographyAnnotation(node, bounds, typographyTokensByNode[node.nodeId].orEmpty())
          ?.let(out::add)
        if (containersFromSemantics) {
          val box = bounds.toAnnotationBounds()
          val role = node.role ?: node.testTag ?: node.textSnippet()
          themeAnnotation(node.tokens, box, role)?.let(out::add)
          layoutAnnotation(node.tokens, box, enclosing, role)?.let {
            out.add(it)
            nextEnclosing = box
          }
        }
      }
      node.children.forEach { walkSemantics(it, nextEnclosing) }
    }
    walkSemantics(payload.root, null)

    if (layout != null) {
      fun walkLayout(node: LayoutInspectorNode, enclosing: AnnotationBounds?) {
        // An unplaced node's whole subtree is off-frame, whatever descendants report.
        if (!node.placed) return
        val box = node.bounds.toAnnotationBounds()
        var nextEnclosing = enclosing
        if (box != null) {
          val role = node.displayName ?: node.component
          themeAnnotation(node.tokens, box, role)?.let(out::add)
          layoutAnnotation(node.tokens, box, enclosing, role)?.let {
            out.add(it)
            nextEnclosing = box
          }
        }
        node.children.forEach { walkLayout(it, nextEnclosing) }
      }
      walkLayout(layout.root, null)
    }
    return out
  }

  private fun SlotBounds.hasArea(): Boolean = right > left && bottom > top

  private fun SlotBounds.toAnnotationBounds(): AnnotationBounds =
    AnnotationBounds(x = left, y = top, width = right - left, height = bottom - top)

  private fun LayoutInspectorBounds.toAnnotationBounds(): AnnotationBounds? =
    if (right > left && bottom > top)
      AnnotationBounds(x = left, y = top, width = right - left, height = bottom - top)
    else null

  /**
   * `"14.0sp/20.0sp · Roboto · 500 · italic"`, omitting what the render left ambiguous. Null when
   * neither size nor face resolved.
   */
  private fun typographyAnnotation(
    node: ComposeSemanticsNode,
    bounds: SlotBounds,
    materialThemeTokens: List<String>,
  ): DesignAnnotation? {
    val type = node.typography ?: return null
    val size =
      when {
        type.fontSize != null && type.lineHeight != null -> "${type.fontSize}/${type.lineHeight}"
        type.fontSize != null -> type.fontSize
        else -> null
      }
    val face = type.fontFamily?.let(::shortFace)
    val parts = buildList {
      materialThemeTokens
        .takeIf { it.isNotEmpty() }
        ?.joinToString(" / ") { "MaterialTheme.typography.$it" }
        ?.let { add(it) }
      size?.let { add(it) }
      face?.let { add(it) }
      effectiveWeight(type)?.let { add(it) }
      addAll(nonDefaultAxes(type))
      type.fontFeatureSettings?.takeIf { it.isNotBlank() }?.let { add("features $it") }
      type.fontStyle?.takeIf { it != "normal" }?.let { add(it) }
      type.letterSpacing?.let { add("tracking $it") }
      type.textAlign?.takeIf { it != "start" }?.let { add(it) }
      type.layoutDirection?.takeIf { it != "ltr" }?.let { add(it) }
    }
    if (parts.isEmpty()) return null
    return DesignAnnotation(
      kind = AnnotationKind.TYPOGRAPHY,
      bounds = bounds.toAnnotationBounds(),
      label = parts.joinToString(" · "),
      role = node.textSnippet(),
      detail = typographyDetail(type, node, materialThemeTokens),
    )
  }

  /**
   * The weight actually drawn: variable faces carry it on the `wght` axis while their `Font` stays
   * at the declared weight.
   */
  private fun effectiveWeight(type: ComposeSemanticsTypography): String? =
    axisValue(type, "wght") ?: type.fontWeight?.toString()

  /**
   * One variable-font axis value from `fontVariationSettings` (`"ROND 100.0, wght 520.0"`), or
   * null.
   */
  private fun axisValue(type: ComposeSemanticsTypography, tag: String): String? =
    variationAxes(type).firstOrNull { it.first == tag }?.second

  /** The `tag to value` pairs of [ComposeSemanticsTypography.fontVariationSettings], in order. */
  private fun variationAxes(type: ComposeSemanticsTypography): List<Pair<String, String>> =
    type.fontVariationSettings.orEmpty().split(',').mapNotNull { entry ->
      val fields = entry.trim().split(Regex("\\s+"), limit = 2)
      val number = fields.getOrNull(1)?.toFloatOrNull() ?: return@mapNotNull null
      fields[0] to (if (number % 1f == 0f) number.toInt().toString() else number.toString())
    }

  /**
   * Variable-font axes worth showing: all except `wght` (shown as weight) and axes at their
   * registered default; `opsz` and custom axes are always shown.
   */
  private fun nonDefaultAxes(type: ComposeSemanticsTypography): List<String> =
    variationAxes(type)
      .filter { (tag, value) -> tag != "wght" && AXIS_DEFAULTS[tag] != value.toFloat() }
      .map { (tag, value) -> "$tag $value" }

  private fun typographyDetail(
    type: ComposeSemanticsTypography,
    node: ComposeSemanticsNode,
    materialThemeTokens: List<String>,
  ): Map<String, String> = buildMap {
    materialThemeTokens.takeIf { it.isNotEmpty() }?.let { put("token", it.joinToString(",")) }
    type.fontSize?.let { put("fontSize", it) }
    type.lineHeight?.let { put("lineHeight", it) }
    type.letterSpacing?.let { put("letterSpacing", it) }
    type.fontFamily?.let { put("fontFamily", it) }
    type.fontWeight?.let { put("fontWeight", it.toString()) }
    type.fontStyle?.let { put("fontStyle", it) }
    type.fontVariationSettings?.let { put("fontVariationSettings", it) }
    type.fontFeatureSettings?.let { put("fontFeatureSettings", it) }
    type.textAlign?.let { put("textAlign", it) }
    node.textColor?.foreground?.let { put("color", it) }
    node.textOverflow?.lineCount?.let { put("lines", it.toString()) }
    node.textOverflow?.maxLines?.let { put("maxLines", it.toString()) }
  }

  /**
   * Theme consumers mix colour, typography and shape names; intersect with resolved typography keys
   * to keep type-scale roles in attribution order. Both products share `SemanticsNode.id`.
   */
  private fun ThemePayload?.typographyTokensByNode(): Map<String, List<String>> {
    if (this == null || resolvedTokens.typography.isEmpty()) return emptyMap()
    val typographyNames = resolvedTokens.typography.keys
    return consumers
      .mapNotNull { consumer ->
        consumer.tokens
          .filter { it in typographyNames }
          .takeIf { it.isNotEmpty() }
          ?.let { consumer.nodeId to it }
      }
      .toMap()
  }

  /**
   * `"fill #FF6750A4 · radius 12.0dp · border 1.0dp #FF79747E · elevation 6.0dp"`, or null when the
   * node declares none. Anchored to [ComposeSemanticsTokens.paintBox] when captured (it differs
   * from placement bounds when padding precedes paint modifiers), else the placement bounds.
   */
  private fun themeAnnotation(
    tokens: ComposeSemanticsTokens?,
    bounds: AnnotationBounds,
    role: String?,
  ): DesignAnnotation? {
    if (tokens == null) return null
    val parts =
      listOfNotNull(
        tokens.backgroundColor?.let { "fill $it" },
        tokens.backgroundGradient?.let { "fill ${gradientText(it)}" },
        tokens.shape,
        radiusText(tokens),
        borderText(tokens),
        tokens.elevation?.let { "elevation $it" },
        tokens.opacity?.takeIf { it < 1.0 }?.let { "alpha ${trimDouble(it)}" },
        "clip".takeIf { tokens.clipsContent == true },
      )
    if (parts.isEmpty()) return null
    val paintBox = tokens.paintBox?.toAnnotationBounds()?.takeIf { it != bounds }
    return DesignAnnotation(
      kind = AnnotationKind.THEME,
      bounds = paintBox ?: bounds,
      label = parts.joinToString(" · "),
      role = role,
      detail = themeDetail(tokens, paintBox != null),
    )
  }

  /**
   * `"120×48px · pad 16.0dp · gap 8.0dp"`. Every drawable node contributes one, since nesting is
   * what makes a redline diagnosable — except a token-less wrapper that exactly reproduces its
   * nearest annotated ancestor's box.
   */
  private fun layoutAnnotation(
    tokens: ComposeSemanticsTokens?,
    bounds: AnnotationBounds,
    enclosing: AnnotationBounds?,
    role: String?,
  ): DesignAnnotation? {
    val shapesLayout =
      tokens != null &&
        (tokens.padding != null ||
          tokens.paintInset != null ||
          tokens.gap != null ||
          tokens.minWidth != null ||
          tokens.minHeight != null)
    if (bounds == enclosing && !shapesLayout) return null
    val parts =
      listOfNotNull(
        "${bounds.width}×${bounds.height}px",
        tokens?.padding?.let { insetsText("pad", it) },
        tokens?.paintInset?.let { insetsText("paint inset", it) },
        tokens?.gap?.let { "gap $it" },
        minSizeText(tokens),
      )
    return DesignAnnotation(
      kind = AnnotationKind.LAYOUT,
      bounds = bounds,
      label = parts.joinToString(" · "),
      role = role,
      detail = layoutDetail(bounds, tokens),
    )
  }

  /** `"radius 12.0dp"`, or the pixel corners a dp radius can't express, or both when both exist. */
  private fun radiusText(tokens: ComposeSemanticsTokens): String? {
    val dp = tokens.cornerRadius?.let { "radius $it" }
    val px = tokens.cornerRadiusPx?.let { "radius $it" }
    return when {
      dp != null && px != null -> "$dp ($px)"
      // Shapes no corner token describes (generic outlines) say so rather than invent a radius.
      else -> dp ?: px ?: "custom shape".takeIf { tokens.shapePath != null }
    }
  }

  private fun borderText(tokens: ComposeSemanticsTokens): String? {
    val colour = tokens.borderColor
    val width = tokens.borderWidth
    val gradient = tokens.borderGradient
    return when {
      width != null && colour != null -> "border $width $colour"
      width != null && gradient != null -> "border $width ${gradientText(gradient)}"
      colour != null -> "border $colour"
      width != null -> "border $width"
      gradient != null -> "border ${gradientText(gradient)}"
      else -> null
    }
  }

  /** `"gradient #FF6750A4→#FF625B71"`: endpoints, with any middle stops counted. */
  private fun gradientText(gradient: LayoutInspectorGradient): String {
    val colours = gradient.colors
    return when (colours.size) {
      0 -> "gradient"
      1 -> "gradient ${colours[0]}"
      2 -> "gradient ${colours[0]}→${colours[1]}"
      else -> "gradient ${colours.first()}→${colours.last()} (${colours.size} stops)"
    }
  }

  /**
   * `"pad 16.0dp"` when uniform, `"pad 8.0dp/16.0dp"` when symmetric, else four edges in CSS order
   * (top, end, bottom, start). Null when nothing is padded.
   */
  private fun insetsText(prefix: String, insets: ComposeSemanticsInsets): String? {
    val edges = listOf(insets.top, insets.end, insets.bottom, insets.start)
    if (edges.all { it == null }) return null
    val (top, end, bottom, start) = edges.map { it ?: "0.0dp" }
    return when {
      top == end && end == bottom && bottom == start -> "$prefix $top"
      top == bottom && end == start -> "$prefix $top/$end"
      else -> "$prefix $top $end $bottom $start"
    }
  }

  private fun minSizeText(tokens: ComposeSemanticsTokens?): String? {
    val width = tokens?.minWidth
    val height = tokens?.minHeight
    return when {
      width != null && height != null -> "min ${width}×${height}"
      width != null -> "min width $width"
      height != null -> "min height $height"
      else -> null
    }
  }

  /**
   * The full resolved container token set for the hover card and machine consumers, including what
   * the label has no room for.
   */
  private fun themeDetail(
    tokens: ComposeSemanticsTokens,
    paintBoxAnchored: Boolean,
  ): Map<String, String> = buildMap {
    tokens.backgroundColor?.let { put("background", it) }
    tokens.backgroundGradient?.let { put("backgroundGradient", gradientDetail(it)) }
    tokens.borderColor?.let { put("borderColor", it) }
    tokens.borderWidth?.let { put("borderWidth", it) }
    tokens.borderGradient?.let { put("borderGradient", gradientDetail(it)) }
    tokens.cornerRadius?.let { put("cornerRadius", it) }
    tokens.cornerRadiusPx?.let { put("cornerRadiusPx", it) }
    tokens.shape?.let { put("shape", it) }
    tokens.shapePath?.let { put("shapePath", it) }
    tokens.elevation?.let { put("elevation", it) }
    tokens.opacity?.let { put("opacity", trimDouble(it)) }
    tokens.clipsContent?.let { put("clipsContent", it.toString()) }
    tokens.minWidth?.let { put("minWidth", it) }
    tokens.minHeight?.let { put("minHeight", it) }
    tokens.padding?.let { insets -> insetsDetail(insets)?.let { put("padding", it) } }
    tokens.paintInset?.let { insets -> insetsDetail(insets)?.let { put("paintInset", it) } }
    tokens.gap?.let { put("gap", it) }
    // Only when the box is the paint box rather than the placement box, so ordinary containers
    // don't carry a redundant row.
    if (paintBoxAnchored) put("box", "paint")
  }

  /** Geometry first — the layout layer's whole point — then the tokens that shaped it. */
  private fun layoutDetail(
    bounds: AnnotationBounds,
    tokens: ComposeSemanticsTokens?,
  ): Map<String, String> = buildMap {
    put("width", "${bounds.width}px")
    put("height", "${bounds.height}px")
    put("x", "${bounds.x}px")
    put("y", "${bounds.y}px")
    tokens?.padding?.let { insets -> insetsDetail(insets)?.let { put("padding", it) } }
    tokens?.paintInset?.let { insets -> insetsDetail(insets)?.let { put("paintInset", it) } }
    tokens?.gap?.let { put("gap", it) }
    tokens?.minWidth?.let { put("minWidth", it) }
    tokens?.minHeight?.let { put("minHeight", it) }
    tokens?.paintBox?.let { put("paintBox", "${it.left},${it.top},${it.right},${it.bottom}") }
  }

  /** `"top 8.0dp, end 16.0dp, bottom 8.0dp, start 16.0dp"`; null when no edge resolved. */
  private fun insetsDetail(insets: ComposeSemanticsInsets): String? {
    val parts =
      listOfNotNull(
        insets.top?.let { "top $it" },
        insets.end?.let { "end $it" },
        insets.bottom?.let { "bottom $it" },
        insets.start?.let { "start $it" },
      )
    return parts.takeIf { it.isNotEmpty() }?.joinToString(", ")
  }

  /**
   * All the gradient's stops and its direction (which changes the paint), named for the three
   * obvious axes and given as unit-space endpoints otherwise.
   */
  private fun gradientDetail(gradient: LayoutInspectorGradient): String {
    val stops = gradient.stops
    val colours =
      gradient.colors.mapIndexed { index, colour ->
        val at = stops?.getOrNull(index)
        if (at == null) colour else "$colour@${trimDouble(at.toDouble())}"
      }
    return "${colours.joinToString(" → ")} (${gradientDirection(gradient)})"
  }

  private fun gradientDirection(gradient: LayoutInspectorGradient): String {
    val dx = gradient.endX - gradient.startX
    val dy = gradient.endY - gradient.startY
    val endpoints =
      "${trimDouble(gradient.startX.toDouble())},${trimDouble(gradient.startY.toDouble())}" +
        " → ${trimDouble(gradient.endX.toDouble())},${trimDouble(gradient.endY.toDouble())}"
    return when {
      dx != 0f && dy == 0f -> if (dx > 0) "horizontal" else "horizontal, reversed"
      dy != 0f && dx == 0f -> if (dy > 0) "vertical" else "vertical, reversed"
      else -> endpoints
    }
  }

  /** `1.0` → `"1"`, `0.5` → `"0.5"` — a token value, not a float dump. */
  private fun trimDouble(value: Double): String {
    val rounded = Math.round(value * 1000.0) / 1000.0
    return if (rounded == Math.floor(rounded)) rounded.toLong().toString() else rounded.toString()
  }

  /** The node's drawn text, trimmed for a legend title. */
  private fun ComposeSemanticsNode.textSnippet(): String? {
    val raw = (text ?: layoutText ?: label)?.trim()?.replace(Regex("\\s+"), " ") ?: return null
    if (raw.isEmpty()) return null
    return if (raw.length <= 32) raw else raw.take(31) + "…"
  }

  /** A face's file name rather than its full path (desktop reports absolute font paths). */
  private fun shortFace(family: String): String =
    family.substringAfterLast('/').substringAfterLast('\\').ifBlank { family }
}
