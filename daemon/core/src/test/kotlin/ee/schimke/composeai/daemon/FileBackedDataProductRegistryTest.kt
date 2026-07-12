package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.DataFetchParams
import ee.schimke.composeai.daemon.protocol.DataProductCapability
import ee.schimke.composeai.daemon.protocol.DataProductExtra
import ee.schimke.composeai.daemon.protocol.DataProductTransport
import java.io.File
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileBackedDataProductRegistryTest {
  @get:Rule val tempFolder: TemporaryFolder = TemporaryFolder()

  private class PathRegistry(rootDir: File) :
    FileBackedDataProductRegistry(
      capabilities =
        listOf(
          DataProductCapability(
            kind = "demo/path",
            schemaVersion = 2,
            transport = DataProductTransport.PATH,
            attachable = true,
            fetchable = true,
            requiresRerender = false,
          )
        )
    ) {
    private val root = rootDir

    override fun fileFor(previewId: String, kind: String): File? =
      if (kind == "demo/path") root.resolve(previewId).resolve("payload.json") else null
  }

  private class InlineRegistry(rootDir: File) :
    FileBackedDataProductRegistry(
      capabilities =
        listOf(
          DataProductCapability(
            kind = "demo/inline",
            schemaVersion = 1,
            transport = DataProductTransport.INLINE,
            attachable = true,
            fetchable = true,
            requiresRerender = false,
          )
        )
    ) {
    private val root = rootDir

    override fun fileFor(previewId: String, kind: String): File? =
      if (kind == "demo/inline") root.resolve(previewId).resolve("payload.json") else null
  }

  private class RerenderRegistry(rootDir: File) :
    FileBackedDataProductRegistry(
      capabilities =
        listOf(
          DataProductCapability(
            kind = "demo/rerender",
            schemaVersion = 1,
            transport = DataProductTransport.PATH,
            attachable = true,
            fetchable = true,
            requiresRerender = true,
          )
        )
    ) {
    private val root = rootDir

    override fun fileFor(previewId: String, kind: String): File? =
      root.resolve(previewId).resolve("rerender.json")

    override fun missingOutcome(previewId: String, kind: String) =
      DataProductRegistry.Outcome.RequiresRerender(mode = "demo")
  }

  private class ExtrasRegistry(rootDir: File) :
    FileBackedDataProductRegistry(
      capabilities =
        listOf(
          DataProductCapability(
            kind = "demo/extras",
            schemaVersion = 1,
            transport = DataProductTransport.INLINE,
            attachable = true,
            fetchable = true,
            requiresRerender = false,
          )
        )
    ) {
    private val root = rootDir

    override fun fileFor(previewId: String, kind: String): File? =
      root.resolve(previewId).resolve("manifest.json")

    override fun extras(
      previewId: String,
      kind: String,
      payload: JsonElement?,
    ): List<DataProductExtra>? =
      listOf(DataProductExtra(name = "extra-of", path = previewId, mediaType = "text/plain"))
  }

  private fun writeFile(dir: File, previewId: String, name: String, contents: String): File {
    val file = dir.resolve(previewId).also { it.mkdirs() }.resolve(name)
    file.writeText(contents)
    return file
  }

  @Test
  fun fetch_returns_unknown_for_unregistered_kind() {
    val reg = PathRegistry(tempFolder.root)
    val outcome = reg.fetch("p", "not/registered", params = null, inline = false)
    assertEquals(DataProductRegistry.Outcome.Unknown, outcome)
  }

  @Test
  fun fetch_returns_not_available_when_file_absent() {
    val reg = PathRegistry(tempFolder.root)
    val outcome = reg.fetch("p", "demo/path", params = null, inline = false)
    assertEquals(DataProductRegistry.Outcome.NotAvailable, outcome)
  }

  @Test
  fun fetch_path_transport_returns_absolute_path() {
    val reg = PathRegistry(tempFolder.root)
    val written = writeFile(tempFolder.root, "p", "payload.json", "{\"k\":1}")
    val outcome = reg.fetch("p", "demo/path", params = null, inline = false)
    val ok = outcome as DataProductRegistry.Outcome.Ok
    assertEquals(written.absolutePath, ok.result.path)
    assertNull(ok.result.payload)
    assertEquals(2, ok.result.schemaVersion)
  }

  @Test
  fun fetch_path_transport_upgrades_to_inline_when_requested() {
    val reg = PathRegistry(tempFolder.root)
    writeFile(tempFolder.root, "p", "payload.json", "{\"k\":1}")
    val outcome = reg.fetch("p", "demo/path", params = null, inline = true)
    val ok = outcome as DataProductRegistry.Outcome.Ok
    assertNull(ok.result.path)
    val obj = ok.result.payload as JsonObject
    assertEquals(JsonPrimitive(1), obj["k"])
  }

  @Test
  fun fetch_inline_transport_reads_payload_regardless_of_inline_flag() {
    val reg = InlineRegistry(tempFolder.root)
    writeFile(tempFolder.root, "p", "payload.json", "{\"k\":\"v\"}")
    val outcome = reg.fetch("p", "demo/inline", params = null, inline = false)
    val ok = outcome as DataProductRegistry.Outcome.Ok
    assertEquals(JsonPrimitive("v"), (ok.result.payload as JsonObject)["k"])
  }

  @Test
  fun fetch_inline_returns_fetch_failed_on_parse_error() {
    val reg = InlineRegistry(tempFolder.root)
    writeFile(tempFolder.root, "p", "payload.json", "not json")
    val outcome = reg.fetch("p", "demo/inline", params = null, inline = true)
    assertTrue(outcome is DataProductRegistry.Outcome.FetchFailed)
  }

  private val forceParams: JsonObject =
    JsonObject(mapOf(DataFetchParams.PARAM_FORCE_RERENDER to JsonPrimitive(true)))

  @Test
  fun fetch_force_rerenders_existing_file_for_rerender_kind() {
    val reg = RerenderRegistry(tempFolder.root)
    writeFile(tempFolder.root, "p", "rerender.json", "{\"stale\":true}")
    // File exists, but `force` on a requiresRerender kind must re-render (serve the fresh
    // artefact).
    val outcome = reg.fetch("p", "demo/rerender", params = forceParams, inline = false)
    assertEquals(DataProductRegistry.Outcome.RequiresRerender(mode = "demo"), outcome)
  }

  @Test
  fun fetch_without_force_serves_existing_rerender_file() {
    val reg = RerenderRegistry(tempFolder.root)
    val written = writeFile(tempFolder.root, "p", "rerender.json", "{\"k\":1}")
    val outcome = reg.fetch("p", "demo/rerender", params = null, inline = false)
    assertEquals(written.absolutePath, (outcome as DataProductRegistry.Outcome.Ok).result.path)
  }

  @Test
  fun fetch_force_is_noop_for_non_rerender_kind() {
    val reg = PathRegistry(tempFolder.root)
    val written = writeFile(tempFolder.root, "p", "payload.json", "{\"k\":1}")
    // A non-rerender kind has no RequiresRerender outcome; honouring `force` would wrongly hide the
    // existing file, so it must still serve it.
    val outcome = reg.fetch("p", "demo/path", params = forceParams, inline = false)
    assertEquals(written.absolutePath, (outcome as DataProductRegistry.Outcome.Ok).result.path)
  }

  @Test
  fun fetch_uses_missingOutcome_override_for_requires_rerender_kinds() {
    val reg = RerenderRegistry(tempFolder.root)
    val outcome = reg.fetch("p", "demo/rerender", params = null, inline = false)
    val req = outcome as DataProductRegistry.Outcome.RequiresRerender
    assertEquals("demo", req.mode)
  }

  @Test
  fun attachmentsFor_returns_path_for_path_transport() {
    val reg = PathRegistry(tempFolder.root)
    val written = writeFile(tempFolder.root, "p", "payload.json", "{}")
    val attachments = reg.attachmentsFor("p", setOf("demo/path"))
    assertEquals(1, attachments.size)
    assertEquals(written.absolutePath, attachments[0].path)
    assertNull(attachments[0].payload)
  }

  @Test
  fun attachmentsFor_returns_payload_for_inline_transport() {
    val reg = InlineRegistry(tempFolder.root)
    writeFile(tempFolder.root, "p", "payload.json", "{\"k\":1}")
    val attachments = reg.attachmentsFor("p", setOf("demo/inline"))
    assertEquals(1, attachments.size)
    assertNull(attachments[0].path)
    assertNotNull(attachments[0].payload)
  }

  @Test
  fun attachmentsFor_skips_kinds_not_in_capabilities() {
    val reg = PathRegistry(tempFolder.root)
    writeFile(tempFolder.root, "p", "payload.json", "{}")
    val attachments = reg.attachmentsFor("p", setOf("demo/path", "other/kind"))
    assertEquals(1, attachments.size)
    assertEquals("demo/path", attachments[0].kind)
  }

  @Test
  fun attachmentsFor_skips_missing_files() {
    val reg = PathRegistry(tempFolder.root)
    val attachments = reg.attachmentsFor("p", setOf("demo/path"))
    assertTrue(attachments.isEmpty())
  }

  @Test
  fun extras_hook_is_invoked_for_both_path_and_inline() {
    val reg = ExtrasRegistry(tempFolder.root)
    writeFile(tempFolder.root, "p", "manifest.json", "{}")
    val attachments = reg.attachmentsFor("p", setOf("demo/extras"))
    assertEquals(1, attachments.size)
    val extras = attachments[0].extras
    assertNotNull(extras)
    assertEquals("p", extras!!.single().path)
  }

  @Test
  fun isKnown_uses_capabilities_table() {
    val reg = PathRegistry(tempFolder.root)
    assertTrue(reg.isKnown("demo/path"))
    assertTrue(!reg.isKnown("nope"))
  }

  private class BinaryPathRegistry(rootDir: File) :
    FileBackedDataProductRegistry(
      capabilities =
        listOf(
          DataProductCapability(
            kind = "demo/binary",
            schemaVersion = 1,
            transport = DataProductTransport.PATH,
            attachable = true,
            fetchable = true,
            requiresRerender = false,
          )
        )
    ) {
    private val root = rootDir

    override fun fileFor(previewId: String, kind: String): File? =
      if (kind == "demo/binary") root.resolve(previewId).resolve("payload.png") else null

    override fun allowInlineUpgrade(kind: String): Boolean = false
  }

  @Test
  fun fetch_path_returns_path_when_inline_upgrade_disallowed() {
    val reg = BinaryPathRegistry(tempFolder.root)
    val written = writeFile(tempFolder.root, "p", "payload.png", "not json — these are PNG bytes")
    val outcome = reg.fetch("p", "demo/binary", params = null, inline = true)
    val ok = outcome as DataProductRegistry.Outcome.Ok
    // The disabled upgrade keeps the response on the path branch — without it the JSON parse
    // of binary bytes would explode and we'd return FetchFailed.
    assertEquals(written.absolutePath, ok.result.path)
    assertNull(ok.result.payload)
  }
}
