package ee.schimke.composeai.data.layoutinspector

import kotlinx.serialization.Serializable

object ComposeSemanticsProduct {
  const val KIND: String = "compose/semantics"
  const val SCHEMA_VERSION: Int = 2
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
  val children: List<ComposeSemanticsNode> = emptyList(),
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
