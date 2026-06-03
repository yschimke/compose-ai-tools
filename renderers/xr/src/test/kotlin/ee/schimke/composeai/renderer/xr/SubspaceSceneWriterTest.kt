package ee.schimke.composeai.renderer.xr

import android.graphics.Color
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
import kotlin.math.roundToInt
import kotlinx.serialization.json.Json
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Verifies the producer's render output: each panel's content view is rasterised to `<id>.png` at
 * the panel's true size, and the scene serialises to `scene.json` in the same directory with
 * relative `<id>.png` texture refs — the exact layout the VS Code 3D viewer (PR #1704) resolves
 * against a `textureBaseUri`. (The end-to-end capture of a real composed `Subspace` is exercised by
 * `XrSubspaceRendererTest`; here we drive the writer with plain views to pin its sizing + output.)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SubspaceSceneWriterTest {

  // roborazzi's standalone view capture re-parents the view into its Activity, so the view must be
  // created with an Activity context (real panels are). The compose rule supplies one.
  @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

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

  private fun solidView(color: Int): View =
    View(rule.activity).apply { setBackgroundColor(color) }

  @Test
  fun capturesPanelTexturesAtPanelSizeAndWritesScene() {
    val dir = tempDir()

    val panels =
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
      )

    SubspaceSceneWriter.captureViewTextures(
      dir,
      panels,
      mapOf("top" to solidView(Color.RED), "bottom" to solidView(Color.BLUE)),
    )

    val topPng = File(dir, "top.png")
    val bottomPng = File(dir, "bottom.png")
    assertThat(topPng.exists()).isTrue()
    assertThat(topPng.length()).isGreaterThan(0L)
    assertThat(bottomPng.exists()).isTrue()

    // Captured at the panel's true pixel size (sizeDp × density), not the view's intrinsic size.
    val density = rule.activity.resources.displayMetrics.density
    val topImg = ImageIO.read(topPng)
    assertThat(topImg.width).isEqualTo((560 * density).roundToInt())
    assertThat(topImg.height).isEqualTo((200 * density).roundToInt())

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
        panels = panels,
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
