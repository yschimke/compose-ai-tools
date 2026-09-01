package ee.schimke.composeai.daemon

import ee.schimke.composeai.fonts.google.GoogleFontKey
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** End-to-end regression for the embedded Remote Compose font-family loss in issue #4935. */
class FigmaSvgRemoteComposeFontEmbedTest {

  @get:Rule val tempFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun `embedded remote compose svg keeps the resolved file font family`() {
    val outputDir = tempFolder.newFolder("renders")
    val cacheDir = tempFolder.newFolder("font-cache")
    val faceBytes = readFixtureFont()
    val embeddedFamily = checkNotNull(FigmaResourceFonts.familyName(faceBytes))
    for (weight in listOf(400, 450, 500)) {
      File(cacheDir, GoogleFontKey("Roboto Flex", weight, false).fileName()).writeBytes(faceBytes)
    }

    val properties =
      mapOf(
        RenderEngine.OUTPUT_DIR_PROP to outputDir.absolutePath,
        "roborazzi.test.record" to "true",
        "composeai.fonts.cacheDir" to cacheDir.absolutePath,
        "composeai.fonts.offline" to "true",
        "composeai.fonts.failOnFallback" to "false",
        "composeai.svg.embedFonts" to "true",
      )
    val restore = properties.keys.associateWith(System::getProperty)
    properties.forEach(System::setProperty)
    FigmaResourceFonts.clear()

    val previewId = "remote-compose-roboto-flex"
    val host =
      PreviewManifestRouter(
        PreviewManifest(
          previews =
            listOf(
              PreviewManifestEntry(
                id = previewId,
                className = "ee.schimke.composeai.daemon.RemoteComposeFontPreviewKt",
                functionName = "RemoteComposeRobotoFlexCard",
                widthPx = 240,
                heightPx = 180,
                density = 1.0f,
                outputBaseName = previewId,
              )
            )
        )
      )
    host.start()
    try {
      host.submit(RenderRequest.Render(payload = "previewId=$previewId"), timeoutMs = 120_000)

      val dataDir = outputDir.parentFile!!.resolve("data").resolve(previewId)
      val svgFile = dataDir.resolve(ComposeFigmaSvgDataProducer.FILE_SVG)
      assertTrue("figma SVG must be produced: ${svgFile.absolutePath}", svgFile.exists())
      val svg = svgFile.readText()
      val fontLines =
        svg.lines().filter { it.contains("<text") || it.contains("font-face") }.joinToString("\n")

      assertTrue("the resolved file face must be embedded:\n$fontLines", svg.contains("@font-face"))
      assertTrue(
        "the @font-face must use '$embeddedFamily':\n$fontLines",
        svg.contains("font-family:'$embeddedFamily'"),
      )
      assertTrue(
        "RemoteText must use '$embeddedFamily':\n$fontLines",
        svg.contains("font-family=\"$embeddedFamily"),
      )
      assertFalse("the export must not switch to tofu", svg.contains("ComposeAI Missing Font"))
      assertFalse(
        "a fully resolved Remote Compose export must not write font warnings",
        dataDir.resolve(ComposeFigmaSvgDataProducer.FILE_FONT_WARNINGS).exists(),
      )
    } finally {
      host.shutdown()
      FigmaResourceFonts.clear()
      restore.forEach { (key, value) ->
        if (value == null) System.clearProperty(key) else System.setProperty(key, value)
      }
    }
  }

  private fun readFixtureFont(): ByteArray =
    checkNotNull(javaClass.getResourceAsStream("/composeai-test-fonts/warm-cache-face.ttf")).use {
      it.readBytes()
    }
}
