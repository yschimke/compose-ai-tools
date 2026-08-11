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
   * Resolved paragraph alignment as captured (`"center"`, `"end"`, `"right"`, …), or null when the
   * capture resolved none. Drives the single-line `<text>`'s `text-anchor` + x so centred /
   * right-aligned text lands where the render drew it rather than at the start of its layout bounds
   * (issue #2885). Wrapped text ignores it: [lines] already carries each line's measured `left`,
   * which encodes the alignment geometrically, and anchoring on top of that would shift every line
   * twice.
   */
  val textAlign: String? = null,
  /**
   * Layout direction the paragraph was laid out in (`"ltr"` / `"rtl"`), when captured. Resolves the
   * *logical* [textAlign] values: Compose puts `start` at the right edge and `end` at the left
   * under RTL, so an LTR-assuming exporter mirrors `end`-aligned text to the wrong side on an `ar`
   * / `ar-XB` render. Absent ⇒ treated as LTR.
   */
  val layoutDirection: String? = null,
  /** Effective styled UTF-16 ranges for annotated text; null for a uniform/plain run. */
  val spans: List<FigmaSvgTextSpan>? = null,
  /**
   * Per-line runs for wrapped or ellipsised text, in px relative to the layer's top-left, in draw
   * order. The renderer emits one positioned `<tspan>` per line instead of the full source string,
   * so wrapping and a single-line ellipsis match the render exactly. Null for ordinary single-line
   * text.
   */
  val lines: List<FigmaSvgTextLine>? = null,
)

/** One laid-out line of a wrapped [FigmaSvgText], px offsets from the text layer's top-left. */
data class FigmaSvgTextLine(
  val content: String,
  val left: Int,
  val baseline: Int,
  /** UTF-16 offsets into the full text; null for schema-v8 captures. */
  val start: Int? = null,
  val end: Int? = null,
  /**
   * The width the render measured this line at, in px, emitted as SVG `textLength` so the viewer
   * lays the run out to the render's width instead of its own (issue #3024). Null for captures
   * before schema v12.
   */
  val width: Int? = null,
)

/** One effective styled UTF-16 range within [FigmaSvgText.content]. */
data class FigmaSvgTextSpan(
  val start: Int,
  val end: Int,
  val fontSizePx: Double? = null,
  val fontFamily: String? = null,
  val fontWeight: Int? = null,
  val italic: Boolean = false,
  val color: FigmaSvgColor? = null,
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

/**
 * An editable vector graphic (an `Icon`/`Image`'s `ImageVector`) emitted as real `<path>` layers —
 * the vector alternative to a [FigmaSvgRaster] leaf. Path coordinates are in the vector's own
 * [viewportWidth] × [viewportHeight]. [layoutWidth] × [layoutHeight] is the pre-transform layout
 * slot, allowing the emitter to distinguish an intentionally transformed vector from a merely
 * non-square slot. [fillBounds] preserves an explicit `ContentScale.FillBounds`.
 */
data class FigmaSvgVector(
  val viewportWidth: Float,
  val viewportHeight: Float,
  val layoutWidth: Int,
  val layoutHeight: Int,
  val fillBounds: Boolean = false,
  /**
   * The node's captured draw-time `graphicsLayer` scale (`LayoutInspectorNode.transform`), 1 when
   * it declared none.
   *
   * This is what separates a genuinely squashed vector from a merely *clipped* one. `bounds` alone
   * can't: a node measured 24×24 and drawn into a 12×3 rect looks identical whether an ancestor
   * scaled it or an animating container clipped it, and inferring a scale from that ratio squashed
   * Jetsnack's square FAB icon to `scale(0.49 0.13)` (issue #2853). The capture states which it is,
   * so the export stops guessing.
   */
  val scaleX: Double = 1.0,
  val scaleY: Double = 1.0,
  /** The captured graphics-layer mirrors this vector around the corresponding placed axis. */
  val flipX: Boolean = false,
  val flipY: Boolean = false,
  /**
   * True when the paths were recorded from the node's own draw lambda rather than an `ImageVector`
   * — which also decides what coordinate space [viewportWidth]/[viewportHeight] are in, and so how
   * the emitter scales them. See [FigmaLayeredSvg]'s vector placement.
   */
  val fromDrawCapture: Boolean = false,
  /**
   * The stock Material icon this vector is, when the capture's `ImageVector` name identified one
   * ([MaterialIconRef.parse]). Null for app artwork, a draw capture, or an unrecognised name.
   *
   * Purely an *identity*: the emitted geometry is [paths] either way. See [FigmaLayeredSvg] for
   * what the export does with it.
   */
  val materialIcon: MaterialIconRef? = null,
  val paths: List<FigmaSvgVectorPath>,
)

/** One `<path>` of a [FigmaSvgVector] in viewport coordinates, with its resolved solid paint. */
data class FigmaSvgVectorPath(
  val pathData: String,
  val fillArgb: String? = null,
  val fillAlpha: Float = 1f,
  val strokeArgb: String? = null,
  val strokeWidth: Float = 0f,
  val strokeAlpha: Float = 1f,
  /** SVG `stroke-linecap` (`"round"`/`"square"`); null = butt. */
  val strokeCap: String? = null,
  /** SVG `stroke-linejoin` (`"round"`/`"bevel"`); null = miter. */
  val strokeJoin: String? = null,
  val evenOdd: Boolean = false,
)

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
  /**
   * Whether these pixels were cropped out of the composited frame (the default) or re-drawn in
   * isolation from the node's own draw lambda ([LayoutInspectorNode.drawRaster], issue #2937).
   *
   * The distinction is an **opacity** one. A frame crop has every ancestor and local graphics-layer
   * alpha already baked into it, so the emitter must not fade its group again — which is why a
   * layer holding one splits its opacity across its parts instead of carrying it on the group. An
   * isolated re-draw is taken from the modifier chain *below* those layers, so its alpha is not
   * baked in and the ordinary group opacity is exactly right for it.
   */
  val fromFrame: Boolean = true,
  /**
   * Whether these pixels paint **over** the layer's own token shape rather than under it.
   *
   * Compose paints a modifier chain outside-in, so where the draw sits relative to the
   * `background`/`border` the shape came from decides the order. `Modifier.background(red)
   * .drawWithContent { blue(); drawContent() }` paints red *then* blue — emitting the capture as an
   * ordinary background would put the red rect on top and hide the blue entirely. The reverse chain
   * (`drawBehind { blue() }.background(red)`) really does paint blue first, and keeps the default.
   */
  val aboveShape: Boolean = false,
  /**
   * Whether an outer `Modifier.clip(shape)` masks this isolated draw capture.
   *
   * [LayoutInspectorNode.drawRaster] replays only draw modifiers, so a clip coordinator outside
   * those modifiers is intentionally absent from its PNG. The SVG emitter restores that mask around
   * this image alone. Modifier order matters: a draw outside the clip must remain unclipped, just
   * like a background outside `Modifier.clip`.
   */
  val clipToShape: Boolean = false,
  /** The clipping coordinator's placed box when it differs from the layer's content box. */
  val clipBounds: LayoutInspectorBounds? = null,
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
  /**
   * The PNG to write at [href], already captured — an isolated re-draw of the node's own draw
   * lambda ([LayoutInspectorNode.drawRaster], issue #2937). Null for the ordinary target, whose
   * pixels the producer still crops out of the rendered frame. A target that carries its own bytes
   * needs no frame at all, so it survives the vector-only export.
   */
  val pngBase64: String? = null,
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
  /**
   * SVG path data for a shape no corner radius could describe — a morph/star/squircle outline, or a
   * shape wrapper the resolver could not reduce (issue #3254). A polyline (`M`/`L`/`Z` only) in the
   * **unit box**: every coordinate is a 0..1 fraction of the layer's own width/height, so the
   * renderer maps it onto the final box without a `transform` (which would distort the stroke).
   * When set — and only when [cornerRadiiPx] is null and [circle] is false — the layer draws as
   * this path instead of a rect. The alternative was a sharp rectangle standing in for geometry
   * that was never resolved.
   */
  val shapePathData: String? = null,
  val text: FigmaSvgText? = null,
  /** Set when this layer is an opaque component rendered as an `<image>`. */
  val raster: FigmaSvgRaster? = null,
  /**
   * Set when this layer is an `Icon`/`Image` whose `ImageVector` was captured — emitted as editable
   * `<path>` layers instead of a [raster] crop. Mutually exclusive with [raster]: a vector-backed
   * icon takes this path, a bitmap-backed one still rasters.
   */
  val vector: FigmaSvgVector? = null,
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
   * A linear gradient this layer's shape is filled with, instead of the flat [fill] (issue #2852).
   * Emitted as an SVG `<linearGradient>` def the shape references, so it stays editable in Figma
   * rather than being flattened into a raster.
   */
  val fillGradient: LayoutInspectorGradient? = null,
  /** The stroke counterpart of [fillGradient] — a `Modifier.border(width, brush, shape)` ring. */
  val strokeGradient: LayoutInspectorGradient? = null,
  /** Alpha from `graphicsLayer`s outside this layer's own drawing modifiers. */
  val opacity: Double = 1.0,
  /** Alpha from `graphicsLayer`s inside this layer's own drawing modifiers. */
  val contentOpacity: Double = 1.0,
  /**
   * Wear curved text (a `CurvedLayout`/`TimeText` clock) carried on this layer — drawn as an SVG
   * `<textPath>` along its baseline arc. Empty for the common straight-text/no-text case.
   */
  val curvedTexts: List<LayoutInspectorCurvedText> = emptyList(),
  /**
   * True when this layer carries a `Modifier.clip(shape)` that masks its children to its own box +
   * corner shape ([cornerRadiiPx] / [circle] / [cut]). A child placed beyond the box — Jetsnack
   * Search/Categories' minimum-size image under `.clip(CategoryShape)` — is clipped to the rounded
   * box instead of overflowing, and does not grow the exported canvas (issue #2852). Independent of
   * [paints]: a clip-only node draws no fill of its own but still masks its subtree.
   */
  val clipChildren: Boolean = false,
  /**
   * The box this layer masks its children to, when that is **not** its own box.
   *
   * Only a node whose own box was narrowed to its painted extent inside a larger clipping
   * coordinator carries one (the `size(100.dp).clip(…).requiredSize(50.dp, 200.dp).background(…)`
   * shape, and the lookahead scroll container of issue #3056 when its paint is narrower than the
   * viewport). Keeping the two apart is what stops the narrowed fill from also shrinking the
   * `<clipPath>` — a `clip(CircleShape)` would become a capsule, and a child drawing in the part of
   * the viewport this node's own paint doesn't cover would be trimmed away. Null everywhere else,
   * where the layer's own box *is* the mask.
   */
  val clipBox: LayoutInspectorBounds? = null,
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
        fillGradient != null ||
        strokeGradient != null ||
        text != null ||
        raster != null ||
        vector != null ||
        background != null ||
        curvedTexts.isNotEmpty() ||
        opacity < 0.999 ||
        contentOpacity < 0.999
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

/** An axis-aligned rectangle in root-pixel space. */
data class FigmaSvgRect(val x: Int, val y: Int, val width: Int, val height: Int)

/**
 * How the `compose/figma-svg` export treats the background the render painted behind the preview.
 *
 * The export's product is **editable layers**, and a baked-in fill is the one thing an importing
 * designer can't easily undo — an opaque shape spanning the canvas that has to be found and deleted
 * before the import works anywhere but the surface it was baked for. Hard to remove, easy to add
 * back: so [NONE] is the default and a background is *requested*, per preview, by whoever knows it
 * is wanted.
 */
@kotlinx.serialization.Serializable
enum class FigmaSvgBackgroundMode {
  /**
   * Export background-free (the default). The tree's own fills still draw — a screen that paints
   * its surface colour keeps painting it; only the *injected* bottom layer is dropped.
   */
  NONE,
  /**
   * Paint the background in the **device-mask** shape: a black `<circle>` for a round Wear face,
   * the vertical stadium for a tall Wear scroll export, and — with no mask — the plain frame rect.
   * The corners outside the mask stay transparent, so the export reads as a watch sitting on the
   * importing canvas rather than a square tile. This is the shape the export used to inject
   * unconditionally, and what a Wear device or tall-scroll preview generally wants.
   */
  DEVICE,
  /**
   * Paint the background in the **content's own** shape — the outermost layer that declares one, so
   * an `OutlinedButton` gets a filled pill exactly under its outline and a circular icon button
   * gets a disc. No device mask involved; a component preview that just needs something to read
   * against wants this, not a full tile.
   *
   * Falls back to the plain frame rect when the tree declares no shape at all.
   */
  CONTENT_SHAPE,
  /**
   * Paint the background as a plain rect across the whole export, ignoring the device mask. The
   * mask keeps clipping the *content*, but the fill runs to the corners — the "stage" look, for an
   * export that has to sit on a solid card rather than on the importing canvas.
   */
  FULL_BLEED;

  companion object {
    /**
     * Parses a mode from a wire/property string, case- and separator-insensitive (`full-bleed`,
     * `full_bleed`, `fullBleed`). Also accepts the pre-modes booleans: `true` is the device-mask
     * shape the export used to inject unconditionally, `false` is [NONE]. Null when unset or
     * unrecognised, so a typo falls back to the caller's default rather than failing a render.
     */
    fun parse(raw: String?): FigmaSvgBackgroundMode? =
      when (raw?.trim()?.lowercase()?.replace("-", "")?.replace("_", "")) {
        null,
        "" -> null
        "false",
        "none" -> NONE
        "true",
        "device",
        "clipped",
        "clip" -> DEVICE
        "contentshape",
        "content",
        "shape" -> CONTENT_SHAPE
        "fullbleed",
        "bleed" -> FULL_BLEED
        else -> null
      }
  }
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
   * when a `deviceBackground` was passed to [from], which the shipped producers do **only** under
   * the `composeai.svg.background` opt-in: an injected fill is an opaque layer spanning the canvas
   * that a designer has to delete before the import works anywhere but the surface it was baked
   * for, and a tree that declared `showBackground` generally paints that same colour itself (a Wear
   * device export carried this black circle directly over the root's own identical black rect).
   * Component previews (no device mask) never carry one either way.
   */
  val deviceBackground: FigmaSvgColor? = null,
  /**
   * The silhouette [deviceBackground] fills in [FigmaSvgBackgroundMode.CONTENT_SHAPE] — the
   * outermost layer that declares a shape, carried as a fill-only layer so the writer draws it
   * through the same corner-radius / circle / sampled-outline path every other layer uses. When set
   * it wins over [backgroundRect] and the mask fill: those are whole-canvas layers, this one hugs
   * the component.
   */
  val backgroundShape: FigmaSvgLayer? = null,
  /**
   * The frame the [deviceBackground] fills when the preview carries **no** device mask — an
   * ordinary `@Preview(showBackground = true, backgroundColor = …)` whose render painted a flat
   * background behind the composable (issue #2884). A masked device frame ignores this and paints
   * the mask shape instead, so the two never both draw. Null when nothing opted in.
   */
  val backgroundRect: FigmaSvgRect? = null,
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
     * filled & outlined `TextField` container + indicator. Its fills never surface as tokens, so a
     * token-driven vector export drops them entirely — the filled `TextField` was the worst sticker
     * in the fidelity audit (dark: 66%, container box missing). Rasterising the component's
     * measure-policy node (`TextFieldMeasurePolicy`, `OutlinedTextFieldMeasurePolicy`) crops its
     * faithful pixels out of the frame while the rest of the screen stays editable vector — the
     * sanctioned hybrid split, tuned by the fidelity diff (render vs. SVG) rather than guessed
     * per-component. `TextField` stays here because its live cursor / selection / IME state is
     * genuinely raster-only.
     *
     * The `Slider` used to sit in this family too, for the same reason (its track + thumb are drawn
     * imperatively by `SliderKt`, never as tokens). It no longer needs a name entry: the
     * draw-capture extractor re-invokes those draw lambdas against a recording `DrawScope` and
     * emits the track and thumb as editable `<path>`s, so the drawn descendants vectorise
     * faithfully instead of being cropped out as an opaque `<image>`. Rasterising the `SliderKt`
     * node by name would pre-empt that capture (the opaque-by-name branch drops the subtree before
     * recursion reaches the drawn leaves), so keep `Slider` out of this set.
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
     * @param padding transparent margin around the extent. Applies only to the **frameless** path:
     *   with [frameWidthPx]/[frameHeightPx] supplied the canvas is anchored to the captured frame
     *   so it matches the paired PNG exactly, and a margin would offset it from that raster again.
     * @param rasterComponents opaque component name-fragments; empty (default) = vector-only.
     * @param captureCanvasDraws when true (hybrid mode — a frame PNG is available to crop), a node
     *   that paints via an imperative `drawBehind` / `drawWithContent` Canvas modifier (which the
     *   token-driven vector export can't see — e.g. a `LinearProgressIndicator`/`Slider` track
     *   drawn into a bare `Spacer`) is emitted as an `<image>` crop of that drawn region instead of
     *   vanishing. Off in vector-only mode (no frame to crop from).
     * @param frameWidthPx the captured frame PNG's pixel width, in the same root-pixel space the
     *   bounds live in. Supplied alongside a [deviceBackground] so a maskless `showBackground`
     *   preview fills its whole crop — the render paints the background across the entire window
     *   and crops top-left, so the PNG size is exactly the background area, even when the only
     *   drawn child is thinner/shorter than the root (issue #2974). Null (the vector-only /
     *   no-frame path) falls back to sizing the background from the drawn-content extent.
     * @param frameHeightPx the captured frame PNG's pixel height; see [frameWidthPx].
     */
    fun from(
      layout: LayoutInspectorPayload,
      semantics: ComposeSemanticsPayload? = null,
      colorNames: Map<String, String> = emptyMap(),
      density: Float = 1f,
      fontScale: Float = 1f,
      padding: Int = DEFAULT_PADDING,
      rasterComponents: Set<String> = emptySet(),
      rasterHref: (nodeId: String) -> String = ::defaultRasterHref,
      captureCanvasDraws: Boolean = false,
      roundClip: Boolean = false,
      capsuleClip: Boolean = false,
      deviceBackground: String? = null,
      backgroundMode: FigmaSvgBackgroundMode = FigmaSvgBackgroundMode.DEVICE,
      frameWidthPx: Int? = null,
      frameHeightPx: Int? = null,
    ): FigmaSvgModel {
      // Everything below works off the *drawn* tree — the retired slots are dropped first, before
      // text matching, layer building and extent measuring all get a chance to believe in them.
      val layoutRoot = layout.root.withoutRetiredSubtrees()
      val textByNodeId =
        semantics?.let { assignTextToLayers(layoutRoot, it, density, fontScale) } ?: emptyMap()
      val names = colorNames.mapKeys { it.key.uppercase() }
      val ctx =
        BuildContext(
          textByNodeId,
          names,
          density,
          fontScale,
          rasterComponents,
          rasterHref,
          captureCanvasDraws,
        )
      val rootLayer = collapsePassthroughGroups(layoutRoot.toLayer(ctx))
      // A round Wear device screen is masked to the inscribed circle of the frame (the root node's
      // bounds) — content outside it (the corners, and any list item scrolled below the frame) is
      // clipped away, matching Roborazzi's device crop. Clamp the extent to that frame so the
      // canvas
      // is the watch face, not the taller off-screen content bbox.
      val frame = layoutRoot.bounds
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
      // The captured frame PNG's rect, anchored at the root origin (the render crops top-left from
      // the window, whose origin is the root node). This is *everything that was rendered* — no
      // pixel outside it exists in the frame.
      val frameExtent =
        if (frameWidthPx != null && frameHeightPx != null && frameWidthPx > 0 && frameHeightPx > 0)
          Extent(frame.left, frame.top, frame.left + frameWidthPx, frame.top + frameHeightPx)
        else null
      val contentExtent =
        if (clip != null || capsule != null)
          Extent(frame.left, frame.top, frame.right, frame.bottom)
        else
        // Clamped to the rendered frame (issue #2853). A lazy list reports every *composed* item,
        // including the ones scrolled past the viewport — Jetsnack's `Screens/App shell` carries a
        // third snack column at x 397…565 and two more rows below y 800, none of which the 400×800
        // render paints. Letting them size the canvas grew it to 598×1003 and stranded their white
        // card backgrounds outside the phone UI as detached tiles. With no frame size (the
        // vector-only path, a synthetic model) there is nothing to clamp to and the drawn-content
        // extent still decides, so an overflowing child of an unclipped container keeps growing
        // the canvas exactly as `FigmaSvgChildClipTest` pins.
        (rootLayer.extent() ?: Extent(0, 0, 0, 0)).let { drawn ->
            frameExtent?.let { f ->
              Extent(
                  maxOf(drawn.minX, f.minX),
                  maxOf(drawn.minY, f.minY),
                  minOf(drawn.maxX, f.maxX),
                  minOf(drawn.maxY, f.maxY),
                )
                // A tree drawn entirely outside its own frame is pathological; keep what it drew
                // rather than emitting an inverted extent.
                .takeIf { it.maxX > it.minX && it.maxY > it.minY }
            } ?: drawn
          }
      val deviceBgResolved =
        deviceBackground
          ?.takeIf { backgroundMode != FigmaSvgBackgroundMode.NONE }
          ?.let { argbToColor(it, names) }
      // FULL_BLEED paints the background as a plain rect across the whole export, even on a device
      // preview whose *tree* is masked — the mask keeps clipping the content, but the fill runs to
      // the corners instead of being cut to the watch face. CLIPPED (the historical shape) hands
      // the colour to the mask so a round Wear export gets a black circle and nothing outside it.
      val fullBleed =
        deviceBgResolved != null && backgroundMode == FigmaSvgBackgroundMode.FULL_BLEED
      // CONTENT_SHAPE hugs the component instead of tiling the canvas: reuse the outermost shaped
      // layer's own geometry as a fill-only layer, so an OutlinedButton's pill and an icon button's
      // disc come out exactly right without a second shape implementation. A tree that declares no
      // shape has nothing to hug — fall through to the whole-canvas rect.
      val contentShape =
        deviceBgResolved
          ?.takeIf { backgroundMode == FigmaSvgBackgroundMode.CONTENT_SHAPE }
          ?.let { bg ->
            rootLayer.outermostShapedLayer()?.let { shaped ->
              FigmaSvgLayer(
                name = "Background",
                left = shaped.left,
                top = shaped.top,
                right = shaped.right,
                bottom = shaped.bottom,
                fill = bg,
                cornerRadiiPx = shaped.cornerRadiiPx,
                circle = shaped.circle,
                cut = shaped.cut,
                shapePathData = shaped.shapePathData,
              )
            }
          }
      // The captured frame PNG's pixel size is the exact area a maskless `showBackground` preview
      // fills: the render paints the background across the whole window and crops top-left, so
      // every
      // pixel of the crop is that colour. The drawn-content extent alone can be smaller — a 1dp
      // divider centred in a taller fixed-size Box (issue #2974) leaves the extent thin, stranding
      // most of the background as transparency. Anchor a frame-sized rect at the root origin (the
      // crop is top-left from the window, whose origin is the root node) so the background — and
      // the
      // canvas that must contain it — cover the full crop. Skipped for masked device frames (they
      // paint the mask shape) and when no frame size is known (the vector-only path keeps the
      // extent-based sizing, e.g. #2884's synthetic model).
      val framePixelExtent =
        if (frameWidthPx != null && frameHeightPx != null && frameWidthPx > 0 && frameHeightPx > 0)
          Extent(frame.left, frame.top, frame.left + frameWidthPx, frame.top + frameHeightPx)
        else null
      val backgroundFrame =
        when {
          deviceBgResolved == null -> null
          // Full-bleed: a rect across the whole export whatever the mask. The Android export runs
          // in the capture phase, before the PNG exists, so it knows no `frameWidthPx` — fall back
          // to the root's own bounds, which for a masked device is the square the mask inscribes.
          fullBleed -> framePixelExtent ?: Extent(frame.left, frame.top, frame.right, frame.bottom)
          // Clipped: a masked frame paints the mask shape instead, so the two never both draw.
          clip != null || capsule != null -> null
          else -> framePixelExtent
        }
      // The canvas has to contain both the drawn content and the background crop.
      val drawnExtent =
        backgroundFrame?.let {
          Extent(
            minOf(contentExtent.minX, it.minX),
            minOf(contentExtent.minY, it.minY),
            maxOf(contentExtent.maxX, it.maxX),
            maxOf(contentExtent.maxY, it.maxY),
          )
        } ?: contentExtent
      // **Raster parity.** When the captured frame's size is known, the exported canvas IS that
      // frame: the SVG and its paired PNG are two renders of one capture, and a viewer that swaps
      // between them (the `serve` viewer's SVG toggle) must not have the box change size or the
      // content move. Shrink-wrapping to the drawn extent instead made the two disagree by however
      // much dead space the composable's layout box carried — a `compose-m3` sticker's 16dp padding
      // at density 2.625 is 42px a side, so its SVG came out 52px smaller in each dimension and
      // shifted the component by up to 26px on the stage, while a `wear-m3` sticker (8dp at density
      // 2.0 = exactly [DEFAULT_PADDING]) matched by pure coincidence. Union rather than replace, so
      // the pathological "drawn entirely outside the frame" fallback above still keeps its content
      // on the canvas. Frameless callers (the vector-only / synthetic path) keep the padded
      // shrink-wrapped canvas — there is no raster for them to agree with.
      // A device mask defines the frame just as firmly as a frame PNG does — nothing outside the
      // circle/stadium is drawn — so a masked export anchors to the masked rect even when the
      // caller passed no frame size. (The Android export is one such caller: its figma-svg
      // extension runs in the capture phase, before the PNG is on disk, so `frameWidthPx` is null
      // there. Without this a round Wear screen exported 32px larger than its own watch face.)
      val maskedFrame =
        if (clip != null || capsule != null)
          Extent(frame.left, frame.top, frame.right, frame.bottom)
        else null
      val anchor = frameExtent ?: maskedFrame
      val extent =
        anchor?.let {
          Extent(
            minOf(drawnExtent.minX, it.minX),
            minOf(drawnExtent.minY, it.minY),
            maxOf(drawnExtent.maxX, it.maxX),
            maxOf(drawnExtent.maxY, it.maxY),
          )
        } ?: drawnExtent
      // The margin exists to keep a shrink-wrapped diagram off its own edge. A frame-anchored
      // canvas is already the render's own bounds, so adding one would reintroduce the very offset
      // this is here to remove.
      val effectivePadding = if (anchor != null) 0 else padding
      // A preview that opted into a background paints it behind the whole tree as the bottom
      // layer. A device frame (round or capsule mask) paints it in the mask shape, so a Wear
      // device export reads as a solid face with light chrome legible while the corners outside
      // the mask stay transparent. A **maskless** preview — the ordinary
      // `@Preview(showBackground = true, backgroundColor = …)` of issue #2884 — paints the frame
      // rect instead; before this it painted nothing, so the SVG was transparent where the PNG was
      // opaque. Previews that pass no `deviceBackground` (the default, and every preview that
      // didn't declare `showBackground`) still export background-free.
      val deviceBg = deviceBgResolved
      // Paint the background across the full exported canvas [extent], which already unions the
      // drawn-content extent with the frame crop:
      //  - the frame crop grows it to the whole cropped area a thin/short child would otherwise
      //    shrink-wrap the fill away from (issue #2974), and
      //  - any drawing the SVG deliberately retains beyond the captured viewport (a card's chrome
      //    wider than its box, #2937; a scroll item past its parent edge) sat on the window
      //    background in the live render, so it must sit on the background here too rather than
      // over
      //    transparency.
      // With no frame size [extent] is just the drawn-content extent, so a wrap-content preview
      // (measured inside a generous 400×800 dp sandbox, PNG cropped back to intrinsic size) still
      // fills only the cropped area rather than the sandbox, and the #2884 maskless path is
      // unchanged.
      // Set alongside a device mask only in FULL_BLEED — which is the writer's signal to paint it
      // outside the clip group, so the fill reaches the corners the mask cuts away.
      val backgroundRect =
        if (contentShape != null) null
        else if (deviceBg != null && (fullBleed || (clip == null && capsule == null)))
          FigmaSvgRect(
            x = extent.minX,
            y = extent.minY,
            width = extent.maxX - extent.minX,
            height = extent.maxY - extent.minY,
          )
        else null
      // A crop whose rect lies entirely outside the rendered frame has no pixels to take — the
      // connector would write a 1×1 transparent placeholder for an `<image>` the canvas no longer
      // shows. Drop those targets so an off-viewport lazy item costs neither a PNG nor a dangling
      // reference (issue #2853). Targets carrying their own captured bytes are unaffected: their
      // pixels come with the payload, not out of the frame.
      // Filtered against the **final canvas**, not the frame: the two must agree, or the SVG
      // references a PNG nobody writes. The clamp above falls back to the drawn extent when content
      // lies entirely outside the frame, and in that case those layers — `<image>` included — are
      // still on the canvas and still need their crops. With no frame the canvas *is* the drawn
      // extent, so every target is inside it and nothing is dropped.
      val targets =
        ctx.rasterTargets.filter {
          it.pngBase64 != null ||
            (it.right > extent.minX &&
              it.left < extent.maxX &&
              it.bottom > extent.minY &&
              it.top < extent.maxY)
        }
      // Filtering only the write targets strands the corresponding `<image href>` in the layer
      // tree: the producer correctly skips the crop, but the published SVG still requests it. This
      // happened for every below-fold Image in a clamped lazy screen (issue #3147). Remove only the
      // raster paint whose target was dropped; keep the layer's vector shape/text/children intact.
      val retainedRasterHrefs = targets.mapTo(mutableSetOf()) { it.href }
      val retainedRoot = rootLayer.withRasterHrefs(retainedRasterHrefs)
      return FigmaSvgModel(
        root = retainedRoot,
        minX = extent.minX,
        minY = extent.minY,
        width = (extent.maxX - extent.minX) + effectivePadding * 2,
        height = (extent.maxY - extent.minY) + effectivePadding * 2,
        padding = effectivePadding,
        rasterTargets = targets,
        roundClip = clip,
        capsuleClip = capsule,
        deviceBackground = deviceBg,
        backgroundShape = contentShape,
        backgroundRect = backgroundRect,
      )
    }

    /**
     * The shallowest layer that declares a shape of its own — a corner radius, a circle, or a
     * sampled outline. Depth-first from the root, so a wrapper Box with no shape hands off to the
     * component inside it.
     */
    private fun FigmaSvgLayer.outermostShapedLayer(): FigmaSvgLayer? =
      if (circle || cornerRadiiPx != null || shapePathData != null) this
      else children.firstNotNullOfOrNull { it.outermostShapedLayer() }

    private fun FigmaSvgLayer.withRasterHrefs(retained: Set<String>): FigmaSvgLayer =
      copy(
        raster = raster?.takeIf { it.href in retained },
        background = background?.takeIf { it.href in retained },
        children = children.map { it.withRasterHrefs(retained) },
      )

    /** Build inputs + the accumulating raster-target list, threaded through the walk. */
    private class BuildContext(
      val textByNodeId: Map<String, FigmaSvgText>,
      val colorNames: Map<String, String>,
      val density: Float,
      val fontScale: Float,
      val rasterComponents: Set<String>,
      val rasterHref: (String) -> String,
      val captureCanvasDraws: Boolean = false,
      val rasterTargets: MutableList<FigmaSvgRasterTarget> = mutableListOf(),
    )

    /**
     * The emitter-native [FigmaSvgVector] for a captured [LayoutInspectorVectorGraphic], or null
     * when it carries nothing paintable — no paths, a degenerate viewport, or only
     * gradient/brush-filled paths (whose colour the capture left null, matching the
     * vector-vs-raster rule: what we can't resolve to a flat paint, we don't emit as vector).
     */
    private fun LayoutInspectorVectorGraphic.toFigmaSvgVector(
      layoutWidth: Int,
      layoutHeight: Int,
      fillBounds: Boolean,
      scaleX: Double = 1.0,
      scaleY: Double = 1.0,
      flipX: Boolean = false,
      flipY: Boolean = false,
    ): FigmaSvgVector? {
      if (viewportWidth <= 0f || viewportHeight <= 0f) return null
      val emittable =
        paths
          .filter { it.pathData.isNotBlank() && (it.fillArgb != null || it.strokeArgb != null) }
          .map {
            FigmaSvgVectorPath(
              pathData = it.pathData,
              fillArgb = it.fillArgb,
              fillAlpha = it.fillAlpha,
              strokeArgb = it.strokeArgb,
              strokeWidth = it.strokeWidth,
              strokeAlpha = it.strokeAlpha,
              strokeCap = it.strokeCap,
              strokeJoin = it.strokeJoin,
              evenOdd = it.evenOdd,
            )
          }
      return if (emittable.isEmpty()) null
      else
        FigmaSvgVector(
          viewportWidth = viewportWidth,
          viewportHeight = viewportHeight,
          layoutWidth = layoutWidth,
          layoutHeight = layoutHeight,
          fillBounds = fillBounds,
          scaleX = scaleX,
          scaleY = scaleY,
          flipX = flipX,
          flipY = flipY,
          fromDrawCapture = fromDrawCapture,
          // A draw capture is imperative chrome (a slider groove, a progress arc), never an
          // `ImageVector` — so it can't be a Material icon whatever its name says.
          materialIcon = if (fromDrawCapture) null else MaterialIconRef.parse(vectorName),
          paths = emittable,
        )
    }

    private fun LayoutInspectorNode.toLayer(
      ctx: BuildContext,
      parentBounds: LayoutInspectorBounds? = null,
      /**
       * The box of the nearest ancestor that actually clips its children (a `Modifier.clip`), or
       * null when nothing above this node clips. Pixels outside it cannot be in the frame, which is
       * what makes it — and not the immediate parent's box — the bound a raster crop can't exceed.
       */
      clipBounds: LayoutInspectorBounds? = null,
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
      // The box the frame was MASKED in — the clipping coordinator's own rect. It is what the
      // subtree inherits and what the emitted `<clipPath>` is cut from, so it stays whole: a
      // `clip(CircleShape)` mask must keep the circle's real box, and a child that legitimately
      // draws in a part of the viewport this node's own paint doesn't cover must not be trimmed.
      val maskBox = clipModifierBounds()
      // …while the node's OWN box is that mask narrowed to what it actually paints, so a node
      // whose fill is smaller than its clip on the un-overflowing axis doesn't spread across
      // margins the render leaves blank.
      val renderedClipBox = maskBox?.let { intersectBounds(paintedExtent(), it) ?: it }
      val bounds = renderedClipBox ?: recoverBounds(parentBounds)
      // Everything below this node — the mask, the inherited clip, the box children place
      // against — is the container the render clipped in, not this node's narrower paint.
      val containerBounds = maskBox ?: bounds
      // Anything the export derives from a *token* or from the measured `size` is an
      // un-transformed value, while `bounds` is the rect as **drawn**. Under a draw-time
      // `graphicsLayer` scale — a Wear `TransformingLazyColumn` item shrunk toward the curved edge
      // — the render draws those measured values at `× scale`, so each is scaled into drawn space
      // before it is used (issue #2615). Identity (`1.0`) for the overwhelming majority of nodes,
      // where every expression below is unchanged.
      // Geometry magnitudes shrink/grow with the layer; a negative captured axis additionally
      // mirrors vector content but must never turn lengths (corner radii, text sizes) negative.
      val signedScaleX = transform?.scaleX?.toDouble() ?: 1.0
      val signedScaleY = transform?.scaleY?.toDouble() ?: 1.0
      val scaleX = abs(signedScaleX)
      val scaleY = abs(signedScaleY)
      // A corner radius is a single length against a box scaled on both axes; with the uniform
      // scale Wear's edge transform applies, the mean is exactly that scale.
      val scaleMean = (scaleX + scaleY) / 2.0
      val (opacity, contentOpacity) = orderedOpacities()
      // An **active placeholder block** (issue #2646): the loading state, where the Wear/M3
      // `Modifier.placeholder` paints its block over the content and the content itself is faded
      // out of the render. Emit the placeholder as its own editable layer — a rounded rect in the
      // placeholder's own colour and shape — and drop the subtree, rather than baking the
      // composited frame into an `<image>`. This is the one state in which a placeholder-shaped
      // rect is the correct export; the ideal state falls through and keeps its real content (the
      // placeholder contributes no shape, no fill, and — via `hasCustomDraw` — no raster).
      //
      // The **shimmer** never takes this path, active or not. It rides on the container's chain (a
      // placeholdered `TitleCard` carries it on the card itself) and only sweeps *over* whatever is
      // beneath, so replacing the container with a block would erase the card's own background and
      // every child. Its whole contribution is negative: no container tokens, no raster.
      placeholder
        ?.takeIf { it.visible == true && it.kind == PlaceholderModifiers.KIND_PLACEHOLDER }
        ?.let { ph ->
          return FigmaSvgLayer(
            name = "${layerName()} Placeholder",
            left = bounds.left,
            top = bounds.top,
            right = bounds.right,
            bottom = bounds.bottom,
            fill = ph.colorArgb?.let { argbToColor(it, ctx.colorNames) },
            // The placeholder's corner is a measured length like any other token, so it rides the
            // node's draw-time scale (issue #2615) — a placeholdered card near a round face's edge
            // is drawn shrunk, corner included.
            cornerRadiiPx =
              if (ph.shape == "circle") null
              else
                (ph.cornerRadius?.let { parseCornersPx(it, ctx.density) }
                    ?: ph.cornerRadiusPx?.let { parseRawCornersPx(it) })
                  ?.map { it * scaleMean },
            circle = ph.shape == "circle",
            cut = ph.shape == "cut",
            // Deliberately NOT the node's own opacities: an active `Modifier.placeholder` fades the
            // content it covers to `alpha = 0` through its own `graphicsLayer`, so inheriting that
            // would emit an invisible block. The block is drawn outside that fade, fully opaque.
          )
        }
      // An `Icon`/`Image` whose `ImageVector` the inspector captured emits as editable `<path>`
      // layers rather than a raster crop — the vector alternative to the opaque-by-name fallback
      // below. Placed before the raster branch so a vector-backed icon never rasterises; a
      // bitmap-backed one (no `vectorGraphic`) falls through and rasters as before. An empty/
      // gradient-only capture yields null and also falls through.
      // …unless something draws *over* that icon which the vector model doesn't represent. Jetsnack
      // tints its gradient icons with `Modifier.drawWithContent { drawContent(); drawRect(brush,
      // blendMode = Plus/Darken) }`; a blend-mode composite over arbitrary content has no faithful
      // SVG equivalent, and emitting the bare `ImageVector` painted the untinted (black) glyph the
      // PNG never shows. Raster the node so the tint survives (issue #2852).
      //
      // Only for an `ImageVector`: when the paths were recorded *from* the draw lambda the modifier
      // is already fully represented by them, and rastering would throw away the editable capture
      // the recorder just made. Hybrid mode only — with no frame to crop from, the untinted vector
      // still beats a broken `<image>` reference.
      if (ctx.captureCanvasDraws && vectorGraphic?.fromDrawCapture == false && hasCustomDraw()) {
        val region = drawnOverlayRegion(bounds, clipBounds)
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
          opacity = opacity,
          contentOpacity = contentOpacity,
        )
      }
      // The box the painter *draws* into, which for a padded `Icon` is not the node's box at all
      // (issue #2853). Jetchat writes `Icon(modifier = Modifier.padding(8.dp).size(56.dp))` inside
      // an `IconButton`, and `Modifier.padding` before the paint insets what the painter fills —
      // so the node measures (and places) the whole button while the glyph is drawn into a much
      // smaller rect. Fitting the vector to the node box is what oversized Jetchat's five action
      // icons and its microphone, in both the isolated and the embedded container.
      // Null when the capture doesn't state one (an older file, a draw-captured vector, a detached
      // coordinate) — then nothing changes and the node's own box and measured `size` still decide.
      val painted = if (fromDrawCapture()) null else paintedVectorRegion(bounds)
      vectorGraphic
        ?.toFigmaSvgVector(
          // The painted region is a *drawn* rect, so it already carries any graphics-layer scale;
          // the emitter's placed/layout ratio then cancels to 1 and the vector fits it exactly.
          layoutWidth = painted?.let { it.right - it.left } ?: size.width,
          layoutHeight = painted?.let { it.bottom - it.top } ?: size.height,
          fillBounds = hasFillBoundsContentScale(),
          scaleX = scaleX,
          scaleY = scaleY,
          flipX = signedScaleX < 0.0,
          flipY = signedScaleY < 0.0,
        )
        ?.let { vec ->
          val box = painted ?: bounds
          return FigmaSvgLayer(
            name = layerName(),
            left = box.left,
            top = box.top,
            right = box.right,
            bottom = box.bottom,
            vector = vec,
            opacity = opacity,
            contentOpacity = contentOpacity,
          )
        }
      // A `drawWithContent` that clips, masks, fades, clears or omits its descendants
      // cannot be reconstructed by layering the node's isolated background under editable child
      // SVG. The connector detects that capability by replaying the draw with a calibration
      // pattern. In hybrid mode preserve this complex composited region from the frame.
      // Pass-through draws, background-only draws and color overlays keep their editable
      // descendants; flattening those would discard representable vector structure for no
      // visibility benefit.
      if (ctx.captureCanvasDraws && modifiesDrawnContent) {
        // [drawnOverlayRegion], not the node's placed box: the draw being preserved is the
        // modifier's, and a modifier can cover more than the layout node it hangs off. Wear's
        // `EdgeButton` is the case that proves it — a trailing
        // `layout`/`ScaleAndAlignContentElement`/`SizeElement` chain shrinks the *layout node* down
        // to its label while `paint`, `drawWithContent` and the `EdgeButtonShape` `graphicsLayer`
        // all still cover the full screen-hugging capsule. Cropping the placed box took a 69×36
        // sliver from behind the word "Start" and threw the capsule away. The union never shrinks
        // below the node box (the content still has to be inside the crop) and is held to the
        // nearest clipping ancestor, so an ordinary overlay node crops exactly as before.
        //
        // Held to the node's **own** mask as well as its ancestors'. A node that clips itself
        // tighter than its draw modifier reports (the lookahead / `requiredSize` shapes
        // [clipModifierBounds] exists for) would otherwise have the union hand back pixels its own
        // clip removes — frame pixels belonging to whatever sits around it — and the raster leaf
        // this returns carries no mask of its own to trim them again.
        val ownAndAncestorClip =
          when {
            maskBox == null -> clipBounds
            clipBounds == null -> maskBox
            else -> intersectBounds(maskBox, clipBounds) ?: maskBox
          }
        val region = drawnOverlayRegion(bounds, ownAndAncestorClip)
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
          opacity = opacity,
          contentOpacity = contentOpacity,
        )
      }
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
          opacity = opacity,
          contentOpacity = contentOpacity,
        )
      }
      // A container filled by paint the token model cannot flatten — a non-ColorPainter
      // `Modifier.paint`, or a brush-backed `Modifier.background` — leaves `backgroundColor`
      // unresolved, so the fill would silently vanish. Fall back to the frame in hybrid mode:
      // capture the complete painted region as an `<image>` and drop its subtree. Rasterising the
      // complete layer is deliberate for brush containers with text/children: a background-only
      // crop is unavailable, and keeping editable descendants would draw them twice.
      if (
        ctx.captureCanvasDraws &&
          tokens?.backgroundColor == null &&
          // A brush we *could* read is emitted as a real `<linearGradient>`; only an unparseable
          // one (radial / sweep / shader / an image painter) still needs the frame crop (#2852).
          tokens?.backgroundGradient == null &&
          hasUnvectorizablePaintFill()
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
          opacity = opacity,
          contentOpacity = contentOpacity,
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
      // Nothing for the vector model to emit: no captured vector, no fill, no border, and no text
      // of its own. Only then is a bare `drawsContent` evidence that the pixels would otherwise be
      // lost — see [hasDelegatedDrawOnly]. The text check has to include the *modifier* projection,
      // not just [BuildContext.textByNodeId]: a Wear picker separator is a `Text` under
      // `clearAndSetSemantics`, so it has no semantics text to match and its glyph arrives from the
      // layout capture alone — rastering it emits the separator twice, once as pixels and once as
      // the live `<text>`.
      val nothingElseToDraw =
        vectorGraphic == null &&
          tokens?.backgroundColor == null &&
          tokens?.backgroundGradient == null &&
          tokens?.borderColor == null &&
          tokens?.borderGradient == null &&
          modifierText(ctx) == null
      val background =
        if (
          ctx.captureCanvasDraws &&
            (hasCustomDraw() || hasDelegatedDrawOnly(nothingElseToDraw)) &&
            children.isEmpty() &&
            ctx.textByNodeId[nodeId] == null
        ) {
          val href = ctx.rasterHref(nodeId)
          val region = drawnRegion()
          ctx.rasterTargets.add(
            FigmaSvgRasterTarget(nodeId, href, region.left, region.top, region.right, region.bottom)
          )
          FigmaSvgBackgroundRaster(href, region.left, region.top, region.right, region.bottom)
        } else
        // The isolated capture of the node's own draw (issue #2937) — the same `<image>` slot,
        // filled from pixels the connector re-drew rather than cropped. It fills exactly the holes
        // the branch above leaves: a node whose draw has children or text (the crop would bake
        // them in and the vector children would then draw twice), and any node at all when there
        // is no frame to crop from. Its pixels carry nothing but this node's own paint, so
        // children stay editable on top of it.
        //
        // …except where that paint is *already* exported as live text. A Wear `CurvedLayout` /
        // `TimeText` paints its runs through a draw modifier, and the export re-emits exactly
        // those runs as `<textPath>` from [curvedTexts] — so laying the capture underneath would
        // draw the clock twice, once as pixels and once as live text. The same hazard applies to a
        // node whose own draw includes its straight text (a Jetchat `Conversation/Input` field):
        // the isolated re-draw bakes the text into its pixels while the export still emits the live
        // `<text>` from [textByNodeId], visibly doubling "Message #composers" (issue #2853). In
        // both cases the live text wins and the raster is dropped — the same rule the
        // `vectorGraphic` tiers follow. A drawn *container* whose text lives on child nodes keeps
        // its background: its own `textByNodeId` entry is null, so the capture rides underneath the
        // still-editable children.
        drawRaster
            ?.takeIf { curvedTexts.isEmpty() && ctx.textByNodeId[nodeId] == null }
            ?.let { captured ->
              val href = ctx.rasterHref(nodeId)
              ctx.rasterTargets.add(
                FigmaSvgRasterTarget(
                  nodeId = nodeId,
                  href = href,
                  left = captured.left,
                  top = captured.top,
                  right = captured.right,
                  bottom = captured.bottom,
                  pngBase64 = captured.pngBase64,
                )
              )
              FigmaSvgBackgroundRaster(
                href = href,
                left = captured.left,
                top = captured.top,
                right = captured.right,
                bottom = captured.bottom,
                fromFrame = false,
                // Where the draw sits in the chain decides whether it lands over or under the token
                // shape — an RC component carries both (a `BackgroundModifierOperation` lowers to
                // `Modifier.background`, its draw-content ops to an inner `drawWithContent`), and
                // defaulting to "under" would hide the capture behind the token rect.
                aboveShape = drawPaintsOverTokenShape(),
                clipToShape = drawIsInsideClip(),
                clipBounds = drawClipBounds(),
              )
            }
      val fill = tokens?.backgroundColor?.let { argbToColor(it, ctx.colorNames) }
      // Gradients ride alongside the flat tokens: the shape below references them as SVG defs, so
      // a brush-painted container stays an editable vector layer (issue #2852).
      val fillGradient = tokens?.backgroundGradient
      val strokeGradient = tokens?.borderGradient
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
          (tokens?.cornerRadius?.let { parseCornersPx(it, ctx.density) }
              // A `RoundedCornerShape(<px>f)` has no dp radius; its raw-pixel corners ride on
              // `cornerRadiusPx` and map straight to layer space with no density conversion.
              ?: tokens?.cornerRadiusPx?.let { parseRawCornersPx(it) })
            ?.map { it * scaleMean }
      // Shadow elevation (dp) → px for the render's drop shadow.
      val elevationPx =
        tokens?.elevation?.removeSuffix("dp")?.toDoubleOrNull()?.let {
          it * ctx.density * scaleMean
        } ?: 0.0
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
      // The growth signals below are measured, un-transformed extents too, so — like the tokens
      // above — each is scaled into drawn space before it competes with `bounds`. Without this the
      // export grew every edge-scaled item back to its full measured size while keeping the
      // compressed placement, and neighbouring items overlapped into one merged blob (issue #2615).
      val boundsW = bounds.right - bounds.left
      val boundsH = bounds.bottom - bounds.top
      // A node whose paint modifiers sit *after* a `Modifier.padding`
      // (`padding(4.dp).clip(…).border(…).background(…)` — Jetsnack's gradient-tinted icon button)
      // draws its shape in the padded box, which is exactly the node's placed `bounds`. But its
      // measured `size` still spans the padding, so the growth heuristic below would inflate the
      // ring back out to the padded root (the 85×85-vs-63×63 defect). Suppress the growth for such
      // a
      // node so the ring stays on the inner control it actually rings (issue #2852).
      // Asked per axis: a leading padding insets only the axes it actually pads. Wear's
      // `CompactButton` pads 8dp `top`/`bottom` *before* its fill and 12dp `start`/`end` *after*
      // it, so the pill it draws is the placed height but the measured width — one flag for both
      // axes squashed it to the narrow content box (issue #3573).
      val paddedPaintX = tokens?.paintInset?.insetsPaintHorizontally() == true
      val paddedPaintY = tokens?.paintInset?.insetsPaintVertically() == true
      val minWidthPx =
        tokens?.minWidth?.removeSuffix("dp")?.toDoubleOrNull()?.let { it * ctx.density * scaleX }
      val minHeightPx =
        tokens?.minHeight?.removeSuffix("dp")?.toDoubleOrNull()?.let { it * ctx.density * scaleY }
      val touchInflated = hasMinimumInteractiveSize()
      // [clipModifierBounds] already narrowed this node to the box the frame was drawn in, and its
      // measured `size` is exactly the too-large signal that narrowing rejected (a scroll
      // container's `size` is its whole content). Growing back to it would undo the fix (#3056).
      val ignoreMeasured = touchInflated || renderedClipBox != null
      val measuredW =
        if (ignoreMeasured) boundsW
        else
          parentBounds?.let { minOf((size.width * scaleX).roundToInt(), it.right - it.left) }
            ?: boundsW
      val measuredH =
        if (ignoreMeasured) boundsH
        else
          parentBounds?.let { minOf((size.height * scaleY).roundToInt(), it.bottom - it.top) }
            ?: boundsH
      val drawW = maxOf(boundsW, minWidthPx?.roundToInt() ?: 0, measuredW)
      val drawH = maxOf(boundsH, minHeightPx?.roundToInt() ?: 0, measuredH)
      // A brush fill/ring counts as painted here just like a flat one. `Modifier.background(brush,
      // …)` resolves no `backgroundColor` — the brush rides on `fillGradient` instead — so gating
      // the growth on the flat tokens alone left every gradient container pinned to its placed
      // `bounds`. Pocket Casts' `GradientRowButton`
      // (`background(brush, RoundedCornerShape(12.dp)).clickable().padding(16.dp)`) paints its
      // gradient across the whole node and pads only its label, but exported as a 966×56 pill
      // floating inside the 1050×140 button the PNG draws edge to edge (issue #3569).
      val paints = fill != null || stroke != null || fillGradient != null || strokeGradient != null
      val mayExpand = paints && ctx.textByNodeId[nodeId] == null
      // The measured paint box supersedes every guess below (issue #3572). It is the rect the
      // fill/ring modifier's own coordinator reports, so it needs no growth, no `paintInset`
      // suppression and no parent clamp — a modifier cannot paint outside the coordinator it hangs
      // off. Still held to the node's own mask: a clipped node's fill stops at the clip, which the
      // coordinator box doesn't know about.
      //
      // Scoped to the same nodes the heuristic governed — a node that paints and carries no text of
      // its own — so a text-bearing layer keeps the layout box its baseline was placed against.
      //
      // Anything that re-places a captured node has to carry `paintBox` with it — it is root-space
      // px like `bounds`, so a rewrite that moves one must move the other
      // (`WearScrollSliceStitcher`
      // is the one place that does).
      val measuredPaintBox =
        tokens
          ?.paintBox
          ?.takeIf { mayExpand }
          ?.let { box -> maskBox?.let { intersectBounds(box, it) } ?: box }
      // Center the grown shape on the placed bounds, then pull the whole rectangle back inside the
      // parent's placed bounds. Clamping only the grown *width/height* (above) isn't enough to keep
      // the promise that a child never paints beyond its parent: a fill whose bounds sit off-center
      // in its parent gets centered on its own bounds and would slide past the parent edge (parent
      // 0..100, child 0..40, grown width 100 → centered at -30..70). Clamp the top-left into
      // `[parent.left, parent.right - drawW]` so the rectangle stays within the parent whenever it
      // fits (and pins to the parent origin in the degenerate case where the grown shape is wider
      // than the parent). No parent (a root node) leaves the centered placement untouched.
      //
      // The parent clamp is only meaningful while the node is actually *inside* its parent. A list
      // item scrolled past the viewport edge is placed beyond the parent's rect on purpose, and
      // clamping would teleport it back into view on top of its neighbour — so the growth is also
      // held to the window where the grown box still covers the node's own `bounds`
      // (`[bounds.end - draw, bounds.start]`), and that window wins when the two can't both be
      // satisfied. The node's own placement is ground truth; the parent clamp is a guard against
      // *centering* drift, not a licence to move a node somewhere it never drew (issue #2615).
      val expandW = mayExpand && measuredPaintBox == null && !paddedPaintX && drawW > boundsW
      val expandH = mayExpand && measuredPaintBox == null && !paddedPaintY && drawH > boundsH
      val drawLeft =
        measuredPaintBox?.left
          ?: if (!expandW) bounds.left
          else
            growthOrigin(
              centered = (bounds.left + bounds.right - drawW) / 2,
              start = bounds.left,
              end = bounds.right,
              extent = drawW,
              parentStart = parentBounds?.left,
              parentEnd = parentBounds?.right,
            )
      val drawTop =
        measuredPaintBox?.top
          ?: if (!expandH) bounds.top
          else
            growthOrigin(
              centered = (bounds.top + bounds.bottom - drawH) / 2,
              start = bounds.top,
              end = bounds.bottom,
              extent = drawH,
              parentStart = parentBounds?.top,
              parentEnd = parentBounds?.bottom,
            )
      val drawRight = measuredPaintBox?.right ?: if (expandW) drawLeft + drawW else bounds.right
      val drawBottom = measuredPaintBox?.bottom ?: if (expandH) drawTop + drawH else bounds.bottom
      val builtChildren = children.map {
        // A `Modifier.clip` here becomes the clip box its subtree inherits; nested clips
        // intersect. Without one the subtree keeps whatever (if anything) clipped it above —
        // an ordinary container does NOT clip, and a child overflowing it still draws.
        val childClip =
          if (tokens?.clipsContent == true)
            intersectOrNull(containerBounds, clipBounds) ?: containerBounds
          else clipBounds
        it.toLayer(ctx, containerBounds, childClip)
      }
      // The ancestor half of the #2853 double-draw rule. That rule drops a node's *own* raster when
      // the node's text stays live; the mirror case is a node whose text is live while a
      // **descendant** falls back to a whole-node raster that covers this box — an
      // `OutlinedTextField`
      // is the one that proves it, its `drawWithContent` flattening the field (border, label and
      // the
      // value's glyphs) into one `<image>` while the value is also emitted as an editable `<text>`
      // from the node above. Both drew, offset by the baseline, so the value read doubled. The
      // raster owns those pixels, so the duplicate live text goes (issue #3573).
      val rasterizedAway = builtChildren.any {
        it.rasterCovers(drawLeft, drawTop, drawRight, drawBottom)
      }
      return FigmaSvgLayer(
        name = layerName(),
        left = drawLeft,
        top = drawTop,
        right = drawRight,
        bottom = drawBottom,
        fill = fill,
        fillGradient = fillGradient,
        strokeGradient = strokeGradient,
        stroke = stroke,
        // Stroke width: use the captured `Modifier.border` width (dp × density) when present, so a
        // 2dp outline (an off-state `Switch` track) isn't drawn as a 1dp hairline. Fall back to a
        // single dp scaled into the render's px space — the width of a Material hairline outline —
        // when the border width wasn't captured. `coerceAtLeast(1.0)` keeps a visible hairline at
        // density < 1.
        // A *gradient* border is a stroke too — it just resolved to a brush instead of a flat
        // colour — so it takes the same captured width. Keying this on `stroke` alone left every
        // brush ring at the 1px data-class default (Jetsnack's 2dp gradient ring drew as a
        // hairline).
        strokeWidthPx =
          if (stroke != null || strokeGradient != null) {
            val dp = tokens?.borderWidth?.removeSuffix("dp")?.toDoubleOrNull()
            ((dp?.let { it * ctx.density } ?: ctx.density.toDouble()) * scaleMean).coerceAtLeast(
              1.0
            )
          } else 1.0,
        cornerRadiiPx = corners,
        circle = circle,
        cut = cut,
        // Only meaningful when no corner radii resolved — the resolver already withholds it
        // otherwise, and the renderer prefers radii regardless, so an understood shape keeps its
        // editable rounded rect. Needs no scaling of its own: the path is normalised to the unit
        // box, so the renderer maps it onto whatever box this layer ends up with — including the
        // draw-time scale and the grow/inset heuristics applied above (issue #2615).
        shapePathData = tokens?.shapePath?.takeIf { corners == null && !circle },
        // The captured typography is in *measured* sp/px, so a scaled node's glyphs are drawn
        // smaller than the capture says — scale the metrics with the box or the text overflows the
        // shrunken card it sits in (issue #2615).
        text =
          (ctx.textByNodeId[nodeId] ?: modifierText(ctx))
            ?.takeUnless { rasterizedAway }
            ?.scaledBy(scaleX, scaleY),
        background = background,
        elevationPx = elevationPx,
        opacity = opacity,
        contentOpacity = contentOpacity,
        curvedTexts = curvedTexts.map { it.scaledInto(bounds, scaleX, scaleY, scaleMean) },
        clipChildren = tokens?.clipsContent == true,
        // Carried only when the mask really is a different rect from the layer's own box (the
        // narrowed-to-paint case above); every ordinary layer masks with its own box and leaves
        // this null.
        clipBox = (maskBox ?: background?.clipBounds)?.takeIf { it != bounds },
        children = builtChildren,
      )
    }

    /**
     * True when a whole-node [FigmaSvgLayer.raster] somewhere in this subtree covers the given box
     * entirely — so anything that box would have drawn is already in those pixels (issue #3573).
     * Containment, not overlap: a raster that merely touches the box (a drawn slider groove under a
     * label, say) redraws none of it, and its neighbour's text must stay live.
     */
    private fun FigmaSvgLayer.rasterCovers(
      left: Int,
      top: Int,
      right: Int,
      bottom: Int,
      ancestorsVisible: Boolean = true,
      ancestorsCover: Boolean = true,
    ): Boolean {
      // Visibility and clipping are inherited. A raster hidden or trimmed by any intermediate
      // group cannot replace live text owned by an ancestor outside that visible region.
      val visible = ancestorsVisible && opacity > 0.0 && contentOpacity > 0.0
      val coversTarget = ancestorsCover && clipBox.covers(left, top, right, bottom)
      return (raster != null &&
        visible &&
        coversTarget &&
        this.left <= left &&
        this.top <= top &&
        this.right >= right &&
        this.bottom >= bottom) ||
        children.any {
          it.rasterCovers(
            left,
            top,
            right,
            bottom,
            ancestorsVisible = visible,
            ancestorsCover = coversTarget,
          )
        }
    }

    /** Whether this mask (null = unmasked) leaves the given box entirely visible. */
    private fun LayoutInspectorBounds?.covers(
      left: Int,
      top: Int,
      right: Int,
      bottom: Int,
    ): Boolean =
      this == null ||
        (this.left <= left && this.top <= top && this.right >= right && this.bottom >= bottom)

    /**
     * Editable-text fallback for a Compose Text whose semantics were intentionally cleared.
     *
     * The normal semantics match remains authoritative because it carries measured px, wrapping and
     * spans. This path reads the compact TextStyle projection retained on the layout modifier only
     * when semantics has no text at all (notably Horologist TimePicker's visual separator).
     */
    private fun LayoutInspectorNode.modifierText(ctx: BuildContext): FigmaSvgText? {
      val props =
        modifiers.firstNotNullOfOrNull { modifier ->
          modifier.properties.takeIf { it["layoutText"]?.isNotBlank() == true }
        } ?: return null
      val content = props["layoutText"] ?: return null
      val fontSize = props["layoutTextFontSize"]
      return FigmaSvgText(
        content = content,
        fontSizePx =
          props["layoutTextFontSizePx"]?.toDoubleOrNull()
            ?: fontSize?.let { spToPx(it, ctx.density, ctx.fontScale) },
        fontFamily = props["layoutTextFontFamily"],
        fontWeight = props["layoutTextFontWeight"]?.toIntOrNull(),
        italic = props["layoutTextFontStyle"]?.contains("Italic", ignoreCase = true) == true,
        color = props["layoutTextColor"]?.let { argbToColor(it, emptyMap()) },
        lineHeightPx =
          props["layoutTextLineHeightPx"]?.toDoubleOrNull()
            ?: props["layoutTextLineHeight"]?.let {
              lineHeightToPx(it, fontSize, ctx.density, ctx.fontScale)
            },
        letterSpacingPx =
          props["layoutTextLetterSpacingPx"]?.toDoubleOrNull()
            ?: props["layoutTextLetterSpacing"]?.let {
              lineHeightToPx(it, fontSize, ctx.density, ctx.fontScale)
            },
      )
    }

    /**
     * Split evaluated graphics-layer alpha at the first drawing modifier. Compose modifier order is
     * outer-to-inner: `graphicsLayer().background()` fades the background and content, while
     * `background().graphicsLayer()` leaves the background outside the faded content group.
     */
    private fun LayoutInspectorNode.orderedOpacities(): Pair<Double, Double> {
      val alphas = modifiers.mapIndexedNotNull { index, modifier ->
        modifier.properties["alpha"]?.toDoubleOrNull()?.coerceIn(0.0, 1.0)?.let { index to it }
      }
      if (alphas.isEmpty()) {
        return (tokens?.opacity?.coerceIn(0.0, 1.0) ?: 1.0) to 1.0
      }
      val firstDraw =
        modifiers.indexOfFirst { it.isDrawingModifier() }.takeIf { it >= 0 } ?: modifiers.size
      val outer =
        alphas.filter { it.first < firstDraw }.fold(1.0) { acc, (_, alpha) -> acc * alpha }
      val content =
        alphas.filter { it.first >= firstDraw }.fold(1.0) { acc, (_, alpha) -> acc * alpha }
      return outer to content
    }

    private fun LayoutInspectorModifier.isDrawingModifier(): Boolean {
      val lower = name.lowercase()
      return lower == "background" ||
        lower.contains("backgroundelement") ||
        lower == "paint" ||
        lower.contains("painterelement") ||
        lower == "border" ||
        lower.contains("bordermodifier") ||
        lower.startsWith("draw")
    }

    private fun LayoutInspectorNode.hasFillBoundsContentScale(): Boolean =
      modifiers.any { modifier ->
        modifier.properties["contentScale"]?.contains("FillBounds", ignoreCase = true) == true
      }

    /**
     * The same text drawn at a node's draw-time scale: vertical metrics (size, line height,
     * baselines) follow [scaleY], horizontal ones (tracking, per-line left offsets) follow
     * [scaleX]. Identity scales return the text untouched.
     *
     * A styled span's own `fontSizePx` scales with the run it lives in: the emitter writes it as an
     * *overriding* `font-size` on that span's `<tspan>`, so leaving it at the captured size would
     * float un-shrunk glyphs over halved baselines.
     */
    private fun FigmaSvgText.scaledBy(scaleX: Double, scaleY: Double): FigmaSvgText {
      if (abs(scaleX - 1.0) < SCALE_EPSILON && abs(scaleY - 1.0) < SCALE_EPSILON) return this
      return copy(
        fontSizePx = fontSizePx?.times(scaleY),
        lineHeightPx = lineHeightPx?.times(scaleY),
        letterSpacingPx = letterSpacingPx?.times(scaleX),
        spans = spans?.map { it.copy(fontSizePx = it.fontSizePx?.times(scaleY)) },
        lines =
          lines?.map {
            it.copy(
              left = (it.left * scaleX).roundToInt(),
              baseline = (it.baseline * scaleY).roundToInt(),
            )
          },
      )
    }

    /**
     * A Wear curved run placed at the node's **drawn** geometry rather than its measured one.
     *
     * The capture states a curved run in root pixels, but computed *before* the node's draw-time
     * `graphicsLayer` — so a run on a transformed node describes where it would have been drawn
     * untransformed. Every other value on the layer is scaled into drawn space (issue #2615); the
     * curved runs were passed through raw, and the emitter builds the baseline arc straight from
     * them.
     *
     * That is what kept a scrolled-away `TimeText` in the export. Wear's `Modifier.scrollAway`
     * hides the clock by scaling it to half size and lifting it off the top of the screen, which
     * the capture records faithfully as `transform = 0.5` and a `bounds` box mostly above `y = 0` —
     * but the run still claimed the full-size arc centred on the frame, so the SVG drew a clock the
     * PNG does not have.
     *
     * The arc is concentric with the node's own box (a `CurvedLayout` lays its runs out around its
     * centre), so re-centring on the drawn [bounds] and scaling the radius and font size by
     * [scaleMean] is the whole transform. An untransformed node is left exactly as captured: its
     * `bounds` centre already *is* the captured centre, so the identity guard is a documented no-op
     * rather than a rounding hazard.
     */
    private fun LayoutInspectorCurvedText.scaledInto(
      bounds: LayoutInspectorBounds,
      scaleX: Double,
      scaleY: Double,
      scaleMean: Double,
    ): LayoutInspectorCurvedText {
      if (abs(scaleX - 1.0) < SCALE_EPSILON && abs(scaleY - 1.0) < SCALE_EPSILON) return this
      return copy(
        centerXPx = (bounds.left + bounds.right) / 2.0,
        centerYPx = (bounds.top + bounds.bottom) / 2.0,
        radiusPx = radiusPx * scaleMean,
        fontSizePx = fontSizePx * scaleMean,
      )
    }

    /**
     * Where a grown shape's leading edge lands on one axis.
     *
     * Starts from [centered] (the grown [extent] centred on the node's own `[start, end)` bounds)
     * and holds it to two windows:
     * - **own-bounds** `[end - extent, start]` — growth may only *inflate* the node's placement, it
     *   may never slide the shape off the box the node actually drew in;
     * - **parent** `[parentStart, parentEnd - extent]` — a child never paints beyond its parent.
     *
     * The parent window applies only where the two overlap. When they don't, the node is placed
     * outside its parent to begin with (a list item scrolled past the viewport edge) and the
     * own-bounds window wins — pulling such a node back inside would drop it on top of a neighbour.
     */
    private fun growthOrigin(
      centered: Int,
      start: Int,
      end: Int,
      extent: Int,
      parentStart: Int?,
      parentEnd: Int?,
    ): Int {
      val ownLo = minOf(end - extent, start)
      val ownHi = maxOf(end - extent, start)
      if (parentStart == null || parentEnd == null) return centered.coerceIn(ownLo, ownHi)
      val parentLo = parentStart
      val parentHi = maxOf(parentStart, parentEnd - extent)
      val lo = maxOf(ownLo, parentLo)
      val hi = minOf(ownHi, parentHi)
      return if (lo <= hi) centered.coerceIn(lo, hi) else centered.coerceIn(ownLo, ownHi)
    }

    /**
     * Collapse pure-grouping pass-through layers — a `<g>` that draws nothing (no
     * fill/stroke/text/raster/background, no shadow) and only wraps a *single* child — into that
     * child. Compose's `LayoutNode` tree stacks several such nodes per widget (a `Button` nests a
     * handful of internal boxes), so a 1:1 layer-per-node export reads as a deep pile of anonymous
     * `Box` groups. Dropping a wrapper that neither paints nor groups siblings is pixel-identical —
     * a bare grouping `<g>` carries no transform/clip/opacity, so removing it moves nothing — while
     * flattening the tree down to the layers that actually stand for something in the design.
     *
     * Two deliberate narrowings keep meaningful structure:
     * - the **root** frame is never dropped (it anchors the canvas); only its descendants collapse.
     * - a grouping node with **2+ children** is kept — it genuinely groups siblings, so it's real
     *   structure, not a redundant nesting level.
     */
    private fun collapsePassthroughGroups(root: FigmaSvgLayer): FigmaSvgLayer =
      root.copy(children = root.children.map { it.collapseSubtree() })

    private fun FigmaSvgLayer.collapseSubtree(): FigmaSvgLayer {
      var layer = copy(children = children.map { it.collapseSubtree() })
      while (layer.isPassthroughGroup && layer.children.size == 1) {
        layer = layer.children.single()
      }
      return layer
    }

    /** A layer that neither paints ([FigmaSvgLayer.paints]) nor casts a shadow — pure nesting. */
    private val FigmaSvgLayer.isPassthroughGroup: Boolean
      get() = !paints && elevationPx == 0.0

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

    /**
     * The tree with every **retired** subtree removed — a node Compose composed but did not
     * [place][LayoutInspectorNode.placed], and everything under it.
     *
     * A lazy container does not discard a row the moment it leaves the viewport: `SubcomposeLayout`
     * keeps the row composed, text and all, and simply stops placing it, so the capture still walks
     * it as an ordinary child of its parent. Compose never *draws* an unplaced node, but the export
     * did — and because an unplaced node's `LayoutCoordinates` report `(0,0,0,0)`, [recoverBounds]
     * (there for a *placed* subcomposed child whose coordinates were detached) then anchored the
     * retired content at its **parent's** origin, where it painted over whatever the parent really
     * draws. That is the JetNews `Screens/Article` article body reappearing inside the `TopAppBar`
     * over the hero image, and the JetLagged `1Y` tab reappearing at its card's top-left (#3324).
     *
     * Dropping the whole subtree is the right granularity: a child of an unplaced node is not drawn
     * either, whatever its own flag says. The root is kept regardless — an export of a tree whose
     * root reports unplaced should still emit the frame rather than nothing.
     *
     * Placement, not zero-area bounds, is the discriminator, so the recovery this protects keeps
     * working: a *placed* node whose bounds collapsed still recovers its rect from its measured
     * size, exactly as before.
     */
    private fun LayoutInspectorNode.withoutRetiredSubtrees(): LayoutInspectorNode =
      copy(children = children.filter { it.placed }.map { it.withoutRetiredSubtrees() })

    /** True when the composable name matches a [rasterComponents] fragment. */
    /**
     * True when the node's composable name carries an opaque, un-vectorisable component as a
     * CamelCase token — `AsyncImage`/`Image`, `IconButton`/`Icon`, `OutlinedTextField`/`TextField`.
     * The match is **case-sensitive** on purpose: the fragments and Compose component names are all
     * PascalCase, so a token boundary is an uppercase letter. A case-insensitive `contains` instead
     * false-matches a keyword buried across a lowercase→uppercase seam — e.g.
     * `MultiContentMeasurePolicyImpl` (the `SegmentedButton`/multi-content layout policy) contains
     * "icon" in "Mult**iCon**tent", which would raster the whole labelled subtree as if it were an
     * `Icon`.
     */
    private fun LayoutInspectorNode.isOpaque(rasterComponents: Set<String>): Boolean =
      rasterComponents.any {
        component.contains(it)
      }

    /**
     * `Modifier.minimumInteractiveComponentSize()` — the M3 touch-target expander. A node carrying
     * it has a measured `size` inflated up to 48dp while its background still paints at the smaller
     * visual `bounds`, so the fill-growth heuristic must not treat `size` as the paint extent.
     */
    private const val MIN_INTERACTIVE_MODIFIER = "minimumInteractiveComponentSize"

    /** Below this, a captured draw-time scale is float noise and everything is left as measured. */
    private const val SCALE_EPSILON = 0.001

    private fun LayoutInspectorNode.hasMinimumInteractiveSize(): Boolean = modifiers.any {
      it.name.equals(MIN_INTERACTIVE_MODIFIER, ignoreCase = true)
    }

    /**
     * The box of this node's **clipping** `graphicsLayer` (what `Modifier.clip(shape)` lowers to),
     * when that box is shorter or narrower than the node's own `bounds` — else null, which is every
     * ordinary node.
     *
     * A node's `bounds` come from its innermost coordinator, and under a lookahead chain
     * (`sharedBounds(… RemeasureToBounds) … .verticalScroll(…).skipToLookaheadSize()`) that
     * coordinator reports the **lookahead/content** extent, not the box the frame was drawn in:
     * Jetsnack's `Catalog/Filter screen` measured its scroll content 652dp tall inside a 450dp
     * `heightIn`. Every modifier coordinator OUTSIDE the scroll — the `clip` among them — still
     * reports the real 450dp viewport, so the clipping modifier's own box is the rendered
     * scroll-container rect the export must clip to. Without it the below-fold children stayed
     * visible in the SVG while the PNG clipped them (issue #3056).
     *
     * Containment in `bounds` is deliberately NOT required. Jetsnack's real chain ends
     * `…verticalScroll(…).clickable(…).background(…).padding(horizontal = 24.dp, vertical =
     * 16.dp).skipToLookaheadSize()`, so the innermost coordinator — the node's `bounds` — is the
     * *content* box: inset 24dp on each side by that trailing padding while overflowing the
     * viewport vertically. The rendered viewport is therefore 48dp **wider** than `bounds` at the
     * same time as it is shorter, and requiring `clip ⊆ bounds` rejected it — which is why the
     * first pass at this fixed the synthetic fixture but left the real screen leaking.
     *
     * What comes back is the **mask**: the clipping coordinator's rect, whole. The caller narrows
     * it by the node's [paintedExtent] to get the node's own box — a node whose paint is smaller
     * than its clip on the un-overflowing axis must not have its fill spread across margins the
     * render leaves blank — but keeps this rect for the emitted `<clipPath>`, the clip its subtree
     * inherits and the box its children place against. Jetsnack's sheet has the two coincide (its
     * `background` sits outside the trailing padding and is as wide as the clip); an ordinary
     * `size(100.dp).clip(…).requiredSize(50.dp, 200.dp).background(…)` is where they part.
     *
     * Only ever fires when the clip box *shrinks* an axis, which keeps it a no-op on ordinary
     * nodes: a plain chain places every coordinator outside the innermost one at or around it, so
     * an ordinary clip box is ≥ `bounds` on both axes and is skipped. An axis that shrinks means
     * something inside reported a size its own clip didn't honour — the lookahead case this exists
     * for.
     */
    private fun LayoutInspectorNode.clipModifierBounds(): LayoutInspectorBounds? {
      if (tokens?.clipsContent != true) return null
      val own = bounds
      if (own.right <= own.left || own.bottom <= own.top) return null
      val clip =
        modifiers
          .asSequence()
          .filter { it.properties["clip"] == "true" }
          .mapNotNull { it.bounds }
          .filter { clip ->
            clip.right > clip.left &&
              clip.bottom > clip.top &&
              // The clip must be this node's own rendered box, not a detached/unplaced coordinate:
              // it has to overlap the box the node was measured into.
              intersectBounds(clip, own) != null &&
              (clip.right - clip.left < own.right - own.left ||
                clip.bottom - clip.top < own.bottom - own.top)
          }
          // Multiple coordinators can clip the same node (a rounded surface around a scroll clip).
          // Their intersection is the actual visible region; for the nested, axis-aligned boxes
          // Compose emits here, the smallest area is the tightest final rendered viewport.
          .minByOrNull { (it.right - it.left).toLong() * (it.bottom - it.top).toLong() }
          ?: return null
      return clip
    }

    /**
     * The box this node's own paint covers: its [LayoutInspectorNode.bounds] unioned with the
     * placed box of every fill/stroke modifier it carries.
     *
     * A node's `bounds` come from its innermost coordinator, so a chain that ends in a layout
     * modifier (`…background(…).padding(…)`, Jetsnack's filter sheet) reports a box *smaller* than
     * the rect the background actually filled. Only modifiers that put pixels on screen count — a
     * `padding`/`size` coordinator is a layout box, not a painted one — and only those that overlap
     * the node's own box, so a detached (0,0,0,0) or not-yet-placed coordinate can't drag the
     * extent to the origin.
     */
    private fun LayoutInspectorNode.paintedExtent(): LayoutInspectorBounds {
      val own = bounds
      var acc = own
      modifiers.forEach { m ->
        val b = m.bounds ?: return@forEach
        if (m.name !in PAINTED_BOX_MODIFIERS && !PAINTED_BOX_CLASSES.any { m.name.startsWith(it) })
          return@forEach
        if (b.right <= b.left || b.bottom <= b.top) return@forEach
        if (intersectBounds(b, own) == null) return@forEach
        acc =
          LayoutInspectorBounds(
            left = minOf(acc.left, b.left),
            top = minOf(acc.top, b.top),
            right = maxOf(acc.right, b.right),
            bottom = maxOf(acc.bottom, b.bottom),
          )
      }
      return acc
    }

    /**
     * The modifier names that project a painter as a container fill: `Modifier.paint` (inspector
     * name `paint`, class-name fallback `PainterElement`) plus Coil's content painter — the
     * modifier `AsyncImage` actually draws through. Coil's `AsyncImage` never surfaces as a node
     * *name* the opaque-by-name matcher can hit: the library ships without composition source info,
     * so its `Layout` falls back to the measure-policy class — a lambda in coil's
     * `internal/utils.kt`, i.e. the layer reads `UtilsKt` — and the photo silently vanished from
     * the export (the Confetti `speakerdetails` sticker). Coil 3's `ContentPainterElement` names
     * itself `content` in its `inspectableProperties`; `ContentPainterModifier` is Coil 2's
     * element, whose `debugInspectorInfo` name is compiled out in release artifacts so it surfaces
     * as its class name. Both carry no `painter` property, so [hasUnvectorizablePaintFill] treats
     * them as an unreadable painter and the hybrid export crops the drawn region from the frame.
     */
    private val PAINT_FILL_MODIFIERS =
      setOf("paint", "PainterElement", "content", "ContentPainterElement", "ContentPainterModifier")

    /** Fill/stroke modifier names — the entries whose box is a *painted* rect. */
    private val PAINTED_BOX_MODIFIERS =
      PAINT_FILL_MODIFIERS + setOf("background", "BackgroundElement", "border")

    /** Release builds compile the inspector name out, leaving these element class-name prefixes. */
    private val PAINTED_BOX_CLASSES = setOf("BackgroundElement", "BorderModifier")

    /**
     * True when this node is filled by paint we can't turn into a flat colour.
     *
     * A brush-backed `Modifier.background` is always unvectorisable in the current model. The
     * connector explicitly carries a `brush` property even when Compose compiled inspector metadata
     * out, so a gradient cannot be mistaken for an unpainted node.
     *
     * For `Modifier.paint`, the token resolver only reads a plain
     * [androidx.compose.ui.graphics.painter.ColorPainter] (whose captured string is
     * `ColorPainter(color=Color(…))`); any other painter — a component's private `Painter`, a
     * `BitmapPainter`, a gradient — stringifies to a class name and leaves the fill unresolved.
     * Recognising the one painter we CAN vectorise (and rastering everything else) keeps this free
     * of per-component knowledge: a painter present but of any other form ⇒ raster.
     *
     * The one caveat is a `ColorPainter` carrying a `colorFilter`: the string still starts with
     * `ColorPainter(`, but the resolver deliberately leaves the fill unresolved because a
     * re-tinting filter can't collapse to a flat token — so that (visible) fill must raster too,
     * not be treated as vectorisable. A `ColorPainter` with no filter that still didn't resolve is
     * a fully transparent fill (no visible pixels) and is left alone.
     *
     * A matched modifier with no `painter` property at all is also unvectorisable: Coil's content
     * painter exposes `request`/`imageLoader` but never the painter itself, and there's nothing a
     * vector export could read from it — raster.
     */
    private fun LayoutInspectorNode.hasUnvectorizablePaintFill(): Boolean {
      val brushBackground = modifiers.firstOrNull {
        (it.name == "background" || it.name == "BackgroundElement") &&
          it.properties["brush"]?.let { brush -> brush != "null" } == true
      }
      if (brushBackground != null) return true
      val paint = modifiers.firstOrNull { it.name in PAINT_FILL_MODIFIERS } ?: return false
      val painter = paint.properties["painter"] ?: return true
      if (!painter.startsWith("ColorPainter(")) return true
      val filter = paint.properties["colorFilter"]
      return filter != null && filter != "null"
    }

    /**
     * True when this node's captured vector came from its own draw lambda, not an `ImageVector`.
     */
    private fun LayoutInspectorNode.fromDrawCapture(): Boolean =
      vectorGraphic?.fromDrawCapture == true

    /**
     * The rect an `ImageVector` painter actually draws into — the placed bounds of the
     * `Modifier.paint` entry that carries it, which is the node's box *after* every layout modifier
     * ahead of it (issue #2853).
     *
     * That distinction is the whole point: `Modifier.padding(18.dp)` before the paint (Jetchat's
     * `RecordButton`) insets the drawn glyph to 20dp inside a 56dp node, and
     * `Modifier.padding(8.dp) .size(56.dp)` inside an `IconButton` (its `InputSelectorButton`) both
     * pads *and* clamps. In either case the node's own `bounds`/`size` describe the button, not the
     * icon, so fitting the viewport to them draws the glyph at the button's size — the oversized
     * mic and action icons.
     *
     * Null whenever the capture can't be trusted, leaving the node's own box and measured `size` to
     * decide exactly as before: no paint entry (an older layout-inspector file that carried no
     * modifier bounds), a degenerate rect, or one that isn't inside the node's own placed box — a
     * painter never paints outside the box it was measured in, so a rect that claims to is a
     * detached/not-yet-placed coordinate we must not fit to. That fallback is what keeps a
     * *clipped* vector square: with no painter rect, a drawn box smaller than the slot is a crop,
     * not a fit.
     */
    private fun LayoutInspectorNode.paintedVectorRegion(
      nodeBounds: LayoutInspectorBounds
    ): LayoutInspectorBounds? {
      val painted =
        modifiers.lastOrNull { it.name in PAINT_FILL_MODIFIERS && it.bounds != null }?.bounds
          ?: return null
      if (painted.right <= painted.left || painted.bottom <= painted.top) return null
      val inside =
        painted.left >= nodeBounds.left &&
          painted.top >= nodeBounds.top &&
          painted.right <= nodeBounds.right &&
          painted.bottom <= nodeBounds.bottom
      return painted.takeIf { inside }
    }

    /** The region a paint-fill painter actually covers — its modifier bounds, else the node box. */
    private fun LayoutInspectorNode.paintFillRegion(): LayoutInspectorBounds =
      modifiers
        .firstOrNull {
          (it.name in PAINT_FILL_MODIFIERS ||
            it.name == "background" ||
            it.name == "BackgroundElement") && it.bounds != null
        }
        ?.bounds ?: bounds

    /** The Compose modifiers that paint via an imperative Canvas the token export can't read. */
    private val DRAW_MODIFIERS = setOf("drawBehind", "drawWithContent", "drawWithCache")

    /**
     * True when the node paints through a custom Canvas draw (a `Canvas`, or a component drawing
     * its chrome via `Modifier.drawBehind {…}` like the progress/slider indicators).
     *
     * The placeholder's **own** draw doesn't count (issue #2646). A Wear/M3 `Modifier.placeholder`
     * draws through a `drawWithContent`, but in the ideal (content-loaded) state that draw is a
     * pass-through: the pixels under it are the node's own text/children, which the vector export
     * represents exactly. Rasterising it crops the composited frame and doubles whatever the vector
     * path already emitted (the "text rendered twice" bug, #2644). An *active* placeholder never
     * reaches here — [toLayer] returns its own vector layer first.
     *
     * Scoped to the entries [LayoutInspectorModifier.placeholder] marks, not to the whole node: a
     * `Modifier.drawBehind {…}.placeholder(state)` chain still paints its own imperative art into
     * the frame, and that art is not something the vector export can otherwise represent.
     */
    private fun LayoutInspectorNode.hasCustomDraw(): Boolean = modifiers.any { it.isCustomDraw() }

    /**
     * The v16 capability bit: the live modifier-node chain contains a `DrawModifierNode`.
     *
     * This is how a *delegated* draw shows up at all — Material 3's wavy indicators hide theirs
     * behind a `CacheDrawModifierNode` whose element exposes no inspectable `onDraw`, so
     * [hasCustomDraw] cannot see it and the node would export as an empty layer.
     *
     * It is deliberately **not** folded into [hasCustomDraw]. The bit is true of most drawn nodes —
     * every `Icon`, every token-backed background — so on its own it says nothing about whether the
     * export can represent the node. Treating it as a custom draw sent every vector-backed icon and
     * every drawn leaf down the raster fallback, which is what turned editable `<path>`s and labels
     * into `<image>` crops (#3686).
     *
     * So it earns a raster only where the vector model would otherwise emit *nothing at all*: the
     * caller has already established a leaf with no text, and [otherwiseEmpty] adds that there is
     * no captured vector and no fill or border token to draw either.
     */
    private fun LayoutInspectorNode.hasDelegatedDrawOnly(otherwiseEmpty: Boolean): Boolean =
      drawsContent && otherwiseEmpty

    private fun LayoutInspectorModifier.isCustomDraw(): Boolean =
      name in DRAW_MODIFIERS && !placeholder

    /**
     * True when the node's captured draw paints **over** the shape its container tokens describe.
     *
     * A modifier chain is painted outside-in, and [modifiers] is in that order, so the comparison
     * is positional: a draw *after* the last `background`/`border` entry paints on top of it, a
     * draw before it paints underneath. With no token-shape modifier on the chain there is no shape
     * to order against and the capture stays a plain background.
     */
    private fun LayoutInspectorNode.drawPaintsOverTokenShape(): Boolean {
      val firstDraw = modifiers.indexOfFirst { it.isCustomDraw() }
      if (firstDraw < 0) return false
      val lastShape = modifiers.indexOfLast { it.isTokenShapeModifier() }
      return lastShape >= 0 && firstDraw > lastShape
    }

    /** True when at least one clipping coordinator is outside the captured draw modifier. */
    private fun LayoutInspectorNode.drawIsInsideClip(): Boolean {
      val firstDraw = modifiers.indexOfFirst { it.isCustomDraw() }
      if (firstDraw < 0 || tokens?.clipsContent != true) return false
      return modifiers.take(firstDraw).any { it.isClipModifier() }
    }

    /** The innermost placed clip outside the draw, used to restore the capture's missing mask. */
    private fun LayoutInspectorNode.drawClipBounds(): LayoutInspectorBounds? {
      val firstDraw = modifiers.indexOfFirst { it.isCustomDraw() }
      if (firstDraw < 0 || tokens?.clipsContent != true) return null
      return modifiers.take(firstDraw).lastOrNull { it.isClipModifier() }?.bounds
    }

    private fun LayoutInspectorModifier.isClipModifier(): Boolean {
      val lower = name.lowercase()
      return properties["clip"] == "true" || lower == "clip"
    }

    /** The modifiers a layer's token-derived `<rect>`/`<path>` shape is resolved from. */
    private fun LayoutInspectorModifier.isTokenShapeModifier(): Boolean {
      val lower = name.lowercase()
      return lower == "background" ||
        lower.contains("backgroundelement") ||
        lower == "border" ||
        lower.contains("bordermodifier") ||
        lower.contains("borderelement")
    }

    /**
     * The region the Canvas draw actually paints — the union of the draw modifiers' bounds, which
     * is tighter than the (padded) node box (`Spacer(padding).drawBehind`). Falls back to the node
     * bounds when a draw modifier carries none.
     */
    private fun LayoutInspectorNode.drawnRegion(): LayoutInspectorBounds {
      val drawn = modifiers.filter { it.isCustomDraw() }.mapNotNull { it.bounds }
      if (drawn.isEmpty()) return bounds
      return LayoutInspectorBounds(
        left = drawn.minOf { it.left },
        top = drawn.minOf { it.top },
        right = drawn.maxOf { it.right },
        bottom = drawn.maxOf { it.bottom },
      )
    }

    /**
     * The region an over-drawing modifier covers on a vector node: the union of the node box and
     * the draw modifiers' own bounds. Unlike [drawnRegion] this never *shrinks* to the draw bounds
     * — the icon underneath still has to be inside the crop, and a tint pass that reports no bounds
     * covers the whole node.
     *
     * It never grows past [clipBounds] either — the box of the nearest ancestor that actually clips
     * its children. The union is a crop taken out of the rendered frame, so a draw modifier
     * reporting a rect beyond what its clip admits (a detached coordinate, a node whose ancestors'
     * transform the capture didn't apply) would mint an `<image>` of frame pixels that belong to
     * something else — the detached white tiles Jetsnack's `Screens/App shell` and `Snack/Detail`
     * grew to the right of and below their UI, which also expand the exported canvas because the
     * document extent is the union of its layers (issue #2853).
     *
     * Only a *clipping* ancestor bounds it. An ordinary container does not clip, and a child
     * overflowing one really is drawn past its edge (`FigmaSvgChildClipTest`'s unclipped case), so
     * the immediate parent's box is not a limit — clamping to that would truncate a crop whose
     * pixels are genuinely in the frame.
     */
    private fun LayoutInspectorNode.drawnOverlayRegion(
      nodeBounds: LayoutInspectorBounds,
      clipBounds: LayoutInspectorBounds? = null,
    ): LayoutInspectorBounds {
      // [nodeBounds], not this node's raw `bounds`: a detached or not-yet-placed node (a vector
      // inside a subcomposed Button/TextField) reports `(0,0,0,0)`, which `toLayer` has already
      // reconstructed from its measured size. Reading the raw field back would crop a zero-sized
      // region and the layer would come back as the transparent 1×1 fallback.
      val drawn = modifiers.filter { it.isCustomDraw() }.mapNotNull { it.bounds }
      if (drawn.isEmpty()) return nodeBounds
      // A draw modifier's own bounds are subject to the same detachment, so an empty one can't be
      // allowed to drag the union back to the origin.
      val placed = drawn.filter { it.right > it.left && it.bottom > it.top }
      if (placed.isEmpty()) return nodeBounds
      val union =
        LayoutInspectorBounds(
          left = minOf(nodeBounds.left, placed.minOf { it.left }),
          top = minOf(nodeBounds.top, placed.minOf { it.top }),
          right = maxOf(nodeBounds.right, placed.maxOf { it.right }),
          bottom = maxOf(nodeBounds.bottom, placed.maxOf { it.bottom }),
        )
      // A node placed entirely outside its clip leaves nothing to clamp to, so `intersectOrNull`
      // comes back null; keep the node's own box rather than emitting an inverted rect.
      return intersectOrNull(union, clipBounds) ?: union
    }

    /**
     * [a] ∩ [b], or [a] itself when [b] is absent or degenerate. Null when the two don't overlap at
     * all, so a caller can tell "clamped" from "nothing left".
     */
    private fun intersectOrNull(
      a: LayoutInspectorBounds,
      b: LayoutInspectorBounds?,
    ): LayoutInspectorBounds? {
      if (b == null || b.right <= b.left || b.bottom <= b.top) return a
      val out =
        LayoutInspectorBounds(
          left = maxOf(a.left, b.left),
          top = maxOf(a.top, b.top),
          right = minOf(a.right, b.right),
          bottom = minOf(a.bottom, b.bottom),
        )
      return out.takeIf { it.right > it.left && it.bottom > it.top }
    }

    private fun LayoutInspectorNode.layerName(): String =
      composableName(displayName ?: component).ifBlank { "Layer" }

    /**
     * The composable name to show as the SVG layer id. When source-info resolution succeeds
     * [LayoutInspectorNode.component] already carries the composable name (`Box`, `Card`, …); when
     * it falls back to the measure-policy class it reads `BoxMeasurePolicy` / `RootMeasurePolicy` /
     * `OutlinedTextFieldMeasurePolicy`. Strip that implementation-detail suffix so the layer reads
     * as the composable — `BoxMeasurePolicy` → `Box` — rather than exposing an internal class name.
     */
    private fun composableName(component: String): String =
      component.removeSuffix(MEASURE_POLICY_SUFFIX).ifBlank { component }

    private const val MEASURE_POLICY_SUFFIX = "MeasurePolicy"

    /**
     * Assigns each semantics text node to the single best-matching layout node, keyed by layout
     * [LayoutInspectorNode.nodeId]. Matching is by bounds with a small tolerance rather than exact
     * equality: the two producers round the same underlying float differently (semantics truncates
     * to `Int`, layout rounds), so text laid out on fractional pixels — common at non-1 densities
     * or with centring/dp offsets — would otherwise miss its layer and silently drop out of the
     * export. Each layout node keeps only its closest text and each text lands on its closest
     * layout node (favouring the tight `Text` leaf over a looser wrapper), so text is neither
     * dropped nor duplicated across nested layers that share bounds.
     *
     * The "favour the tight leaf" rule is what the tie-break enforces: a `Text` and a wrapper that
     * shares its bounds (a slot `Box`, a `fillMaxWidth` parent) match a run equally well, and the
     * text MUST land on the leaf. If the wrapper wins, the leaf is left text-less — and a leaf that
     * also carries a `drawWithContent` draw (Wear M3 `Modifier.placeholder` is one) is then treated
     * as un-vectorisable canvas chrome and rasterised out of the frame, baking its glyphs into an
     * `<image>` that doubles the `<text>` the wrapper emits (the "text rendered twice" bug). Nodes
     * are collected with their tree depth so a bounds tie resolves to the deepest (innermost) node.
     */
    /**
     * [b] ∩ [clip], or [b] itself when the clip doesn't cut it (so callers can skip a duplicate).
     */
    private fun clipped(
      b: LayoutInspectorBounds,
      clip: LayoutInspectorBounds,
    ): LayoutInspectorBounds? {
      val c = intersectBounds(b, clip) ?: return null
      return if (c.left == b.left && c.top == b.top && c.right == b.right && c.bottom == b.bottom) b
      else c
    }

    /** The overlap of two boxes, or null when they don't overlap at all. */
    private fun intersectBounds(
      a: LayoutInspectorBounds,
      b: LayoutInspectorBounds,
    ): LayoutInspectorBounds? {
      val left = maxOf(a.left, b.left)
      val top = maxOf(a.top, b.top)
      val right = minOf(a.right, b.right)
      val bottom = minOf(a.bottom, b.bottom)
      return if (right > left && bottom > top) LayoutInspectorBounds(left, top, right, bottom)
      else null
    }

    /**
     * The exact all-zero `(0,0,0,0)` box, which both capture producers mint to mean **"this node
     * has no coordinates"** — not "this node is a zero-area box at the origin".
     *
     * The layout inspector reports it for a detached / not-yet-placed subcomposed child (the case
     * [recoverBounds] reconstructs from the measured size), and a semantics node's `boundsInRoot`
     * collapses to it when the node is clipped entirely away by an ancestor — a Wear `EdgeButton`
     * label while `ScreenScaffold` still holds the button collapsed, for instance.
     *
     * Such a box carries no position, so feeding it to [assignTextToLayers]'s **proximity** match
     * is a category error: every zero-bounds box sits at distance 0 from every other one, and a
     * capture normally has several. The collapsed `EdgeButton`'s "Start" therefore matched the
     * first zero-bounds layout node in the tree — a `TitleCard`'s 0×4 `Spacer` — which
     * [recoverBounds] then re-anchored inside the visible card, painting a `<text>` over the card's
     * title that the PNG never drew.
     *
     * So such a box is excluded from the proximity search on **both** sides, and a semantics node
     * that has one is instead matched on **identity**: both producers key a node on the same
     * Compose `SemanticsNode.id`, so the run either lands on its real owner or on nothing. The
     * owner is a node the render drew nothing for (that is why it lost its coordinates), so this
     * changes no pixels — it keeps the richer semantics typography on the layer that would
     * otherwise fall back to its cruder `layoutText` modifier projection, and it leaves
     * [recoverBounds]' own case (a *placed* subcomposed child whose coordinates were detached)
     * matched exactly as before.
     */
    private fun LayoutInspectorBounds.isNoGeometry(): Boolean =
      left == 0 && top == 0 && right == 0 && bottom == 0

    /** [isNoGeometry] for a parsed `[l,t,r,b]` semantics box. */
    private fun IntArray.isNoGeometry(): Boolean = all { it == 0 }

    /**
     * The match cost booked for an identity match (see [isNoGeometry]). Below every proximity cost
     * — which is a sum of absolute pixel deltas, so never negative — so an identity match both wins
     * its node and is never displaced by a later zero-distance neighbour.
     */
    private const val IDENTITY_DIST: Int = -1

    private fun assignTextToLayers(
      layoutRoot: LayoutInspectorNode,
      semantics: ComposeSemanticsPayload,
      density: Float,
      fontScale: Float,
    ): Map<String, FigmaSvgText> {
      val candidates = mutableListOf<Triple<String, IntArray, Int>>()
      val nodesWithModifierText = mutableSetOf<String>()
      // …and the same, counting the whole subtree. An accessibility-only wrapper (Material 3's date
      // cells) sets `SemanticsProperties.Text` to a sentence for the screen reader while a `Text`
      // *child* draws the glyph; matching the wrapper and painting its label would double the text.
      // The distinguishing question is whether anything below the matched node already draws real
      // text, not whether the capture recorded typography — a Wear picker separator clears its
      // semantics too, and there its string is the only copy of the visible content.
      val nodesWithModifierTextBelow = mutableSetOf<String>()
      // Every layout node the (already retired-pruned) tree still carries, for the identity match
      // a coordinate-less semantics node falls back to — see [isNoGeometry].
      val layoutNodeIds = mutableSetOf<String>()
      // The two producers disagree about clipping, and the disagreement is invisible until a node
      // straddles a clip edge. The layout-inspector records every node's UNCLIPPED box
      // (`localBoundingBoxOf(clipBounds = false)`), while a semantics node's `boundsInRoot` is
      // CLIPPED by its ancestors. A lazy-list row half above the viewport's top edge therefore
      // presents two boxes that differ by the whole clipped-away strip — far past
      // [BOUNDS_TOLERANCE_PX] — so its text matched nothing and the row exported as an empty group
      // while the PNG painted the visible lines (issue #3057). Offering the clipped box as an
      // ADDITIONAL candidate alongside the raw one lets an edge row match without loosening the
      // tolerance for anything else.
      fun collect(n: LayoutInspectorNode, depth: Int, clip: LayoutInspectorBounds?) {
        layoutNodeIds += n.nodeId
        val ownsModifierText =
          n.modifiers.any { modifier -> modifier.properties["layoutText"]?.isNotBlank() == true }
        if (ownsModifierText) {
          nodesWithModifierText += n.nodeId
        }
        val candidateBounds =
          buildList {
              add(n.bounds)
              // A Text's semantics bounds include semantic modifiers such as clickable, minimum
              // touch size, and padding, while LayoutInspectorNode.bounds is the inner glyph box.
              // Keep the text attached to that same layout node by accepting any captured
              // modifier boundary as a matching surface. This is essential for emoji-table cells:
              // their 42dp clickable semantics surround a much smaller padded text layout.
              n.modifiers.mapNotNullTo(this) { it.bounds }
            }
            .distinct()
        candidateBounds
          .filterNot { it.isNoGeometry() }
          .forEach { b ->
            candidates.add(Triple(n.nodeId, intArrayOf(b.left, b.top, b.right, b.bottom), depth))
            clip
              ?.let { clipped(b, it) }
              ?.takeIf { it !== b }
              ?.let { c ->
                candidates.add(
                  Triple(n.nodeId, intArrayOf(c.left, c.top, c.right, c.bottom), depth)
                )
              }
          }
        // Nested clips intersect, exactly as they do when the layers are built — and against the
        // same **rendered** box `toLayer` clips with. A lookahead-inflated node (issue #3056)
        // reports bounds taller than the frame, so clipping a descendant against `n.bounds` would
        // leave a row straddling the real viewport edge mismatched all over again.
        val childClip =
          if (n.tokens?.clipsContent == true) {
            val own = n.clipModifierBounds() ?: n.bounds
            clip?.let { intersectBounds(own, it) } ?: own
          } else clip
        n.children.forEach { collect(it, depth + 1, childClip) }
        // Post-order: a node counts as covered once anything in its subtree draws real text.
        if (ownsModifierText || n.children.any { it.nodeId in nodesWithModifierTextBelow }) {
          nodesWithModifierTextBelow += n.nodeId
        }
      }
      // The rendered window clips everything, whether or not any composable asked it to.
      collect(layoutRoot, 0, layoutRoot.bounds)

      val textByNodeId = HashMap<String, FigmaSvgText>()
      val bestDistForNode = HashMap<String, Int>()
      fun walk(node: ComposeSemanticsNode) {
        val measuredContent = node.layoutText?.takeIf { it.isNotBlank() }
        val legacySemanticsContent = node.text?.takeIf { it.isNotBlank() }
        val raw = parseBoundsList(node.boundsInRoot)
        // `(0,0,0,0)` is the "no coordinates" signature, not a box at the origin — see
        // [isNoGeometry], which is also where the identity fallback below is motivated.
        val b = raw?.takeUnless { it.isNoGeometry() }
        if ((measuredContent != null || legacySemanticsContent != null) && raw != null) {
          var bestId: String? = null
          var bestDist = Int.MAX_VALUE
          var bestDepth = -1
          if (b == null) {
            // No box to be near: fall back to the node's own identity. Both producers key a node
            // on the same Compose `SemanticsNode.id`, so this lands the run on its real owner
            // whenever that node survived into the layer tree — and on nothing at all when it
            // didn't. `IDENTITY_DIST` outranks every proximity match so a coincidental zero-cost
            // neighbour can never displace it.
            if (node.nodeId in layoutNodeIds) {
              bestId = node.nodeId
              bestDist = IDENTITY_DIST
            }
          } else {
            for ((id, lb, depth) in candidates) {
              val d0 = abs(lb[0] - b[0])
              val d1 = abs(lb[1] - b[1])
              val d2 = abs(lb[2] - b[2])
              val d3 = abs(lb[3] - b[3])
              if (maxOf(d0, d1, d2, d3) <= BOUNDS_TOLERANCE_PX) {
                val d = d0 + d1 + d2 + d3
                // Closest match wins; on an exact tie prefer the DEEPER (innermost) node — the real
                // `Text` leaf over a wrapper that shares its bounds — so the leaf keeps its
                // editable text and a `drawWithContent`/placeholder leaf isn't rasterised as canvas
                // chrome.
                if (d < bestDist || (d == bestDist && depth > bestDepth)) {
                  bestDist = d
                  bestDepth = depth
                  bestId = id
                }
              }
            }
          }
          val chosen = bestId
          // TextLayoutResult is visual ground truth despite being exposed through a semantics
          // action. Plain SemanticsProperties.Text is accessibility data and may be overridden or
          // merged, so it is only a compatibility fallback for old layout captures where nothing in
          // the matched subtree already projects the text itself.
          val content =
            measuredContent
              ?: legacySemanticsContent?.takeUnless { chosen in nodesWithModifierTextBelow }
          if (
            chosen != null &&
              content != null &&
              bestDist < (bestDistForNode[chosen] ?: Int.MAX_VALUE)
          ) {
            textByNodeId[chosen] = textFrom(node, content, density, fontScale)
            bestDistForNode[chosen] = bestDist
          }
        }
        node.children.forEach(::walk)
      }
      walk(semantics.root)
      return textByNodeId
    }

    /**
     * The capture's own resolved px for a typographic value, else the linear `sp × density ×
     * fontScale` fallback (issue #3024).
     *
     * The resolved value always wins where the capture has one. Compose resolves `sp` through the
     * platform `FontScaleConverter` on API 34+, a **non-linear** curve in the font scale: body
     * sizes take the full multiplier, display sizes almost none. The linear formula is only ever
     * right at `fontScale = 1`, and on a scaled render it over-sized headings by up to 50% — enough
     * that the captured line breaks no longer fit the bounds they were measured in. The fallback
     * stays for captures older than schema v12, which carry no resolved px at all.
     */
    private inline fun resolvedPx(captured: Double?, fallback: () -> Double?): Double? =
      captured ?: fallback()

    private fun textFrom(
      node: ComposeSemanticsNode,
      content: String,
      density: Float,
      fontScale: Float,
    ): FigmaSvgText =
      FigmaSvgText(
        content = content,
        fontSizePx =
          resolvedPx(node.typography?.fontSizePx) {
            node.typography?.fontSize?.let { spToPx(it, density, fontScale) }
          },
        fontFamily = node.typography?.fontFamily,
        fontWeight = node.typography?.fontWeight,
        italic = node.typography?.fontStyle == "italic",
        color = node.textColor?.foreground?.let { argbToColor(it, emptyMap()) },
        lineHeightPx =
          resolvedPx(node.typography?.lineHeightPx) {
            node.typography?.lineHeight?.let {
              lineHeightToPx(it, node.typography.fontSize, density, fontScale)
            }
          },
        // Letter spacing uses the same sp×density×fontScale / em×fontSize resolution as line
        // height.
        letterSpacingPx =
          resolvedPx(node.typography?.letterSpacingPx) {
            node.typography?.letterSpacing?.let {
              lineHeightToPx(it, node.typography.fontSize, density, fontScale)
            }
          },
        textAlign = node.typography?.textAlign,
        layoutDirection = node.typography?.layoutDirection,
        spans =
          node.typography?.spans?.map { span ->
            FigmaSvgTextSpan(
              start = span.start,
              end = span.end,
              fontSizePx =
                resolvedPx(span.fontSizePx) {
                  span.fontSize?.let { spToPx(it, density, fontScale) }
                },
              fontFamily = span.fontFamily,
              fontWeight = span.fontWeight,
              italic = span.fontStyle == "italic",
              color = span.foregroundColor?.let { argbToColor(it, emptyMap()) },
            )
          },
        // Carry per-line runs for wrapped and single-line ellipsised text. The captured offsets are
        // already in render px (same space as the node bounds), so they map straight to layer space
        // with no density conversion.
        lines =
          node.textOverflow
            ?.lines
            ?.takeIf { it.isNotEmpty() }
            ?.map {
              FigmaSvgTextLine(
                content = it.text,
                left = it.left,
                baseline = it.baseline,
                start = it.start,
                end = it.end,
                width = it.width,
              )
            },
      )

    /**
     * Resolves a captured line-height string to px. `"20.0sp"` → sp × density; `"1.4em"` → em ×
     * resolved font size (in px). Returns null when neither the value nor (for `em`) the font size
     * parses.
     */
    fun lineHeightToPx(
      value: String,
      fontSize: String?,
      density: Float,
      fontScale: Float = 1f,
    ): Double? {
      val trimmed = value.trim()
      return when {
        trimmed.endsWith("sp") -> spToPx(trimmed, density, fontScale)
        trimmed.endsWith("em") -> {
          val em = trimmed.removeSuffix("em").trim().toDoubleOrNull() ?: return null
          // em is a multiple of the (already fontScale-scaled) font size.
          val fontPx = fontSize?.let { spToPx(it, density, fontScale) } ?: return null
          em * fontPx
        }
        else -> null
      }
    }

    private data class Extent(val minX: Int, val minY: Int, val maxX: Int, val maxY: Int)

    /**
     * Union of every drawing layer's bounds (grouping-only layers don't constrain the canvas).
     *
     * A `Modifier.clip` ancestor ([FigmaSvgLayer.clipChildren]) masks its subtree to its own box,
     * so a descendant is counted only within that clip — a child intentionally placed beyond the
     * clip (Jetsnack Search/Categories' minimum-size image under `.clip(CategoryShape)`) no longer
     * grows the canvas past the clipped viewport the render shows (issue #2852). The clip narrows
     * as it nests (intersection of every clipping ancestor); a node's own draws are held to its
     * *ancestor* clips, not its own, which it fills to the edge of.
     */
    private fun FigmaSvgLayer.extent(): Extent? {
      var acc: Extent? = null
      fun add(left: Int, top: Int, right: Int, bottom: Int, clip: Extent?) {
        val l = clip?.let { maxOf(left, it.minX) } ?: left
        val t = clip?.let { maxOf(top, it.minY) } ?: top
        val r = clip?.let { minOf(right, it.maxX) } ?: right
        val b = clip?.let { minOf(bottom, it.maxY) } ?: bottom
        if (r <= l || b <= t) return
        acc =
          acc?.let {
            Extent(minOf(it.minX, l), minOf(it.minY, t), maxOf(it.maxX, r), maxOf(it.maxY, b))
          } ?: Extent(l, t, r, b)
      }
      fun merge(l: FigmaSvgLayer, clip: Extent?) {
        if (l.paints) add(l.left, l.top, l.right, l.bottom, clip)
        // A background raster is drawn at its **own** bounds, which need not sit inside the layer
        // box: a card's chrome is captured from a draw modifier that sits above the padding, so it
        // is wider and taller than the padded content box the layer is placed at. Counting only the
        // box would shrink-wrap the canvas over pixels the SVG really draws (issue #2937).
        l.background?.let { add(it.left, it.top, it.right, it.bottom, clip) }
        val childClip =
          if (l.clipChildren) {
            // The mask box, which is the layer's own box for every layer that doesn't carry a
            // separate one.
            val m = l.clipBox
            val box =
              if (m != null) Extent(m.left, m.top, m.right, m.bottom)
              else Extent(l.left, l.top, l.right, l.bottom)
            clip?.let {
              Extent(
                maxOf(it.minX, box.minX),
                maxOf(it.minY, box.minY),
                minOf(it.maxX, box.maxX),
                minOf(it.maxY, box.maxY),
              )
            } ?: box
          } else clip
        l.children.forEach { merge(it, childClip) }
      }
      merge(this, null)
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

    /**
     * `"22.0sp"` → px at [density], scaled by [fontScale]. Compose sizes `sp` text as `sp × density
     * × fontScale`, and the layer geometry this SVG places text into is captured *after* that
     * fontScale is applied (the boxes are measured for scaled text), so the emitted `<text
     * font-size>` must carry the same fontScale or the glyphs float undersized in boxes sized for
     * larger text. [fontScale] defaults to 1.0 (an un-scaled capture).
     */
    fun spToPx(value: String, density: Float, fontScale: Float = 1f): Double? {
      val n = value.removeSuffix("sp").trim().toDoubleOrNull() ?: return null
      return n * density * fontScale
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
