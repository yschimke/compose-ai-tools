package ee.schimke.composeai.data.displayfilter

import ee.schimke.composeai.data.render.extensions.CommonDataProducts
import ee.schimke.composeai.data.render.extensions.ExtensionContextData
import ee.schimke.composeai.data.render.extensions.ExtensionPostCaptureContext
import ee.schimke.composeai.data.render.extensions.RecordingDataProductStore
import ee.schimke.composeai.data.render.extensions.RenderImageArtifact
import ee.schimke.composeai.data.render.extensions.provides
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DisplayFilterExtensionTest {

  @get:Rule val temp = TemporaryFolder()

  @Test
  fun emitsOnePngPerEnabledFilterUnderTheConfiguredOutputDirectory() {
    val source = writeSolidPng(width = 4, height = 4, argb = 0xFFFF0000.toInt())
    val outputDir = temp.newFolder("renders")
    val store = RecordingDataProductStore()
    store.put(CommonDataProducts.ImageArtifact, RenderImageArtifact(path = source.absolutePath))

    val context =
      ExtensionPostCaptureContext(
        extensionId = DisplayFilterExtension().id,
        previewId = "demo.SomePreview",
        renderMode = "default",
        products = store.scopedFor(DisplayFilterExtension()),
        data =
          ExtensionContextData.of(
            DisplayFilterContextKeys.OutputDirectory provides outputDir,
            DisplayFilterContextKeys.Filters provides
              listOf(DisplayFilter.Grayscale, DisplayFilter.Invert),
          ),
      )

    DisplayFilterExtension().process(context)

    val emitted = store.require(DisplayFilterDataProducts.Variants).artifacts
    assertEquals(2, emitted.size)
    assertEquals(
      setOf(DisplayFilter.Grayscale, DisplayFilter.Invert),
      emitted.map { it.filter }.toSet(),
    )
    for (artifact in emitted) {
      val file = File(artifact.path)
      assertTrue("missing ${file.absolutePath}", file.isFile)
      assertEquals(outputDir, file.parentFile)
    }

    // Pure red (255,0,0) under grayscale -> 54,54,54; under invert -> 0,255,255.
    val grayPixel = readFirstPixel(emitted.first { it.filter == DisplayFilter.Grayscale }.path)
    assertEquals(0xFF363636.toInt(), grayPixel)
    val invertPixel = readFirstPixel(emitted.first { it.filter == DisplayFilter.Invert }.path)
    assertEquals(0xFF00FFFF.toInt(), invertPixel)
  }

  @Test
  fun emptyFilterListEmitsEmptyArtifactsAndDoesNotRequireOutputDirectory() {
    val source = writeSolidPng(width = 2, height = 2, argb = 0xFF112233.toInt())
    val store = RecordingDataProductStore()
    store.put(CommonDataProducts.ImageArtifact, RenderImageArtifact(path = source.absolutePath))

    val context =
      ExtensionPostCaptureContext(
        extensionId = DisplayFilterExtension().id,
        previewId = null,
        renderMode = null,
        products = store.scopedFor(DisplayFilterExtension()),
        data = ExtensionContextData.Empty,
      )

    DisplayFilterExtension().process(context)
    val variants = store.require(DisplayFilterDataProducts.Variants)
    assertTrue(variants.artifacts.isEmpty())
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

  private fun readFirstPixel(path: String): Int = ImageIO.read(File(path)).getRGB(0, 0)
}
