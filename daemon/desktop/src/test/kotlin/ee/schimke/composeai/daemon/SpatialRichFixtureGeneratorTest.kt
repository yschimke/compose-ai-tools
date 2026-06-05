package ee.schimke.composeai.daemon

import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import ee.schimke.composeai.data.layoutinspector.ComposeSemanticsNode
import ee.schimke.composeai.xr.OrbitCamera
import ee.schimke.composeai.xr.OrbiterAffordance
import ee.schimke.composeai.xr.Quat
import ee.schimke.composeai.xr.Size3dDp
import ee.schimke.composeai.xr.SizeDp
import ee.schimke.composeai.xr.SpatialEnvironment
import ee.schimke.composeai.xr.SpatialPanel
import ee.schimke.composeai.xr.SpatialPose
import ee.schimke.composeai.xr.SpatialScene
import ee.schimke.composeai.xr.SpatialSemanticsKind
import ee.schimke.composeai.xr.SpatialSemanticsNode
import ee.schimke.composeai.xr.SpatialSemanticsTree
import ee.schimke.composeai.xr.Vec3
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.serialization.json.Json
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Generates the committed `spatial-rich` fixture from real Compose: renders each panel composable
 * ([SpatialPanelFixtures]) to a PNG texture AND harvests its real Compose semantics into the
 * panel's `panelContent`, so the `spatial-semantics` preview's wireframe lands exactly on the
 * rendered UI (no hand-authored boxes).
 *
 * Outputs go to `SPATIAL_FIXTURES_DIR=<dir>` when set (the manual regeneration path, pointed at
 * `vscode-extension/spatial-fixtures/spatial-rich`); otherwise a temp dir, so the normal CI test
 * run still exercises render + harvest + serialization without writing into the source tree.
 *
 * SPATIAL_FIXTURES_DIR=$PWD/vscode-extension/spatial-fixtures/spatial-rich \ ./gradlew
 * :daemon:desktop:test --tests '*SpatialRichFixtureGeneratorTest' --rerun-tasks
 */
class SpatialRichFixtureGeneratorTest {

  private val density = 2

  private data class PanelSpec(
    val id: String,
    val label: String,
    val isOrbiter: Boolean,
    val edge: String?,
    val width: Int,
    val height: Int,
    val pose: SpatialPose,
    val content: @Composable () -> Unit,
  )

  @OptIn(ExperimentalComposeUiApi::class)
  @Test
  fun generatesSceneAndSemanticsTree() {
    val specs =
      listOf(
        PanelSpec(
          "now-playing",
          "Now Playing",
          false,
          null,
          560,
          180,
          pose(0, 340, -120, pitch(8.0)),
        ) {
          NowPlayingPanel()
        },
        PanelSpec("album-art", "Album Art", false, null, 460, 460, pose(0, -40, 0, IDENTITY)) {
          AlbumArtPanel()
        },
        PanelSpec("queue", "Up Next", false, null, 300, 520, pose(-520, 40, 160, yaw(32.0))) {
          QueuePanel()
        },
        PanelSpec("lyrics", "Lyrics", false, null, 300, 520, pose(520, 40, 160, yaw(-32.0))) {
          LyricsPanel()
        },
        PanelSpec(
          "transport",
          "Transport",
          true,
          "bottom",
          560,
          96,
          pose(0, -340, 80, pitch(-18.0)),
        ) {
          TransportPanel()
        },
        PanelSpec("volume", "Volume", true, "end", 80, 320, pose(360, -40, 40, yaw(-20.0))) {
          VolumePanel()
        },
      )

    // `SPATIAL_FIXTURES_DIR` (env var — inherited by the forked test JVM, unlike a `-D` property)
    // points at `vscode-extension/spatial-fixtures/spatial-rich` for manual regeneration; otherwise
    // a
    // temp dir, so a normal CI run still exercises render + harvest without touching the source
    // tree.
    val outDir =
      System.getenv("SPATIAL_FIXTURES_DIR")?.let(::File)
        ?: createTempDirectory("spatial-rich").toFile()
    val panelsDir = File(outDir, "panels").apply { mkdirs() }

    val semanticsByid = LinkedHashMap<String, ComposeSemanticsNode>()
    for (spec in specs) {
      val node = renderAndHarvest(spec, File(panelsDir, "${spec.id}.png"))
      semanticsByid[spec.id] = node
    }

    val scene = buildScene(specs)
    val tree = buildTree(specs, semanticsByid)

    val json = Json {
      prettyPrint = true
      prettyPrintIndent = "  "
      // Emit `version` / `units` / `kind` etc. (they equal their DTO defaults) so the committed
      // JSON satisfies the webview's `isSpatialScene` / `isSpatialSemanticsTree` contract guards.
      encodeDefaults = true
    }
    File(outDir, "scene.json")
      .writeText(json.encodeToString(SpatialScene.serializer(), scene) + "\n")
    File(outDir, "semantics-tree.json")
      .writeText(json.encodeToString(SpatialSemanticsTree.serializer(), tree) + "\n")

    // Every panel produced a non-empty texture and a harvested 2D tree.
    for (spec in specs) {
      assertTrue("empty texture for ${spec.id}", File(panelsDir, "${spec.id}.png").length() > 0)
    }
    assertTrue(tree.root.children.size == specs.size)
  }

  @OptIn(ExperimentalComposeUiApi::class)
  private fun renderAndHarvest(spec: PanelSpec, outFile: File): ComposeSemanticsNode {
    val scene =
      ImageComposeScene(
        width = spec.width * density,
        height = spec.height * density,
        density = Density(density.toFloat()),
      )
    try {
      scene.setContent { spec.content() }
      scene.render() // settle a frame
      val image = scene.render()
      val png =
        image.encodeToData(EncodedImageFormat.PNG)?.bytes
          ?: error("PNG encode failed for ${spec.id}")
      outFile.writeBytes(png)
      val root =
        scene.semanticsOwners.firstOrNull()?.unmergedRootSemanticsNode
          ?: error("no semantics root for ${spec.id}")
      return ComposeSemanticsDataProducer.buildPayload(root).root
    } finally {
      scene.close()
    }
  }

  private fun buildScene(specs: List<PanelSpec>): SpatialScene =
    SpatialScene(
      previewId = "spatial-fixtures.spatial-rich",
      camera =
        OrbitCamera(target = Vec3(0.0, 0.0, 0.0), distance = 1700.0, yawDeg = 0.0, pitchDeg = -8.0),
      panels =
        specs
          .filterNot { it.isOrbiter }
          .map {
            SpatialPanel(
              id = it.id,
              label = it.label,
              poseInRoot = it.pose,
              sizeDp = SizeDp(it.width, it.height),
              texture = "panels/${it.id}.png",
              parentId = null,
            )
          },
      orbiters =
        specs
          .filter { it.isOrbiter }
          .map {
            OrbiterAffordance(
              id = it.id,
              label = it.label,
              edge = it.edge!!,
              poseInRoot = it.pose,
              sizeDp = SizeDp(it.width, it.height),
              texture = "panels/${it.id}.png",
            )
          },
      environment = SpatialEnvironment(kind = "color", color = "#101014"),
    )

  private fun buildTree(
    specs: List<PanelSpec>,
    semanticsById: Map<String, ComposeSemanticsNode>,
  ): SpatialSemanticsTree =
    SpatialSemanticsTree(
      previewId = "spatial-fixtures.spatial-rich",
      root =
        SpatialSemanticsNode(
          id = "subspaceRoot",
          kind = SpatialSemanticsKind.SUBSPACE_ROOT,
          poseInRoot = SpatialPose(Vec3(0.0, 0.0, 0.0), IDENTITY),
          sizeDp = Size3dDp(0, 0, 0),
          children =
            specs.map {
              SpatialSemanticsNode(
                id = it.id,
                kind =
                  if (it.isOrbiter) SpatialSemanticsKind.ORBITER else SpatialSemanticsKind.PANEL,
                label = it.label,
                poseInRoot = it.pose,
                sizeDp = Size3dDp(it.width, it.height, 0),
                panelContent = semanticsById.getValue(it.id),
              )
            },
        ),
    )

  private fun pose(x: Int, y: Int, z: Int, rotation: Quat) =
    SpatialPose(Vec3(x.toDouble(), y.toDouble(), z.toDouble()), rotation)

  private companion object {
    val IDENTITY = Quat(0.0, 0.0, 0.0, 1.0)

    fun yaw(deg: Double): Quat {
      val a = Math.toRadians(deg) / 2
      return Quat(0.0, sin(a), 0.0, cos(a))
    }

    fun pitch(deg: Double): Quat {
      val a = Math.toRadians(deg) / 2
      return Quat(sin(a), 0.0, 0.0, cos(a))
    }
  }
}
