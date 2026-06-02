package ee.schimke.composeai.xr

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

/**
 * Locks the Kotlin [SpatialScene] mirror to the contract the TypeScript consumer uses, by reading
 * the single committed fixture
 * (`vscode-extension/preview-harness/fixtures/spatial-scene/scene.json`) rather than a copy. If the
 * wire shape changes on one side without the other, this fails.
 */
class SpatialSceneTest {

  private val json = Json { ignoreUnknownKeys = true }

  private fun repoRoot(): File {
    var dir = File(".").absoluteFile
    while (dir.parentFile != null && !File(dir, "settings.gradle.kts").exists()) {
      dir = dir.parentFile
    }
    return dir
  }

  private fun fixtureScene(): SpatialScene {
    val file =
      File(repoRoot(), "vscode-extension/preview-harness/fixtures/spatial-scene/scene.json")
    assertTrue(file.exists(), "contract fixture missing at ${file.path}")
    return json.decodeFromString(SpatialScene.serializer(), file.readText())
  }

  @Test
  fun deserializesCommittedFixtureToTheContractShape() {
    val scene = fixtureScene()

    assertEquals(SPATIAL_SCENE_VERSION, scene.version)
    assertEquals("dp", scene.units)
    assertEquals("orbit", scene.camera.kind)
    assertEquals(1200.0, scene.camera.distance)
    assertEquals(2, scene.panels.size)

    val top = scene.panels.single { it.id == "top" }
    assertEquals("Now Playing", top.label)
    assertEquals(80.0, top.poseInRoot.translation.y)
    assertEquals(SizeDp(560, 200), top.sizeDp)
    assertEquals("top.png", top.texture)

    val bottom = scene.panels.single { it.id == "bottom" }
    assertEquals(-100.0, bottom.poseInRoot.translation.y)
    assertEquals(SizeDp(560, 160), bottom.sizeDp)

    // The top panel sits above the bottom one — the genuine SpatialColumn stacking.
    assertTrue(top.poseInRoot.translation.y > bottom.poseInRoot.translation.y)

    assertNotNull(scene.environment)
    assertEquals("color", scene.environment!!.kind)
  }

  @Test
  fun roundTripsThroughJson() {
    val scene = fixtureScene()
    val encoded = json.encodeToString(SpatialScene.serializer(), scene)
    val decoded = json.decodeFromString(SpatialScene.serializer(), encoded)
    assertEquals(scene, decoded)
  }

  @Test
  fun defaultsStampCurrentVersionAndUnits() {
    val scene =
      SpatialScene(
        camera =
          OrbitCamera(
            target = Vec3(0.0, 0.0, 0.0),
            distance = 1000.0,
            yawDeg = 0.0,
            pitchDeg = 0.0,
          ),
        panels = emptyList(),
      )
    assertEquals(SPATIAL_SCENE_VERSION, scene.version)
    assertEquals("dp", scene.units)
  }
}
