package ee.schimke.composeai.renderer.xr

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.google.common.truth.Truth.assertThat
import ee.schimke.composeai.xr.OrbitCamera
import ee.schimke.composeai.xr.Quat
import ee.schimke.composeai.xr.SizeDp
import ee.schimke.composeai.xr.SpatialPanel
import ee.schimke.composeai.xr.SpatialPose
import ee.schimke.composeai.xr.SpatialScene
import ee.schimke.composeai.xr.Vec3
import java.io.File
import javax.imageio.ImageIO
import kotlinx.serialization.json.Json
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Verifies the producer's render output: each panel's 2D content is rasterised to `<id>.png` and
 * the scene serialises to `scene.json` in the same directory, with relative `<id>.png` texture refs
 * — the exact layout the VS Code 3D viewer (PR #1704) resolves against a `textureBaseUri`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SubspaceSceneWriterTest {

  private fun tempDir(): File =
    File.createTempFile("xr-render", "").let {
      it.delete()
      it.mkdirs()
      it
    }

  private fun centrePixel(png: File): Triple<Int, Int, Int> {
    val img = ImageIO.read(png)
    val argb = img.getRGB(img.width / 2, img.height / 2)
    return Triple((argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF)
  }

  @Test
  fun capturesPanelTexturesAndWritesScene() {
    val dir = tempDir()

    SubspaceSceneWriter.captureTextures(
      dir,
      listOf(
        PanelTexture("top", SizeDp(560, 200)) {
          Box(Modifier.fillMaxSize().background(Color.Red))
        },
        PanelTexture("bottom", SizeDp(560, 160)) {
          Box(Modifier.fillMaxSize().background(Color.Blue))
        },
      ),
    )

    val topPng = File(dir, "top.png")
    val bottomPng = File(dir, "bottom.png")
    assertThat(topPng.exists()).isTrue()
    assertThat(topPng.length()).isGreaterThan(0L)
    assertThat(bottomPng.exists()).isTrue()

    // Real rasterisation of distinct panel content, not blank frames.
    val (tr, tg, tb) = centrePixel(topPng)
    assertThat(tr).isGreaterThan(180)
    assertThat(tg).isLessThan(80)
    assertThat(tb).isLessThan(80)
    val (br, bg, bb) = centrePixel(bottomPng)
    assertThat(bb).isGreaterThan(180)
    assertThat(br).isLessThan(80)
    assertThat(bg).isLessThan(80)

    val scene =
      SpatialScene(
        previewId = "test",
        camera =
          OrbitCamera(target = Vec3(0.0, -10.0, 0.0), distance = 1200.0, yawDeg = 0.0, pitchDeg = -10.0),
        panels =
          listOf(
            SpatialPanel(
              id = "top",
              poseInRoot = SpatialPose(Vec3(0.0, 80.0, 0.0), Quat(0.0, 0.0, 0.0, 1.0)),
              sizeDp = SizeDp(560, 200),
              texture = "top.png",
            ),
            SpatialPanel(
              id = "bottom",
              poseInRoot = SpatialPose(Vec3(0.0, -100.0, 0.0), Quat(0.0, 0.0, 0.0, 1.0)),
              sizeDp = SizeDp(560, 160),
              texture = "bottom.png",
            ),
          ),
      )

    val sceneFile = SubspaceSceneWriter.writeScene(dir, scene)
    assertThat(sceneFile.name).isEqualTo("scene.json")
    assertThat(sceneFile.exists()).isTrue()

    // The emitted scene parses back, and each texture path resolves to a written PNG in the dir.
    val decoded =
      Json { ignoreUnknownKeys = true }
        .decodeFromString(SpatialScene.serializer(), sceneFile.readText())
    assertThat(decoded.panels.map { it.id }).containsExactly("top", "bottom")
    for (panel in decoded.panels) {
      assertThat(File(dir, panel.texture).exists()).isTrue()
    }
  }
}
