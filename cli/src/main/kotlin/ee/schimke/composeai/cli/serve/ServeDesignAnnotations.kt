package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.data.layoutinspector.ComposeSemanticsNode
import ee.schimke.composeai.data.layoutinspector.ComposeSemanticsPayload
import ee.schimke.composeai.data.layoutinspector.ComposeSemanticsTokens
import ee.schimke.composeai.data.layoutinspector.ComposeSemanticsTypography
import ee.schimke.composeai.data.layoutinspector.SlotBounds
import ee.schimke.composeai.data.theme.ThemePayload

/**
 * Derive the viewer's **typography** and **theme** inspection layers from a render's own
 * `compose/semantics` tree.
 *
 * The compare page reads [DesignAnnotation]s a producer authored into a bundle
 * ([ServeAnnotationStore]) — the spec side of a design ↔ code comparison. The viewer needs the same
 * shape for the *code* side, and the daemon already captures it: every semantics node carries its
 * resolved typographic identity ([ComposeSemanticsNode.typography] — the size, face, weight, line
 * height, and variation axes the render actually resolved), while [ThemePayload.consumers]
 * attributes that resolved style back to its Material typography role. Each semantics node also
 * carries its resolved container tokens ([ComposeSemanticsNode.tokens] — fill / border colour,
 * corner radius, shape). Projecting those onto [DesignAnnotation] means the viewer draws them with
 * exactly the numbered-box + legend idiom the compare page already uses, with no second overlay
 * model to maintain.
 *
 * No typography metrics are re-measured here. Material roles use the theme producer's
 * resolved-value attribution and may therefore contain multiple honest candidates when two roles
 * resolve identically. A node that resolved no typography (or no container tokens) simply
 * contributes no annotation to that layer.
 */
object ServeDesignAnnotations {

  /**
   * The typography + theme annotations for [payload]'s tree, in depth-first order (the order the
   * legend numbers them in).
   *
   * Bounds are the nodes' `boundsInRoot` — absolute-to-root **render pixels**, the same space the
   * served PNG is in, so the viewer scales one layer to the on-screen image and is done. A node
   * with malformed or zero-area bounds is skipped; it can't be drawn and would only produce a
   * legend row pointing at nothing.
   */
  fun annotations(
    payload: ComposeSemanticsPayload,
    theme: ThemePayload? = null,
  ): List<DesignAnnotation> {
    val out = mutableListOf<DesignAnnotation>()
    val typographyTokensByNode = theme.typographyTokensByNode()
    fun walk(node: ComposeSemanticsNode) {
      val bounds = SlotBounds.parse(node.boundsInRoot)?.takeIf { it.hasArea() }
      if (bounds != null) {
        typographyAnnotation(node, bounds, typographyTokensByNode[node.nodeId].orEmpty())
          ?.let(out::add)
        themeAnnotation(node, bounds)?.let(out::add)
      }
      node.children.forEach(::walk)
    }
    walk(payload.root)
    return out
  }

  private fun SlotBounds.hasArea(): Boolean = right > left && bottom > top

  private fun SlotBounds.toAnnotationBounds(): AnnotationBounds =
    AnnotationBounds(x = left, y = top, width = right - left, height = bottom - top)

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
   * `"fill #FF6750A4 · radius 12.0dp · border 1.0dp #FF79747E"` — the resolved theme attributes of
   * a container. Null for the common node that declares none of them (pure layout / text nodes).
   */
  private fun themeAnnotation(node: ComposeSemanticsNode, bounds: SlotBounds): DesignAnnotation? {
    val tokens = node.tokens ?: return null
    val radius = tokens.cornerRadius ?: tokens.cornerRadiusPx
    val parts =
      listOfNotNull(
        tokens.backgroundColor?.let { "fill $it" },
        tokens.backgroundGradient?.let { "fill gradient" },
        tokens.shape,
        radius?.let { "radius $it" },
        borderText(tokens),
      )
    if (parts.isEmpty()) return null
    return DesignAnnotation(
      kind = AnnotationKind.THEME,
      bounds = bounds.toAnnotationBounds(),
      label = parts.joinToString(" · "),
      role = node.role ?: node.testTag ?: node.textSnippet(),
      detail = themeDetail(tokens),
    )
  }

  private fun borderText(tokens: ComposeSemanticsTokens): String? {
    val colour = tokens.borderColor
    val width = tokens.borderWidth
    return when {
      width != null && colour != null -> "border $width $colour"
      colour != null -> "border $colour"
      width != null -> "border $width"
      tokens.borderGradient != null -> "border gradient"
      else -> null
    }
  }

  private fun themeDetail(tokens: ComposeSemanticsTokens): Map<String, String> = buildMap {
    tokens.backgroundColor?.let { put("background", it) }
    tokens.borderColor?.let { put("borderColor", it) }
    tokens.borderWidth?.let { put("borderWidth", it) }
    tokens.cornerRadius?.let { put("cornerRadius", it) }
    tokens.cornerRadiusPx?.let { put("cornerRadiusPx", it) }
    tokens.shape?.let { put("shape", it) }
    tokens.minWidth?.let { put("minWidth", it) }
    tokens.minHeight?.let { put("minHeight", it) }
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
