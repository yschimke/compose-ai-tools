package ee.schimke.composeai.data.layoutinspector

import kotlin.math.abs
import kotlin.math.roundToInt

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
  /**
   * Resolved letter spacing in px (sp × density, or em × font size), when the capture resolved one.
   * Emitted as SVG `letter-spacing` so the `<text>`'s glyph advances match the render — without it
   * a browser lays the run out with the font's natural advances, so a non-zero tracked run
   * (Material label/body text carries `0.1–0.5sp`) drifts progressively across the line and never
   * registers.
   */
  val letterSpacingPx: Double? = null,
  /**
   * Per-line runs for wrapped text, in px relative to the layer's top-left, in draw order. When
   * present (2+ lines) the renderer emits one positioned `<tspan>` per line instead of a single
   * baseline — so text wraps exactly where the render wrapped it. Null for single-line text.
   */
  val lines: List<FigmaSvgTextLine>? = null,
)

/** One laid-out line of a wrapped [FigmaSvgText], px offsets from the text layer's top-left. */
data class FigmaSvgTextLine(val content: String, val left: Int, val baseline: Int)

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

/**
 * A raster drawn beneath a layer's content ([FigmaSvgLayer.background]) — the pixels of a
 * `Modifier.drawBehind {…}` cropped to the drawn region, which may be tighter than the layer's own
 * box (a padded `Spacer` paints only the bar). Carries its own bounds so the `<image>` lands on the
 * drawn region rather than the layer box.
 */
data class FigmaSvgBackgroundRaster(
  val href: String,
  val left: Int,
  val top: Int,
  val right: Int,
  val bottom: Int,
) {
  val width: Int
    get() = (right - left).coerceAtLeast(0)

  val height: Int
    get() = (bottom - top).coerceAtLeast(0)
}

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
  /**
   * A raster `<image>` drawn *beneath* this layer's own shape/text/children — the pixels of an
   * imperative `Modifier.drawBehind {…}` (a progress track, a slider groove, a custom-drawn
   * background) the token export can't vectorise. Kept separate from [raster] (a whole-node opaque
   * leaf) so a drawn *container* — `Box(Modifier.drawBehind {…}) { Text(…) }` — carries its custom
   * background as its own layer while its text/children stay editable vector layers on top.
   */
  val background: FigmaSvgBackgroundRaster? = null,
  /**
   * Shadow elevation in px (dp × density) for a Material-elevated surface (`Surface`/`Card`/`FAB`,
   * captured from `graphicsLayer { shadowElevation }`). `0.0` casts no shadow. The renderer turns a
   * positive value into an SVG `feDropShadow` on this layer's group so the elevated surface carries
   * its drop shadow instead of reading as a flat fill against the render.
   */
  val elevationPx: Double = 0.0,
  /**
   * Wear curved text (a `CurvedLayout`/`TimeText` clock) carried on this layer — drawn as an SVG
   * `<textPath>` along its baseline arc. Empty for the common straight-text/no-text case.
   */
  val curvedTexts: List<LayoutInspectorCurvedText> = emptyList(),
  val children: List<FigmaSvgLayer> = emptyList(),
) {
  val width: Int
    get() = (right - left).coerceAtLeast(0)

  val height: Int
    get() = (bottom - top).coerceAtLeast(0)

  /** True when the layer draws pixels itself (vs. a pure grouping container). */
  val paints: Boolean
    get() =
      fill != null ||
        stroke != null ||
        text != null ||
        raster != null ||
        background != null ||
        curvedTexts.isNotEmpty()
}

/**
 * The whole export: a single [root] layer (the padded-canvas frame) whose [FigmaSvgLayer.children]
 * mirror the layout tree, plus the canvas extent. [tx]/[ty] translate root-pixel space into the
 * padded canvas exactly as [WireframeModel] does, so a layer at absolute `(left, top)` is drawn at
 * `(left + tx, top + ty)`.
 */
/**
 * A circular device-screen clip in root-pixel space. A round Wear preview is rendered through
 * Roborazzi's `applyDeviceCrop`, which masks the frame to the inscribed circle — so the exported
 * SVG must mask to the same circle or its (square) full-frame background paints the corners the
 * render leaves transparent, tanking render-parity on every round-device scaffold.
 */
data class FigmaSvgRoundClip(val cx: Int, val cy: Int, val r: Int)

/**
 * A capsule (vertical stadium) device-screen clip in root-pixel space. The Wear scroll-SVG export
 * grows the round watch face into a **tall** frame so the whole `TransformingLazyColumn` composes
 * in one pass; masking that tall frame to the inscribed circle ([FigmaSvgRoundClip]) would clip the
 * list to a lens. Instead the frame is masked to a stadium — a top half-circle of radius `width/2`,
 * straight vertical sides, and a bottom half-circle — the vector analogue of the raster
 * `applyWearPillClip`. Rendered as a single `<rect rx=width/2>`; degenerates to the round clip's
 * circle when `height == width`.
 */
data class FigmaSvgCapsuleClip(val x: Int, val y: Int, val width: Int, val height: Int) {
  /**
   * Corner radius of the stadium — half the (narrower) width, so the caps are true half-circles.
   */
  val rx: Int
    get() = width / 2
}

data class FigmaSvgModel(
  val root: FigmaSvgLayer,
  val minX: Int,
  val minY: Int,
  val width: Int,
  val height: Int,
  val padding: Int,
  /** Opaque nodes emitted as `<image>` — each needs a raster captured at its bounds. */
  val rasterTargets: List<FigmaSvgRasterTarget> = emptyList(),
  /** Set for a round Wear device screen — the whole tree is masked to this circle on render. */
  val roundClip: FigmaSvgRoundClip? = null,
  /**
   * Set for a **tall** Wear scroll-SVG frame — the whole tree is masked to this vertical stadium on
   * render (see [FigmaSvgCapsuleClip]). Mutually exclusive with [roundClip].
   */
  val capsuleClip: FigmaSvgCapsuleClip? = null,
  /**
   * The device screen background painted behind the whole tree, clipped to the device mask
   * ([roundClip]/[capsuleClip]) — the black watch face a Wear **device** preview sits on. Only set
   * for a device frame that opted in (a `deviceBackground` was passed to [from]); component
   * previews (no device mask) never carry one, so their export stays transparent behind the
   * content. Without it a device export is transparent between rows and behind light-on-dark
   * chrome, so the light `TimeText`/header vanish on a light canvas (Figma) — the fill gives them
   * the dark face to read against while the corners outside the mask stay transparent.
   */
  val deviceBackground: FigmaSvgColor? = null,
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
     * @param captureCanvasDraws when true (hybrid mode — a frame PNG is available to crop), a node
     *   that paints via an imperative `drawBehind` / `drawWithContent` Canvas modifier (which the
     *   token-driven vector export can't see — e.g. a `LinearProgressIndicator`/`Slider` track
     *   drawn into a bare `Spacer`) is emitted as an `<image>` crop of that drawn region instead of
     *   vanishing. Off in vector-only mode (no frame to crop from).
     */
    fun from(
      layout: LayoutInspectorPayload,
      semantics: ComposeSemanticsPayload? = null,
      colorNames: Map<String, String> = emptyMap(),
      density: Float = 1f,
      padding: Int = DEFAULT_PADDING,
      rasterComponents: Set<String> = emptySet(),
      rasterHref: (nodeId: String) -> String = ::defaultRasterHref,
      captureCanvasDraws: Boolean = false,
      roundClip: Boolean = false,
      capsuleClip: Boolean = false,
      deviceBackground: String? = null,
    ): FigmaSvgModel {
      val textByNodeId =
        semantics?.let { assignTextToLayers(layout.root, it, density) } ?: emptyMap()
      val names = colorNames.mapKeys { it.key.uppercase() }
      val ctx =
        BuildContext(textByNodeId, names, density, rasterComponents, rasterHref, captureCanvasDraws)
      val rootLayer = layout.root.toLayer(ctx)
      // A round Wear device screen is masked to the inscribed circle of the frame (the root node's
      // bounds) — content outside it (the corners, and any list item scrolled below the frame) is
      // clipped away, matching Roborazzi's device crop. Clamp the extent to that frame so the
      // canvas
      // is the watch face, not the taller off-screen content bbox.
      val frame = layout.root.bounds
      val w = frame.right - frame.left
      val h = frame.bottom - frame.top
      // A tall Wear scroll frame is masked to a vertical stadium (capsule), not the inscribed
      // circle — the circle would clip the grown list to a lens. Two ways in: an explicit
      // `capsuleClip`, or a `roundClip` on a frame that's taller than it is wide. A normal round
      // watch face is square (h == w) → circle; the Wear scroll-SVG export grows it much taller
      // (h > w) → capsule. So the always-on Android export can keep passing `roundClip = isRound`
      // and the grown render auto-selects the stadium with no extra plumbing. Capsule wins when
      // both apply (they are mutually exclusive on the emitted model).
      val wantsCapsule = capsuleClip || (roundClip && h > w)
      val capsule =
        if (wantsCapsule) FigmaSvgCapsuleClip(x = frame.left, y = frame.top, width = w, height = h)
        else null
      val clip =
        if (capsule == null && roundClip) {
          FigmaSvgRoundClip(cx = frame.left + w / 2, cy = frame.top + h / 2, r = minOf(w, h) / 2)
        } else null
      // A device-screen clip (round or capsule) masks anything outside the frame, so clamp the
      // canvas extent to the frame — otherwise the square/tall full-frame background paints the
      // corners the render leaves transparent. No drawing layer (a tree of pure grouping nodes) → a
      // minimal padding-square canvas, matching the wireframe's empty-tree convention.
      val extent =
        if (clip != null || capsule != null)
          Extent(frame.left, frame.top, frame.right, frame.bottom)
        else rootLayer.extent() ?: Extent(0, 0, 0, 0)
      // A device preview (round or capsule mask) that opted in paints its screen background behind
      // the tree, clipped to that mask — so a Wear device export reads as a solid face with light
      // chrome legible, while the corners outside the mask stay transparent. Component previews
      // pass
      // no `deviceBackground` (and carry no mask), so they never get one.
      val deviceBg =
        if ((clip != null || capsule != null) && deviceBackground != null)
          argbToColor(deviceBackground, names)
        else null
      return FigmaSvgModel(
        root = rootLayer,
        minX = extent.minX,
        minY = extent.minY,
        width = (extent.maxX - extent.minX) + padding * 2,
        height = (extent.maxY - extent.minY) + padding * 2,
        padding = padding,
        rasterTargets = ctx.rasterTargets,
        roundClip = clip,
        capsuleClip = capsule,
        deviceBackground = deviceBg,
      )
    }

    /** Build inputs + the accumulating raster-target list, threaded through the walk. */
    private class BuildContext(
      val textByNodeId: Map<String, FigmaSvgText>,
      val colorNames: Map<String, String>,
      val density: Float,
      val rasterComponents: Set<String>,
      val rasterHref: (String) -> String,
      val captureCanvasDraws: Boolean = false,
      val rasterTargets: MutableList<FigmaSvgRasterTarget> = mutableListOf(),
    )

    private fun LayoutInspectorNode.toLayer(
      ctx: BuildContext,
      parentBounds: LayoutInspectorBounds? = null,
    ): FigmaSvgLayer {
      // Recover a usable rect for a node whose captured `bounds` collapsed to a zero-area box. The
      // Android/Wear layout inspector reports (0,0,0,0) for a node whose `LayoutCoordinates` were
      // detached / not-yet-placed at capture — a subcomposed `OutlinedTextField` / `Button` child
      // can hit this — and `boundsIn` mints those zeros faithfully. Propagating them verbatim emits
      // a degenerate `<image>` / `<rect>` (width 0, height 0) that vanishes, and — worse —
      // collapses
      // the whole subtree, because every descendant is placed against this box (its bounds become
      // the child `parentBounds`). The node's measured `size` survives that detachment (it comes
      // off
      // the layout node, not its coordinates), so anchor a `size`-sized rect at the parent's placed
      // origin, clamped to the parent, and use it everywhere below. Best-effort geometry for a
      // pathological capture; a normally-placed node keeps its real `bounds` untouched.
      val bounds = recoverBounds(parentBounds)
      // An opaque component matched by name (Image/Icon/TextField/…) can't be vectorised at all —
      // emit an <image> for the whole node and drop the subtree.
      val opaqueByName = isOpaque(ctx.rasterComponents)
      if (opaqueByName) {
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
      // A container filled by a `Modifier.paint(painter)` whose painter is NOT a plain
      // `ColorPainter`
      // — a component's private `Painter` (Wear `SwitchButton`'s animated colour painter, a
      // lazy-list `BackgroundPainter`), a `BitmapPainter`, a gradient — leaves `backgroundColor`
      // unresolved, so the fill would silently vanish from a vector-only export (the pill/card just
      // disappears). We can't teach the token resolver every component's private painter type, so
      // fall back to the frame: in hybrid mode capture the painted region as an `<image>` (exactly
      // as an opaque `Image`/`Icon` is handled) and drop the subtree. The ONLY painter we can turn
      // into a flat vector fill is a `ColorPainter`, so "the painter isn't `ColorPainter(...)`" is
      // the general signal to raster — no per-class knowledge, keyed only off the captured painter
      // string + the modifier's own drawn bounds.
      if (
        ctx.captureCanvasDraws && tokens?.backgroundColor == null && hasUnvectorizablePaintFill()
      ) {
        val region = paintFillRegion()
        val href = ctx.rasterHref(nodeId)
        ctx.rasterTargets.add(
          FigmaSvgRasterTarget(nodeId, href, region.left, region.top, region.right, region.bottom)
        )
        return FigmaSvgLayer(
          name = layerName(),
          left = region.left,
          top = region.top,
          right = region.right,
          bottom = region.bottom,
          raster = FigmaSvgRaster(href),
        )
      }
      // A *leaf* node that paints via an imperative Canvas draw (`drawBehind`/`drawWithContent`) —
      // the progress track, the slider groove — carries pixels the token export can't represent. In
      // hybrid mode (a frame PNG exists to crop from) attach that drawn region as a `background`
      // <image> beneath the node's own vector shape/text, so a bare `Spacer` becomes a group
      // holding
      // just that background. Restricted to leaf draw nodes (no children, no text): the background
      // is
      // cropped from the *composited* frame, so on a container (`Box(Modifier.drawBehind {…}) {
      // Text(…) }`) the crop would bake in the descendants' pixels — and re-drawing the editable
      // children over it double-renders them. Cropping a container's background-only pass needs an
      // isolated render, which the frame crop can't provide, so a drawn container stays fully
      // vector
      // (its children/text are preserved as editable layers) rather than double-render.
      val background =
        if (
          ctx.captureCanvasDraws &&
            hasCustomDraw() &&
            children.isEmpty() &&
            ctx.textByNodeId[nodeId] == null
        ) {
          val href = ctx.rasterHref(nodeId)
          val region = drawnRegion()
          ctx.rasterTargets.add(
            FigmaSvgRasterTarget(nodeId, href, region.left, region.top, region.right, region.bottom)
          )
          FigmaSvgBackgroundRaster(href, region.left, region.top, region.right, region.bottom)
        } else null
      val fill = tokens?.backgroundColor?.let { argbToColor(it, ctx.colorNames) }
      // A fully-transparent border (a `Switch` on-track carries `borderColor` at alpha 0) is no
      // border — dropping it keeps the stroke off *and* avoids the stroke-inset shrinking the fill
      // for an outline that never paints.
      val stroke =
        tokens?.borderColor?.let { argbToColor(it, ctx.colorNames) }?.takeIf { it.opacity > 0.0 }
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
      // Shadow elevation (dp) → px for the render's drop shadow.
      val elevationPx =
        tokens?.elevation?.removeSuffix("dp")?.toDoubleOrNull()?.let { it * ctx.density } ?: 0.0
      // A node whose background is *measured* larger than its *placed* content rect grows its drawn
      // shape to the measured extent, centered on the bounds, so the fill matches the render
      // instead
      // of a squashed shape at the narrow placement. Two signals, taking the larger:
      //  - the captured `Modifier.defaultMinSize` min constraints (dp × density) — an M3 `Badge`
      //    whose single-digit content is placed in a narrow box but draws its background at the min
      //    box;
      //  - the node's measured `size`, **clamped to the parent's placed bounds**. A Wear `Button`/
      //    `Card` places its background across content + its own horizontal padding, so `bounds`
      //    (the inner content rect) is narrower than the drawn button; `size` carries the full
      //    extent. Raw `size` is unreliable — a `fillMaxSize`/loosely-constrained node reports the
      //    whole sandbox — so it is clamped to the parent's real placed rect, which a child can
      //    never paint beyond.
      // The measured-`size` signal is SUPPRESSED for a node carrying
      // `Modifier.minimumInteractiveComponentSize()` (every M3 `Button`/`IconButton`/…): that
      // modifier inflates the measured `size` up to the 48dp touch target while the
      // `BackgroundElement` still paints at the smaller visual `bounds`, so growing the fill to
      // `size` would balloon a 40dp pill into its invisible 48dp touch margin. Those nodes'
      // `bounds`
      // is the true paint rect (this is what makes the growth a no-op on the desktop compose-m3
      // catalog, whose buttons all carry the modifier); the `size` under-report the growth corrects
      // is a Wear/Android `boundsIn` artifact on nodes that don't inflate their touch target.
      // Only when the node draws a shape and carries no text of its own (nothing else is positioned
      // against the box).
      val boundsW = bounds.right - bounds.left
      val boundsH = bounds.bottom - bounds.top
      val minWidthPx =
        tokens?.minWidth?.removeSuffix("dp")?.toDoubleOrNull()?.let { it * ctx.density }
      val minHeightPx =
        tokens?.minHeight?.removeSuffix("dp")?.toDoubleOrNull()?.let { it * ctx.density }
      val touchInflated = hasMinimumInteractiveSize()
      val measuredW =
        if (touchInflated) boundsW
        else parentBounds?.let { minOf(size.width, it.right - it.left) } ?: boundsW
      val measuredH =
        if (touchInflated) boundsH
        else parentBounds?.let { minOf(size.height, it.bottom - it.top) } ?: boundsH
      val drawW = maxOf(boundsW, minWidthPx?.roundToInt() ?: 0, measuredW)
      val drawH = maxOf(boundsH, minHeightPx?.roundToInt() ?: 0, measuredH)
      val expand =
        (fill != null || stroke != null) &&
          ctx.textByNodeId[nodeId] == null &&
          (drawW > boundsW || drawH > boundsH)
      // Center the grown shape on the placed bounds, then pull the whole rectangle back inside the
      // parent's placed bounds. Clamping only the grown *width/height* (above) isn't enough to keep
      // the promise that a child never paints beyond its parent: a fill whose bounds sit off-center
      // in its parent gets centered on its own bounds and would slide past the parent edge (parent
      // 0..100, child 0..40, grown width 100 → centered at -30..70). Clamp the top-left into
      // `[parent.left, parent.right - drawW]` so the rectangle stays within the parent whenever it
      // fits (and pins to the parent origin in the degenerate case where the grown shape is wider
      // than the parent). No parent (a root node) leaves the centered placement untouched.
      val drawLeft =
        if (!expand) bounds.left
        else {
          val centered = (bounds.left + bounds.right - drawW) / 2
          if (parentBounds != null)
            centered.coerceIn(
              parentBounds.left,
              maxOf(parentBounds.left, parentBounds.right - drawW),
            )
          else centered
        }
      val drawTop =
        if (!expand) bounds.top
        else {
          val centered = (bounds.top + bounds.bottom - drawH) / 2
          if (parentBounds != null)
            centered.coerceIn(
              parentBounds.top,
              maxOf(parentBounds.top, parentBounds.bottom - drawH),
            )
          else centered
        }
      val drawRight = if (expand) drawLeft + drawW else bounds.right
      val drawBottom = if (expand) drawTop + drawH else bounds.bottom
      return FigmaSvgLayer(
        name = layerName(),
        left = drawLeft,
        top = drawTop,
        right = drawRight,
        bottom = drawBottom,
        fill = fill,
        stroke = stroke,
        // Stroke width: use the captured `Modifier.border` width (dp × density) when present, so a
        // 2dp outline (an off-state `Switch` track) isn't drawn as a 1dp hairline. Fall back to a
        // single dp scaled into the render's px space — the width of a Material hairline outline —
        // when the border width wasn't captured. `coerceAtLeast(1.0)` keeps a visible hairline at
        // density < 1.
        strokeWidthPx =
          if (stroke != null) {
            val dp = tokens?.borderWidth?.removeSuffix("dp")?.toDoubleOrNull()
            (dp?.let { it * ctx.density } ?: ctx.density.toDouble()).coerceAtLeast(1.0)
          } else 1.0,
        cornerRadiiPx = corners,
        circle = circle,
        cut = cut,
        text = ctx.textByNodeId[nodeId],
        background = background,
        elevationPx = elevationPx,
        curvedTexts = curvedTexts,
        children = children.map { it.toLayer(ctx, bounds) },
      )
    }

    /**
     * The node's captured [LayoutInspectorNode.bounds], or — only for the exact all-zero
     * `(0,0,0,0)` signature the layout inspector mints for a **detached / unplaced** node — a
     * best-effort rect reconstructed from the measured [LayoutInspectorNode.size], anchored at the
     * parent's placed origin and clamped to the parent. This guards the export against a
     * subcomposed child whose `LayoutCoordinates` were null at capture: propagating its zeros emits
     * degenerate geometry and, because descendants place against this box, drops the whole subtree.
     *
     * Two deliberate narrowings keep the recovery off genuinely tiny content:
     * - It fires **only** on `(0,0,0,0)`. A node that is *placed* but measures to zero area (an
     *   intentionally collapsed `Modifier.size(0.dp)` child, or an animated collapse) still reports
     *   its real non-zero origin, so it never matches and keeps its captured bounds.
     * - It reconstructs a dimension **only when the measured `size` for that dimension is
     *   positive** (clamped to the parent). A dimension with no measured size stays zero rather
     *   than ballooning to the parent's extent, so a truly 0×0 node isn't materialised into a
     *   parent-sized rect/image. When neither dimension can be recovered, the raw `bounds` are
     *   kept.
     */
    private fun LayoutInspectorNode.recoverBounds(
      parentBounds: LayoutInspectorBounds?
    ): LayoutInspectorBounds {
      if (bounds.left != 0 || bounds.top != 0 || bounds.right != 0 || bounds.bottom != 0)
        return bounds
      val parent = parentBounds ?: return bounds
      val parentW = parent.right - parent.left
      val parentH = parent.bottom - parent.top
      if (parentW <= 0 || parentH <= 0) return bounds
      val rectW = size.width.coerceIn(0, parentW)
      val rectH = size.height.coerceIn(0, parentH)
      if (rectW <= 0 && rectH <= 0) return bounds
      return LayoutInspectorBounds(
        left = parent.left,
        top = parent.top,
        right = parent.left + rectW,
        bottom = parent.top + rectH,
      )
    }

    /** True when the composable name matches a [rasterComponents] fragment. */
    private fun LayoutInspectorNode.isOpaque(rasterComponents: Set<String>): Boolean =
      rasterComponents.any {
        component.contains(it, ignoreCase = true)
      }

    /**
     * `Modifier.minimumInteractiveComponentSize()` — the M3 touch-target expander. A node carrying
     * it has a measured `size` inflated up to 48dp while its background still paints at the smaller
     * visual `bounds`, so the fill-growth heuristic must not treat `size` as the paint extent.
     */
    private const val MIN_INTERACTIVE_MODIFIER = "minimumInteractiveComponentSize"

    private fun LayoutInspectorNode.hasMinimumInteractiveSize(): Boolean = modifiers.any {
      it.name.equals(MIN_INTERACTIVE_MODIFIER, ignoreCase = true)
    }

    /** The `Modifier.paint`/`PainterElement` names that project a painter as a container fill. */
    private val PAINT_FILL_MODIFIERS = setOf("paint", "PainterElement")

    /**
     * True when this node is filled by a `Modifier.paint` painter we can't turn into a flat colour.
     * The token resolver only reads a plain [androidx.compose.ui.graphics.painter.ColorPainter]
     * (whose captured string is `ColorPainter(color=Color(…))`); any other painter — a component's
     * private `Painter`, a `BitmapPainter`, a gradient — stringifies to a class name and leaves the
     * fill unresolved. Recognising the one painter we CAN vectorise (and rastering everything else)
     * keeps this free of per-component knowledge: a painter present but of any other form ⇒ raster.
     *
     * The one caveat is a `ColorPainter` carrying a `colorFilter`: the string still starts with
     * `ColorPainter(`, but the resolver deliberately leaves the fill unresolved because a
     * re-tinting filter can't collapse to a flat token — so that (visible) fill must raster too,
     * not be treated as vectorisable. A `ColorPainter` with no filter that still didn't resolve is
     * a fully transparent fill (no visible pixels) and is left alone.
     */
    private fun LayoutInspectorNode.hasUnvectorizablePaintFill(): Boolean {
      val paint = modifiers.firstOrNull { it.name in PAINT_FILL_MODIFIERS } ?: return false
      val painter = paint.properties["painter"] ?: return true
      if (!painter.startsWith("ColorPainter(")) return true
      val filter = paint.properties["colorFilter"]
      return filter != null && filter != "null"
    }

    /** The region a paint-fill painter actually covers — its modifier bounds, else the node box. */
    private fun LayoutInspectorNode.paintFillRegion(): LayoutInspectorBounds =
      modifiers.firstOrNull { it.name in PAINT_FILL_MODIFIERS && it.bounds != null }?.bounds
        ?: bounds

    /** The Compose modifiers that paint via an imperative Canvas the token export can't read. */
    private val DRAW_MODIFIERS = setOf("drawBehind", "drawWithContent", "drawWithCache")

    /**
     * True when the node paints through a custom Canvas draw (a `Canvas`, or a component drawing
     * its chrome via `Modifier.drawBehind {…}` like the progress/slider indicators).
     */
    private fun LayoutInspectorNode.hasCustomDraw(): Boolean = modifiers.any {
      it.name in DRAW_MODIFIERS
    }

    /**
     * The region the Canvas draw actually paints — the union of the draw modifiers' bounds, which
     * is tighter than the (padded) node box (`Spacer(padding).drawBehind`). Falls back to the node
     * bounds when a draw modifier carries none.
     */
    private fun LayoutInspectorNode.drawnRegion(): LayoutInspectorBounds {
      val drawn = modifiers.filter { it.name in DRAW_MODIFIERS }.mapNotNull { it.bounds }
      if (drawn.isEmpty()) return bounds
      return LayoutInspectorBounds(
        left = drawn.minOf { it.left },
        top = drawn.minOf { it.top },
        right = drawn.maxOf { it.right },
        bottom = drawn.maxOf { it.bottom },
      )
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
          node.typography?.lineHeight?.let {
            lineHeightToPx(it, node.typography.fontSize, density)
          },
        // Letter spacing uses the same sp×density / em×fontSize resolution as line height.
        letterSpacingPx =
          node.typography?.letterSpacing?.let {
            lineHeightToPx(it, node.typography.fontSize, density)
          },
        // Carry per-line runs only for genuinely wrapped text (2+ lines). The captured offsets are
        // already in render px (same space as the node bounds), so they map straight to layer space
        // with no density conversion.
        lines =
          node.textOverflow
            ?.lines
            ?.takeIf { it.size > 1 }
            ?.map { FigmaSvgTextLine(content = it.text, left = it.left, baseline = it.baseline) },
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
