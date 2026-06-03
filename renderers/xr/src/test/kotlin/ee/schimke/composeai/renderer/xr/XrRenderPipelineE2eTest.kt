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

/** A top-level `@XrSubspacePreview`-shaped composable the manifest below points at by name. */
@Composable
fun PipelineSpatialPreview() {
  Subspace {
    SpatialColumn {
      SpatialPanel(SubspaceModifier.testTag("hero").width(640.dp).height(360.dp)) {
        Box(Modifier.fillMaxSize())
      }
      SpatialPanel(SubspaceModifier.testTag("dock").width(640.dp).height(120.dp)) {
        Box(Modifier.fillMaxSize())
      }
    }
  }
}

/**
 * End-to-end of the render pipeline the `composePreviewRenderXr` task drives: a `previews.json`
 * manifest → [XrManifestReader] → [XrSubspaceRenderer.render] → `scene.json`, exactly as the
 * `XrSubspaceRenderTest` entry will, but without the plugin task. Proves the manifest-to-scene path
 * for a real, reflected subspace preview.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class XrRenderPipelineE2eTest {

  // v2 rule API (StandardTestDispatcher) is not on the compat compile classpath yet;
  // suppress until the floor moves up. See renderer-android RobolectricRenderTest.
  @Suppress("DEPRECATION")
  @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

  @Test
  fun manifestToSceneJson() {
    val pm = ApplicationProvider.getApplicationContext<Context>().packageManager
    shadowOf(pm).setSystemFeature(SubspaceSceneRecorder.XR_SPATIAL_FEATURE, true)

    val workDir = File.createTempFile("xr-e2e", "").apply { delete(); mkdirs() }
    val rendersDir = File(workDir, "renders").apply { mkdirs() }
    val previewClass = "ee.schimke.composeai.renderer.xr.XrRenderPipelineE2eTestKt"
    val manifest =
      File(workDir, "previews.json").apply {
        writeText(
          """
          {
            "module": "demo",
            "variant": "debug",
            "previews": [
              { "id": "media-room", "functionName": "PipelineSpatialPreview",
                "className": "$previewClass", "params": { "kind": "XR_SUBSPACE" } },
              { "id": "ignored", "functionName": "SomethingElse",
                "className": "test.OtherKt", "params": { "kind": "COMPOSE" } }
            ]
          }
          """
            .trimIndent()
        )
      }

    // 1) Read the manifest -> only the XR_SUBSPACE preview survives.
    val xrPreviews = XrManifestReader.xrPreviews(manifest)
    assertThat(xrPreviews.map { it.id }).containsExactly("media-room")

    // 2) Render each (one here) the way the task entry will, into a per-preview subdir.
    val outDir = File(rendersDir, xrPreviews.single().id).apply { mkdirs() }
    val sceneFile =
      XrSubspaceRenderer.render(
        rule = rule,
        className = xrPreviews.single().className,
        functionName = xrPreviews.single().functionName,
        previewId = xrPreviews.single().id,
        outputDir = outDir,
      )

    // 3) The scene.json describes the recovered subspace layout.
    assertThat(sceneFile.exists()).isTrue()
    val scene =
      Json { ignoreUnknownKeys = true }
        .decodeFromString(SpatialScene.serializer(), sceneFile.readText())
    assertThat(scene.previewId).isEqualTo("media-room")
    assertThat(scene.panels.map { it.id }).containsExactly("hero", "dock")
    // hero (above) stacks over dock.
    val byId = scene.panels.associateBy { it.id }
    assertThat(byId.getValue("hero").poseInRoot.translation.y)
      .isGreaterThan(byId.getValue("dock").poseInRoot.translation.y)
    // Texture paths are relative to scene.json (geometry-only: files not written yet).
    assertThat(scene.panels.map { it.texture }).containsExactly("hero.png", "dock.png")
  }
}
