package ee.schimke.composeai.daemon

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ComposeFigmaSvgDataProductRegistryTest {
  private lateinit var rootDir: File

  @Before
  fun setUp() {
    rootDir = Files.createTempDirectory("compose-figma-svg-product-test").toFile()
  }

  @After
  fun tearDown() {
    rootDir.deleteRecursively()
  }

  @Test
  fun `protocol preview id resolves fetch and attachments for latest concrete output`() {
    val protocolPreviewId = "com.example.SharedPreview"
    val lightSvg = writeSvg("shared-light", "light")
    val darkSvg = writeSvg("shared-dark", "dark")
    val registry = ComposeFigmaSvgDataProductRegistry(rootDir)

    registry.onRender(
      protocolPreviewId,
      RenderResult(
        id = 1,
        classLoaderHashCode = 0,
        classLoaderName = "test",
        outputBaseName = "shared-light",
      ),
    )
    assertEquals(lightSvg.absolutePath, fetchPath(registry, protocolPreviewId))
    assertEquals(
      lightSvg.absolutePath,
      registry
        .attachmentsFor(protocolPreviewId, setOf(ComposeFigmaSvgDataProducer.KIND))
        .single()
        .path,
    )

    registry.onRender(
      protocolPreviewId,
      RenderResult(
        id = 2,
        classLoaderHashCode = 0,
        classLoaderName = "test",
        outputBaseName = "shared-dark",
      ),
    )
    assertEquals(darkSvg.absolutePath, fetchPath(registry, protocolPreviewId))
    assertEquals(
      darkSvg.absolutePath,
      registry
        .attachmentsFor(protocolPreviewId, setOf(ComposeFigmaSvgDataProducer.KIND))
        .single()
        .path,
    )
    assertTrue("the prior variant artifact must remain isolated", lightSvg.exists())
  }

  private fun writeSvg(outputBaseName: String, marker: String): File =
    rootDir
      .resolve(outputBaseName)
      .also { it.mkdirs() }
      .resolve(ComposeFigmaSvgDataProducer.FILE_SVG)
      .also { it.writeText("<svg data-variant=\"$marker\"/>") }

  private fun fetchPath(registry: ComposeFigmaSvgDataProductRegistry, previewId: String): String? {
    val outcome =
      registry.fetch(
        previewId = previewId,
        kind = ComposeFigmaSvgDataProducer.KIND,
        params = null,
        inline = false,
      )
    assertTrue(outcome is DataProductRegistry.Outcome.Ok)
    return (outcome as DataProductRegistry.Outcome.Ok).result.path
  }
}
