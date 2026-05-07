package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.DataProductTransport
import ee.schimke.composeai.renderer.uiautomator.UiAutomatorDataProducts
import ee.schimke.composeai.renderer.uiautomator.UiAutomatorHierarchyNode
import ee.schimke.composeai.renderer.uiautomator.UiAutomatorHierarchyPayload
import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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

/**
 * Pins the producer/registry contract for the daemon's `uia/hierarchy` data product (#874).
 * Producer writes `<rootDir>/<previewId>/uia-hierarchy.json`; registry advertises one
 * path-transport kind, hands back the absolute path on `inline=false` and a parsed payload on
 * `inline=true`. Unknown kinds route to `Outcome.Unknown`; missing files route to
 * `Outcome.NotAvailable`. Mirrors `AccessibilityDataProductRegistryTest` so contract drift
 * between the two surfaces shows up as a diff against the same shape of test.
 */
class UiAutomatorDataProductRegistryTest {

  private lateinit var rootDir: File

  @Before
  fun setUp() {
    rootDir = Files.createTempDirectory("uia-data-product-test").toFile()
  }

  @After
  fun tearDown() {
    rootDir.deleteRecursively()
  }

  @Test
  fun `capabilities advertise uia hierarchy as a single path-transport kind`() {
    val registry = UiAutomatorDataProductRegistry(rootDir)

    val byKind = registry.capabilities.associateBy { it.kind }
    assertEquals(setOf(UiAutomatorDataProducts.KIND_HIERARCHY), byKind.keys)
    val cap = byKind.getValue(UiAutomatorDataProducts.KIND_HIERARCHY)
    assertEquals(DataProductTransport.PATH, cap.transport)
    assertEquals(UiAutomatorDataProducts.SCHEMA_VERSION, cap.schemaVersion)
    assertTrue("uia/hierarchy should be attachable", cap.attachable)
    assertTrue("uia/hierarchy should be fetchable", cap.fetchable)
    assertEquals(false, cap.requiresRerender)
    assertEquals(listOf("application/json"), cap.mediaTypes)
    assertTrue(registry.isKnown(UiAutomatorDataProducts.KIND_HIERARCHY))
  }

  @Test
  fun `fetch unknown kind returns Outcome Unknown`() {
    val registry = UiAutomatorDataProductRegistry(rootDir)

    val outcome = registry.fetch(previewId = "p", kind = "uia/nonsense", params = null, inline = false)
    assertEquals(DataProductRegistry.Outcome.Unknown, outcome)
  }

  @Test
  fun `fetch with no rendered preview returns Outcome NotAvailable`() {
    val registry = UiAutomatorDataProductRegistry(rootDir)

    val outcome =
      registry.fetch(
        previewId = "never-rendered",
        kind = UiAutomatorDataProducts.KIND_HIERARCHY,
        params = null,
        inline = false,
      )
    assertEquals(DataProductRegistry.Outcome.NotAvailable, outcome)
  }

  @Test
  fun `producer writes and registry returns path on default fetch and parsed payload when inline`() {
    val payload =
      UiAutomatorHierarchyPayload(
        nodes =
          listOf(
            UiAutomatorHierarchyNode(
              text = "Submit",
              testTag = "submit-button",
              actions = listOf(UiAutomatorDataProducts.ACTION_CLICK),
              boundsInScreen = "10,20,110,80",
            )
          )
      )
    UiAutomatorDataProducer.writeArtifacts(rootDir, previewId = "p", payload = payload)
    val registry = UiAutomatorDataProductRegistry(rootDir)

    // Default `inline=false` returns the absolute on-disk path so a co-located client can
    // read the file directly.
    val pathOutcome =
      registry.fetch(
        previewId = "p",
        kind = UiAutomatorDataProducts.KIND_HIERARCHY,
        params = null,
        inline = false,
      ) as DataProductRegistry.Outcome.Ok
    assertNotNull("path-transport fetch must carry an absolute path", pathOutcome.result.path)
    assertNull("path-transport fetch must not pre-parse the payload", pathOutcome.result.payload)
    assertTrue(File(pathOutcome.result.path!!).exists())

    // `inline=true` parses the JSON and hands the payload back so a remote client doesn't
    // have to make a second hop.
    val inlineOutcome =
      registry.fetch(
        previewId = "p",
        kind = UiAutomatorDataProducts.KIND_HIERARCHY,
        params = null,
        inline = true,
      ) as DataProductRegistry.Outcome.Ok
    assertNotNull("inline fetch must produce a parsed payload", inlineOutcome.result.payload)
    val node =
      (inlineOutcome.result.payload as JsonObject)["nodes"]!!.jsonArray.first().jsonObject
    assertEquals("Submit", node["text"]!!.jsonPrimitive.content)
    assertEquals("submit-button", node["testTag"]!!.jsonPrimitive.content)
  }

  @Test
  fun `attachmentsFor returns one path attachment per kind when the file exists and skips otherwise`() {
    val payload = UiAutomatorHierarchyPayload(nodes = emptyList())
    UiAutomatorDataProducer.writeArtifacts(rootDir, previewId = "p", payload = payload)
    val registry = UiAutomatorDataProductRegistry(rootDir)

    val attachments =
      registry.attachmentsFor(previewId = "p", kinds = setOf(UiAutomatorDataProducts.KIND_HIERARCHY))
    assertEquals(1, attachments.size)
    val attachment = attachments.single()
    assertEquals(UiAutomatorDataProducts.KIND_HIERARCHY, attachment.kind)
    assertEquals(UiAutomatorDataProducts.SCHEMA_VERSION, attachment.schemaVersion)
    assertNotNull("path-transport attachment must carry a path", attachment.path)
    assertNull("path-transport attachment must not inline the payload", attachment.payload)

    // No render for `q` → no file → no attachment, but the call must not throw.
    val emptyAttachments =
      registry.attachmentsFor(previewId = "q", kinds = setOf(UiAutomatorDataProducts.KIND_HIERARCHY))
    assertTrue(emptyAttachments.isEmpty())
  }

  @Test
  fun `producer overwrites prior file so the registry always sees the latest payload`() {
    val first =
      UiAutomatorHierarchyPayload(
        nodes =
          listOf(
            UiAutomatorHierarchyNode(
              text = "Initial",
              actions = listOf(UiAutomatorDataProducts.ACTION_CLICK),
              boundsInScreen = "0,0,100,100",
            )
          )
      )
    UiAutomatorDataProducer.writeArtifacts(rootDir, previewId = "p", payload = first)

    val second =
      UiAutomatorHierarchyPayload(
        nodes =
          listOf(
            UiAutomatorHierarchyNode(
              text = "Updated",
              actions = listOf(UiAutomatorDataProducts.ACTION_CLICK),
              boundsInScreen = "0,0,100,100",
            )
          )
      )
    UiAutomatorDataProducer.writeArtifacts(rootDir, previewId = "p", payload = second)

    val file =
      File(rootDir, "p").resolve(UiAutomatorDataProducer.FILE_HIERARCHY)
    val parsed = Json.parseToJsonElement(file.readText()).jsonObject
    val node = parsed["nodes"]!!.jsonArray.first().jsonObject
    assertEquals("Updated", node["text"]!!.jsonPrimitive.content)
  }
}
