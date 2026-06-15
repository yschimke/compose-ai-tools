package ee.schimke.composeai.data.layoutinspector

import kotlinx.serialization.Serializable

object ComposeSemanticsProduct {
  const val KIND: String = "compose/semantics"
  // v3 (#1897): each node may carry resolved design-token data (`tokens`) — container colour,
  // corner radius, padding — so design-parity's token-compliance check can populate `actual`
  // instead of degrading to "missing from candidate". Additive: older `compose-semantics.json`
  // parses with `tokens = null`.
  const val SCHEMA_VERSION: Int = 3
  const val FILE: String = "compose-semantics.json"
}

object LayoutInspectorProduct {
  const val KIND: String = "layout/inspector"
  const val SCHEMA_VERSION: Int = 1
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
  val layoutFontSize: String? = null,
  val layoutForegroundColor: String? = null,
  val layoutBackgroundColor: String? = null,
  val layoutLineCount: Int? = null,
  val layoutMaxLines: Int? = null,
  val layoutOverflow: String? = null,
  val layoutTruncated: Boolean? = null,
  val layoutDidOverflowWidth: Boolean? = null,
  val layoutDidOverflowHeight: Boolean? = null,
  val editableText: String? = null,
  val inputText: String? = null,
  val role: String? = null,
  val testTag: String? = null,
  val mergeMode: String? = null,
  val clickable: Boolean = false,
  /**
   * Resolved design-token data extracted from this node's modifiers (issue #1897).
   *
   * The text half of the projection (`layoutForegroundColor`, `layoutFontSize`, …) describes drawn
   * text; this carries the *container* tokens design-parity's token-compliance check compares
   * against — resolved background/fill colour, corner radius, and padding. Null for the common case
   * of a node that declares none of them (pure layout / text nodes).
   */
  val tokens: ComposeSemanticsTokens? = null,
  val children: List<ComposeSemanticsNode> = emptyList(),
)

/**
 * Resolved design-token data for a single semantics node (issue #1897). Populated from the node's
 * Compose modifiers — `Modifier.background` (and `Surface`/`Card`, which apply it), the shape on
 * `background` / `clip` / `border`, and `Modifier.padding`. All fields are optional: a node emits
 * only the tokens it actually declares.
 */
@Serializable
data class ComposeSemanticsTokens(
  /** Resolved container/fill colour as ARGB hex (`#AARRGGBB`), e.g. from `Modifier.background`. */
  val backgroundColor: String? = null,
  /**
   * Resolved corner radius in dp from the node's `background` / `clip` / `border` shape. A uniform
   * `RoundedCornerShape` emits a single value (`"12.0dp"`); a non-uniform shape emits the four
   * corners comma-separated (`"12.0dp,12.0dp,0.0dp,0.0dp"`, top-start → bottom-start).
   */
  val cornerRadius: String? = null,
  /** Resolved padding from `Modifier.padding`, in dp per edge. */
  val padding: ComposeSemanticsInsets? = null,
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
