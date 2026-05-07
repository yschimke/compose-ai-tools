package ee.schimke.composeai.daemon

import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Pins [NavigationDataProductRegistry] — the registry side of the `data/navigation` data product.
 * Plain JUnit (no Robolectric) because the registry only reads JSON off disk; the producer side
 * is exercised in [NavigationDataProductTest] which boots a real `ComponentActivity`.
 */
class NavigationDataProductRegistryTest {
  private lateinit var rootDir: File

  @Before
  fun setUp() {
    rootDir = Files.createTempDirectory("navigation-product-test").toFile()
  }

  @After
  fun tearDown() {
    rootDir.deleteRecursively()
  }

  @Test
  fun `capability advertises data slash navigation as path transport`() {
    val registry = NavigationDataProductRegistry(rootDir)
    val cap = registry.capabilities.single()
    assertEquals("data/navigation", cap.kind)
    assertEquals(1, cap.schemaVersion)
    assertTrue(cap.attachable)
    assertTrue(cap.fetchable)
    assertTrue(!cap.requiresRerender)
  }

  @Test
  fun `fetch returns path by default and payload when inline requested`() {
    val previewId = "com.example.NavPreview"
    val file =
      rootDir
        .resolve(previewId)
        .also { it.mkdirs() }
        .resolve(NavigationDataProducer.FILE)
    file.writeText(
      """{"intent":{"action":"android.intent.action.VIEW","dataUri":"app://route/home"},"onBackPressed":{"hasEnabledCallbacks":true}}"""
    )
    val registry = NavigationDataProductRegistry(rootDir)

    val pathOutcome =
      registry.fetch(
        previewId = previewId,
        kind = "data/navigation",
        params = null,
        inline = false,
      )
    assertTrue(pathOutcome is DataProductRegistry.Outcome.Ok)
    val pathResult = (pathOutcome as DataProductRegistry.Outcome.Ok).result
    assertEquals(file.absolutePath, pathResult.path)
    assertNull(pathResult.payload)

    val inlineOutcome =
      registry.fetch(
        previewId = previewId,
        kind = "data/navigation",
        params = null,
        inline = true,
      )
    assertTrue(inlineOutcome is DataProductRegistry.Outcome.Ok)
    val inlineResult = (inlineOutcome as DataProductRegistry.Outcome.Ok).result
    assertNull(inlineResult.path)
    assertNotNull(inlineResult.payload)
    assertEquals(
      "app://route/home",
      inlineResult.payload!!.jsonObject["intent"]!!.jsonObject["dataUri"]!!.jsonPrimitive.content,
    )
  }

  @Test
  fun `fetch returns NotAvailable when the artefact is missing`() {
    val registry = NavigationDataProductRegistry(rootDir)
    val outcome =
      registry.fetch(
        previewId = "missing-preview",
        kind = "data/navigation",
        params = null,
        inline = false,
      )
    assertTrue(outcome is DataProductRegistry.Outcome.NotAvailable)
  }

  @Test
  fun `fetch returns Unknown for foreign kinds`() {
    val registry = NavigationDataProductRegistry(rootDir)
    val outcome =
      registry.fetch(
        previewId = "irrelevant",
        kind = "compose/semantics",
        params = null,
        inline = false,
      )
    assertTrue(outcome is DataProductRegistry.Outcome.Unknown)
  }

  @Test
  fun `attachmentsFor returns the artefact path when the kind is requested`() {
    val previewId = "preview-attached"
    val file =
      rootDir
        .resolve(previewId)
        .also { it.mkdirs() }
        .resolve(NavigationDataProducer.FILE)
    file.writeText("""{"onBackPressed":{"hasEnabledCallbacks":false}}""")
    val registry = NavigationDataProductRegistry(rootDir)

    val attachments = registry.attachmentsFor(previewId, setOf("data/navigation"))
    assertEquals(1, attachments.size)
    assertEquals(file.absolutePath, attachments.single().path)
    assertEquals("data/navigation", attachments.single().kind)

    val foreign = registry.attachmentsFor(previewId, setOf("compose/semantics"))
    assertTrue(foreign.isEmpty())
  }
}
