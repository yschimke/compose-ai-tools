package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.DataProductTransport
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FontsUsedDataProductRegistryTest {
  @get:Rule val tempFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun capabilities_advertise_fonts_used_as_default_inline_product() {
    val registry = FontsUsedDataProductRegistry(tempFolder.root)
    val cap = registry.capabilities.single()
    assertEquals("fonts/used", cap.kind)
    assertEquals(1, cap.schemaVersion)
    assertEquals(DataProductTransport.INLINE, cap.transport)
    assertTrue(cap.attachable)
    assertTrue(cap.fetchable)
    assertTrue(!cap.requiresRerender)
  }

  @Test
  fun fetch_reads_payload_written_by_render_loop() {
    val root = tempFolder.newFolder("data")
    FontsUsedDataProducer.writeArtifacts(
      rootDir = root,
      previewId = "preview-1",
      payload =
        FontsUsedPayload(
          fonts =
            listOf(
              FontUsedEntry(
                requestedFamily = "serif",
                resolvedFamily = "Serif",
                weight = 400,
                style = "normal",
                sourceFile = null,
                fellBackFrom = listOf("Poppins"),
                consumerNodeIds = emptyList(),
              )
            )
        ),
    )

    val outcome =
      FontsUsedDataProductRegistry(root)
        .fetch("preview-1", "fonts/used", params = null, inline = true)
    assertTrue(outcome is DataProductRegistry.Outcome.Ok)
    val payload = (outcome as DataProductRegistry.Outcome.Ok).result.payload!!.jsonObject
    val font = payload["fonts"]!!.jsonArray.single().jsonObject
    assertEquals("serif", font["requestedFamily"]!!.jsonPrimitive.content)
    assertEquals("Serif", font["resolvedFamily"]!!.jsonPrimitive.content)
    assertEquals("400", font["weight"]!!.jsonPrimitive.content)
  }

  @Test
  fun fetch_missing_payload_returns_not_available() {
    val root = tempFolder.newFolder("data-missing")
    val outcome = FontsUsedDataProductRegistry(root).fetch("missing", "fonts/used", null, true)
    assertEquals(DataProductRegistry.Outcome.NotAvailable, outcome)
  }

  @Test
  fun attachments_return_written_payload() {
    val root = tempFolder.newFolder("data-attachments")
    FontsUsedDataProducer.writeArtifacts(
      rootDir = root,
      previewId = "preview-1",
      payload = FontsUsedPayload(fonts = emptyList()),
    )

    val attachments =
      FontsUsedDataProductRegistry(root).attachmentsFor("preview-1", setOf("fonts/used"))
    assertEquals(1, attachments.size)
    assertEquals("fonts/used", attachments.single().kind)
  }
}
