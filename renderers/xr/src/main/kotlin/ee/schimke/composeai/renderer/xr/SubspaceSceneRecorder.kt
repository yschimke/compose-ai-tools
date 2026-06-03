package ee.schimke.composeai.renderer.xr

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.xr.compose.subspace.node.SubspaceSemanticsInfo
import androidx.xr.compose.testing.SubspaceTestContext
import androidx.xr.compose.testing.onSubspaceNodeWithTag
import ee.schimke.composeai.xr.OrbitCamera
import ee.schimke.composeai.xr.Quat
import ee.schimke.composeai.xr.SizeDp
import ee.schimke.composeai.xr.SpatialPanel
import ee.schimke.composeai.xr.SpatialPose
import ee.schimke.composeai.xr.SpatialScene
import ee.schimke.composeai.xr.Vec3

/**
 * Recovers a [SpatialScene] from a Compose-XR `Subspace` that has already been composed under a
 * fake XR runtime (no headset / OpenXR / SceneCore native — see
 * docs/design/XR_SPATIAL_PREVIEW.md and `:samples:xr-spatial`'s `SubspaceLayoutPoseTest`).
 *
 * This is the geometry half of the producer: it reads each named panel's `poseInRoot` and `size`
 * from the public spatial-semantics tree and maps them to the [SpatialScene] wire shape. Rendering
 * each panel's 2D content to the `texture` PNG is a separate pass; until then the texture path
 * follows the `<tag>.png` convention so the emitted scene is shape-complete.
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
  ): SpatialScene {
    val panels =
      allSemanticsNodes(rule).mapNotNull { node ->
        val tag = node.config.getOrNull(SemanticsProperties.TestTag) ?: return@mapNotNull null
        panelFrom(node, id = tag, parentId = null)
      }
    return SpatialScene(previewId = previewId, camera = defaultCamera(panels), panels = panels)
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
    return SpatialPanel(
      id = id,
      poseInRoot =
        SpatialPose(
          translation = Vec3(t.x.toDouble(), t.y.toDouble(), t.z.toDouble()),
          rotation = Quat(r.x.toDouble(), r.y.toDouble(), r.z.toDouble(), r.w.toDouble()),
        ),
      sizeDp = SizeDp(width = size.width, height = size.height),
      texture = "$id.png",
      parentId = parentId,
    )
  }

  /**
   * A neutral orbit camera framing the panels: look at the vertical centre of their bounds from a
   * distance scaled to the layout, at a slight downward pitch. Producers/consumers may override.
   */
  internal fun defaultCamera(panels: List<SpatialPanel>): OrbitCamera {
    if (panels.isEmpty()) {
      return OrbitCamera(target = Vec3(0.0, 0.0, 0.0), distance = 1200.0, yawDeg = 0.0, pitchDeg = -10.0)
    }
    val ys = panels.map { it.poseInRoot.translation.y }
    val centreY = (ys.min() + ys.max()) / 2.0
    val span = panels.maxOf { maxOf(it.sizeDp.width, it.sizeDp.height).toDouble() }
    return OrbitCamera(
      target = Vec3(0.0, centreY, 0.0),
      distance = (span * 2.0).coerceAtLeast(600.0),
      yawDeg = 0.0,
      pitchDeg = -10.0,
    )
  }
}
