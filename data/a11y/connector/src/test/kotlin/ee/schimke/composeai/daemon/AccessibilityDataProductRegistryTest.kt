package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.DataProductTransport
import ee.schimke.composeai.renderer.AccessibilityFinding
import ee.schimke.composeai.renderer.AccessibilityNode
import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * D2 — pins the producer/registry contract for the daemon's a11y data products. Producer
 * writes to `<rootDir>/<previewId>/a11y-{atf,hierarchy}.json`; registry reads back what's
 * there and surfaces it as inline (`a11y/atf`) or path (`a11y/hierarchy`). Unknown kinds
 * route to `Outcome.Unknown`; missing files route to `Outcome.NotAvailable`.
 */
class AccessibilityDataProductRegistryTest {

  private lateinit var rootDir: File

  @Before
  fun setUp() {
    rootDir = Files.createTempDirectory("a11y-data-product-test").toFile()
  }

  @After
  fun tearDown() {
    rootDir.deleteRecursively()
  }

  @Test
  fun `capabilities advertise a11y atf hierarchy and overlay with the documented transports`() {
    val registry = AccessibilityDataProductRegistry(rootDir)
    val byKind = registry.capabilities.associateBy { it.kind }
    assertEquals(setOf("a11y/atf", "a11y/hierarchy", "a11y/overlay"), byKind.keys)
    assertEquals(DataProductTransport.INLINE, byKind.getValue("a11y/atf").transport)
    assertEquals(DataProductTransport.PATH, byKind.getValue("a11y/hierarchy").transport)
    assertEquals(DataProductTransport.PATH, byKind.getValue("a11y/overlay").transport)
    for (cap in registry.capabilities) {
      assertTrue(cap.attachable)
      assertTrue(cap.fetchable)
      // The producer always runs in daemon a11y mode — no per-fetch re-render.
      assertTrue(!cap.requiresRerender)
    }
  }

  @Test
  fun `attachmentsFor returns inline a11y atf payload and path-only a11y hierarchy entry`() {
    val registry = AccessibilityDataProductRegistry(rootDir)
    val finding =
      AccessibilityFinding(
        level = "WARNING",
        type = "TouchTargetSizeCheck",
        message = "below 48dp",
        viewDescription = "Button",
        boundsInScreen = "0,0,32,32",
      )
    val node =
      AccessibilityNode(
        label = "Submit",
        role = "Button",
        states = listOf("clickable"),
        merged = true,
        boundsInScreen = "10,20,200,80",
      )
    AccessibilityDataProducer.writeArtifacts(
      rootDir = rootDir,
      previewId = "com.example.HomeKt#HomePreview",
      findings = listOf(finding),
      nodes = listOf(node),
    )

    val attachments =
      registry.attachmentsFor(
        previewId = "com.example.HomeKt#HomePreview",
        kinds = setOf("a11y/atf", "a11y/hierarchy"),
      )
    assertEquals(2, attachments.size)
    val byKind = attachments.associateBy { it.kind }

    val atf = byKind.getValue("a11y/atf")
    assertNotNull("a11y/atf should travel inline", atf.payload)
    assertNull("a11y/atf must not also carry a path", atf.path)
    val findings = (atf.payload as JsonObject)["findings"]?.jsonArray
    assertNotNull(findings)
    assertEquals(1, findings!!.size)
    assertEquals(
      "TouchTargetSizeCheck",
      findings[0].jsonObject["type"]!!.toString().trim('"'),
    )

    val hierarchy = byKind.getValue("a11y/hierarchy")
    assertNull("a11y/hierarchy travels by path, not inline", hierarchy.payload)
    assertNotNull("a11y/hierarchy must point at the JSON file", hierarchy.path)
    assertTrue(File(hierarchy.path!!).exists())
  }

  @Test
  fun `attachmentsFor skips kinds with no on-disk file rather than emitting an empty entry`() {
    val registry = AccessibilityDataProductRegistry(rootDir)
    // No producer has run for this preview yet — neither file exists.
    val attachments =
      registry.attachmentsFor(
        previewId = "com.example.NoRender",
        kinds = setOf("a11y/atf", "a11y/hierarchy"),
      )
    assertEquals(emptyList<Any>(), attachments)
  }

  @Test
  fun `fetch returns NotAvailable when the preview never rendered`() {
    val registry = AccessibilityDataProductRegistry(rootDir)
    val outcome =
      registry.fetch(
        previewId = "com.example.NoRender",
        kind = "a11y/hierarchy",
        params = null,
        inline = false,
      )
    assertEquals(DataProductRegistry.Outcome.NotAvailable, outcome)
  }

  @Test
  fun `fetch returns Unknown for a kind the registry does not advertise`() {
    val registry = AccessibilityDataProductRegistry(rootDir)
    val outcome =
      registry.fetch(
        previewId = "com.example.HomeKt#HomePreview",
        kind = "compose/recomposition",
        params = null,
        inline = false,
      )
    assertEquals(DataProductRegistry.Outcome.Unknown, outcome)
  }

  @Test
  fun `fetch on a11y hierarchy returns the absolute path when caller did not request inline`() {
    val registry = AccessibilityDataProductRegistry(rootDir)
    AccessibilityDataProducer.writeArtifacts(
      rootDir = rootDir,
      previewId = "com.example.X",
      findings = emptyList(),
      nodes = emptyList(),
    )
    val outcome =
      registry.fetch(
        previewId = "com.example.X",
        kind = "a11y/hierarchy",
        params = null,
        inline = false,
      )
    assertTrue(outcome is DataProductRegistry.Outcome.Ok)
    val result = (outcome as DataProductRegistry.Outcome.Ok).result
    assertNotNull(result.path)
    assertNull(result.payload)
    assertTrue(File(result.path!!).exists())
  }

  @Test
  fun `attachmentsFor surfaces overlay PNG as an extra under a11y atf and hierarchy when present`() {
    val registry = AccessibilityDataProductRegistry(rootDir)
    val previewId = "com.example.HomeKt#HomePreview"
    AccessibilityDataProducer.writeArtifacts(
      rootDir = rootDir,
      previewId = previewId,
      findings = emptyList(),
      nodes = emptyList(),
    )
    // Simulate the AccessibilityImageProcessor having written the overlay PNG into the
    // per-preview data dir alongside the JSON artefacts. The registry doesn't care how the
    // bytes got there; it just surfaces the file as an extra.
    val previewDir = File(rootDir, previewId).also { it.mkdirs() }
    val overlay = File(previewDir, AccessibilityDataProducer.FILE_OVERLAY)
    overlay.writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47))

    val attachments =
      registry.attachmentsFor(
        previewId = previewId,
        kinds = setOf("a11y/atf", "a11y/hierarchy", "a11y/overlay"),
      )
    val byKind = attachments.associateBy { it.kind }
    val atfExtras = byKind.getValue("a11y/atf").extras
    assertNotNull("extras must populate when overlay PNG is on disk", atfExtras)
    assertEquals(1, atfExtras!!.size)
    assertEquals("overlay", atfExtras[0].name)
    assertEquals("image/png", atfExtras[0].mediaType)
    assertEquals(overlay.absolutePath, atfExtras[0].path)

    val overlayKind = byKind.getValue("a11y/overlay")
    assertNotNull(overlayKind.path)
    assertEquals(overlay.absolutePath, overlayKind.path)
    assertNull(overlayKind.payload)
  }

  @Test
  fun `fetch on a11y overlay returns the path and the same extra as the JSON kinds`() {
    val registry = AccessibilityDataProductRegistry(rootDir)
    val previewId = "com.example.X"
    AccessibilityDataProducer.writeArtifacts(
      rootDir = rootDir,
      previewId = previewId,
      findings = emptyList(),
      nodes = emptyList(),
    )
    val previewDir = File(rootDir, previewId).also { it.mkdirs() }
    val overlay = File(previewDir, AccessibilityDataProducer.FILE_OVERLAY)
    overlay.writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47))

    val outcome =
      registry.fetch(previewId = previewId, kind = "a11y/overlay", params = null, inline = false)
    assertTrue(outcome is DataProductRegistry.Outcome.Ok)
    val result = (outcome as DataProductRegistry.Outcome.Ok).result
    assertEquals(overlay.absolutePath, result.path)
    assertNull("a11y/overlay must not be parsed as JSON", result.payload)
    val extras = result.extras
    assertNotNull(extras)
    assertEquals(1, extras!!.size)
    assertEquals(overlay.absolutePath, extras[0].path)
  }

  @Test
  fun `attachmentsFor omits extras when the overlay PNG never landed`() {
    val registry = AccessibilityDataProductRegistry(rootDir)
    val previewId = "com.example.NoOverlay"
    AccessibilityDataProducer.writeArtifacts(
      rootDir = rootDir,
      previewId = previewId,
      findings = emptyList(),
      nodes = emptyList(),
    )
    val attachments =
      registry.attachmentsFor(previewId = previewId, kinds = setOf("a11y/atf", "a11y/hierarchy"))
    for (att in attachments) {
      assertNull("extras must be absent when overlay PNG is missing", att.extras)
    }
  }
}
