package ee.schimke.composeai.daemon

import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ResourcesUsedDataProductRegistryTest {
  private lateinit var rootDir: File

  @Before
  fun setUp() {
    rootDir = Files.createTempDirectory("resources-used-product-test").toFile()
  }

  @After
  fun tearDown() {
    rootDir.deleteRecursively()
  }

  @Test
  fun `capability advertises resources used as path transport`() {
    val registry = ResourcesUsedDataProductRegistry(rootDir)
    val cap = registry.capabilities.single()
    assertEquals("resources/used", cap.kind)
    assertEquals(1, cap.schemaVersion)
    assertTrue(cap.attachable)
    assertTrue(cap.fetchable)
    assertTrue(!cap.requiresRerender)
  }

  @Test
  fun `fetch returns path by default and payload when inline requested`() {
    val previewId = "com.example.ResourcePreview"
    val file =
      rootDir
        .resolve(previewId)
        .also { it.mkdirs() }
        .resolve(ResourcesUsedDataProducer.FILE)
    file.writeText(
      """{"references":[{"resourceType":"string","resourceName":"app_name","packageName":"com.example","resolvedValue":"Demo","consumers":[]}]}"""
    )
    val registry = ResourcesUsedDataProductRegistry(rootDir)

    val pathOutcome =
      registry.fetch(previewId = previewId, kind = "resources/used", params = null, inline = false)
    assertTrue(pathOutcome is DataProductRegistry.Outcome.Ok)
    val pathResult = (pathOutcome as DataProductRegistry.Outcome.Ok).result
    assertEquals(file.absolutePath, pathResult.path)
    assertNull(pathResult.payload)

    val inlineOutcome =
      registry.fetch(previewId = previewId, kind = "resources/used", params = null, inline = true)
    assertTrue(inlineOutcome is DataProductRegistry.Outcome.Ok)
    val inlineResult = (inlineOutcome as DataProductRegistry.Outcome.Ok).result
    assertNull(inlineResult.path)
    assertNotNull(inlineResult.payload)
    assertEquals(
      "app_name",
      inlineResult
        .payload!!
        .jsonObject["references"]!!
        .jsonArray
        .single()
        .jsonObject["resourceName"]!!
        .jsonPrimitive
        .content,
    )
  }

  @Test
  fun `attachmentsFor emits path only after render wrote resources file`() {
    val previewId = "com.example.ResourcePreview"
    val registry = ResourcesUsedDataProductRegistry(rootDir)
    assertEquals(emptyList<Any>(), registry.attachmentsFor(previewId, setOf("resources/used")))

    val file =
      rootDir
        .resolve(previewId)
        .also { it.mkdirs() }
        .resolve(ResourcesUsedDataProducer.FILE)
    file.writeText("""{"references":[]}""")

    val attachment = registry.attachmentsFor(previewId, setOf("resources/used")).single()
    assertEquals("resources/used", attachment.kind)
    assertEquals(file.absolutePath, attachment.path)
    assertNull(attachment.payload)
  }
}
