package ee.schimke.composeai.renderer.xr

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import javax.imageio.ImageIO
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
        Box(Modifier.fillMaxSize().background(Color.Red))
      }
      SpatialPanel(SubspaceModifier.testTag("controls").width(560.dp).height(120.dp)) {
        Box(Modifier.fillMaxSize().background(Color.Blue))
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

  // v2 rule API (StandardTestDispatcher) is not on the compat compile classpath yet;
  // suppress until the floor moves up. See renderer-android RobolectricRenderTest.
  @Suppress("DEPRECATION")
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

    // Each panel's real content is rasterised to its <id>.png next to scene.json — not a blank
    // frame: the now-playing panel is red, the controls panel blue.
    for (panel in scene.panels) {
      val png = File(outDir, panel.texture)
      assertThat(png.exists()).isTrue()
      assertThat(png.length()).isGreaterThan(0L)
    }
    val (nr, ng, nb) = centrePixel(File(outDir, byId.getValue("now-playing").texture))
    assertThat(nr).isGreaterThan(180)
    assertThat(ng).isLessThan(80)
    assertThat(nb).isLessThan(80)
    val (cr, cg, cb) = centrePixel(File(outDir, byId.getValue("controls").texture))
    assertThat(cb).isGreaterThan(180)
    assertThat(cr).isLessThan(80)
    assertThat(cg).isLessThan(80)
  }

  private fun centrePixel(png: File): Triple<Int, Int, Int> {
    val img = ImageIO.read(png)
    val argb = img.getRGB(img.width / 2, img.height / 2)
    return Triple((argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF)
  }
}
