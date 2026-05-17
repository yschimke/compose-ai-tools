package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.DataProductTransport
import ee.schimke.composeai.data.navigation.NavigationDataProduct
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NavigationDataProductRegistryTest {
  @get:Rule val tempFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun capabilities_advertise_path_transport_for_data_navigation() {
    val cap = NavigationDataProductRegistry(tempFolder.root).capabilities.single()
    assertEquals(NavigationDataProduct.KIND, cap.kind)
    assertEquals(NavigationDataProduct.SCHEMA_VERSION, cap.schemaVersion)
    assertEquals(DataProductTransport.PATH, cap.transport)
    assertTrue(cap.attachable)
    assertTrue(cap.fetchable)
  }

  @Test
  fun fetch_returns_not_available_when_artefact_absent() {
    val outcome =
      NavigationDataProductRegistry(tempFolder.root)
        .fetch("p1", NavigationDataProduct.KIND, params = null, inline = false)
    assertEquals(DataProductRegistry.Outcome.NotAvailable, outcome)
  }

  @Test
  fun fetch_returns_absolute_path_when_artefact_present() {
    val previewDir = tempFolder.newFolder("p1")
    val file = previewDir.resolve(NavigationDataProduct.FILE)
    file.writeText("{\"onBackPressed\":{\"hasEnabledCallbacks\":true}}")
    val outcome =
      NavigationDataProductRegistry(tempFolder.root)
        .fetch("p1", NavigationDataProduct.KIND, params = null, inline = false)
    val ok = outcome as DataProductRegistry.Outcome.Ok
    assertEquals(file.absolutePath, ok.result.path)
    assertNull(ok.result.payload)
  }

  @Test
  fun fetch_inflate_inline_reads_file_contents() {
    val previewDir = tempFolder.newFolder("p1")
    previewDir
      .resolve(NavigationDataProduct.FILE)
      .writeText("{\"onBackPressed\":{\"hasEnabledCallbacks\":false}}")
    val outcome =
      NavigationDataProductRegistry(tempFolder.root)
        .fetch("p1", NavigationDataProduct.KIND, params = null, inline = true)
    val ok = outcome as DataProductRegistry.Outcome.Ok
    val payload = ok.result.payload as JsonObject
    assertNotNull(payload["onBackPressed"])
  }

  @Test
  fun fetch_rejects_unknown_kind() {
    val outcome =
      NavigationDataProductRegistry(tempFolder.root).fetch("p1", "other/kind", null, false)
    assertEquals(DataProductRegistry.Outcome.Unknown, outcome)
  }

  @Test
  fun attachmentsFor_emits_path_attachment_when_file_exists() {
    val previewDir = tempFolder.newFolder("p1")
    val file =
      previewDir.resolve(NavigationDataProduct.FILE).also {
        it.writeText("{\"onBackPressed\":{\"hasEnabledCallbacks\":true}}")
      }
    val attachments =
      NavigationDataProductRegistry(tempFolder.root)
        .attachmentsFor("p1", setOf(NavigationDataProduct.KIND))
    assertEquals(1, attachments.size)
    assertEquals(file.absolutePath, attachments[0].path)
  }

  @Test
  fun attachmentsFor_empty_when_artefact_absent() {
    val attachments =
      NavigationDataProductRegistry(tempFolder.root)
        .attachmentsFor("p1", setOf(NavigationDataProduct.KIND))
    assertTrue(attachments.isEmpty())
  }
}
