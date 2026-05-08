package ee.schimke.composeai.daemon

import ee.schimke.composeai.data.displayfilter.DisplayFilter
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DisplayFilterDataProducerTest {

  @get:Rule val temp = TemporaryFolder()

  @Test
  fun writesOnePngPerFilterAndManifestEnumeratingThem() {
    val rootDir = temp.newFolder("data")
    val pngFile = writeSolidPng(width = 4, height = 4, argb = 0xFFFF0000.toInt())

    val records =
      DisplayFilterDataProducer.writeArtifacts(
        rootDir = rootDir,
        previewId = "demo.SomePreview",
        pngFile = pngFile,
        filters = listOf(DisplayFilter.Grayscale, DisplayFilter.Invert),
      )

    val previewDir = rootDir.resolve("demo.SomePreview")
    assertEquals(2, records.size)
    val grayscaleRecord = records.first { it.filter == DisplayFilter.Grayscale }
    assertTrue(File(grayscaleRecord.path).isFile)
    assertEquals(previewDir, File(grayscaleRecord.path).parentFile)

    val manifest = previewDir.resolve(DisplayFilterDataProducer.FILE_VARIANTS)
    assertTrue("manifest at ${manifest.absolutePath}", manifest.isFile)
    val text = manifest.readText()
    assertTrue("manifest mentions grayscale: $text", text.contains("\"grayscale\""))
    assertTrue("manifest mentions invert: $text", text.contains("\"invert\""))
  }

  @Test
  fun emptyFilterListSkipsManifestAndPngsEntirely() {
    val rootDir = temp.newFolder("data")
    val pngFile = writeSolidPng(width = 2, height = 2, argb = 0xFF000000.toInt())

    val records =
      DisplayFilterDataProducer.writeArtifacts(
        rootDir = rootDir,
        previewId = "demo.Empty",
        pngFile = pngFile,
        filters = emptyList(),
      )

    assertEquals(emptyList<DisplayFilterArtifactRecord>(), records)
    assertTrue(rootDir.listFiles()?.toList().orEmpty().none { it.name == "demo.Empty" })
  }

  @Test
  fun filterFailureFallsBackToAnEmptyManifestWithoutThrowing() {
    val rootDir = temp.newFolder("data")
    val nonExistent = temp.newFile("missing.png").also { it.delete() }
    // Pipeline logs + skips; producer still writes an empty manifest.
    val records =
      DisplayFilterDataProducer.writeArtifacts(
        rootDir = rootDir,
        previewId = "demo.Broken",
        pngFile = nonExistent,
        filters = listOf(DisplayFilter.Grayscale),
      )
    assertEquals(emptyList<DisplayFilterArtifactRecord>(), records)
    val manifest = rootDir.resolve("demo.Broken").resolve(DisplayFilterDataProducer.FILE_VARIANTS)
    assertTrue(manifest.isFile)
    assertNotNull(manifest.readText())
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
