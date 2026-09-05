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
 * Derive the viewer's **typography**, **theme** and **layout** inspection layers from a render's
 * own capture.
 *
 * The compare page reads [DesignAnnotation]s a producer authored into a bundle
 * ([ServeAnnotationStore]) — the spec side of a design ↔ code comparison. The viewer needs the same
 * shape for the *code* side, and the daemon already captures it: every semantics node carries its
 * resolved typographic identity ([ComposeSemanticsNode.typography] — the size, face, weight, line
 * height, and variation axes the render actually resolved), while [ThemePayload.consumers]
 * attributes that resolved style back to its Material typography role. Projecting those onto
 * [DesignAnnotation] means the viewer draws them with exactly the numbered-box + legend idiom the
 * compare page already uses, with no second overlay model to maintain.
 *
 * **Two trees, deliberately.** Typography exists only on `compose/semantics`, so that layer is
 * walked there. The container layers read `layout/inspector` instead, because that is the tree the
 * facts actually live in: `LayoutInspectorProduct` is the canonical home for
 * [ComposeSemanticsTokens] (they are modifier-derived, and that product models the modifier chain;
 * `compose/semantics` merely mirrors them), and it walks every `LayoutNode` rather than only the
 * nodes that carry semantics. A `Column(Modifier.padding(16.dp), Arrangement.spacedBy(8.dp))`
 * declares no semantics at all, so on the semantics tree the very padding and gap these layers
 * advertise are invisible. A capture without the layout product falls back to the semantics tree's
 * mirrored tokens — fewer boxes, same projection — rather than dropping the layers.
 *
 * The **layout** layer is the code-side counterpart of the producer-authored
 * [AnnotationKind.LAYOUT] the compare page has always drawn on the reference side (issue #4328).
 * Until it existed the viewer could only inspect *paint* — fill, radius, type — and the redline
 * values a layout diff is actually argued in (the box's own size, its padding per edge, the
 * arrangement gap, a `defaultMinSize` floor) had no surface at all. Those are the same tokens the
 * published layout wireframe (`render-layout-wireframe-svg.mjs`) draws off the same tree, so the
 * two agree by construction.
 *
 * No typography metrics are re-measured here, and no geometry is inferred: every number is one the
 * capture already resolved. Material roles use the theme producer's resolved-value attribution and
 * may therefore contain multiple honest candidates when two roles resolve identically. A node that
 * resolved no typography (or no container tokens) simply contributes no annotation to that layer.
 */
object ServeDesignAnnotations {

  /**
   * The typography, theme and layout annotations for one render, in depth-first order (the order
   * the legend numbers them in).
   *
   * Bounds are absolute-to-root **render pixels** — `boundsInRoot` on the semantics tree,
   * [LayoutInspectorNode.bounds] on the layout tree, and the node's captured paint box where the
   * theme layer has one. All are the space the served PNG is in, so the viewer scales one layer to
   * the on-screen image and is done. A node with malformed or zero-area bounds is skipped; it can't
   * be drawn and would only produce a legend row pointing at nothing.
   *
   * `enclosing` threads the nearest **annotated** layout box down the walk rather than the literal
   * parent, so a chain of wrappers that all reproduce one box collapses to one rectangle instead of
   * comparing each node only against the one directly above it and emitting the whole stack.
   */
  fun annotations(
    payload: ComposeSemanticsPayload,
    theme: ThemePayload? = null,
    layout: LayoutInspectorPayload? = null,
  ): List<DesignAnnotation> {
    val out = mutableListOf<DesignAnnotation>()
    val typographyTokensByNode = theme.typographyTokensByNode()
    // The semantics walk always carries typography; it carries the container layers too only when
    // there is no layout tree to take them from, so the two trees can never both describe one node.
    val containersFromSemantics = layout == null
    fun walkSemantics(node: ComposeSemanticsNode, enclosing: AnnotationBounds?) {
      // An unplaced node was measured but never positioned, so it draws nothing and its bounds
      // read as the frame's ORIGIN rather than as "nowhere" — the same rule the layout walk below
      // applies, and the same reason. Wear's `AlertDialogContent` is the case that found this: it
      // subcomposes a full trial copy of the dialog to decide whether the content has to scroll,
      // and that copy reached the typography layer as a second title stacked in the top-left
      // corner (yschimke/wear-m3-catalog#77). The whole subtree goes with it: nothing under a node
      // that was never placed is on the frame either.
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
        // An unplaced node was measured but never positioned, so its bounds describe nowhere on
        // the frame — and nothing beneath it is on the frame either, whatever its own `placed`
        // says. Suppressing only this node's box left a descendant free to draw one.
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
   * `"14.0sp/20.0sp · Roboto · 500 · italic"` — the one-line spec a designer reads off a type ramp,
   * dropping whatever the render left ambiguous. Null when the node resolved no size *and* no face:
   * an annotation whose label would be empty is not worth a box.
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
    val parts =
      listOfNotNull(
        materialThemeTokens
          .takeIf { it.isNotEmpty() }
          ?.joinToString(" / ") { "MaterialTheme.typography.$it" },
        size,
        face,
        type.fontWeight?.toString(),
        type.fontStyle?.takeIf { it != "normal" },
        type.letterSpacing?.let { "tracking $it" },
        type.textAlign?.takeIf { it != "start" },
      )
    if (parts.isEmpty()) return null
    return DesignAnnotation(
      kind = AnnotationKind.TYPOGRAPHY,
      bounds = bounds.toAnnotationBounds(),
      label = parts.joinToString(" · "),
      role = node.textSnippet(),
      detail = typographyDetail(type, node, materialThemeTokens),
    )
  }

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
    type.textAlign?.let { put("textAlign", it) }
    node.textColor?.foreground?.let { put("color", it) }
    node.textOverflow?.lineCount?.let { put("lines", it.toString()) }
    node.textOverflow?.maxLines?.let { put("maxLines", it.toString()) }
  }

  /**
   * Theme consumers contain colour, typography, and shape names in one flat list. Intersecting with
   * the payload's resolved typography keys retains only type-scale roles while preserving the
   * consumer's stable attribution order. Both products use the same Compose `SemanticsNode.id`.
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
   * `"fill #FF6750A4 · radius 12.0dp · border 1.0dp #FF79747E · elevation 6.0dp"` — the resolved
   * theme attributes of a container. Null for the common node that declares none of them (pure
   * layout / text nodes).
   *
   * Anchored to [ComposeSemanticsTokens.paintBox] when the capture read one, falling back to the
   * node's placement bounds. The two differ whenever a `padding` sits before the paint modifiers in
   * the chain (`padding(4.dp).clip(CircleShape).background(…)`), and the box being described is the
   * one the fill and ring were actually drawn into — outlining the placement box instead reports a
   * radius against geometry that was never painted.
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
   * `"120×48px · pad 16.0dp · gap 8.0dp"` — the box a layout diff is argued in.
   *
   * Every node with a drawable box contributes one, because a redline's value is the *nesting*: a
   * component that measures 4px wider than the kit is only diagnosable when the slot boxes inside
   * it are on screen too. The one exclusion is a node that exactly reproduces its nearest annotated
   * ancestor's box **and** declares no layout tokens of its own — a wrapper that adds nothing but a
   * second rectangle on the same pixels and a legend row pointing at the row above it.
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
      // A shape none of the corner tokens could describe (an `Outline.Generic` morph/star): say so
      // rather than print a radius the render does not have.
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

  /**
   * `"gradient #FF6750A4→#FF625B71"` — a linear brush named by its endpoints rather than the bare
   * word "gradient" the layer used to print, which told a reader nothing they couldn't already see.
   * More than two stops keep the ends and count the middle.
   */
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
   * `"pad 16.0dp"` when every edge agrees, `"pad 8.0dp/16.0dp"` for the symmetric vertical/
   * horizontal case, else all four edges in CSS order (top, end, bottom, start) so an asymmetric
   * inset stays readable on one line. Null for an inset that pads nothing.
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
   * The whole resolved container token set, not the eight fields this used to carry (issue #4328).
   * The hover card and any machine consumer read this map, so a token the capture resolved and the
   * label had no room for — the gradient's stops, the shadow, the effective alpha, the clip — is
   * dropped here or nowhere.
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
    // Which rectangle the numbered box is on, present only when it is NOT the node's placement box
    // — otherwise every ordinary container would carry a `box placement` row saying nothing. The
    // padded paint chain this exists for is exactly the case where the label's radius and the
    // Layout layer's size describe two different rectangles, and nothing else says so.
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
   * All the gradient's stops (the label only had room for its endpoints) **and its direction**.
   *
   * The direction is not decoration: two brushes with identical colours and stops paint visibly
   * differently when one runs left-to-right and the other top-to-bottom, so a detail map that
   * serialised only the colours could not tell them apart — and this map is what a machine consumer
   * diffs. Named where the axis is one of the three obvious ones, and given as its unit-space
   * endpoints otherwise.
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

  /**
   * The node's drawn text, trimmed to a legend-sized handle. The legend shows this as the
   * annotation's title, so a whole paragraph would push the spec — the thing being inspected — off
   * the row.
   */
  private fun ComposeSemanticsNode.textSnippet(): String? {
    val raw = (text ?: layoutText ?: label)?.trim()?.replace(Regex("\\s+"), " ") ?: return null
    if (raw.isEmpty()) return null
    return if (raw.length <= 32) raw else raw.take(31) + "…"
  }

  /**
   * A resolved face identity is whatever handle the platform exposes — a generic name
   * (`"sans-serif"`), but on desktop routinely an absolute font-file path. Show the file's own name
   * so the legend reads `Roboto-Medium.ttf` rather than 90 characters of directory.
   */
  private fun shortFace(family: String): String =
    family.substringAfterLast('/').substringAfterLast('\\').ifBlank { family }
}
