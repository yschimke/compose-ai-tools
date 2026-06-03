package ee.schimke.composeai.renderer.xr

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.xr.compose.spatial.Subspace
import androidx.xr.compose.subspace.SpatialColumn
import androidx.xr.compose.subspace.SpatialPanel
import androidx.xr.compose.subspace.SpatialRow
import androidx.xr.compose.subspace.layout.SubspaceModifier
import androidx.xr.compose.subspace.layout.height
import androidx.xr.compose.subspace.layout.width
import androidx.xr.compose.subspace.semantics.testTag
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Auto-enumeration: [SubspaceSceneRecorder.recordAll] discovers every tagged panel of a `Subspace`
 * with no tag list supplied — the path the render pipeline takes for a discovered
 * `@XrSubspacePreview`. Tags become panel ids; the untagged layout groups are not panels.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SubspaceSceneRecorderAutoEnumTest {

  // v2 rule API (StandardTestDispatcher) is not on the compat compile classpath yet;
  // suppress until the floor moves up. See renderer-android RobolectricRenderTest.
  @Suppress("DEPRECATION")
  @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

  @Test
  fun recordAllDiscoversTaggedPanels() {
    val pm = ApplicationProvider.getApplicationContext<Context>().packageManager
    shadowOf(pm).setSystemFeature(SubspaceSceneRecorder.XR_SPATIAL_FEATURE, true)

    rule.setContent {
      Subspace {
        // Groups are intentionally untagged; only the panels carry a testTag.
        SpatialColumn {
          SpatialPanel(SubspaceModifier.testTag("main").width(720.dp).height(360.dp)) {
            Box(Modifier.fillMaxSize())
          }
          SpatialRow {
            SpatialPanel(SubspaceModifier.testTag("queue").width(320.dp).height(280.dp)) {
              Box(Modifier.fillMaxSize())
            }
            SpatialPanel(SubspaceModifier.testTag("lyrics").width(320.dp).height(280.dp)) {
              Box(Modifier.fillMaxSize())
            }
          }
        }
      }
    }
    rule.waitForIdle()

    val scene = SubspaceSceneRecorder.recordAll(rule, previewId = "auto")

    // All three tagged panels discovered, keyed by their tags; the column/row groups are not panels.
    assertThat(scene.panels.map { it.id }).containsExactly("main", "queue", "lyrics")
    assertThat(scene.panels.all { it.texture == "${it.id}.png" }).isTrue()
    assertThat(scene.panels.all { it.parentId == null }).isTrue()

    val byId = scene.panels.associateBy { it.id }
    assertThat(byId.getValue("main").sizeDp.width).isEqualTo(720)
    assertThat(byId.getValue("queue").sizeDp.width).isEqualTo(320)

    // The two side panels share a row → same height, different x.
    assertThat(byId.getValue("queue").poseInRoot.translation.y)
      .isEqualTo(byId.getValue("lyrics").poseInRoot.translation.y)
    assertThat(byId.getValue("queue").poseInRoot.translation.x)
      .isNotEqualTo(byId.getValue("lyrics").poseInRoot.translation.x)
    // The main panel sits above the side-panel row.
    assertThat(byId.getValue("main").poseInRoot.translation.y)
      .isGreaterThan(byId.getValue("queue").poseInRoot.translation.y)
  }
}
