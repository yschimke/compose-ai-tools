package ee.schimke.composeai.data.layoutinspector

import kotlin.math.abs
import kotlinx.serialization.Serializable

object ComposeSemanticsProduct {
  const val KIND: String = "compose/semantics"
  // v3 (#1897): each node may carry resolved design-token data (`tokens`) — container colour,
  // corner radius, padding — so design-parity's token-compliance check can populate `actual`
  // instead of degrading to "missing from candidate". Additive: older `compose-semantics.json`
  // parses with `tokens = null`.
  // v4 (#1908): the `tokens` object gains `borderColor` (outline role), `gap` (arrangement
  // spacing), and `shape` (descriptor for non-dp shapes), and `cornerRadius` now resolves
  // percent/`CircleShape` corners against the node's size. Still additive — every new field is
  // optional, so a v3 reader parses a v4 file unchanged.
  // v5 (#1934): each text node gains a `typography` object — its resolved typographic identity
  // beyond size/colour: `fontFamily`, `fontWeight`, `fontStyle`, `fontVariationSettings` (the
  // variable-font axes actually applied), plus `fontFeatureSettings`, `letterSpacing`, and
  // `lineHeight`. The typography analogue of the v3 `tokens` addition, so a parity consumer can
  // compare *which face* the text is drawn in, not just how big. Still additive — the object is
  // optional, so a v4 reader parses a v5 file unchanged.
  // v6 (#1903): the flat `layout*` text fields are consolidated into themed sub-objects, closing
  // the
  // smell #1903 flagged. `layoutFontSize` moves to `typography.fontSize`; `layoutForegroundColor` /
  // `layoutBackgroundColor` become `textColor.{foreground,background}`; the line/overflow metrics
  // (`layoutLineCount` / `layoutMaxLines` / `layoutOverflow` / `layoutTruncated` /
  // `layoutDidOverflow{Width,Height}`) become the `textOverflow` object. **Breaking** — the flat
  // fields are removed, so a v5 reader does not see them; consumers read the sub-objects instead.
  // History compatibility: stored v5 history entries still carry the flat fields, which decode away
  // (`ignoreUnknownKeys`) into the v6 model — so diffing a render across the bump won't surface
  // changes to those text fields. The loss is a one-time artifact of the consolidation, accepted
  // rather than carrying a legacy decode path; entries captured at v6+ diff normally.
  // v7 (#1908 follow-up): tokens may carry `cornerRadiusPx` — the raw-pixel corner radius of a
  // `RoundedCornerShape(<px>f)` that the dp-only `cornerRadius` couldn't express. Additive; older
  // entries decode with `cornerRadiusPx = null`.
  // v8: `textOverflow` may carry `lines` — per-line geometry (visible substring + left + baseline,
  // px relative to the node's top-left) for wrapped text, so the figma-svg export places one run
  // per line at the render's break points instead of collapsing the string onto one baseline.
  // Additive; older entries decode with `lines = null` (single-line rendering).
  // v9: `tokens` may carry `opacity`, the effective alpha of the node's graphics layers. Additive;
  // older entries decode it as null (fully opaque).
  // v10 (#2854): typography may carry effective styled `spans`, and wrapped line entries carry
  // their UTF-16 start/end offsets. This preserves the paragraph face across AnnotatedString runs
  // while retaining explicit per-span overrides (for example Karla body text with a monospace code
  // span). Additive; older entries decode with `spans = null` and line offsets unset.
  // v11 (#2852): the mirrored `tokens` may carry `clipsContent` — the node has a
  // `Modifier.clip(shape)` masking its children. Additive; older entries decode it as null.
  // v12 (#3024): typography may carry the **resolved pixel** metrics — `fontSizePx`,
  // `lineHeightPx`, `letterSpacingPx` (and `fontSizePx` per span) — alongside the `sp`/`em`
  // strings, and a wrapped line may carry its measured `width`. A consumer cannot re-derive px
  // from sp: Compose resolves `sp` through the platform `FontScaleConverter` on API 34+, which is
  // **non-linear** in the font scale (small text scales fully, display sizes flatten toward
  // identity), so `sp × density × fontScale` over-sizes large text on any `fontScale != 1` render
  // — 50% on JetNews's 32sp article title, enough to push captured line breaks past their bounds.
  // These fields carry what the render actually used. Additive; older entries decode them as null
  // and consumers fall back to the linear conversion.
  // v13 (#3254): `tokens` may carry `shapePath` — a unit-box polyline outline for a shape none of
  // the corner tokens can describe (an `Outline.Generic` morph/star/squircle, or a shape wrapper
  // the resolver can't reduce to corners). Populated only after `cornerRadius`, `cornerRadiusPx`
  // and the rounded-outline fallback have all come up empty, so an understood shape is unaffected.
  // Additive; older entries decode with `shapePath = null`.
  // v14: the payload carries `density` — render pixels per dp for this capture. Every node states
  // its `boundsInRoot` in the render's own pixels while `tokens` resolve to dp, and nothing on the
  // wire said which factor separates them, so a consumer measuring a token against a box had to
  // assume they shared a unit. They don't: a 52dp Wear icon button with a fully-clamped 26dp corner
  // is a 104x104 box at dpi 320, and design-parity's "is this corner already a stadium?" test read
  // 26 against half of 104, called a pill un-clamped, and reported a Δ against the kit's
  // fully-rounded sentinel on every icon button it compared. Additive; older entries decode with
  // `density = null`, which a consumer reads as "not stated" rather than as 1.
  const val SCHEMA_VERSION: Int = 14
  const val FILE: String = "compose-semantics.json"
}

object LayoutInspectorProduct {
  const val KIND: String = "layout/inspector"
  // v2 (#1903): each node may carry resolved design `tokens` — the modifier-derived
  // `{backgroundColor, borderColor, cornerRadius, shape, gap, padding}` projection. This is the
  // *canonical* home for those tokens: they come from modifiers, which `layout/inspector` already
  // models, and the kind now ships on desktop too (the gap #1897 worked around by mirroring them
  // onto `compose/semantics`). They stay mirrored on `compose/semantics` for the design-parity
  // consumer; both products feed the same `ModifierTokenResolver`. Additive — older
  // `layout-inspector.json` parses with `tokens = null`.
  // v3 (#1908 follow-up): `tokens` may carry `cornerRadiusPx` (raw-pixel
  // `RoundedCornerShape(<px>f)`
  // corners the dp-only `cornerRadius` dropped). Additive — older entries parse with it null.
  // v4: each node may carry `curvedTexts` — Wear `CurvedLayout`/`TimeText` runs laid out along an
  // arc (string + baseline circle + font), captured from the `CurvedTextChild` runtime state a
  // LayoutNode walk can't see, so the figma-svg export reproduces the clock as an SVG `<textPath>`
  // instead of dropping it. Additive — older entries parse with an empty list.
  // v5: each node may carry a `displayName` — the nearest enclosing composable name, used as the
  // figma-svg layer label so an internal `Box`/`Row` reads as the `Button`/`IconButton` that owns
  // it, while `component` stays the node's own identity for raster/curved matching. Additive —
  // older entries parse with `displayName = null` and fall back to `component`.
  // v6: each node may carry a `vectorGraphic` — editable `<path>`s captured either from an
  // `Icon`/`Image`'s `ImageVector` or (draw-capture) from a control's imperative draw lambda; its
  // paths may carry `strokeCap` / `strokeJoin` (SVG linecap/linejoin) for round-capped chrome.
  // Additive — older entries parse with `vectorGraphic = null` and paths with butt/miter defaults.
  // v7: `tokens` may carry effective graphics-layer `opacity`. Additive; older entries decode it
  // as null (fully opaque).
  // v8 (#2646): each node may carry a `placeholder` — the Wear/M3 content-loading placeholder its
  // modifier chain declares, plus whether that placeholder is currently *visible*, and each
  // modifier entry carries a `placeholder` flag marking the entries that ARE that placeholder. The
  // figma-svg export needs the state, not just the chain: the ideal state must keep its editable
  // content (no `drawWithContent` raster, no 50%-pill corner) while the loading state gets the
  // placeholder block as its own vector layer. Additive — older entries parse with
  // `placeholder = null` / `false`.
  // v9 (#2615): each node may carry a `transform` — the draw-time `graphicsLayer` scale it inherits
  // from its ancestors, present only when it isn't the identity (a Wear `TransformingLazyColumn`
  // item shrunk toward the curved edge). `bounds` already carries the scaled rect; `transform` says
  // the shrink is real, so a consumer doesn't grow the node back to its measured `size`. Additive —
  // older entries decode with `transform = null`.
  // v10 (#2852): a `vectorGraphic` says whether its paths came from the node's own draw lambda
  // (`fromDrawCapture`) or from an `ImageVector`. The export needs the distinction to read a draw
  // modifier on a vector node: for a captured draw the modifier *is* those paths, while on an
  // `ImageVector` it's an unrepresented overlay (Jetsnack's blend-mode gradient icon tint) that has
  // to raster instead of exporting the untinted glyph. Additive — older entries decode with
  // `fromDrawCapture = false`, which is the `ImageVector` reading they all had.
  // v11 (#2937): each node may carry a `drawRaster` — its own imperative draw re-invoked against an
  // offscreen bitmap, as a base64 PNG plus the region it covers. Captured only when the draw could
  // not be vectorised, so it is the *fallback* for a draw that reaches for a transform / shader /
  // bitmap / native canvas (every component the Remote Compose embedded player interprets). Unlike
  // a frame crop it holds no descendant pixels, so a container that draws can keep editable
  // children over it. Additive — older entries decode with `drawRaster = null`.
  // v12 (#2852): `tokens` may carry `clipsContent` — the node has a `Modifier.clip(shape)` that
  // masks its children to its shape. Distinct from the paint-shape tokens (a `background(color,
  // shape)` rounds its own fill but doesn't clip an overflowing child); the figma-svg export turns
  // it into an SVG `clipPath` so a child placed beyond the clip is masked instead of overflowing.
  // Additive — older entries decode with `clipsContent = null`.
  // v13: each node may report `modifiesDrawnContent` when replaying its `drawWithContent` clips,
  // masks, fades, clears or omits descendant pixels. The figma-svg exporter uses this
  // capability signal to crop only that composited region from the frame; no component names are
  // involved. Additive — older entries decode it as false.
  // v14 (#3254): `tokens` may carry `shapePath` — see the `compose/semantics` v13 note; both
  // products mirror the same `ModifierTokenResolver` projection. Additive — older entries decode
  // with `shapePath = null`.
  // v15: a `vectorGraphic` may carry `vectorName` — the `ImageVector`'s own name, read off the live
  // `VectorComponent` at capture (`"Filled.Menu"`, `"AutoMirrored.Outlined.ArrowBack"`, or whatever
  // an app passed its own builder). It is the only signal that separates a stock Material icon from
  // an app's artwork — the geometry can't — and it is what lets the figma-svg export annotate an
  // icon with its canonical fonts.google.com identity ([MaterialIconRef]). Additive — older entries
  // decode with `vectorName = null`, which exports exactly as before.
  // v16: each node reports `drawsContent` when its live modifier-node chain contains a
  // `DrawModifierNode`. This covers modern delegated `CacheDrawModifierNode` implementations whose
  // element exposes no replayable `onDraw` lambda (Material 3's wavy progress indicators). The
  // hybrid figma-svg export uses the signal to crop an otherwise-unrepresentable leaf from the
  // rendered frame. Additive — older entries decode it as false.
  // v17: a node's `transform` may carry `rotationDegrees` — the in-plane rotation measured through
  // the same root-mapped axes its scale is. It is the one case where `bounds` is a *bounding box*
  // rather than the drawn rect (Wear's `AlertDialog` confirm button: a 126x108 pill turned -45
  // degrees reports 166x166), so a consumer that places by `bounds` alone draws the node too big
  // and un-turned. Additive — older entries decode with `rotationDegrees = 0f`.
  const val SCHEMA_VERSION: Int = 17
  const val FILE: String = "layout-inspector.json"
}

/**
 * `compose/semantics-wireframe` — a standalone 2D schematic of the semantics tree, derived from the
 * same captured root as [ComposeSemanticsProduct]. Two files per preview: the SVG (the primary,
 * path-transported artifact) and a baked PNG (rides as a [DataProductExtra][name=[PNG_EXTRA_NAME]]
 * for raster-only consumers).
 */
object ComposeSemanticsWireframeProduct {
  const val KIND: String = "compose/semantics-wireframe"
  const val SCHEMA_VERSION: Int = 1
  const val FILE_SVG: String = "compose-semantics-wireframe.svg"
  const val FILE_PNG: String = "compose-semantics-wireframe.png"
  const val PNG_EXTRA_NAME: String = "png"
  const val MEDIA_TYPE_SVG: String = "image/svg+xml"
  const val MEDIA_TYPE_PNG: String = "image/png"
}

/**
 * `compose/figma-svg` — a **layered, editable SVG** export of the preview, produced from the same
 * captured layout + semantics trees as [ComposeSemanticsWireframeProduct] but with design fidelity
 * (real fills/strokes/corner radii and editable text) rather than a schematic skeleton. The design
 * counterpart of the wireframe: the wireframe is for *reading* the structure, this is for *editing*
 * it in Figma. One file per preview: the layered SVG. See [FigmaLayeredSvg] for the layer mapping.
 */
object ComposeFigmaSvgProduct {
  const val KIND: String = "compose/figma-svg"
  const val SCHEMA_VERSION: Int = 1
  const val FILE_SVG: String = "compose-figma.svg"
  const val MEDIA_TYPE_SVG: String = "image/svg+xml"

  /**
   * `compose/figma-svg-long` — the **full-page** variant of [KIND] for a *scrolling* preview. A
   * `LazyColumn`/`LazyRow` is virtualised, so the normal viewport-sized [KIND] export captures only
   * the on-screen rows. The long export renders the preview at an expanded viewport (grown until
   * the measured content geometry stops increasing, so every item composes) sized to the content,
   * so the layered SVG carries the whole scrollable screen — a pinned top bar, every row, and a
   * pinned bottom bar — as one editable tree. Distinct file + kind so it never overwrites the
   * viewport-sized [FILE_SVG]. `requiresRerender = true`: a `data/fetch` re-renders in
   * [RENDER_MODE_LONG]. See [docs/design/SCROLLING_SVG.md].
   */
  const val KIND_LONG: String = "compose/figma-svg-long"
  const val FILE_SVG_LONG: String = "compose-figma-long.svg"
  const val RENDER_MODE_LONG: String = "figma-svg-long"

  /**
   * Subdirectory (under the preview's output dir) the long export lives in — the SVG plus its own
   * `figma-raster/` crops. Isolated from the viewport export because a **hybrid** export references
   * per-node `figma-raster/<node>.png` crops and Compose reassigns node ids per render, so writing
   * the tall render's crops next to the viewport render's would collide (a `figma-raster/5.png`
   * from one render overwriting the other's). Its own subdir keeps each export's crops
   * self-consistent.
   */
  const val LONG_SUBDIR: String = "figma-long"

  /**
   * Directory (relative to the preview's output dir) holding the per-node `<node>.png` crops a
   * **hybrid** export references via `<image href="figma-raster/<node>.png">`. Empty for a
   * vector-only export. The single source of truth for the prefix [FigmaSvgModel.defaultRasterHref]
   * emits and consumers (the design-catalog carrier) collect.
   */
  const val RASTER_DIR: String = "figma-raster"
}

@Serializable
data class ComposeSemanticsPayload(
  val root: ComposeSemanticsNode,
  /**
   * Render pixels per dp for this capture — the factor that turns a node's
   * [ComposeSemanticsNode. boundsInRoot] (px) into the dp its [ComposeSemanticsNode.tokens] are
   * already in (issue #1908 left the two units unstated; schema v14).
   *
   * Null when the producer didn't state it: read that as "unknown", not as `1f`. Only the render
   * knows the factor — a `@Preview` that pins no `device`/`widthDp` gives a consumer nothing to
   * derive it from, which is exactly the case that made a clamped corner look un-clamped.
   */
  val density: Float? = null,
)

@Serializable
data class ComposeSemanticsNode(
  val nodeId: String,
  /**
   * Stable, content-independent handle for this node within the tree, assigned by [SemanticsRefs].
   *
   * Unlike [nodeId] (Compose's per-composition `SemanticsNode.id`, which is reassigned on every
   * fresh render) this survives content edits, so it is the handle agents target for interaction
   * (issue #1784) and the key the semantics differ matches on (issue #1785). Null only when the
   * payload was built without running ref assignment.
   */
  val ref: String? = null,
  val boundsInRoot: String,
  val label: String? = null,
  val text: String? = null,
  val layoutText: String? = null,
  /**
   * Resolved typographic identity of the text this node draws (issues #1934, #1903): the resolved
   * size, face, weight, style, and variable-font axes, plus letter spacing / line height. Read from
   * the node's `TextLayoutResult` (the `GetTextLayoutResult` semantics action). The typography
   * analogue of the v3 container [tokens]; null when the node draws no text or its style is
   * ambiguous across spans.
   */
  val typography: ComposeSemanticsTypography? = null,
  /** Resolved text colours (issue #1903), or null when the node draws no coloured text. */
  val textColor: ComposeSemanticsTextColor? = null,
  /** Resolved text line/overflow metrics (issue #1903), or null when the node draws no text. */
  val textOverflow: ComposeSemanticsTextOverflow? = null,
  val editableText: String? = null,
  val inputText: String? = null,
  val role: String? = null,
  val testTag: String? = null,
  val mergeMode: String? = null,
  val clickable: Boolean = false,
  /**
   * Resolved design-token data extracted from this node's modifiers (issue #1897).
   *
   * The text half of the projection (`textColor`, `typography`, …) describes drawn text; this
   * carries the *container* tokens design-parity's token-compliance check compares against —
   * resolved background/fill colour, corner radius, and padding. Null for the common case of a node
   * that declares none of them (pure layout / text nodes).
   */
  val tokens: ComposeSemanticsTokens? = null,
  val children: List<ComposeSemanticsNode> = emptyList(),
)

/**
 * Resolved typographic identity of the text a semantics node draws (issues #1934, #1903) — read
 * from the node's `TextLayoutResult` (`TextLayoutResult.layoutInput.style` / its span styles). Each
 * field collapses to a value only when every drawn range agrees on it, so a node with mixed spans
 * omits the ambiguous field; a node that declares nothing typographic emits no `typography` object.
 */
@Serializable
data class ComposeSemanticsTypography(
  /**
   * Resolved text size as `"<value>sp"`, e.g. `"22.0sp"` (was the flat `layoutFontSize`, #1903).
   */
  val fontSize: String? = null,
  /**
   * [fontSize] as the render actually resolved it, in **px** — the authoritative size for anything
   * reproducing the render's typography (issue #3024).
   *
   * A consumer cannot recover this from [fontSize]: on API 34+ Compose resolves `sp` through the
   * platform `FontScaleConverter`, whose curve is **non-linear** in the font scale — 12sp and 14sp
   * take the full multiplier while a 32sp heading takes almost none. The `sp × density × fontScale`
   * conversion the `compose/figma-svg` export used therefore over-sized JetNews's article title by
   * 50% on a `fontScale = 1.5` render, and the captured line breaks overflowed their card. The
   * curve is a platform table that varies by API level and Compose version, so it is read here
   * (through the layout's own `Density`) rather than re-implemented downstream.
   *
   * Null when the capture had no layout result to resolve against, or the size wasn't in `sp`.
   */
  val fontSizePx: Double? = null,
  /**
   * Resolved typeface identity. For a
   * [GenericFontFamily][androidx.compose.ui.text.font.GenericFontFamily] this is its declared name
   * (`"sans-serif"`, `"monospace"`); for a
   * [FontListFontFamily][androidx.compose.ui.text.font.FontListFontFamily] — which carries no
   * family display name — it is the *resolved face's* stable identity (the platform font's
   * `identity`: a file path / declared name on desktop, or `res/font/<id>` on Android), the only
   * stable per-face handle Compose exposes. Null when the family is inherited or ambiguous across
   * spans.
   */
  val fontFamily: String? = null,
  /** Resolved font weight as its numeric value (`400`, `500`, `700`, …). */
  val fontWeight: Int? = null,
  /** Resolved font style — `"normal"` or `"italic"`. */
  val fontStyle: String? = null,
  /**
   * The variable-font axes actually applied to the resolved face, formatted as `"<axis> <value>"`
   * pairs sorted by axis tag and comma-separated, e.g. `"opsz 18.0, wght 700.0"`. For a variable
   * font the axis values (`wght`/`wdth`/`opsz`/`GRAD`/…) pin the rendered instance — [fontWeight]
   * alone doesn't capture `wdth`/`opsz` or custom axes. Null when the face declares none (the
   * common non-variable case).
   */
  val fontVariationSettings: String? = null,
  /** Resolved OpenType feature settings (ligatures / figures), e.g. `"\"tnum\" 1"`. */
  val fontFeatureSettings: String? = null,
  /** Resolved letter spacing as `"<value>sp"` / `"<value>em"`. */
  val letterSpacing: String? = null,
  /** [letterSpacing] as the render resolved it, in px — see [fontSizePx] for why it is carried. */
  val letterSpacingPx: Double? = null,
  /** Resolved line height as `"<value>sp"` / `"<value>em"`. */
  val lineHeight: String? = null,
  /** [lineHeight] as the render resolved it, in px — see [fontSizePx] for why it is carried. */
  val lineHeightPx: Double? = null,
  /**
   * Resolved paragraph alignment (`TextStyle.textAlign`) as a lowercase name — `"left"`, `"right"`,
   * `"center"`, `"justify"`, `"start"`, `"end"` — or null when the node leaves it unset or the
   * drawn ranges disagree (issue #2885). Without it the `compose/figma-svg` export left-anchored
   * every single-line `<text>` at the start of its layout bounds, so a `TextAlign.Center` heading
   * inside a `fillMaxWidth()` box drifted to the left edge. Wrapped text needs no such field: its
   * per-line `left` offsets in [ComposeSemanticsTextOverflow.lines] already encode the alignment
   * geometrically. This is what recovers it for the single-line case, where no per-line run is
   * captured at all.
   */
  val textAlign: String? = null,
  /**
   * Layout direction the paragraph was laid out in — `"ltr"` or `"rtl"` — or null when the capture
   * couldn't resolve one. Only meaningful alongside [textAlign], and only for its *logical* values:
   * Compose resolves `TextAlign.Start` to the right edge and `End` to the left under RTL, so an
   * exporter that assumed LTR would mirror `end`-aligned text to the wrong side of its paragraph
   * box on an `ar` / `ar-XB` render. `Left`/`Right`/`Center` are absolute and need no direction.
   */
  val layoutDirection: String? = null,
  /**
   * Effective styles for the text's UTF-16 ranges when it contains an `AnnotatedString`. Each entry
   * has already been merged over the paragraph style and any overlapping spans, so a consumer can
   * emit editable styled runs without losing the base family. Null for plain text.
   */
  val spans: List<ComposeSemanticsTextSpan>? = null,
)

/** One effective styled UTF-16 range within a text node. */
@Serializable
data class ComposeSemanticsTextSpan(
  val start: Int,
  val end: Int,
  val fontSize: String? = null,
  /**
   * [fontSize] as the render resolved it, in px — see [ComposeSemanticsTypography.fontSizePx] for
   * why the resolved value is carried rather than re-derived from `sp`.
   */
  val fontSizePx: Double? = null,
  val fontFamily: String? = null,
  val fontWeight: Int? = null,
  val fontStyle: String? = null,
  val foregroundColor: String? = null,
)

/**
 * A linear gradient brush, reduced to what an SVG `<linearGradient>` needs (issue #2852).
 *
 * Coordinates are **fractions of the node's own box** (`0..1`), which is exactly SVG's default
 * `objectBoundingBox` gradient space — so the export needs no size arithmetic, and the same capture
 * is correct whatever the node was measured at. Compose's own `horizontalGradient` /
 * `verticalGradient` express "to the far edge" as `Float.POSITIVE_INFINITY`; that resolves to the
 * box edge, i.e. `1.0`, at capture time.
 *
 * Only linear gradients are modelled. A radial/sweep/shader brush leaves this null so the layer
 * takes the raster fallback rather than being emitted as a gradient it isn't.
 */
@Serializable
data class LayoutInspectorGradient(
  /** `#AARRGGBB` stop colours, in order. */
  val colors: List<String>,
  /** Explicit stop positions (`0..1`), or null for evenly spaced stops. */
  val stops: List<Float>? = null,
  val startX: Float = 0f,
  val startY: Float = 0f,
  val endX: Float = 1f,
  val endY: Float = 0f,
)

/**
 * Resolved text colours of a semantics node (issue #1903) — the consolidation home for the former
 * flat `layoutForegroundColor` / `layoutBackgroundColor`. ARGB hex (`#AARRGGBB`). Null fields where
 * the node doesn't resolve an unambiguous colour (e.g. text usually has no own background — the
 * surface supplies it).
 */
@Serializable
data class ComposeSemanticsTextColor(
  /** Resolved text foreground colour as ARGB hex (`#AARRGGBB`). */
  val foreground: String? = null,
  /** Resolved text background colour as ARGB hex (`#AARRGGBB`); usually unset. */
  val background: String? = null,
)

/**
 * Resolved line/overflow metrics of a text node (issue #1903) — the consolidation home for the
 * former flat `layoutLineCount` / `layoutMaxLines` / `layoutOverflow` / `layoutTruncated` /
 * `layoutDidOverflow{Width,Height}`, all read from the node's `TextLayoutResult`.
 */
@Serializable
data class ComposeSemanticsTextOverflow(
  /** Total laid-out line count across the node's text. */
  val lineCount: Int? = null,
  /** The `maxLines` constraint, when one was set (not `Int.MAX_VALUE`). */
  val maxLines: Int? = null,
  /** The `TextOverflow` mode (`Clip` / `Ellipsis` / `Visible`) as a string, when unambiguous. */
  val overflow: String? = null,
  /** Whether any laid-out text had visual overflow. */
  val truncated: Boolean? = null,
  /** Whether the text overflowed its width. */
  val didOverflowWidth: Boolean? = null,
  /** Whether the text overflowed its height. */
  val didOverflowHeight: Boolean? = null,
  /**
   * Per-line geometry from the node's `TextLayoutResult`, in px **relative to the node's
   * `boundsInRoot` top-left** (issue: wrapped text collapsed to one line in the figma-svg export).
   * Carries each visible line's substring plus its left edge and baseline, so a consumer can place
   * one `<tspan>`/run per line at the exact position the render wrapped it — the line-break points
   * can't be recovered from the flat string + block bounds alone. Null for single-line text (the
   * common case, drawn from the block baseline) and when the layout result was unavailable.
   */
  val lines: List<ComposeSemanticsTextLine>? = null,
)

/**
 * One laid-out line of a wrapped text node, in px relative to the node's `boundsInRoot` top-left.
 * [baseline] is where the glyphs sit (not the line top); [left] is the line's left edge (non-zero
 * for centred/right-aligned text).
 */
@Serializable
data class ComposeSemanticsTextLine(
  val text: String,
  val left: Int,
  val baseline: Int,
  /** UTF-16 offsets into the node's full text; absent on schema v8 captures. */
  val start: Int? = null,
  val end: Int? = null,
  /**
   * The line's measured width in px (`getLineRight - getLineLeft`), so a consumer reproducing the
   * line can pin it to the width the render measured instead of trusting its own text engine to
   * agree (issue #3024). Browser SVG shaping differs from Android's in small ways that a captured
   * break point cannot absorb — the exported face is subset with its `GPOS`/`kern` stripped, so the
   * browser lays the run out unkerned while the render kerned it. Absent on captures before schema
   * v12.
   */
  val width: Int? = null,
)

/**
 * Resolved design-token data for a single semantics node (issues #1897, #1908). Populated from the
 * node's Compose modifiers — `Modifier.background` (and `Surface`/`Card`, which apply it),
 * `Modifier.border` (outline colour), the shape on `background` / `clip` / `border` /
 * `graphicsLayer`, `Modifier.padding`, and the `Arrangement` spacing of a `Row`/`Column` measure
 * policy. All fields are optional: a node emits only the tokens it actually declares.
 */
@Serializable
data class ComposeSemanticsTokens(
  /** Resolved container/fill colour as ARGB hex (`#AARRGGBB`), e.g. from `Modifier.background`. */
  val backgroundColor: String? = null,
  /**
   * A **linear** gradient `Modifier.background(brush, …)` paints, when the brush could be read
   * (issue #2852). Present instead of [backgroundColor], which only ever resolves a `SolidColor`. A
   * brush we can't parse leaves both null and the export rasters the layer instead of dropping the
   * paint.
   */
  val backgroundGradient: LayoutInspectorGradient? = null,
  /** The border counterpart of [backgroundGradient] — `Modifier.border(width, brush, shape)`. */
  val borderGradient: LayoutInspectorGradient? = null,
  /**
   * Resolved outline/stroke colour as ARGB hex (`#AARRGGBB`) from `Modifier.border` (issue #1908) —
   * the role colours (`outline` / `outlineVariant`) a direct `Modifier.background` never carries.
   */
  val borderColor: String? = null,
  /**
   * Minimum width / height in dp from a `Modifier.defaultMinSize(minWidth, minHeight)`, when set,
   * e.g. `"16.0dp"`. An M3 `Badge` (and other min-sized chrome) can measure — and draw its
   * background at — a larger box than its narrow content is *placed* in; the figma-svg export grows
   * the drawn shape to `max(bounds, minSize)` so it isn't squashed to the placement bounds. Null
   * when the node declares no `defaultMinSize`.
   */
  val minWidth: String? = null,
  val minHeight: String? = null,
  /**
   * Resolved `Modifier.border` stroke width in dp (e.g. `"2.0dp"`), when a border is present and
   * the width could be read. Null falls the figma-svg export back to a 1dp hairline — so an
   * off-state `Switch` track (2dp) or any thicker outline renders at its real width instead of a
   * hairline.
   */
  val borderWidth: String? = null,
  /**
   * Resolved corner radius in dp from the node's `background` / `clip` / `border` / `graphicsLayer`
   * shape. A uniform shape emits a single value (`"12.0dp"`); a non-uniform shape emits the four
   * corners comma-separated (`"12.0dp,12.0dp,0.0dp,0.0dp"`, top-start → bottom-start). dp-based
   * corners are emitted verbatim; percent-based corners (`CircleShape`, `CornerSize(50%)`) are
   * resolved against the node's measured size and density so a circular avatar still reports its
   * effective radius instead of dropping out (issue #1908). Pixel corners
   * (`RoundedCornerShape(12f)`) stay null — they can't be expressed as a fixed dp.
   */
  val cornerRadius: String? = null,
  /**
   * Resolved corner radius in **raw pixels** for a `RoundedCornerShape` built from pixel corner
   * sizes (`RoundedCornerShape(20f)` / any `PxCornerSize`), which [cornerRadius] can't express as a
   * fixed dp and drops. Same uniform-or-four-comma shape as [cornerRadius] but each value carries a
   * `px` suffix (`"20.0px"`, or `"20.0px,10.0px,0.0px,0.0px"` top-start → bottom-start). Populated
   * only when every corner is a pixel corner; a dp/percent `RoundedCornerShape` uses [cornerRadius]
   * instead. The figma-svg export works in captured-pixel space, so this maps straight to the
   * layer's corner radii with no density round-trip; the dp token-compliance consumer ignores it.
   */
  val cornerRadiusPx: String? = null,
  /**
   * Shape-family descriptor for shapes whose radius isn't a single dp number (issue #1908):
   * `"circle"` for a `CircleShape` / all-`CornerSize(50%)` rounded shape, `"cut"` for a
   * `CutCornerShape`. Null for a plain rectangle or an ordinary dp `RoundedCornerShape` (whose
   * radius is already carried by [cornerRadius]).
   */
  val shape: String? = null,
  /**
   * SVG path data — a polyline (`M`/`L`/`Z` only) normalised to the **unit box**, each coordinate a
   * 0..1 fraction of the node's measured size — for a shape none of the corner tokens above could
   * describe: an `Outline.Generic` morph/star/squircle, or a shape wrapper the resolver could not
   * unwrap to its corners (issue #3254). Populated only when [cornerRadius], [cornerRadiusPx] and
   * the rounded-outline fallback have all come up empty, so an understood shape keeps its editable
   * `<rect rx>` and never degrades to a polyline.
   *
   * The figma-svg export draws this path in place of the layer's rect. Before it existed, such a
   * shape exported as a *sharp rectangle* — geometry that was never established, painted over the
   * correctly-shaped pixels underneath. Sampled as a single closed contour; the dp token-compliance
   * consumer ignores it, as it does [cornerRadiusPx].
   */
  val shapePath: String? = null,
  /**
   * Resolved inter-child spacing in dp from a `Row`/`Column` `Arrangement.spacedBy(...)` (or any
   * `Arrangement.HorizontalOrVertical` carrying a non-zero `spacing`), e.g. `"8.0dp"`
   * (issue #1908). Null for layouts with no arrangement spacing. This is the gap the spacing tokens
   * (`cardGap` / `rowGap`) compare against — distinct from [padding], which is the node's own
   * inset.
   */
  val gap: String? = null,
  /** Resolved padding from `Modifier.padding`, in dp per edge. */
  val padding: ComposeSemanticsInsets? = null,
  /**
   * The **leading** padding — a `Modifier.padding` that sits *before* the node's own paint
   * modifiers (`clip` / `background` / `border` / `paint`) in the chain, in dp per edge. Compose
   * applies such padding first, so the shape, fill, and border draw inside the padded box, not the
   * node's outer bounds. The figma-svg export insets the drawn shape by this amount so an
   * expressive `padding(4.dp).clip(CircleShape).border(brush, CircleShape).background(…)` control
   * (Jetsnack's gradient-tinted icon button) rings the inner control instead of the padded root
   * (issue #2852). Trailing padding — `background().padding()` — is *not* recorded here: it insets
   * the content, not the paint, and is handled by the measured-size growth heuristic instead.
   */
  val paintInset: ComposeSemanticsInsets? = null,
  /**
   * The box the node's fill/ring modifier actually painted into, in the same root-space px as
   * [LayoutInspectorNode.bounds] — measured from that modifier's own coordinator, not inferred
   * (issue #3572).
   *
   * Everything above ([paintInset], and the measured-size growth heuristic it holds off) exists to
   * *guess* this rect from the signals a node exposes: its placed `bounds` against its measured
   * `size`, plus where a `padding` sits in the chain. Those signals cannot separate chains that
   * paint differently — `background(brush).padding(16.dp)` (paints the outer box) and
   * `size(120.dp).wrapContentSize().size(40.dp).background(…)` (paints the inner one) present
   * identically. A modifier's coordinator carries the box it drew into directly, so when this is
   * present the export uses it and skips the inference entirely.
   *
   * Null when the capture couldn't read it (a backend whose `ModifierInfo` carries no usable
   * coordinates, or a node with no fill/ring at all), which is what keeps the heuristic alive as
   * the fallback.
   */
  val paintBox: LayoutInspectorBounds? = null,
  /**
   * Resolved shadow elevation in dp from a `Modifier.graphicsLayer { shadowElevation = … }` (what
   * `Surface`/`Card`/`FloatingActionButton` use to cast a Material drop shadow), e.g. `"6.0dp"`.
   * Null when the node casts no shadow. The figma-svg export turns this into an SVG `feDropShadow`
   * so an elevated surface carries its shadow instead of reading as a flat fill against the render.
   */
  val elevation: String? = null,
  /**
   * Effective alpha of the node's `graphicsLayer` chain (`0.0` transparent, `1.0` opaque).
   * Translation and scale are already reflected in captured bounds; alpha is not, so the figma-svg
   * exporter applies this value to the layer group. Null is the common fully-opaque case.
   */
  val opacity: Double? = null,
  /**
   * True when the node carries a `Modifier.clip(shape)` (a `graphicsLayer` with `clip = true`),
   * which masks the node's own draws **and its children** to the node's shape. Distinct from the
   * shape tokens above, which describe what a `background`/`border` *paints*: a plain
   * `background(color, shape)` rounds its fill but does **not** clip an overflowing child, whereas
   * `clip(shape)` does. The figma-svg export turns this into an SVG `clipPath` on the node's group
   * so a child placed beyond the clip (Jetsnack Search/Categories' minimum-size image under
   * `.clip(CategoryShape)`) is masked to the rounded box instead of overflowing and growing the
   * canvas (issue #2852).
   */
  val clipsContent: Boolean? = null,
)

/**
 * True when at least one edge is a positive length — a padding that actually insets the box. A
 * `padding(0.dp)` resolves to an all-`"0.0dp"` inset that changes no geometry, so it must not count
 * as a paint-insetting padding (it would otherwise suppress the growth heuristic for a Wear control
 * whose chain merely contains `padding(0.dp)`) (issue #2852).
 */
fun ComposeSemanticsInsets.insetsPaint(): Boolean =
  insetsPaintHorizontally() || insetsPaintVertically()

/**
 * The horizontal half of [insetsPaint]. A leading padding insets only the axes it actually pads, so
 * the two are asked separately: Wear's `CompactButton` pads `top`/`bottom` by 8dp *before* its fill
 * and `start`/`end` by 12dp *after* it, so its drawn pill is the placed height but the measured
 * width. Suppressing both axes together squashed it to the narrow content box (issue #3573).
 */
fun ComposeSemanticsInsets.insetsPaintHorizontally(): Boolean = positive(start) || positive(end)

/** The vertical half of [insetsPaint] — see there. */
fun ComposeSemanticsInsets.insetsPaintVertically(): Boolean = positive(top) || positive(bottom)

private fun positive(edge: String?): Boolean =
  (edge?.removeSuffix("dp")?.toDoubleOrNull() ?: 0.0) > 0.0

/** Per-edge insets in dp (`"16.0dp"`), as resolved from `Modifier.padding` (issue #1897). */
@Serializable
data class ComposeSemanticsInsets(
  val start: String? = null,
  val top: String? = null,
  val end: String? = null,
  val bottom: String? = null,
)

@Serializable data class LayoutInspectorPayload(val root: LayoutInspectorNode)

/**
 * The scale a node inherits from the `graphicsLayer`s between it and the root — the *drawn* size of
 * one of its own layout pixels. `1.0 × 1.0` (the identity) is the overwhelmingly common case and is
 * never captured; a value is present only when something really shrinks or grows the node at draw
 * time.
 *
 * The canonical producer is Wear's `TransformingLazyColumn`, which shrinks items toward the round
 * face's edges through a draw-time `graphicsLayer` scale while leaving their *measured*
 * [LayoutInspectorNode.size] at full height. [LayoutInspectorNode.bounds] already carries the
 * scaled, on-screen rect (it is mapped through the layer chain), so this field is not needed to
 * place the node — it exists to tell a consumer that the gap between `bounds` and `size` is a real
 * transform rather than a `boundsIn` under-report, so the consumer doesn't "recover" the node back
 * to its unscaled size (issue #2615).
 */
@Serializable
data class LayoutInspectorTransform(
  val scaleX: Float = 1f,
  val scaleY: Float = 1f,
  /**
   * In-plane rotation of the node's local x-axis in root space, degrees clockwise (SVG's own sense,
   * y down). `0` for the overwhelmingly common un-rotated node.
   *
   * A rotated node is the one case where [LayoutInspectorNode.bounds] is *not* the rect the node
   * drew: `boundsIn(root)` returns the axis-aligned bounding box of the rotated rect, which is
   * larger than the node on both axes and has no shape of its own. Wear's `AlertDialog` confirm
   * button is the case that surfaced it — a 126x108 pill turned -45 degrees reports a 166x166 box,
   * and the export drew a 166px circle over a render 120px across. A consumer needs this to know to
   * take the node's own measured extent instead, and to turn the shape back.
   */
  val rotationDegrees: Float = 0f,
) {
  /** True when either axis is scaled enough to matter (beyond float/rounding noise). */
  val scaled: Boolean
    get() = abs(scaleX - 1f) > EPSILON || abs(scaleY - 1f) > EPSILON

  /** True when the node is turned far enough off-axis for its `bounds` to be a bounding box. */
  val rotated: Boolean
    get() = abs(rotationDegrees) > ROTATION_EPSILON_DEGREES

  companion object {
    const val EPSILON: Float = 0.001f

    /**
     * Below this the "rotation" is sub-pixel placement noise on any realistic box, and honouring it
     * would re-centre a node the render drew exactly on its bounds.
     */
    const val ROTATION_EPSILON_DEGREES: Float = 0.5f
  }
}

@Serializable
data class LayoutInspectorNode(
  val nodeId: String,
  /**
   * The node's **own** identity — its own `C(Composable)` name, or (when it has none) its
   * measure-policy class. Kept as the node's own identity on purpose: opaque-component raster
   * matching (`Icon`/`Image`/`TextField`/…) and curved-text detection key off *what the node
   * actually is*, so an inherited display label (`IconButton` ⊃ `Icon`) can never wrongly rasterise
   * a non-opaque wrapper's subtree. The friendly, possibly-inherited label lives in [displayName].
   */
  val component: String,
  /**
   * Friendly layer label — the nearest *enclosing* composable name, filled in when this node's own
   * group carries no `C(Composable)` marker (a library-internal `Box`/`Row` a `Button`/`IconButton`
   * builds itself from). Used only as the figma-svg `<g>` layer id, so the export reads
   * `IconButton` instead of an anonymous `Box`. Null when it would just equal [component]. Additive
   * (v5) — older `layout-inspector.json` decodes with `displayName = null` and falls back to
   * [component].
   */
  val displayName: String? = null,
  val source: String? = null,
  val sourceInfo: String? = null,
  val bounds: LayoutInspectorBounds,
  val size: LayoutInspectorSize,
  val constraints: LayoutInspectorConstraints? = null,
  val placed: Boolean = true,
  val attached: Boolean = true,
  val zIndex: Float? = null,
  val modifiers: List<LayoutInspectorModifier> = emptyList(),
  /**
   * Resolved design tokens for this node, derived from its [modifiers] + measure policy by the
   * shared `ModifierTokenResolver` (issue #1903). `layout/inspector` is the canonical home for
   * these — they are modifier-derived, and this product already models the modifier chain — while
   * `compose/semantics` mirrors the same object (via the same resolver) for the design-parity
   * consumer. Null for the common case of a node that declares none of them (pure layout nodes).
   */
  val tokens: ComposeSemanticsTokens? = null,
  /**
   * Curved text drawn by a Wear `CurvedLayout` / `TimeText` — laid out along an arc, so it can't be
   * a normal straight `<text>`. Captured from the `CurvedTextChild`/`CurvedLayoutInfo` runtime
   * state (which a plain layout-node walk can't see) and rendered as an SVG `<textPath>` on the
   * baseline arc. Empty for the common case.
   */
  val curvedTexts: List<LayoutInspectorCurvedText> = emptyList(),
  /**
   * An editable vector graphic captured from this node's `VectorPainter` (an `Icon`/`Image` backed
   * by an `ImageVector`), so the `compose/figma-svg` export can emit it as real `<path>` layers
   * instead of an opaque raster crop. Null for the common node — including a *bitmap*-backed
   * `Icon`/`Image` (a `BitmapPainter`), which has no vector form and still rasterises. Additive
   * (v6): older `layout-inspector.json` decodes with `vectorGraphic = null`.
   */
  val vectorGraphic: LayoutInspectorVectorGraphic? = null,
  /**
   * The content-loading placeholder this node's modifier chain declares — Wear M3's
   * `Modifier.placeholder` / `Modifier.placeholderShimmer` — together with whether it is currently
   * **visible** (issue #2646). Null for the overwhelming majority of nodes (no placeholder on the
   * chain). See [LayoutInspectorPlaceholder] and [PlaceholderModifiers]. Additive (v8): older
   * `layout-inspector.json` decodes with `placeholder = null`.
   */
  val placeholder: LayoutInspectorPlaceholder? = null,
  /**
   * The pixels this node's own imperative draw painted, re-rendered in **isolation** — captured
   * when [vectorGraphic] couldn't be (the draw used a transform, a shader, a bitmap, a native
   * canvas). Null for the overwhelming majority of nodes (nothing draws imperatively, or the draw
   * vectorised, or it painted nothing of its own). Additive (v10): older `layout-inspector.json`
   * decodes with `drawRaster = null`. See [LayoutInspectorDrawRaster].
   */
  val drawRaster: LayoutInspectorDrawRaster? = null,
  /**
   * True when the live modifier-node chain contains a `DrawModifierNode`, including a draw node
   * delegated by a custom `Modifier.NodeElement`. Unlike inspecting serialized modifier names, this
   * detects modern components whose drawing implementation is hidden behind a
   * `CacheDrawModifierNode`; the hybrid SVG exporter can then preserve an unvectorisable leaf by
   * cropping its rendered frame region. Additive (v16): older captures decode as false.
   */
  val drawsContent: Boolean = false,
  /**
   * True when this node's `drawWithContent` obscures pixels produced by `drawContent()` through a
   * clip, mask, alpha fade, clear blend, or omission. Such an effect is not represented by the
   * layout/semantics tree and cannot be layered beneath editable children; a fidelity export needs
   * the node's composited frame region instead. Capability-based and component-agnostic; false for
   * pass-through draws, background-only draws, and color overlays that leave content present.
   */
  val modifiesDrawnContent: Boolean = false,
  /**
   * The draw-time scale this node inherits from the `graphicsLayer`s above it, when it isn't the
   * identity — a Wear `TransformingLazyColumn` item shrunk toward the curved edge. [bounds] already
   * carries the scaled rect; this says the shrink is *real* so a consumer doesn't grow the node
   * back to its measured [size] (issue #2615). Additive (v9): older `layout-inspector.json` decodes
   * with `transform = null`.
   */
  val transform: LayoutInspectorTransform? = null,
  val children: List<LayoutInspectorNode> = emptyList(),
)

/**
 * A content-loading placeholder declared on a node's modifier chain (issue #2646), resolved by the
 * connector's `ModifierTokenResolver` from a modifier [PlaceholderModifiers] recognises.
 *
 * This is what makes the figma-svg export **state-aware** rather than point-fixing per symptom: the
 * exporter otherwise sees only a `drawWithContent` (which it crops to an `<image>`) and a 50%-pill
 * `shape` (which hijacks the container corner), with no way to tell the ideal state from the
 * loading one. With this object it can do the right thing in both — ignore the overlay entirely
 * when [visible] is false (the real content is drawn, and stays editable vector), and emit the
 * placeholder as its own vector layer, in its own [colorArgb] / corner, when [visible] is true.
 */
@Serializable
data class LayoutInspectorPlaceholder(
  /**
   * [PlaceholderModifiers.KIND_PLACEHOLDER] (the content-covering block) or
   * [PlaceholderModifiers.KIND_SHIMMER] (the sweep overlay drawn over it).
   */
  val kind: String,
  /**
   * Whether the placeholder is currently painting over the content — read from the modifier's
   * `PlaceholderState`. `false` is the ideal/content-loaded state (the `__ideal__` render
   * variants); `true` is the loading state. **Null when the state could not be read**, which the
   * export treats like `false`: the conservative choice, since assuming "loading" would blank real
   * content.
   */
  val visible: Boolean? = null,
  /** The placeholder block's colour as `#AARRGGBB`, or null when it couldn't be resolved. */
  val colorArgb: String? = null,
  /** Corner radius of the placeholder's own shape in dp wire form, as `ComposeSemanticsTokens`. */
  val cornerRadius: String? = null,
  /** Raw-pixel corner radius for a shape with no dp corners. See `ComposeSemanticsTokens`. */
  val cornerRadiusPx: String? = null,
  /** Shape descriptor (`"circle"` / `"cut"` / …) for a shape the corner fields can't express. */
  val shape: String? = null,
)

/**
 * The pixels a node's own draw modifier painted, captured by **re-invoking its draw lambda against
 * an offscreen bitmap** rather than by cropping them out of the composited frame.
 *
 * This is the fallback for a draw the `DrawCaptureExtractor` recorder can't turn into `<path>`s —
 * anything that reaches for a transform, a clip, a shader, a bitmap or the native canvas. The
 * Remote Compose embedded player is the motivating case (issue #2937): every component it
 * interprets paints its background/shape through one `drawWithContent { executeOperations(…) }`,
 * which uses `drawContext.canvas` directly, so the recorder aborts and the node's chrome used to
 * vanish from the export entirely.
 *
 * Isolation is what makes this usable where the frame crop isn't. The frame carries *composited*
 * pixels, so cropping a container's box bakes in its descendants — which is why the crop path is
 * restricted to childless leaves, and why an RC card (a container that draws) fell through it. An
 * isolated re-draw has no descendants in it by construction: [PNG_BASE64][pngBase64] holds only
 * what this node's own modifier painted, so the export can lay it under the node's still-editable
 * children without double-rendering anything. It also needs no frame at all, so the vector-only
 * export gains the same chrome.
 *
 * The capture stops at the lambda's `drawContent()` call, so these pixels are strictly the
 * *behind-the-content* pass. Anything a `drawWithContent` paints *over* its children (a scrim, a
 * blend-mode tint) is deliberately not here — it would be wrong beneath them, and dropping it
 * matches what the export did before.
 */
@Serializable
data class LayoutInspectorDrawRaster(
  /** Captured region in root-pixel space — the union of the node's draw modifiers' own bounds. */
  val left: Int,
  val top: Int,
  val right: Int,
  val bottom: Int,
  /** The isolated re-draw as a base64 PNG, `right-left` × `bottom-top` px. */
  val pngBase64: String,
) {
  val width: Int
    get() = (right - left).coerceAtLeast(0)

  val height: Int
    get() = (bottom - top).coerceAtLeast(0)
}

/**
 * An editable vector graphic (an `ImageVector` an `Icon`/`Image` painted) captured off a node's
 * `VectorPainter`. Path coordinates are in the vector's own viewport ([viewportWidth] ×
 * [viewportHeight]); the figma-svg export uniformly scales and centers them in the node's placed
 * bounds, so the same icon renders crisp without changing aspect ratio. Only solid (`SolidColor`)
 * fills/strokes are captured — a gradient/brush leaves its colour null and the export drops that
 * fill rather than guessing — matching the vector-vs-raster rule the rest of the export follows.
 */
@Serializable
data class LayoutInspectorVectorGraphic(
  val viewportWidth: Float,
  val viewportHeight: Float,
  val paths: List<LayoutInspectorVectorPath>,
  /**
   * True when these paths were recorded from the node's own imperative draw lambda (the
   * `DrawCaptureExtractor` path: a slider groove, a progress arc) rather than reflected off an
   * `ImageVector` painter.
   *
   * The export needs the distinction to know what a draw modifier on the same node means. For a
   * captured draw the modifier *is* these paths, and everything it painted is already represented.
   * For an `ImageVector` the draw modifier is a separate overlay that nothing here accounts for —
   * Jetsnack tints its icons with `drawWithContent { drawContent(); drawRect(brush, blendMode) }` —
   * so the layer has to raster instead of emitting the untinted icon (issue #2852).
   */
  val fromDrawCapture: Boolean = false,
  /**
   * The `ImageVector`'s own name, when the capture reflected one off a `VectorPainter` —
   * `"Filled.Menu"`, `"AutoMirrored.Outlined.ArrowBack"`, or whatever an app passed to its own
   * `ImageVector.Builder`. Null for a draw-capture ([fromDrawCapture]) and for a vector whose name
   * couldn't be read.
   *
   * The geometry alone can't tell a stock Material icon from an app's own artwork; this name can,
   * which is what lets the figma-svg export annotate an icon with its canonical fonts.google.com
   * identity ([MaterialIconRef]). Carried raw — the *interpretation* lives in `MaterialIconRef`, so
   * a name this repo doesn't recognise today stays available to a later reader.
   */
  val vectorName: String? = null,
)

/**
 * One `<path>` of a [LayoutInspectorVectorGraphic]: SVG path data plus its resolved solid paint.
 */
@Serializable
data class LayoutInspectorVectorPath(
  /** SVG path `d` string, in viewport coordinates. */
  val pathData: String,
  /** Solid fill as `#AARRGGBB`, or null when there is no fill (or a non-solid brush fill). */
  val fillArgb: String? = null,
  /** Extra fill alpha (`fillAlpha`, 0..1) multiplied onto [fillArgb]'s own alpha. */
  val fillAlpha: Float = 1f,
  /** Solid stroke as `#AARRGGBB`, or null when there is no stroke (or a non-solid brush stroke). */
  val strokeArgb: String? = null,
  /** Stroke width in viewport units; 0 when unstroked. */
  val strokeWidth: Float = 0f,
  /** Extra stroke alpha (`strokeAlpha`, 0..1) multiplied onto [strokeArgb]'s own alpha. */
  val strokeAlpha: Float = 1f,
  /**
   * SVG `stroke-linecap` (`"round"`/`"square"`) for a non-default cap; null = butt (the default).
   */
  val strokeCap: String? = null,
  /**
   * SVG `stroke-linejoin` (`"round"`/`"bevel"`) for a non-default join; null = miter (the default).
   */
  val strokeJoin: String? = null,
  /** True when the fill uses the even-odd winding rule (SVG `fill-rule="evenodd"`). */
  val evenOdd: Boolean = false,
)

/**
 * One run of Wear curved text along a circular baseline, in root-pixel space. The baseline circle
 * is centred at ([centerXPx], [centerYPx]) with radius [radiusPx]; the run spans [sweepRadians]
 * from [startAngleRadians] (screen convention: angle measured clockwise from +x, so `1.5π` = top).
 * Text reads [clockwise] along the arc at [fontSizePx].
 */
@Serializable
data class LayoutInspectorCurvedText(
  val text: String,
  val centerXPx: Double,
  val centerYPx: Double,
  val radiusPx: Double,
  val startAngleRadians: Double,
  val sweepRadians: Double,
  val clockwise: Boolean,
  val fontSizePx: Double,
  val fontWeight: Int? = null,
  val colorArgb: String? = null,
)

@Serializable
data class LayoutInspectorBounds(val left: Int, val top: Int, val right: Int, val bottom: Int)

@Serializable data class LayoutInspectorSize(val width: Int, val height: Int)

@Serializable
data class LayoutInspectorConstraints(
  val minWidth: Int,
  val maxWidth: Int? = null,
  val minHeight: Int,
  val maxHeight: Int? = null,
)

@Serializable
data class LayoutInspectorModifier(
  val name: String,
  val value: String? = null,
  val properties: Map<String, String> = emptyMap(),
  val bounds: LayoutInspectorBounds? = null,
  /**
   * True when this entry belongs to a content-loading placeholder ([PlaceholderModifiers]) — the
   * shimmer's own element, or the anonymous `drawWithContent` / `graphicsLayer` pair
   * `Modifier.placeholder` lowers to.
   *
   * Node-level [LayoutInspectorNode.placeholder] says a placeholder is present; this says *which
   * entries are it*, which is what lets the export drop the placeholder's pass-through draw without
   * dropping an unrelated `Modifier.drawBehind {…}` on the same chain (whose pixels are genuinely
   * in the frame). Additive (v8): older files decode with `placeholder = false`.
   */
  val placeholder: Boolean = false,
)
