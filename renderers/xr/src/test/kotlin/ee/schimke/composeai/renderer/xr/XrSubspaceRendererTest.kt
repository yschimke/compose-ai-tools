@file:OptIn(androidx.xr.compose.subspace.layout.ExperimentalRotateToLookAtUserApi::class)

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
import androidx.xr.compose.subspace.SpatialBox
import androidx.xr.compose.subspace.SpatialColumn
import androidx.xr.compose.subspace.SpatialPanel
import androidx.xr.compose.subspace.layout.SubspaceModifier
import androidx.xr.compose.subspace.layout.height
import androidx.xr.compose.subspace.layout.offset
import androidx.xr.compose.subspace.layout.rotateToLookAtUser
import androidx.xr.compose.subspace.layout.width
import androidx.xr.compose.subspace.semantics.testTag
import com.google.common.truth.Truth.assertThat
import ee.schimke.composeai.xr.SpatialScene
import ee.schimke.composeai.xr.SpatialSemanticsKind
import ee.schimke.composeai.xr.SpatialSemanticsTree
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.serialization.json.Json
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * A stand-in `@XrSubspacePreview` — a top-level `@Composable` whose body is a tagged `Subspace`.
 */
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

@Composable
fun SampleLookAtUserPreview() {
  Subspace {
    SpatialBox {
      listOf("look-left" to -520, "look-center" to 0, "look-right" to 520).forEach { (tag, x) ->
        SpatialPanel(
          SubspaceModifier.testTag(tag)
            .width(300.dp)
            .height(200.dp)
            .offset(x = x.dp)
            .rotateToLookAtUser()
        ) {
          Box(Modifier.fillMaxSize().background(Color.Red))
        }
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
  @Suppress("DEPRECATION") @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

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

    val scene = Json {
      ignoreUnknownKeys = true
    }
      .decodeFromString(SpatialScene.serializer(), sceneFile.readText())
    assertThat(scene.previewId).isEqualTo("sample-preview")
    assertThat(scene.panels.map { it.id }).containsExactly("now-playing", "controls")
    val byId = scene.panels.associateBy { it.id }
    assertThat(byId.getValue("now-playing").sizeDp)
      .isEqualTo(ee.schimke.composeai.xr.SizeDp(560, 220))
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

    // The unified spatial-semantics tree (compose/spatial-semantics) is produced beside scene.json:
    // a subspaceRoot over the two panels, each carrying its 2D semantics content.
    val treeFile = File(outDir, "compose-spatial-semantics.json")
    assertThat(treeFile.exists()).isTrue()
    val tree = Json {
      ignoreUnknownKeys = true
    }
      .decodeFromString(SpatialSemanticsTree.serializer(), treeFile.readText())
    assertThat(tree.version).isEqualTo(1)
    assertThat(tree.previewId).isEqualTo("sample-preview")
    assertThat(tree.root.kind).isEqualTo(SpatialSemanticsKind.SUBSPACE_ROOT)
    val panelNodes = tree.root.children.filter { it.kind == SpatialSemanticsKind.PANEL }
    assertThat(panelNodes.map { it.id }).containsExactly("now-playing", "controls")
  }

  @Test
  fun facesInitialReviewerCameraAfterHeadPoseSettles() {
    val pm = ApplicationProvider.getApplicationContext<Context>().packageManager
    shadowOf(pm).setSystemFeature(SubspaceSceneRecorder.XR_SPATIAL_FEATURE, true)
    val outDir = createOutputDir("xr-look-at-user")

    val sceneFile =
      XrSubspaceRenderer.render(
        rule = rule,
        className = "ee.schimke.composeai.renderer.xr.XrSubspaceRendererTestKt",
        functionName = "SampleLookAtUserPreview",
        previewId = "look-at-user-preview",
        outputDir = outDir,
      )

    val scene = Json.decodeFromString(SpatialScene.serializer(), sceneFile.readText())
    val cameraEye = cameraEye(scene)

    // Every panel's local +Z normal points at the exact eye used by the compositor. This geometric
    // assertion catches both the old arbitrary +Z seed and the origin seed that turned side panels
    // edge-on, without pinning the test to either seed's incidental quaternion.
    for (panel in scene.panels) {
      val forward = panelForward(panel.poseInRoot.rotation)
      val toCamera = normalized(cameraEye - panel.poseInRoot.translation)
      assertThat(forward dot toCamera).isGreaterThan(0.999)
    }

    // A human review camera should produce a modest inward turn, not the previous 90° edge-on yaw.
    val byId = scene.panels.associateBy { it.id }
    val leftYawComponent = kotlin.math.abs(byId.getValue("look-left").poseInRoot.rotation.y)
    val rightYawComponent = kotlin.math.abs(byId.getValue("look-right").poseInRoot.rotation.y)
    assertThat(leftYawComponent).isGreaterThan(0.15)
    assertThat(leftYawComponent).isLessThan(0.25)
    assertThat(rightYawComponent).isGreaterThan(0.15)
    assertThat(rightYawComponent).isLessThan(0.25)
    assertThat(scene.camera.pitchDeg).isGreaterThan(0.0)
  }

  private fun cameraEye(scene: SpatialScene): Vec {
    val camera = scene.camera
    val yaw = Math.toRadians(camera.yawDeg)
    val pitch = Math.toRadians(camera.pitchDeg)
    val cosPitch = cos(pitch)
    return Vec(
      camera.target.x + cosPitch * sin(yaw) * camera.distance,
      camera.target.y + sin(pitch) * camera.distance,
      camera.target.z + cosPitch * cos(yaw) * camera.distance,
    )
  }

  private fun panelForward(rotation: ee.schimke.composeai.xr.Quat): Vec =
    Vec(
      2.0 * (rotation.x * rotation.z + rotation.w * rotation.y),
      2.0 * (rotation.y * rotation.z - rotation.w * rotation.x),
      1.0 - 2.0 * (rotation.x * rotation.x + rotation.y * rotation.y),
    )

  private fun normalized(vector: Vec): Vec {
    val length = sqrt(vector dot vector)
    return Vec(vector.x / length, vector.y / length, vector.z / length)
  }

  private infix fun Vec.dot(other: Vec): Double = x * other.x + y * other.y + z * other.z

  private operator fun Vec.minus(other: ee.schimke.composeai.xr.Vec3): Vec =
    Vec(x - other.x, y - other.y, z - other.z)

  private data class Vec(val x: Double, val y: Double, val z: Double)

  private fun createOutputDir(prefix: String): File =
    File.createTempFile(prefix, "").let {
      it.delete()
      it.mkdirs()
      it
    }

  private fun centrePixel(png: File): Triple<Int, Int, Int> {
    val img = ImageIO.read(png)
    val argb = img.getRGB(img.width / 2, img.height / 2)
    return Triple((argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF)
  }
}
