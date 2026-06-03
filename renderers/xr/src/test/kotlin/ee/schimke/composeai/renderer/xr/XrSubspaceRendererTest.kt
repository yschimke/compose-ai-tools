package ee.schimke.composeai.renderer.xr

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
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
import ee.schimke.composeai.xr.SpatialScene
import java.io.File
import kotlinx.serialization.json.Json
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** A stand-in `@XrSubspacePreview` — a top-level `@Composable` whose body is a tagged `Subspace`. */
@Composable
fun SampleSpatialPreview() {
  Subspace {
    SpatialColumn {
      SpatialPanel(SubspaceModifier.testTag("now-playing").width(560.dp).height(220.dp)) {
        Box(Modifier.fillMaxSize())
      }
      SpatialPanel(SubspaceModifier.testTag("controls").width(560.dp).height(120.dp)) {
        Box(Modifier.fillMaxSize())
      }
    }
  }
}

/**
 * Drives [XrSubspaceRenderer] end-to-end the way the render task will: enable the spatial feature,
 * reflect + compose a preview function by name, and assert the written `scene.json`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class XrSubspaceRendererTest {

  @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

  @Test
  fun rendersSceneJsonFromPreviewFunction() {
    val pm = ApplicationProvider.getApplicationContext<Context>().packageManager
    shadowOf(pm).setSystemFeature(SubspaceSceneRecorder.XR_SPATIAL_FEATURE, true)

    val outDir =
      File.createTempFile("xr-render", "").let {
        it.delete()
        it.mkdirs()
        it
      }

    val sceneFile =
      XrSubspaceRenderer.render(
        rule = rule,
        className = "ee.schimke.composeai.renderer.xr.XrSubspaceRendererTestKt",
        functionName = "SampleSpatialPreview",
        previewId = "sample-preview",
        outputDir = outDir,
      )

    assertThat(sceneFile.exists()).isTrue()
    assertThat(sceneFile.name).isEqualTo("scene.json")

    val scene =
      Json { ignoreUnknownKeys = true }
        .decodeFromString(SpatialScene.serializer(), sceneFile.readText())
    assertThat(scene.previewId).isEqualTo("sample-preview")
    assertThat(scene.panels.map { it.id }).containsExactly("now-playing", "controls")
    val byId = scene.panels.associateBy { it.id }
    assertThat(byId.getValue("now-playing").sizeDp).isEqualTo(ee.schimke.composeai.xr.SizeDp(560, 220))
    // The recovered column stacks now-playing above controls.
    assertThat(byId.getValue("now-playing").poseInRoot.translation.y)
      .isGreaterThan(byId.getValue("controls").poseInRoot.translation.y)
  }
}
