package ee.schimke.composeai.xr

import ee.schimke.composeai.data.layoutinspector.ComposeSemanticsNode
import kotlinx.serialization.Serializable

/**
 * The unified **3D-over-2D semantics tree**: one tree whose top levels are 3D (the subspace layout)
 * and whose every panel carries a normal 2D semantics tree.
 *
 * - The 3D hierarchy ([SpatialSemanticsNode.children]) mirrors a Compose-XR `Subspace` —
 *   `SpatialRow`/`SpatialColumn`/`SpatialBox` containers down to `SpatialPanel` leaves — with each
 *   node's [SpatialSemanticsNode.poseInRoot] + [SpatialSemanticsNode.sizeDp] recovered offline the
 *   way [`SubspaceSceneRecorder`] already recovers `SpatialScene` poses (no headset / OpenXR).
 * - Each panel leaf carries [SpatialSemanticsNode.panelContent] — the 2D [ComposeSemanticsNode]
 *   tree of the panel's hosted content, the same projection `compose/semantics` and the wireframe
 *   use.
 *
 * **An ordinary (non-XR) preview is the degenerate single-panel case**: one `panel` node at
 * identity pose whose `panelContent` is the whole 2D tree. So the per-panel 2D wireframe is the
 * leaf renderer for every preview, XR or not.
 *
 * Conventions match [SpatialScene] (shared [Vec3]/[Quat]/[SpatialPose]): linear quantities are
 * **dp**; axes are right-handed (+x right, +y up, +z toward the viewer); rotation is a unit
 * quaternion. See
 * [docs/design/SPATIAL_SEMANTICS_TREE.md](../../../../../../../../docs/design/SPATIAL_SEMANTICS_TREE.md).
 *
 * Like `SpatialScene`, this is a wire DTO with a TypeScript mirror; a round-trip test against a
 * committed fixture keeps the two languages locked. Bump [SPATIAL_SEMANTICS_TREE_VERSION] on any
 * breaking shape change.
 */
public const val SPATIAL_SEMANTICS_TREE_VERSION: Int = 1

/** Box extent in dp; `depth` is 0 for a flat panel and non-zero only for a `SpatialBox`. */
@Serializable public data class Size3dDp(val width: Int, val height: Int, val depth: Int = 0)

/** The kind of a [SpatialSemanticsNode] — the 3D container type, or a content-hosting `panel`. */
public object SpatialSemanticsKind {
  public const val SUBSPACE_ROOT: String = "subspaceRoot"
  public const val ROW: String = "row"
  public const val COLUMN: String = "column"
  public const val BOX: String = "box"
  public const val PANEL: String = "panel"
  public const val ORBITER: String = "orbiter"
}

/**
 * A node in the spatial semantics tree. Container kinds (`row`/`column`/`box`/`subspaceRoot`) carry
 * [children]; a `panel`/`orbiter` leaf carries [panelContent] (its 2D semantics tree). A node may
 * carry both when a panel itself nests further subspace content.
 */
@Serializable
public data class SpatialSemanticsNode(
  val id: String,
  /** One of [SpatialSemanticsKind]. */
  val kind: String,
  val label: String? = null,
  val poseInRoot: SpatialPose,
  val sizeDp: Size3dDp,
  /** The 2D semantics tree of this panel's hosted content (null for pure container nodes). */
  val panelContent: ComposeSemanticsNode? = null,
  val children: List<SpatialSemanticsNode> = emptyList(),
)

/**
 * The full 3D-over-2D tree for one preview. [version] must equal [SPATIAL_SEMANTICS_TREE_VERSION].
 */
@Serializable
public data class SpatialSemanticsTree(
  val version: Int = SPATIAL_SEMANTICS_TREE_VERSION,
  val units: String = "dp",
  val previewId: String? = null,
  val root: SpatialSemanticsNode,
)

/** Pure constructors for [SpatialSemanticsTree] shared by every producer (XR + degenerate 2D). */
public object SpatialSemanticsTrees {

  /** The identity pose ([Vec3] 0 + unit [Quat]) shared by every container/degenerate node. */
  public fun identityPose(): SpatialPose =
    SpatialPose(translation = Vec3(0.0, 0.0, 0.0), rotation = Quat(0.0, 0.0, 0.0, 1.0))

  /**
   * Wraps [panelNodes] under a `subspaceRoot` at identity pose (poses on children are absolute).
   */
  public fun subspaceRoot(panelNodes: List<SpatialSemanticsNode>): SpatialSemanticsNode =
    SpatialSemanticsNode(
      id = "subspaceRoot",
      kind = SpatialSemanticsKind.SUBSPACE_ROOT,
      poseInRoot = identityPose(),
      sizeDp = Size3dDp(width = 0, height = 0),
      children = panelNodes,
    )

  /**
   * The degenerate **non-XR** tree: a single `panel` node at identity pose whose [panelContent] is
   * the whole 2D semantics tree, wrapped under a `subspaceRoot` so its shape matches the XR path
   * (every tree's root is a `subspaceRoot`). This is what an ordinary `@Preview` projects to — the
   * per-panel 2D wireframe is the leaf renderer for every preview, XR or not.
   */
  public fun singlePanel(
    content: ComposeSemanticsNode,
    sizeDp: Size3dDp,
    previewId: String? = null,
    panelId: String = "panel",
    label: String? = null,
  ): SpatialSemanticsTree =
    SpatialSemanticsTree(
      previewId = previewId,
      root =
        subspaceRoot(
          listOf(
            SpatialSemanticsNode(
              id = panelId,
              kind = SpatialSemanticsKind.PANEL,
              label = label,
              poseInRoot = identityPose(),
              sizeDp = sizeDp,
              panelContent = content,
            )
          )
        ),
    )
}
