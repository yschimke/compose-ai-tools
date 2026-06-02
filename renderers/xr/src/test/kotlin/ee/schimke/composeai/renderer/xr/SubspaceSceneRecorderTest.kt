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
import androidx.xr.compose.subspace.layout.SubspaceModifier
import androidx.xr.compose.subspace.layout.height
import androidx.xr.compose.subspace.layout.width
import androidx.xr.compose.subspace.semantics.testTag
import com.google.common.truth.Truth.assertThat
import ee.schimke.composeai.xr.SPATIAL_SCENE_VERSION
import ee.schimke.composeai.xr.SpatialScene
import kotlinx.serialization.json.Json
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Exercises [SubspaceSceneRecorder] end-to-end: composes a real `Subspace` under the fake XR
 * runtime (registered for ServiceLoader in test resources) with the spatial system feature
 * shadowed on, then asserts the recovered [SpatialScene] matches the framework-computed layout and
 * serialises to the wire contract.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SubspaceSceneRecorderTest {

  @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

  private fun enableSpatialFeature() {
    val pm = ApplicationProvider.getApplicationContext<Context>().packageManager
    shadowOf(pm).setSystemFeature(SubspaceSceneRecorder.XR_SPATIAL_FEATURE, true)
  }

  @Test
  fun recordsSpatialSceneFromSubspace() {
    enableSpatialFeature()
    rule.setContent {
      Subspace {
        SpatialColumn(SubspaceModifier.testTag("column")) {
          SpatialPanel(SubspaceModifier.testTag("top").width(560.dp).height(200.dp)) {
            Box(Modifier.fillMaxSize())
          }
          SpatialPanel(SubspaceModifier.testTag("bottom").width(560.dp).height(160.dp)) {
            Box(Modifier.fillMaxSize())
          }
        }
      }
    }
    rule.waitForIdle()

    val scene = SubspaceSceneRecorder.record(rule, listOf("top", "bottom"), previewId = "test")

    assertThat(scene.version).isEqualTo(SPATIAL_SCENE_VERSION)
    assertThat(scene.units).isEqualTo("dp")
    assertThat(scene.previewId).isEqualTo("test")
    assertThat(scene.panels.map { it.id }).containsExactly("top", "bottom")

    val top = scene.panels.single { it.id == "top" }
    assertThat(top.sizeDp.width).isEqualTo(560)
    assertThat(top.sizeDp.height).isEqualTo(200)
    assertThat(top.texture).isEqualTo("top.png")

    val bottom = scene.panels.single { it.id == "bottom" }
    assertThat(bottom.sizeDp.height).isEqualTo(160)

    // The genuine SpatialColumn stacking: top panel sits above the bottom one (+y is up).
    assertThat(top.poseInRoot.translation.y).isGreaterThan(bottom.poseInRoot.translation.y)

    // Round-trips through the wire format.
    val json = Json { ignoreUnknownKeys = true }
    val encoded = json.encodeToString(SpatialScene.serializer(), scene)
    val decoded = json.decodeFromString(SpatialScene.serializer(), encoded)
    assertThat(decoded).isEqualTo(scene)
  }
}
