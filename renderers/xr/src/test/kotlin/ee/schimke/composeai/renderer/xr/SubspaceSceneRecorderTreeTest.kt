package ee.schimke.composeai.renderer.xr

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.xr.compose.spatial.Subspace
import androidx.xr.compose.subspace.SpatialColumn
import androidx.xr.compose.subspace.SpatialPanel
import androidx.xr.compose.subspace.layout.SubspaceModifier
import androidx.xr.compose.subspace.layout.height
import androidx.xr.compose.subspace.layout.width
import androidx.xr.compose.subspace.semantics.testTag as subspaceTestTag
import com.google.common.truth.Truth.assertThat
import ee.schimke.composeai.data.layoutinspector.ComposeSemanticsNode
import ee.schimke.composeai.xr.SPATIAL_SEMANTICS_TREE_VERSION
import ee.schimke.composeai.xr.Size3dDp
import ee.schimke.composeai.xr.SpatialSemanticsKind
import ee.schimke.composeai.xr.SpatialSemanticsTree
import kotlinx.serialization.json.Json
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Exercises [SubspaceSceneRecorder.recordTree] (the unified 3D-over-2D harvest) and the pure
 * [SubspaceSceneRecorder.singlePanelTree] degenerate case. The 2D projection is injected — here a
 * trivial id/tag/children walk standing in for the daemon's `ComposeSemanticsDataProducer`, so the
 * test stays inside `:renderer-xr` without the daemon connector — and we assert the recorder both
 * recovers each panel's 2D content tree and assembles it under the 3D subspace nodes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SubspaceSceneRecorderTreeTest {

  @Suppress("DEPRECATION")
  @get:Rule
  val rule = createAndroidComposeRule<ComponentActivity>()

  private fun enableSpatialFeature() {
    val pm = ApplicationProvider.getApplicationContext<Context>().packageManager
    shadowOf(pm).setSystemFeature(SubspaceSceneRecorder.XR_SPATIAL_FEATURE, true)
  }

  /** Minimal `SemanticsNode -> ComposeSemanticsNode` projection (the daemon injects the real one). */
  private fun project(node: SemanticsNode): ComposeSemanticsNode =
    ComposeSemanticsNode(
      nodeId = node.id.toString(),
      boundsInRoot = "0,0,0,0",
      testTag = node.config.getOrNull(SemanticsProperties.TestTag),
      children = node.children.map(::project),
    )

  private fun ComposeSemanticsNode.collectTestTags(): Set<String> =
    buildSet {
      testTag?.let(::add)
      children.forEach { addAll(it.collectTestTags()) }
    }

  @Test
  fun recordsThreeDimensionalTreeWithPerPanelTwoDimensionalContent() {
    enableSpatialFeature()
    rule.setContent {
      Subspace {
        // The column is left untagged: recordAll enumerates every *tagged* subspace node, so tagging
        // only the panels keeps the tree's panel set to {top, bottom}. (Distinguishing container
        // kinds — row/column/box — from panels is a future recorder enhancement.)
        SpatialColumn {
          SpatialPanel(SubspaceModifier.subspaceTestTag("top").width(560.dp).height(200.dp)) {
            Column(Modifier.fillMaxSize().testTag("top-body")) {
              BasicText("Top", Modifier.testTag("top-title"))
            }
          }
          SpatialPanel(SubspaceModifier.subspaceTestTag("bottom").width(560.dp).height(160.dp)) {
            Column(Modifier.fillMaxSize().testTag("bottom-body")) {}
          }
        }
      }
    }
    rule.waitForIdle()

    val tree = SubspaceSceneRecorder.recordTree(rule, previewId = "test", projectSemantics = ::project)

    assertThat(tree.version).isEqualTo(SPATIAL_SEMANTICS_TREE_VERSION)
    assertThat(tree.units).isEqualTo("dp")
    assertThat(tree.previewId).isEqualTo("test")

    val root = tree.root
    assertThat(root.kind).isEqualTo(SpatialSemanticsKind.SUBSPACE_ROOT)
    // The tagged SpatialPanels become panel children (the SpatialColumn container is not itself a
    // panel node here — recordAll enumerates tagged panels with absolute poses).
    val panels = root.children.filter { it.kind == SpatialSemanticsKind.PANEL }
    assertThat(panels.map { it.id }).containsExactly("top", "bottom")

    val top = panels.single { it.id == "top" }
    assertThat(top.sizeDp).isEqualTo(Size3dDp(width = 560, height = 200))
    // The panel's recovered 2D content tree carries the tagged content composed inside it.
    val topTags = top.panelContent?.collectTestTags() ?: emptySet<String>()
    assertThat(topTags).containsAtLeast("top-body", "top-title")

    val bottom = panels.single { it.id == "bottom" }
    assertThat(bottom.sizeDp.height).isEqualTo(160)
    assertThat(bottom.panelContent?.collectTestTags() ?: emptySet<String>()).contains("bottom-body")

    // SpatialColumn stacks top above bottom (+y up) — the 3D geometry survives into the tree.
    assertThat(top.poseInRoot.translation.y).isGreaterThan(bottom.poseInRoot.translation.y)

    // Round-trips through the wire contract.
    val json = Json { ignoreUnknownKeys = true }
    val encoded = json.encodeToString(SpatialSemanticsTree.serializer(), tree)
    val decoded = json.decodeFromString(SpatialSemanticsTree.serializer(), encoded)
    assertThat(decoded).isEqualTo(tree)
  }

  @Test
  fun singlePanelTreeWrapsOrdinaryPreviewAsDegenerateCase() {
    val content =
      ComposeSemanticsNode(
        nodeId = "1",
        boundsInRoot = "0,0,100,50",
        testTag = "body",
        children = listOf(ComposeSemanticsNode(nodeId = "2", boundsInRoot = "0,0,100,20")),
      )

    val tree =
      SubspaceSceneRecorder.singlePanelTree(
        content = content,
        sizeDp = Size3dDp(width = 100, height = 50),
        previewId = "plain",
      )

    assertThat(tree.root.kind).isEqualTo(SpatialSemanticsKind.SUBSPACE_ROOT)
    val panel = tree.root.children.single()
    assertThat(panel.kind).isEqualTo(SpatialSemanticsKind.PANEL)
    assertThat(panel.poseInRoot.translation.x).isEqualTo(0.0)
    assertThat(panel.poseInRoot.rotation.w).isEqualTo(1.0)
    assertThat(panel.sizeDp).isEqualTo(Size3dDp(width = 100, height = 50))
    assertThat(panel.panelContent).isEqualTo(content)
  }
}
