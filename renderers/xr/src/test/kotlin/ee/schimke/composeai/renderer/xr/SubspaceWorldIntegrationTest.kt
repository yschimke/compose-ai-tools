package ee.schimke.composeai.renderer.xr

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.xr.compose.spatial.Orbiter
import androidx.xr.compose.spatial.OrbiterAnchorPoint
import androidx.xr.compose.spatial.Subspace
import androidx.xr.compose.subspace.SpatialColumn
import androidx.xr.compose.subspace.SpatialPanel
import androidx.xr.compose.subspace.SpatialRow
import androidx.xr.compose.subspace.layout.SubspaceModifier
import androidx.xr.compose.subspace.layout.height
import androidx.xr.compose.subspace.layout.width
import androidx.xr.compose.subspace.semantics.testTag
import com.google.common.truth.Truth.assertThat
import ee.schimke.composeai.xr.SizeDp
import ee.schimke.composeai.xr.SpatialScene
import java.io.File
import kotlinx.serialization.json.Json
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * End-to-end producer run against a richer "3D world with floating windows": a `SpatialColumn` with
 * a main panel over a `SpatialRow` of two side panels, plus an `Orbiter`-anchored control strip.
 * Composes it offline under the fake XR runtime, recovers the layout, captures every panel's
 * texture, writes `scene.json`, and asserts the full render output is internally consistent — the
 * shape the VS Code 3D viewer (#1704) consumes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SubspaceWorldIntegrationTest {

  // v2 rule API (StandardTestDispatcher) is not on the compat compile classpath yet;
  // suppress until the floor moves up. See renderer-android RobolectricRenderTest.
  @Suppress("DEPRECATION")
  @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

  @Test
  fun producesCompleteSceneForFloatingWindowWorld() {
    val pm = ApplicationProvider.getApplicationContext<Context>().packageManager
    shadowOf(pm).setSystemFeature(SubspaceSceneRecorder.XR_SPATIAL_FEATURE, true)

    rule.setContent {
      Subspace {
        SpatialColumn {
          SpatialPanel(SubspaceModifier.testTag("main").width(720.dp).height(360.dp)) {
            Box(Modifier.fillMaxSize().background(Color(0xFF1E3C78)))
            Orbiter(OrbiterAnchorPoint.Bottom) {
              Box(Modifier.fillMaxSize().background(Color(0xFF333845)))
            }
          }
          SpatialRow {
            SpatialPanel(SubspaceModifier.testTag("queue").width(320.dp).height(280.dp)) {
              Box(Modifier.fillMaxSize().background(Color(0xFF146E6E)))
            }
            SpatialPanel(SubspaceModifier.testTag("lyrics").width(320.dp).height(280.dp)) {
              Box(Modifier.fillMaxSize().background(Color(0xFF7A3E1E)))
            }
          }
        }
      }
    }
    rule.waitForIdle()

    // Auto-enumerate the tagged panels and recover their content views — the path the render
    // pipeline takes for a discovered @XrSubspacePreview.
    val recorded = SubspaceSceneRecorder.recordAllWithViews(rule, previewId = "spatial-world")
    val scene = recorded.scene

    // Geometry recovered for every floating window.
    val panelTags = listOf("main", "queue", "lyrics")
    assertThat(scene.panels.map { it.id }).containsExactlyElementsIn(panelTags)
    val byId = scene.panels.associateBy { it.id }
    assertThat(byId.getValue("main").sizeDp).isEqualTo(SizeDp(720, 360))
    assertThat(byId.getValue("queue").sizeDp).isEqualTo(SizeDp(320, 280))
    // The two side panels share a row, so they sit at the same height but different x.
    assertThat(byId.getValue("queue").poseInRoot.translation.y)
      .isEqualTo(byId.getValue("lyrics").poseInRoot.translation.y)
    assertThat(byId.getValue("queue").poseInRoot.translation.x)
      .isNotEqualTo(byId.getValue("lyrics").poseInRoot.translation.x)
    // The main panel sits above the side-panel row.
    assertThat(byId.getValue("main").poseInRoot.translation.y)
      .isGreaterThan(byId.getValue("queue").poseInRoot.translation.y)

    // Full render output: a real content texture for every panel + scene.json, co-located and
    // consistent.
    val outDir =
      File.createTempFile("xr-world", "").let {
        it.delete()
        it.mkdirs()
        it
      }
    SubspaceSceneWriter.captureViewTextures(outDir, scene.panels, recorded.panelViews)
    val sceneFile = SubspaceSceneWriter.writeScene(outDir, scene)

    val decoded =
      Json { ignoreUnknownKeys = true }
        .decodeFromString(SpatialScene.serializer(), sceneFile.readText())
    assertThat(decoded.previewId).isEqualTo("spatial-world")
    for (panel in decoded.panels) {
      val texture = File(outDir, panel.texture)
      assertThat(texture.exists()).isTrue()
      assertThat(texture.length()).isGreaterThan(0L)
    }
  }
}
