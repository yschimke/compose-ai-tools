package ee.schimke.composeai.daemon

import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ComposeSemanticsDataProductRegistryTest {
  private lateinit var rootDir: File

  @Before
  fun setUp() {
    rootDir = Files.createTempDirectory("compose-semantics-product-test").toFile()
  }

  @After
  fun tearDown() {
    rootDir.deleteRecursively()
  }

  @Test
  fun `capability advertises compose semantics as path transport`() {
    val registry = ComposeSemanticsDataProductRegistry(rootDir)
    val cap = registry.capabilities.single()
    assertEquals("compose/semantics", cap.kind)
    assertEquals(2, cap.schemaVersion)
    assertTrue(cap.attachable)
    assertTrue(cap.fetchable)
    assertTrue(!cap.requiresRerender)
  }

  @Test
  fun `fetch returns path by default and payload when inline requested`() {
    val previewId = "com.example.SemanticsPreview"
    val file =
      rootDir
        .resolve(previewId)
        .also { it.mkdirs() }
        .resolve(ComposeSemanticsDataProducer.FILE)
    file.writeText(
      """{"root":{"nodeId":"1","boundsInRoot":"0,0,64,64","label":"Submit","role":"Button","clickable":true}}"""
    )
    val registry = ComposeSemanticsDataProductRegistry(rootDir)

    val pathOutcome =
      registry.fetch(previewId = previewId, kind = "compose/semantics", params = null, inline = false)
    assertTrue(pathOutcome is DataProductRegistry.Outcome.Ok)
    val pathResult = (pathOutcome as DataProductRegistry.Outcome.Ok).result
    assertEquals(file.absolutePath, pathResult.path)
    assertNull(pathResult.payload)

    val inlineOutcome =
      registry.fetch(previewId = previewId, kind = "compose/semantics", params = null, inline = true)
    assertTrue(inlineOutcome is DataProductRegistry.Outcome.Ok)
    val inlineResult = (inlineOutcome as DataProductRegistry.Outcome.Ok).result
    assertNull(inlineResult.path)
    assertNotNull(inlineResult.payload)
    assertEquals(
      "Submit",
      inlineResult.payload!!.jsonObject["root"]!!.jsonObject["label"].toString().trim('"'),
    )
  }

  @Test
  fun `attachmentsFor emits path only after render wrote semantics file`() {
    val previewId = "com.example.SemanticsPreview"
    val registry = ComposeSemanticsDataProductRegistry(rootDir)
    assertEquals(emptyList<Any>(), registry.attachmentsFor(previewId, setOf("compose/semantics")))

    val file =
      rootDir
        .resolve(previewId)
        .also { it.mkdirs() }
        .resolve(ComposeSemanticsDataProducer.FILE)
    file.writeText("""{"root":{"nodeId":"1","boundsInRoot":"0,0,1,1"}}""")

    val attachment = registry.attachmentsFor(previewId, setOf("compose/semantics")).single()
    assertEquals("compose/semantics", attachment.kind)
    assertEquals(file.absolutePath, attachment.path)
    assertNull(attachment.payload)
  }

  @Test
  fun `layout inspector capability advertises path transport`() {
    val registry = LayoutInspectorDataProductRegistry(rootDir)
    val cap = registry.capabilities.single()
    assertEquals("layout/inspector", cap.kind)
    assertEquals(1, cap.schemaVersion)
    assertTrue(cap.attachable)
    assertTrue(cap.fetchable)
    assertTrue(!cap.requiresRerender)
  }

  @Test
  fun `layout inspector fetch returns path by default and payload when inline requested`() {
    val previewId = "com.example.LayoutPreview"
    val file =
      rootDir
        .resolve(previewId)
        .also { it.mkdirs() }
        .resolve(LayoutInspectorDataProducer.FILE)
    file.writeText(
      """{"root":{"nodeId":"1","component":"SettingsRow","bounds":{"left":0,"top":80,"right":360,"bottom":136},"size":{"width":360,"height":56},"modifiers":[{"name":"padding","properties":{"top":"2.dp"}}]}}"""
    )
    val registry = LayoutInspectorDataProductRegistry(rootDir)

    val pathOutcome =
      registry.fetch(previewId = previewId, kind = "layout/inspector", params = null, inline = false)
    assertTrue(pathOutcome is DataProductRegistry.Outcome.Ok)
    val pathResult = (pathOutcome as DataProductRegistry.Outcome.Ok).result
    assertEquals(file.absolutePath, pathResult.path)
    assertNull(pathResult.payload)

    val inlineOutcome =
      registry.fetch(previewId = previewId, kind = "layout/inspector", params = null, inline = true)
    assertTrue(inlineOutcome is DataProductRegistry.Outcome.Ok)
    val inlineResult = (inlineOutcome as DataProductRegistry.Outcome.Ok).result
    assertNull(inlineResult.path)
    assertNotNull(inlineResult.payload)
    assertEquals(
      "SettingsRow",
      inlineResult.payload!!.jsonObject["root"]!!.jsonObject["component"].toString().trim('"'),
    )
  }

  @Test
  fun `layout inspector attachments emit path only after render wrote file`() {
    val previewId = "com.example.LayoutPreview"
    val registry = LayoutInspectorDataProductRegistry(rootDir)
    assertEquals(emptyList<Any>(), registry.attachmentsFor(previewId, setOf("layout/inspector")))

    val file =
      rootDir
        .resolve(previewId)
        .also { it.mkdirs() }
        .resolve(LayoutInspectorDataProducer.FILE)
    file.writeText(
      """{"root":{"nodeId":"1","component":"Box","bounds":{"left":0,"top":0,"right":1,"bottom":1},"size":{"width":1,"height":1}}}"""
    )

    val attachment = registry.attachmentsFor(previewId, setOf("layout/inspector")).single()
    assertEquals("layout/inspector", attachment.kind)
    assertEquals(file.absolutePath, attachment.path)
    assertNull(attachment.payload)
  }
}
