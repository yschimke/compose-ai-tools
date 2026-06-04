package ee.schimke.composeai.xr

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

/**
 * Locks the Kotlin [SpatialSemanticsTree] mirror to the committed contract fixture
 * (`vscode-extension/preview-harness/fixtures/spatial-semantics-tree/tree.json`) — the same
 * discipline [SpatialSceneTest] applies to `SpatialScene`. The TS mirror will read the same file;
 * if the wire shape changes on one side without the other, this fails.
 */
class SpatialSemanticsTreeTest {

  private val json = Json { ignoreUnknownKeys = true }

  private fun repoRoot(): File {
    var dir = File(".").absoluteFile
    while (dir.parentFile != null && !File(dir, "settings.gradle.kts").exists()) {
      dir = dir.parentFile
    }
    return dir
  }

  private fun fixtureTree(): SpatialSemanticsTree {
    val file =
      File(repoRoot(), "vscode-extension/preview-harness/fixtures/spatial-semantics-tree/tree.json")
    assertTrue(file.exists(), "contract fixture missing at ${file.path}")
    return json.decodeFromString(SpatialSemanticsTree.serializer(), file.readText())
  }

  @Test
  fun deserializesCommittedFixtureToTheContractShape() {
    val tree = fixtureTree()

    assertEquals(SPATIAL_SEMANTICS_TREE_VERSION, tree.version)
    assertEquals("dp", tree.units)
    assertEquals("NowPlayingSpatialPreview", tree.previewId)

    // Top level is 3D: a column container with two panel children, no 2D content of its own.
    val column = tree.root
    assertEquals(SpatialSemanticsKind.COLUMN, column.kind)
    assertNull(column.panelContent)
    assertEquals(2, column.children.size)
    assertEquals(Size3dDp(560, 360, 0), column.sizeDp)

    // Each panel sits at its recovered 3D pose (top above bottom — genuine SpatialColumn stacking).
    val nowPlaying = column.children.single { it.id == "now-playing" }
    val transport = column.children.single { it.id == "transport" }
    assertEquals(SpatialSemanticsKind.PANEL, nowPlaying.kind)
    assertEquals(80.0, nowPlaying.poseInRoot.translation.y)
    assertEquals(-100.0, transport.poseInRoot.translation.y)
    assertTrue(nowPlaying.poseInRoot.translation.y > transport.poseInRoot.translation.y)

    // Each panel carries a normal 2D semantics tree as its content.
    val content = nowPlaying.panelContent
    assertNotNull(content)
    assertEquals("0,0,560,200", content.boundsInRoot)
    assertEquals(2, content.children.size)
    assertEquals("Midnight City", content.children.first().text)

    // The clickable Play button survives the round-trip into the transport panel's 2D tree.
    val play = transport.panelContent!!.children.single()
    assertEquals("Play", play.label)
    assertEquals(true, play.clickable)
  }

  @Test
  fun roundTripsThroughJson() {
    val tree = fixtureTree()
    val encoded = json.encodeToString(SpatialSemanticsTree.serializer(), tree)
    val decoded = json.decodeFromString(SpatialSemanticsTree.serializer(), encoded)
    assertEquals(tree, decoded)
  }

  @Test
  fun ordinaryPreviewIsASinglePanelTree() {
    // The degenerate (non-XR) case: one panel at identity pose holding the whole 2D tree.
    val tree =
      SpatialSemanticsTree(
        previewId = "PlainPreview",
        root =
          SpatialSemanticsNode(
            id = "root",
            kind = SpatialSemanticsKind.PANEL,
            poseInRoot = SpatialPose(Vec3(0.0, 0.0, 0.0), Quat(0.0, 0.0, 0.0, 1.0)),
            sizeDp = Size3dDp(360, 640),
            panelContent =
              ee.schimke.composeai.data.layoutinspector.ComposeSemanticsNode(
                nodeId = "1",
                boundsInRoot = "0,0,360,640",
              ),
          ),
      )
    val decoded =
      json.decodeFromString(
        SpatialSemanticsTree.serializer(),
        json.encodeToString(SpatialSemanticsTree.serializer(), tree),
      )
    assertEquals(tree, decoded)
    assertEquals(SpatialSemanticsKind.PANEL, decoded.root.kind)
    assertTrue(decoded.root.children.isEmpty())
    assertNotNull(decoded.root.panelContent)
  }
}
