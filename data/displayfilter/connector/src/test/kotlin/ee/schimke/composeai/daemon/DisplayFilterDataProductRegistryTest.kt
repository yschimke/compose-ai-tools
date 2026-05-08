package ee.schimke.composeai.daemon

import ee.schimke.composeai.data.displayfilter.DisplayFilter
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DisplayFilterDataProductRegistryTest {

  @get:Rule val temp = TemporaryFolder()

  @Test
  fun advertisesDisplayFilterVariantsKindAsInlineAttachableFetchable() {
    val registry = DisplayFilterDataProductRegistry(rootDir = temp.newFolder("data"))
    val capability = registry.capabilities.single()
    assertEquals("displayfilter/variants", capability.kind)
    assertEquals(true, capability.attachable)
    assertEquals(true, capability.fetchable)
    assertEquals(false, capability.requiresRerender)
  }

  @Test
  fun fetchReturnsNotAvailableWhenManifestNotWrittenYet() {
    val registry = DisplayFilterDataProductRegistry(rootDir = temp.newFolder("data"))
    val outcome =
      registry.fetch(
        previewId = "missing.Preview",
        kind = "displayfilter/variants",
        params = null,
        inline = true,
      )
    assertEquals(DataProductRegistry.Outcome.NotAvailable, outcome)
  }

  @Test
  fun fetchReturnsUnknownForOtherKinds() {
    val registry = DisplayFilterDataProductRegistry(rootDir = temp.newFolder("data"))
    val outcome =
      registry.fetch(previewId = "any", kind = "a11y/atf", params = null, inline = false)
    assertEquals(DataProductRegistry.Outcome.Unknown, outcome)
  }

  @Test
  fun fetchAfterProducerReturnsManifestPayloadAndVariantPngsAsExtras() {
    val rootDir = temp.newFolder("data")
    val pngFile = writeSolidPng(width = 2, height = 2, argb = 0xFFFF0000.toInt())
    DisplayFilterDataProducer.writeArtifacts(
      rootDir = rootDir,
      previewId = "demo.Preview",
      pngFile = pngFile,
      filters = listOf(DisplayFilter.Grayscale, DisplayFilter.Invert),
    )

    val registry = DisplayFilterDataProductRegistry(rootDir = rootDir)
    val outcome =
      registry.fetch(
        previewId = "demo.Preview",
        kind = "displayfilter/variants",
        params = null,
        inline = true,
      )
    val ok = outcome as DataProductRegistry.Outcome.Ok
    assertEquals("displayfilter/variants", ok.result.kind)
    assertNotNull(ok.result.payload)
    assertNull(ok.result.path) // INLINE transport — no path set
    val variantsArray = (ok.result.payload as JsonObject)["variants"] as JsonArray
    assertEquals(
      setOf("grayscale", "invert"),
      variantsArray.map { it.jsonObject["filter"]!!.jsonPrimitive.content }.toSet(),
    )

    val extras = ok.result.extras.orEmpty()
    assertEquals(setOf("grayscale", "invert"), extras.map { it.name }.toSet())
    extras.forEach { assertTrue("extra exists: ${it.path}", File(it.path).isFile) }
  }

  @Test
  fun attachmentsForReturnsAttachmentOnlyForVariantsKind() {
    val rootDir = temp.newFolder("data")
    val pngFile = writeSolidPng(width = 2, height = 2, argb = 0xFFFFFFFF.toInt())
    DisplayFilterDataProducer.writeArtifacts(
      rootDir = rootDir,
      previewId = "demo.Attach",
      pngFile = pngFile,
      filters = listOf(DisplayFilter.Grayscale),
    )

    val registry = DisplayFilterDataProductRegistry(rootDir = rootDir)
    val attachments =
      registry.attachmentsFor(
        previewId = "demo.Attach",
        kinds = setOf("displayfilter/variants", "a11y/atf"),
      )
    assertEquals(1, attachments.size)
    assertEquals("displayfilter/variants", attachments.single().kind)
  }

  private fun writeSolidPng(width: Int, height: Int, argb: Int): File {
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    for (y in 0 until height) {
      for (x in 0 until width) {
        image.setRGB(x, y, argb)
      }
    }
    val file = temp.newFile("base-${System.nanoTime()}.png")
    ImageIO.write(image, "png", file)
    return file
  }
}
