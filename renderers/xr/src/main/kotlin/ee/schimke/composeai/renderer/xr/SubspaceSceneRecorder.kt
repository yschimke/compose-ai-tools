package ee.schimke.composeai.renderer.xr

import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.xr.compose.subspace.node.SubspaceSemanticsInfo
import androidx.xr.compose.testing.SubspaceTestContext
import androidx.xr.compose.testing.onSubspaceNodeWithTag
import ee.schimke.composeai.data.layoutinspector.ComposeSemanticsNode
import ee.schimke.composeai.xr.OrbitCamera
import ee.schimke.composeai.xr.Quat
import ee.schimke.composeai.xr.SizeDp
import ee.schimke.composeai.xr.Size3dDp
import ee.schimke.composeai.xr.SpatialPanel
import ee.schimke.composeai.xr.SpatialPose
import ee.schimke.composeai.xr.SpatialScene
import ee.schimke.composeai.xr.SpatialSemanticsKind
import ee.schimke.composeai.xr.SpatialSemanticsNode
import ee.schimke.composeai.xr.SpatialSemanticsTree
import ee.schimke.composeai.xr.SpatialSemanticsTrees
import ee.schimke.composeai.xr.Vec3

/**
 * Recovers a [SpatialScene] from a Compose-XR `Subspace` that has already been composed under a
 * fake XR runtime (no headset / OpenXR / SceneCore native — see
 * docs/design/XR_SPATIAL_PREVIEW.md and `:samples:xr-spatial`'s `SubspaceLayoutPoseTest`).
 *
 * This reads each named panel's `poseInRoot` and `size` from the public spatial-semantics tree and
 * maps them to the [SpatialScene] wire shape. It also recovers each panel's live content [View] (see
 * [recordAllWithViews]) so the texture pass ([SubspaceSceneWriter.captureViewTextures]) can
 * rasterise the real panel content into the `<tag>.png` the scene references.
 *
 * The caller owns the composition: it must enable the `android.software.xr.api.spatial` system
 * feature (so `Subspace` takes its spatial path) and `setContent { Subspace { … } }` with each
 * panel carrying a `SubspaceModifier.testTag(...)`, then pass those tags here.
 */
public object SubspaceSceneRecorder {

  /** The system feature `Subspace` checks to select its spatial path over the 2D fallback. */
  public const val XR_SPATIAL_FEATURE: String = "android.software.xr.api.spatial"

  /**
   * Reads the [panelTags] from the subspace composed on [rule] into a [SpatialScene]. Each tag must
   * resolve to exactly one subspace node (a `SpatialPanel`); [previewId] is recorded for traceback.
   */
  public fun record(
    rule: AndroidComposeTestRule<*, ComponentActivity>,
    panelTags: List<String>,
    previewId: String? = null,
  ): SpatialScene {
    val panels =
      panelTags.map { tag ->
        val node = rule.onSubspaceNodeWithTag(tag).fetchSemanticsNode("no subspace node '$tag'")
        panelFrom(node, id = tag, parentId = null)
      }
    return SpatialScene(previewId = previewId, camera = defaultCamera(panels), panels = panels)
  }

  /**
   * Auto-enumerates the **tagged** panels of the subspace composed on [rule] — the path the render
   * pipeline takes for a discovered `@XrSubspacePreview`. Every node carrying a
   * `SubspaceModifier.testTag(...)` becomes a [SpatialPanel], with the tag as its id. Authors tag
   * the panels they want in the scene; an untagged `SpatialPanel` produces no spatial-semantics
   * node and is therefore invisible here (and to `onSubspaceNodeWithTag`), so tagging is required
   * either way.
   *
   * `poseInRoot` is absolute, so `parentId` is left null — the viewer positions panels without the
   * hierarchy.
   *
   * Implementation note: the public spatial-semantics surface only exposes nodes by *unique* match,
   * and the merged root reports no children, so there's no public way to list every node. We reach
   * the flat node list through one reflective call into `androidx.xr.compose.testing`'s internal
   * `SubspaceTestContext.getAllSemanticsNodes`; the nodes it returns are the public
   * [SubspaceSemanticsInfo] type and the tag is read from the standard
   * [SemanticsProperties.TestTag], so only the list *access* is reflective. `:samples:xr-spatial`'s
   * `SubspaceLayoutPoseTest` (and these tests) are the canary if that internal shifts in a future
   * `androidx.xr.compose:compose-testing`.
   */
  public fun recordAll(
    rule: AndroidComposeTestRule<*, ComponentActivity>,
    previewId: String? = null,
  ): SpatialScene = recordAllWithViews(rule, previewId).scene

  /** A recovered [SpatialScene] plus each panel's live content [View], keyed by panel id. */
  public class RecordedSubspace(
    public val scene: SpatialScene,
    public val panelViews: Map<String, View>,
  )

  /**
   * Like [recordAll], but also recovers each tagged panel's live content [View] so the texture pass
   * can rasterise it. The view is reached through the runtime panel entity backing each
   * spatial-semantics node (see [contentView]); panels whose view can't be recovered are simply
   * absent from [RecordedSubspace.panelViews] (the scene still carries their geometry + texture
   * path, so a missing capture degrades to a placeholder rather than failing the render).
   */
  public fun recordAllWithViews(
    rule: AndroidComposeTestRule<*, ComponentActivity>,
    previewId: String? = null,
  ): RecordedSubspace {
    val tagged =
      allSemanticsNodes(rule).mapNotNull { node ->
        val tag = node.config.getOrNull(SemanticsProperties.TestTag) ?: return@mapNotNull null
        tag to node
      }
    val panels = tagged.map { (tag, node) -> panelFrom(node, id = tag, parentId = null) }
    // The tag is the panel id and drives the `<id>.png` texture path, so duplicates would yield an
    // ambiguous scene + colliding captures. The tag-based `record` path fails on this implicitly
    // (onSubspaceNodeWithTag requires a unique match); make recordAll fail just as loudly.
    val duplicates = panels.groupingBy { it.id }.eachCount().filterValues { it > 1 }.keys
    check(duplicates.isEmpty()) {
      "Duplicate subspace panel testTag(s) ${duplicates.sorted()}: each SpatialPanel in an " +
        "@XrSubspacePreview must carry a unique testTag (panel ids and texture paths derive from it)."
    }
    val views = tagged.mapNotNull { (tag, node) -> contentView(node)?.let { tag to it } }.toMap()
    val scene = SpatialScene(previewId = previewId, camera = defaultCamera(panels), panels = panels)
    return RecordedSubspace(scene, views)
  }

  /**
   * Records the unified **3D-over-2D** [SpatialSemanticsTree]: the subspace layout (a `subspaceRoot`
   * with one `panel` child per tagged `SpatialPanel`, each carrying the recovered pose/size) with
   * every panel's 2D content tree attached as [SpatialSemanticsNode.panelContent].
   *
   * The 2D tree is recovered from each panel's live content [View]: a `SpatialPanel` composes its
   * content into a view whose Compose root implements [RootForTest], so its
   * `semanticsOwner.unmergedRootSemanticsNode` is the ordinary 2D semantics root (proven by
   * `SubspacePanelSemanticsSpikeTest`). That [SemanticsNode] is handed to [projectSemantics] — the
   * same projection `compose/semantics` and the wireframe use, injected rather than imported so this
   * renderer module stays free of the daemon-side connector that owns it (the daemon passes
   * `ComposeSemanticsDataProducer.buildPayload(it).root`). A panel whose view or semantics can't be
   * recovered simply gets a null [SpatialSemanticsNode.panelContent] — its geometry still lands, so
   * one unreadable panel degrades to a face without an overlay rather than failing the tree.
   */
  public fun recordTree(
    rule: AndroidComposeTestRule<*, ComponentActivity>,
    previewId: String? = null,
    projectSemantics: (SemanticsNode) -> ComposeSemanticsNode,
  ): SpatialSemanticsTree {
    val recorded = recordAllWithViews(rule, previewId)
    val panelNodes =
      recorded.scene.panels.map { panel ->
        val content =
          recorded.panelViews[panel.id]?.findRootForTest()?.let { rootForTest ->
            runCatching { projectSemantics(rootForTest.semanticsOwner.unmergedRootSemanticsNode) }
              .getOrNull()
          }
        SpatialSemanticsNode(
          id = panel.id,
          kind = SpatialSemanticsKind.PANEL,
          poseInRoot = panel.poseInRoot,
          sizeDp = Size3dDp(width = panel.sizeDp.width, height = panel.sizeDp.height),
          panelContent = content,
        )
      }
    return SpatialSemanticsTree(previewId = previewId, root = subspaceRootOf(panelNodes))
  }

  /**
   * The degenerate **non-XR** case: an ordinary preview is a single `panel` at identity pose whose
   * [SpatialSemanticsNode.panelContent] is the whole 2D tree. Pure (no subspace / Robolectric), so
   * the daemon's normal 2D render path can wrap its `compose/semantics` payload into the same tree
   * shape the XR path produces — making the per-panel wireframe the leaf renderer for every preview.
   */
  public fun singlePanelTree(
    content: ComposeSemanticsNode,
    sizeDp: Size3dDp,
    previewId: String? = null,
    panelId: String = "panel",
  ): SpatialSemanticsTree =
    SpatialSemanticsTrees.singlePanel(
      content = content,
      sizeDp = sizeDp,
      previewId = previewId,
      panelId = panelId,
    )

  /** Wraps [panelNodes] under a `subspaceRoot` at identity pose (poses on children are absolute). */
  private fun subspaceRootOf(panelNodes: List<SpatialSemanticsNode>): SpatialSemanticsNode =
    SpatialSemanticsTrees.subspaceRoot(panelNodes)

  private fun identityPose(): SpatialPose = SpatialSemanticsTrees.identityPose()

  /** First [RootForTest] (the Compose `AndroidComposeView`) in this view's subtree, or null. */
  private fun View.findRootForTest(): RootForTest? {
    if (this is RootForTest) return this
    if (this is ViewGroup) {
      for (i in 0 until childCount) {
        getChildAt(i).findRootForTest()?.let { return it }
      }
    }
    return null
  }

  /**
   * Recovers the content [View] hosted by a panel node. A `SpatialPanel`'s
   * [SubspaceSemanticsInfo.getSemanticsEntity] is the public `androidx.xr.scenecore.PanelEntity`,
   * whose `rtEntity` is the runtime panel — under the fake XR runtime that's a
   * `FakePanelEntity`, which holds the `android.view.View` the panel composed. Both the
   * `getRtEntity$scenecore` bridge and `getView` are reached reflectively so this module compiles
   * without the scenecore-testing fakes on its main classpath (they're a render-time dependency,
   * mirroring how the node enumeration reaches `compose-testing` internals); the recorder tests are
   * the canary if either shifts.
   */
  private fun contentView(node: SubspaceSemanticsInfo): View? {
    val entity: Any = node.semanticsEntity ?: return null
    return runCatching {
        val rt = entity.javaClass.getMethod("getRtEntity\$scenecore").invoke(entity) ?: return null
        rt.javaClass.getMethod("getView").invoke(rt) as? View
      }
      .getOrNull()
  }

  @Suppress("UNCHECKED_CAST")
  private fun allSemanticsNodes(
    rule: AndroidComposeTestRule<*, ComponentActivity>
  ): List<SubspaceSemanticsInfo> {
    val context = SubspaceTestContext(rule)
    return try {
      val method =
        context.javaClass.getMethod(
          "getAllSemanticsNodes\$compose_testing",
          Boolean::class.javaPrimitiveType,
        )
      method.isAccessible = true
      (method.invoke(context, /* useUnmergedTree = */ true) as Iterable<SubspaceSemanticsInfo>)
        .toList()
    } catch (e: ReflectiveOperationException) {
      throw IllegalStateException(
        "Could not enumerate subspace nodes via androidx.xr.compose.testing internals; the " +
          "compose-testing API may have changed (see SubspaceSceneRecorder.recordAll).",
        e,
      )
    }
  }

  private fun panelFrom(node: SubspaceSemanticsInfo, id: String, parentId: String?): SpatialPanel {
    val t = node.poseInRoot.translation
    val r = node.poseInRoot.rotation
    val size = node.size
    // A modifier can resolve to a degenerate pose — e.g. `rotateToLookAtUser` when the head pose
    // coincides with the panel position gives a zero-length look direction and a NaN quaternion.
    // JSON has no NaN/Infinity, so emitting one fails scene.json serialization and breaks the whole
    // render; sanitise to a finite pose (identity rotation, dropped non-finite offset) so one bad
    // panel degrades to facing forward instead of taking down the scene.
    val rot =
      if (r.x.isFinite() && r.y.isFinite() && r.z.isFinite() && r.w.isFinite()) {
        Quat(r.x.toDouble(), r.y.toDouble(), r.z.toDouble(), r.w.toDouble())
      } else {
        Quat(0.0, 0.0, 0.0, 1.0)
      }
    return SpatialPanel(
      id = id,
      poseInRoot =
        SpatialPose(
          translation = Vec3(t.x.finiteOrZero(), t.y.finiteOrZero(), t.z.finiteOrZero()),
          rotation = rot,
        ),
      sizeDp = SizeDp(width = size.width, height = size.height),
      texture = "$id.png",
      parentId = parentId,
    )
  }

  /** A finite value as a [Double], or `0.0` for `NaN`/`Infinity` (which JSON can't represent). */
  private fun Float.finiteOrZero(): Double = if (isFinite()) toDouble() else 0.0

  /**
   * A neutral orbit camera framing the panels near head-on: look at the centre of their combined
   * bounds from the distance that makes the layout fill most of the frame, at a gentle downward
   * pitch. Producers/consumers may override.
   *
   * Framing matches the Android XR reference shots, where a panel sits large and roughly head-on
   * rather than small in a sea of void. We fit the layout's full XY bounds (not just one panel's
   * largest side) into the compositor's 45° vertical / 16:10 (1280x800) frame, leave a small margin
   * so corners and the soft shadow aren't clipped, and clamp the distance so a tiny single panel
   * doesn't pull the camera implausibly close. Generic across one panel and a multi-panel row.
   */
  internal fun defaultCamera(panels: List<SpatialPanel>): OrbitCamera {
    if (panels.isEmpty()) {
      return OrbitCamera(target = Vec3(0.0, 0.0, 0.0), distance = 1200.0, yawDeg = 0.0, pitchDeg = -6.0)
    }
    // Combined axis-aligned bounds of every panel (centre ± half-size on each axis).
    val xs = panels.flatMap { listOf(it.poseInRoot.translation.x - it.sizeDp.width / 2.0, it.poseInRoot.translation.x + it.sizeDp.width / 2.0) }
    val ys = panels.flatMap { listOf(it.poseInRoot.translation.y - it.sizeDp.height / 2.0, it.poseInRoot.translation.y + it.sizeDp.height / 2.0) }
    val boundsW = xs.max() - xs.min()
    val boundsH = ys.max() - ys.min()
    val centreX = (xs.min() + xs.max()) / 2.0
    val centreY = (ys.min() + ys.max()) / 2.0

    // Compositor frame: 45° vertical FOV, 1280x800 (aspect 1.6). Distance to fit a given extent:
    //   verticalFit  -> halfH / tan(vFov/2)
    //   horizontalFit-> halfW / (tan(vFov/2) * aspect)
    // Take the larger so the whole layout fits, then divide by FILL so it occupies ~FILL of the
    // frame (margin for rounded corners + soft shadow).
    val vFovRad = Math.toRadians(VERTICAL_FOV_DEG)
    val aspect = COMPOSITE_WIDTH.toDouble() / COMPOSITE_HEIGHT.toDouble()
    val tanHalf = Math.tan(vFovRad / 2.0)
    val distForHeight = (boundsH / 2.0) / tanHalf
    val distForWidth = (boundsW / 2.0) / (tanHalf * aspect)
    val fit = maxOf(distForHeight, distForWidth)
    val distance = (fit / FRAME_FILL).coerceAtLeast(MIN_DISTANCE)

    return OrbitCamera(
      target = Vec3(centreX, centreY, 0.0),
      distance = distance,
      yawDeg = 0.0,
      // Near head-on, with just a hint of downward tilt so panels read as floating in front of you.
      pitchDeg = -6.0,
    )
  }

  // Compositor framing constants — kept in sync with renderers/xr-composite (45° vertical FOV) and
  // the bake size the Gradle `composePreviewCompositeXr` task passes (--width 1280 --height 800).
  private const val VERTICAL_FOV_DEG = 45.0
  private const val COMPOSITE_WIDTH = 1280
  private const val COMPOSITE_HEIGHT = 800
  // Fraction of the frame the layout should fill (the rest is margin for corners + shadow).
  private const val FRAME_FILL = 0.82
  // Don't let a single small panel pull the camera implausibly close.
  private const val MIN_DISTANCE = 600.0
}
