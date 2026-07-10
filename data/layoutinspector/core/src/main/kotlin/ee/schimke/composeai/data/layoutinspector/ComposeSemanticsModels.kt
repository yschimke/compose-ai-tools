package ee.schimke.composeai.data.layoutinspector

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
  const val SCHEMA_VERSION: Int = 8
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
  const val SCHEMA_VERSION: Int = 3
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
   * Directory (relative to the preview's output dir) holding the per-node `<node>.png` crops a
   * **hybrid** export references via `<image href="figma-raster/<node>.png">`. Empty for a
   * vector-only export. The single source of truth for the prefix [FigmaSvgModel.defaultRasterHref]
   * emits and consumers (the design-catalog carrier) collect.
   */
  const val RASTER_DIR: String = "figma-raster"
}

@Serializable data class ComposeSemanticsPayload(val root: ComposeSemanticsNode)

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
  /** Resolved line height as `"<value>sp"` / `"<value>em"`. */
  val lineHeight: String? = null,
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
data class ComposeSemanticsTextLine(val text: String, val left: Int, val baseline: Int)

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
   * Resolved shadow elevation in dp from a `Modifier.graphicsLayer { shadowElevation = … }` (what
   * `Surface`/`Card`/`FloatingActionButton` use to cast a Material drop shadow), e.g. `"6.0dp"`.
   * Null when the node casts no shadow. The figma-svg export turns this into an SVG `feDropShadow`
   * so an elevated surface carries its shadow instead of reading as a flat fill against the render.
   */
  val elevation: String? = null,
)

/** Per-edge insets in dp (`"16.0dp"`), as resolved from `Modifier.padding` (issue #1897). */
@Serializable
data class ComposeSemanticsInsets(
  val start: String? = null,
  val top: String? = null,
  val end: String? = null,
  val bottom: String? = null,
)

@Serializable data class LayoutInspectorPayload(val root: LayoutInspectorNode)

@Serializable
data class LayoutInspectorNode(
  val nodeId: String,
  val component: String,
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
  val children: List<LayoutInspectorNode> = emptyList(),
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
)
