package ee.schimke.composeai.daemon

import ee.schimke.composeai.xr.SpatialSemanticsKind
import ee.schimke.composeai.xr.SpatialSemanticsTree
import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SpatialSemanticsDataProductTest {
  private lateinit var rootDir: File

  @Before
  fun setUp() {
    rootDir = Files.createTempDirectory("compose-spatial-semantics-test").toFile()
  }

  @After
  fun tearDown() {
    rootDir.deleteRecursively()
  }

  private fun decode(file: File): SpatialSemanticsTree =
    Json.decodeFromString(SpatialSemanticsTree.serializer(), file.readText())

  @Test
  fun `writeSinglePanel wraps the 2D tree as a degenerate single-panel subspace`() {
    val previewId = "com.example.CardPreview"
    val payload =
      ComposeSemanticsPayload(
        root =
          ComposeSemanticsNode(
            nodeId = "1",
            boundsInRoot = "0,0,360,640",
            label = "Card",
            children =
              listOf(ComposeSemanticsNode(nodeId = "2", boundsInRoot = "16,16,200,48", text = "Hi")),
          )
      )

    SpatialSemanticsDataProducer.writeSinglePanel(rootDir, previewId, payload)

    val file = rootDir.resolve(previewId).resolve(SpatialSemanticsDataProducer.FILE)
    assertTrue(file.exists())
    val tree = decode(file)

    // version + units must be on the wire — the consumer's `isSpatialSemanticsTree` guard requires
    // both, so the producer serializes defaults.
    assertEquals(1, tree.version)
    assertEquals("dp", tree.units)
    assertEquals(previewId, tree.previewId)

    assertEquals(SpatialSemanticsKind.SUBSPACE_ROOT, tree.root.kind)
    val panel = tree.root.children.single()
    assertEquals(SpatialSemanticsKind.PANEL, panel.kind)
    // sizeDp recovered from the root node's "0,0,360,640" bounds.
    assertEquals(360, panel.sizeDp.width)
    assertEquals(640, panel.sizeDp.height)
    assertEquals("Card", panel.label)
    assertNotNull(panel.panelContent)
    assertEquals("Hi", panel.panelContent!!.children.single().text)
  }

  @Test
  fun `capability advertises compose spatial-semantics as path transport`() {
    val cap = SpatialSemanticsDataProductRegistry(rootDir).capabilities.single()
    assertEquals("compose/spatial-semantics", cap.kind)
    assertEquals(1, cap.schemaVersion)
    assertTrue(cap.attachable)
    assertTrue(cap.fetchable)
    assertTrue(!cap.requiresRerender)
  }

  @Test
  fun `fetch returns the written tree path`() {
    val previewId = "com.example.CardPreview"
    val payload =
      ComposeSemanticsPayload(root = ComposeSemanticsNode(nodeId = "1", boundsInRoot = "0,0,10,10"))
    SpatialSemanticsDataProducer.writeSinglePanel(rootDir, previewId, payload)
    val file = rootDir.resolve(previewId).resolve(SpatialSemanticsDataProducer.FILE)

    val outcome =
      SpatialSemanticsDataProductRegistry(rootDir)
        .fetch(
          previewId = previewId,
          kind = "compose/spatial-semantics",
          params = null,
          inline = false,
        )
    assertTrue(outcome is DataProductRegistry.Outcome.Ok)
    val result = (outcome as DataProductRegistry.Outcome.Ok).result
    assertEquals(file.absolutePath, result.path)
    assertNull(result.payload)
  }
}
