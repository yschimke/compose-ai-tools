package ee.schimke.composeai.daemon

import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * End-to-end guard for the branded-downloadable-font capture (#2730): a preview whose text draws in
 * `Font(GoogleFont("Orbitron"), …)` must export a `compose/figma-svg` whose `<text>` names
 * **Orbitron**, not the Roboto default.
 *
 * The unit coverage for this stops at the two ends — [googleFontFamilyName] against a stand-in, and
 * `ComposeFigmaSvgDataProducer` against a hand-written payload that already says `"Orbitron"`.
 * Neither runs a real render, so the middle (a real `GoogleFontImpl` reaching the semantics
 * typography extraction) was never exercised, and the published meshcore stickers kept shipping
 * `font-family="Roboto, sans-serif"` over a PNG drawn in Orbitron. This closes that gap by driving
 * the actual engine through [PreviewManifestRouter], the same way [FigmaSvgPerVariantTest] does.
 *
 * The face itself never resolves here (no GMS provider in the sandbox, no warmed cache), so the
 * render falls back to the platform typeface — that's why `failOnFallback` is switched off. The
 * *pixels* are not what this asserts; the captured family name is, and that is read off the
 * `FontFamily` the composition declared, independent of whether the typeface loaded.
 */
class FigmaSvgDownloadableFontFamilyTest {

  @get:Rule val tempFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun `figma svg names the branded downloadable family instead of collapsing to Roboto`() {
    val outputDir = tempFolder.newFolder("renders-figma-downloadable-font")
    System.setProperty(RenderEngine.OUTPUT_DIR_PROP, outputDir.absolutePath)
    System.setProperty("roborazzi.test.record", "true")
    // The face can't resolve in the sandbox; a fallback must not fail the preview here.
    val priorFailOnFallback = System.getProperty("composeai.fonts.failOnFallback")
    System.setProperty("composeai.fonts.failOnFallback", "false")
    // Keep the export vector-only: embedding would try to fetch Orbitron's WOFF2 from Google Fonts,
    // which a sandboxed/offline test must not depend on. The `<text>` family name — the thing under
    // test — is emitted either way.
    val priorEmbedFonts = System.getProperty("composeai.svg.embedFonts")
    System.setProperty("composeai.svg.embedFonts", "false")

    val manifest =
      PreviewManifest(
        previews =
          listOf(
            PreviewManifestEntry(
              id = "branded-downloadable",
              className = "ee.schimke.composeai.daemon.BrandedDownloadableFontPreviewKt",
              functionName = "BrandedDownloadableText",
              widthPx = 200,
              heightPx = 64,
              density = 1.0f,
              outputBaseName = "branded-downloadable",
            )
          )
      )
    val host = PreviewManifestRouter(manifest = manifest)
    host.start()
    try {
      host.submit(
        RenderRequest.Render(payload = "previewId=branded-downloadable"),
        timeoutMs = 120_000,
      )

      val svgFile =
        outputDir
          .parentFile!!
          .resolve("data")
          .resolve("branded-downloadable")
          .resolve("compose-figma.svg")
      assertTrue("figma SVG must be produced: ${svgFile.absolutePath}", svgFile.exists())
      val svg = svgFile.readText()
      assertTrue("export must carry the text", svg.contains("MeshCore"))
      assertTrue(
        "the <text> must name the branded downloadable family, got:\n" +
          svg.lines().filter { it.contains("<text") }.joinToString("\n"),
        svg.contains("font-family=\"Orbitron"),
      )
    } finally {
      host.shutdown()
      restore("composeai.fonts.failOnFallback", priorFailOnFallback)
      restore("composeai.svg.embedFonts", priorEmbedFonts)
    }
  }

  private fun restore(key: String, value: String?) {
    if (value == null) System.clearProperty(key) else System.setProperty(key, value)
  }
}
