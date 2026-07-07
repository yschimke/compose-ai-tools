package ee.schimke.composeai.data.layoutinspector

import kotlin.math.abs

/**
 * Backend-agnostic model for the **layered Figma SVG** export — the design-fidelity counterpart to
 * the schematic [WireframeModel]. Where the wireframe is a flat list of stroked boxes meant to be
 * *read* (a skeleton), this model is a **nested tree of layers** meant to be *edited*: every layer
 * maps to a `<g>` in the emitted SVG (and therefore a named group/frame when Figma imports it),
 * carrying the composable name, the resolved fill/stroke/corner tokens, and — for text nodes — the
 * editable string plus its typography.
 *
 * Built from the two trees the renderer already captures per frame:
 * - [LayoutInspectorPayload] provides the **structure**: the composable
 *   [LayoutInspectorNode.component] name (retained as the layer name so a component/screen becomes
 *   a named Figma layer), the full nesting, and the modifier-derived container [tokens]
 *   (background/border colour, corner radius, shape).
 * - [ComposeSemanticsPayload] (optional) provides the **text**: a node's drawn string plus its
 *   [ComposeSemanticsTypography]/[ComposeSemanticsTextColor], matched onto the layout node with the
 *   same absolute bounds so the SVG carries editable `<text>` with the right face/size/colour.
 *
 * Pure data in root-pixel space (bounds are already absolute-to-root), so — like [WireframeModel] —
 * a single translate ([tx]/[ty]) drops it into the padded canvas. All dp/sp token values are
 * converted to px here (× density) so the renderer never has to know about density.
 */
data class FigmaSvgColor(
  /** `#RRGGBB` — the opaque RGB channel, ready to drop into an SVG `fill`/`stroke`. */
  val hex: String,
  /** Alpha in `0.0..1.0`; emitted as `fill-opacity`/`stroke-opacity` only when < 1. */
  val opacity: Double = 1.0,
  /**
   * The theme role this colour resolves to (`"primary"`, `"surface"`, …) when a colour-name map was
   * supplied, else null. Retained on the layer so the Figma import — and the sibling
   * `figma-variables.json` — can bind the fill to a named variable rather than a raw literal.
   */
  val tokenName: String? = null,
)

/** Editable text carried by a leaf layer, with the typography needed to reproduce its face. */
data class FigmaSvgText(
  val content: String,
  /** Resolved size in px (sp × density), or null when the capture didn't resolve one. */
  val fontSizePx: Double? = null,
  /** Resolved family/face identity as captured (a generic name or a font handle). */
  val fontFamily: String? = null,
  /** Numeric weight (`400`, `700`, …). */
  val fontWeight: Int? = null,
  val italic: Boolean = false,
  val color: FigmaSvgColor? = null,
  /**
   * Resolved line height in px (sp × density, or em × font size), when the capture resolved one.
   * Used to place the `<text>` baseline within its box — the extra leading beyond the font's own
   * ascent/descent is split above the first line, so the baseline sits lower than a bare
   * ascent-from-top would put it.
   */
  val lineHeightPx: Double? = null,
)

/**
 * A font face to embed in the export as an SVG `@font-face` so the `<text>` renders with the real
 * typeface — closing the "browser/Figma substitutes its own `sans-serif`" gap. [dataBase64] is the
 * base64 of the face's bytes in [format]: `woff2` for the Google-Fonts fetch (smallest, and what
 * the SVG's consumers read natively), or `truetype`/`opentype` when embedding the exact font *file*
 * the render loaded (a downloaded / bundled / custom / variable face the capture recorded by path).
 */
data class FigmaSvgFontFace(
  val family: String,
  val weight: Int,
  val italic: Boolean,
  val dataBase64: String,
  val format: String = "woff2",
)

/** Background-free raster standing in for an opaque, un-vectorisable subtree. */
data class FigmaSvgRaster(val href: String)

/** An opaque node to rasterise: its nodeId, `<image>` href, and bounds to capture. */
data class FigmaSvgRasterTarget(
  val nodeId: String,
  val href: String,
  val left: Int,
  val top: Int,
  val right: Int,
  val bottom: Int,
)

/**
 * One layer in the export tree ⇒ one `<g>` in the SVG. A layer may draw a filled/stroked rectangle
 * (from container tokens), hold editable text, both, or neither (a pure grouping layer for
 * nesting).
 */
data class FigmaSvgLayer(
  /** Layer name — the composable name (plus a role/label hint when it disambiguates). */
  val name: String,
  val left: Int,
  val top: Int,
  val right: Int,
  val bottom: Int,
  val fill: FigmaSvgColor? = null,
  val stroke: FigmaSvgColor? = null,
  /**
   * Stroke width in px for [stroke]. The layout inspector doesn't capture a `Modifier.border`
   * width, so this defaults to one density-independent pixel scaled into the render's px space
   * (`density`) — the width of a Material hairline outline (`OutlinedCard`/`OutlinedButton`/chip),
   * which is 1dp. At the desktop 2× capture density a 1dp border is 2px, so a hardcoded `1` drew
   * every outline at half width and lost fidelity along the whole edge; scaling by density matches
   * the render. `1.0` (the data-class default) keeps density-1 callers and fixtures unchanged.
   */
  val strokeWidthPx: Double = 1.0,
  /**
   * Per-corner radius in px, in the order top-left, top-right, bottom-right, bottom-left. `null`
   * means a sharp rectangle. A uniform radius still lists four equal values so the renderer has one
   * path to walk. A [circle] layer leaves this null and is drawn as a max-radius rounded rect.
   */
  val cornerRadiiPx: List<Double>? = null,
  /** True for a `CircleShape`/all-50% shape — drawn with radius = min(w,h)/2. */
  val circle: Boolean = false,
  /**
   * True for a `CutCornerShape` — the corner sizes in [cornerRadiiPx] are drawn as straight
   * chamfers (a bevelled corner) rather than arcs. Mutually exclusive with [circle].
   */
  val cut: Boolean = false,
  val text: FigmaSvgText? = null,
  /** Set when this layer is an opaque component rendered as an `<image>`. */
  val raster: FigmaSvgRaster? = null,
  val children: List<FigmaSvgLayer> = emptyList(),
) {
  val width: Int
    get() = (right - left).coerceAtLeast(0)

  val height: Int
    get() = (bottom - top).coerceAtLeast(0)

  /** True when the layer draws pixels itself (vs. a pure grouping container). */
  val paints: Boolean
    get() = fill != null || stroke != null || text != null || raster != null
}

/**
 * The whole export: a single [root] layer (the padded-canvas frame) whose [FigmaSvgLayer.children]
 * mirror the layout tree, plus the canvas extent. [tx]/[ty] translate root-pixel space into the
 * padded canvas exactly as [WireframeModel] does, so a layer at absolute `(left, top)` is drawn at
 * `(left + tx, top + ty)`.
 */
data class FigmaSvgModel(
  val root: FigmaSvgLayer,
  val minX: Int,
  val minY: Int,
  val width: Int,
  val height: Int,
  val padding: Int,
  /** Opaque nodes emitted as `<image>` — each needs a raster captured at its bounds. */
  val rasterTargets: List<FigmaSvgRasterTarget> = emptyList(),
) {
  val tx: Int
    get() = padding - minX

  val ty: Int
    get() = padding - minY

  companion object {
    /** Default transparent margin (px) around the diagram extent. */
    const val DEFAULT_PADDING: Int = 16

    /**
     * Composable-name fragments exported as opaque `<image>` placeholders (opt in via `from`).
     *
     * Two families live here. First, the obviously un-vectorisable leaves — bitmaps (`Image`),
     * vector assets (`Icon`), custom `Canvas`/chart drawing. Second, **Material components whose
     * chrome is drawn imperatively** (a `drawWithContent`/`Canvas` inside the component, not a
     * `Modifier.background`/`border` the layout inspector can read as a container token): the
     * filled & outlined `TextField` container + indicator, and the `Slider` track + thumb. Their
     * fills never surface as tokens, so a token-driven vector export drops them entirely — the
     * filled `TextField` was the worst sticker in the fidelity audit (dark: 66%, container box
     * missing). Rasterising the component's measure-policy node (`TextFieldMeasurePolicy`,
     * `OutlinedTextFieldMeasurePolicy`, `SliderKt`) crops its faithful pixels out of the frame
     * while the rest of the screen stays editable vector — the sanctioned hybrid split, tuned by
     * the fidelity diff (render vs. SVG) rather than guessed per-component.
     */
    val DEFAULT_RASTER_COMPONENTS: Set<String> =
      setOf(
        "Image",
        "AsyncImage",
        "Icon",
        "Canvas",
        "Chart",
        "Graph",
        "Map",
        "Video",
        "AndroidView",
        "Painter",
        "TextField",
        "Slider",
      )

    /** Default `<image>` href for an opaque node: a per-node PNG under `figma-raster/`. */
    fun defaultRasterHref(nodeId: String): String {
      val safe = nodeId.map { if (it.isLetterOrDigit()) it else '_' }.joinToString("")
      return "figma-raster/$safe.png"
    }

    /**
     * Per-edge slack (px) when matching a semantics text node to its layout layer. The two
     * producers round the same float differently (truncate vs. round), so an exact match drops text
     * on fractional pixels; 2px absorbs that skew without bleeding onto a genuinely different node.
     */
    const val BOUNDS_TOLERANCE_PX: Int = 2

    /**
     * Builds the export model.
     *
     * @param layout the layout-inspector tree — the source of structure, composable names, and
     *   container tokens.
     * @param semantics optional semantics tree whose text nodes are matched by exact bounds to
     *   attach editable text + typography onto the corresponding layout layer.
     * @param colorNames maps a normalized ARGB colour string (as tokens carry it, `#AARRGGBB`,
     *   upper case) to a theme role name; a matched fill/stroke carries the name for variable
     *   binding.
     * @param density px-per-dp of the captured frame, used to convert dp corner radii and sp font
     *   sizes into the px coordinate space the bounds live in.
     * @param padding transparent margin around the extent.
     * @param rasterComponents opaque component name-fragments; empty (default) = vector-only.
     */
    fun from(
      layout: LayoutInspectorPayload,
      semantics: ComposeSemanticsPayload? = null,
      colorNames: Map<String, String> = emptyMap(),
      density: Float = 1f,
      padding: Int = DEFAULT_PADDING,
      rasterComponents: Set<String> = emptySet(),
      rasterHref: (nodeId: String) -> String = ::defaultRasterHref,
    ): FigmaSvgModel {
      val textByNodeId =
        semantics?.let { assignTextToLayers(layout.root, it, density) } ?: emptyMap()
      val names = colorNames.mapKeys { it.key.uppercase() }
      val ctx = BuildContext(textByNodeId, names, density, rasterComponents, rasterHref)
      val rootLayer = layout.root.toLayer(ctx)
      // No drawing layer (a tree of pure grouping nodes) → a minimal padding-square canvas,
      // matching
      // the wireframe's empty-tree convention.
      val extent = rootLayer.extent() ?: Extent(0, 0, 0, 0)
      return FigmaSvgModel(
        root = rootLayer,
        minX = extent.minX,
        minY = extent.minY,
        width = (extent.maxX - extent.minX) + padding * 2,
        height = (extent.maxY - extent.minY) + padding * 2,
        padding = padding,
        rasterTargets = ctx.rasterTargets,
      )
    }

    /** Build inputs + the accumulating raster-target list, threaded through the walk. */
    private class BuildContext(
      val textByNodeId: Map<String, FigmaSvgText>,
      val colorNames: Map<String, String>,
      val density: Float,
      val rasterComponents: Set<String>,
      val rasterHref: (String) -> String,
      val rasterTargets: MutableList<FigmaSvgRasterTarget> = mutableListOf(),
    )

    private fun LayoutInspectorNode.toLayer(ctx: BuildContext): FigmaSvgLayer {
      // Opaque components can't be vectorised — emit an <image> and drop the subtree.
      if (isOpaque(ctx.rasterComponents)) {
        val href = ctx.rasterHref(nodeId)
        ctx.rasterTargets.add(
          FigmaSvgRasterTarget(nodeId, href, bounds.left, bounds.top, bounds.right, bounds.bottom)
        )
        return FigmaSvgLayer(
          name = layerName(),
          left = bounds.left,
          top = bounds.top,
          right = bounds.right,
          bottom = bounds.bottom,
          raster = FigmaSvgRaster(href),
        )
      }
      val fill = tokens?.backgroundColor?.let { argbToColor(it, ctx.colorNames) }
      val stroke = tokens?.borderColor?.let { argbToColor(it, ctx.colorNames) }
      val circle = tokens?.shape == "circle"
      // A `CutCornerShape` reports its corner sizes on `cornerRadius`/`cornerRadiusPx` like a
      // rounded
      // shape, plus a `shape="cut"` descriptor; the renderer draws those sizes as straight
      // chamfers.
      val cut = tokens?.shape == "cut"
      val corners =
        if (circle) null
        else
          tokens?.cornerRadius?.let { parseCornersPx(it, ctx.density) }
            // A `RoundedCornerShape(<px>f)` has no dp radius; its raw-pixel corners ride on
            // `cornerRadiusPx` and map straight to layer space with no density conversion.
            ?: tokens?.cornerRadiusPx?.let { parseRawCornersPx(it) }
      return FigmaSvgLayer(
        name = layerName(),
        left = bounds.left,
        top = bounds.top,
        right = bounds.right,
        bottom = bounds.bottom,
        fill = fill,
        stroke = stroke,
        // A Material outline is a 1dp hairline; the inspector doesn't carry its width, so scale a
        // single dp into the render's px space. `coerceAtLeast(1.0)` keeps a visible hairline at
        // density < 1.
        strokeWidthPx = if (stroke != null) ctx.density.toDouble().coerceAtLeast(1.0) else 1.0,
        cornerRadiiPx = corners,
        circle = circle,
        cut = cut,
        text = ctx.textByNodeId[nodeId],
        children = children.map { it.toLayer(ctx) },
      )
    }

    /** True when the composable name matches a [rasterComponents] fragment. */
    private fun LayoutInspectorNode.isOpaque(rasterComponents: Set<String>): Boolean =
      rasterComponents.any {
        component.contains(it, ignoreCase = true)
      }

    private fun LayoutInspectorNode.layerName(): String = component.ifBlank { "Layer" }

    /**
     * Assigns each semantics text node to the single best-matching layout node, keyed by layout
     * [LayoutInspectorNode.nodeId]. Matching is by bounds with a small tolerance rather than exact
     * equality: the two producers round the same underlying float differently (semantics truncates
     * to `Int`, layout rounds), so text laid out on fractional pixels — common at non-1 densities
     * or with centring/dp offsets — would otherwise miss its layer and silently drop out of the
     * export. Each layout node keeps only its closest text and each text lands on its closest
     * layout node (favouring the tight `Text` leaf over a looser wrapper), so text is neither
     * dropped nor duplicated across nested layers that share bounds.
     */
    private fun assignTextToLayers(
      layoutRoot: LayoutInspectorNode,
      semantics: ComposeSemanticsPayload,
      density: Float,
    ): Map<String, FigmaSvgText> {
      val candidates = mutableListOf<Pair<String, IntArray>>()
      fun collect(n: LayoutInspectorNode) {
        candidates.add(
          n.nodeId to intArrayOf(n.bounds.left, n.bounds.top, n.bounds.right, n.bounds.bottom)
        )
        n.children.forEach(::collect)
      }
      collect(layoutRoot)

      val textByNodeId = HashMap<String, FigmaSvgText>()
      val bestDistForNode = HashMap<String, Int>()
      fun walk(node: ComposeSemanticsNode) {
        val content =
          node.text?.takeIf { it.isNotBlank() } ?: node.layoutText?.takeIf { it.isNotBlank() }
        val b = parseBoundsList(node.boundsInRoot)
        if (content != null && b != null) {
          var bestId: String? = null
          var bestDist = Int.MAX_VALUE
          for ((id, lb) in candidates) {
            val d0 = abs(lb[0] - b[0])
            val d1 = abs(lb[1] - b[1])
            val d2 = abs(lb[2] - b[2])
            val d3 = abs(lb[3] - b[3])
            if (maxOf(d0, d1, d2, d3) <= BOUNDS_TOLERANCE_PX) {
              val d = d0 + d1 + d2 + d3
              if (d < bestDist) {
                bestDist = d
                bestId = id
              }
            }
          }
          val chosen = bestId
          if (chosen != null && bestDist < (bestDistForNode[chosen] ?: Int.MAX_VALUE)) {
            textByNodeId[chosen] = textFrom(node, content, density)
            bestDistForNode[chosen] = bestDist
          }
        }
        node.children.forEach(::walk)
      }
      walk(semantics.root)
      return textByNodeId
    }

    private fun textFrom(
      node: ComposeSemanticsNode,
      content: String,
      density: Float,
    ): FigmaSvgText =
      FigmaSvgText(
        content = content,
        fontSizePx = node.typography?.fontSize?.let { spToPx(it, density) },
        fontFamily = node.typography?.fontFamily,
        fontWeight = node.typography?.fontWeight,
        italic = node.typography?.fontStyle == "italic",
        color = node.textColor?.foreground?.let { argbToColor(it, emptyMap()) },
        lineHeightPx =
          node.typography?.lineHeight?.let { lineHeightToPx(it, node.typography.fontSize, density) },
      )

    /**
     * Resolves a captured line-height string to px. `"20.0sp"` → sp × density; `"1.4em"` → em ×
     * resolved font size (in px). Returns null when neither the value nor (for `em`) the font size
     * parses.
     */
    fun lineHeightToPx(value: String, fontSize: String?, density: Float): Double? {
      val trimmed = value.trim()
      return when {
        trimmed.endsWith("sp") -> spToPx(trimmed, density)
        trimmed.endsWith("em") -> {
          val em = trimmed.removeSuffix("em").trim().toDoubleOrNull() ?: return null
          val fontPx = fontSize?.let { spToPx(it, density) } ?: return null
          em * fontPx
        }
        else -> null
      }
    }

    private data class Extent(val minX: Int, val minY: Int, val maxX: Int, val maxY: Int)

    /** Union of every drawing layer's bounds (grouping-only layers don't constrain the canvas). */
    private fun FigmaSvgLayer.extent(): Extent? {
      var acc: Extent? = null
      fun merge(l: FigmaSvgLayer) {
        if (l.paints && l.width > 0 && l.height > 0) {
          acc =
            acc?.let {
              Extent(
                minOf(it.minX, l.left),
                minOf(it.minY, l.top),
                maxOf(it.maxX, l.right),
                maxOf(it.maxY, l.bottom),
              )
            } ?: Extent(l.left, l.top, l.right, l.bottom)
        }
        l.children.forEach(::merge)
      }
      merge(this)
      return acc
    }

    /** `"left,top,right,bottom"` → `[l,t,r,b]`, or null if malformed. */
    private fun parseBoundsList(s: String?): IntArray? {
      if (s == null) return null
      val parts = s.split(",")
      if (parts.size != 4) return null
      val ints = parts.map { it.trim().toIntOrNull() ?: return null }
      return intArrayOf(ints[0], ints[1], ints[2], ints[3])
    }

    /**
     * Parses a token corner-radius string — `"12.0dp"` (uniform) or `"12.0dp,8.0dp,0.0dp,0.0dp"`
     * (top-start → bottom-start) — into four px radii (top-left, top-right, bottom-right,
     * bottom-left) at [density]. Returns null when the value can't be read as dp (e.g. a px corner
     * the resolver left unresolved), so the layer falls back to a sharp rectangle.
     */
    fun parseCornersPx(value: String, density: Float): List<Double>? {
      val parts = value.split(",").map { it.trim() }
      val dps =
        when (parts.size) {
          1 -> parts[0].dpToPxOrNull(density)?.let { listOf(it, it, it, it) }
          4 -> {
            val px = parts.map { it.dpToPxOrNull(density) }
            if (px.any { it == null }) null else px.map { it!! }
          }
          else -> null
        }
      // Token order is top-start, top-end, bottom-end, bottom-start (LTR) — which is already
      // top-left, top-right, bottom-right, bottom-left. Keep it.
      return dps?.takeIf { it.any { r -> r > 0.0 } }
    }

    /**
     * Parses a `cornerRadiusPx` token — `"20.0px"` (uniform) or `"20.0px,10.0px,0.0px,0.0px"`
     * (top-start, top-end, bottom-end, bottom-left) — into layer-space pixel radii. Unlike
     * [parseCornersPx] these are already pixels (a `RoundedCornerShape(<px>f)` corner), so there's
     * no density conversion. Returns null when unreadable or all corners are zero.
     */
    fun parseRawCornersPx(value: String): List<Double>? {
      val parts = value.split(",").map { it.trim().removeSuffix("px") }
      val px =
        when (parts.size) {
          1 -> parts[0].toDoubleOrNull()?.let { listOf(it, it, it, it) }
          4 -> {
            val vals = parts.map { it.toDoubleOrNull() }
            if (vals.any { it == null }) null else vals.map { it!! }
          }
          else -> null
        }
      return px?.takeIf { it.any { r -> r > 0.0 } }
    }

    private fun String.dpToPxOrNull(density: Float): Double? {
      val n = removeSuffix("dp").trim().toDoubleOrNull() ?: return null
      return n * density
    }

    /** `"22.0sp"` → px at [density]. Font scale is intentionally not applied (capture is 1.0). */
    fun spToPx(value: String, density: Float): Double? {
      val n = value.removeSuffix("sp").trim().toDoubleOrNull() ?: return null
      return n * density
    }

    /**
     * Parses an `#AARRGGBB` (or `#RRGGBB`) token colour into a [FigmaSvgColor] with the alpha split
     * out into [FigmaSvgColor.opacity] and the theme role name attached when [colorNames] knows it.
     * Returns null for an unparseable value.
     */
    fun argbToColor(argb: String, colorNames: Map<String, String>): FigmaSvgColor? {
      val hex = argb.removePrefix("#")
      val (rgb, opacity) =
        when (hex.length) {
          8 -> {
            val a = hex.substring(0, 2).toIntOrNull(16) ?: return null
            hex.substring(2) to (a / 255.0)
          }
          6 -> hex to 1.0
          else -> return null
        }
      if (rgb.toLongOrNull(16) == null) return null
      return FigmaSvgColor(
        hex = "#${rgb.uppercase()}",
        opacity = opacity,
        tokenName = colorNames[argb.uppercase()],
      )
    }
  }
}
