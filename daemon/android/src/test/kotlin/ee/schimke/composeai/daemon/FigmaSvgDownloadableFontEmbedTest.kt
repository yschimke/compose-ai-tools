package ee.schimke.composeai.daemon

import ee.schimke.composeai.fonts.google.GoogleFontKey
import java.io.ByteArrayInputStream
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * End-to-end guard for the downloadable-`GoogleFont` embed path (issue #2906), driving the real
 * `androidx.compose.ui.text.googlefonts.Font(GoogleFont(...))` provider through the daemon render
 * engine with a warmed downloadable-font cache — the setup catalog generation uses.
 *
 * The gap this closes: every prior test for the embed either hand-registered the recovered file on
 * [FigmaResourceFonts] ([FigmaFontEmbedTest]) or left the cache cold and only checked the family
 * *name* ([FigmaSvgDownloadableFontFamilyTest]). None drove a real `GoogleFont` render whose cache
 * held the resolved face, so the render-side recovery ([FontResolverRecorder]'s
 * `recoverDownloadableFont`) was never actually exercised — which is why the `compose/figma-svg`
 * export kept shipping JetLagged's Lato heading as `font-family="Lato, sans-serif"` with no
 * `@font-face` despite the seam-level tests all passing.
 *
 * The JetLagged shape reproduced by [JetLaggedHeadingText]: a single `Font(GoogleFont("Lato"))`
 * declared at its default weight, drawn at a heavier heading weight the family never declares a face
 * for, so the export asks the recovery for `("Lato", <heading weight>)` while the downloadable cache
 * only holds the face at another weight — the mismatch that made the old exact-weight lookup miss
 * and drop the `@font-face` entirely.
 *
 * The warmed face is a distinctive stand-in TTF (Orbitron — the assertions read its real family out
 * of the bytes exactly as the export does, so the test never depends on which stand-in warms it).
 * It is warmed at a weight *offset* from both the requested and heading weights, on purpose: the
 * daemon's Robolectric tier (SDK 35 / JDK 17) can't build a file-backed downloadable `Typeface`
 * (that only works on the catalog render tier, SDK 36 / JDK 21), so warming the exact requested
 * weight would crash the sandbox render rather than exercise the export. Warming an offset weight
 * lets the provider miss and fall back to the platform face for the *pixels* while the resolved TTF
 * stays on disk for the export's nearest-weight recovery — the code path #2906 fixes. The heading
 * weight ([FontWeight.Medium]) is kept below Compose's fake-bold threshold (600) so the fallback
 * typeface never triggers the same sandbox synthesis NPE. Pixel fidelity of the real downloadable
 * face at its literal weight is covered by the catalog render pipeline.
 */
class FigmaSvgDownloadableFontEmbedTest {

  @get:Rule val tempFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun `figma svg embeds the downloadable face recovered for a non-default heading weight`() {
    val outputDir = tempFolder.newFolder("renders")
    val cacheDir = tempFolder.newFolder("font-cache")
    // Warm the downloadable cache with a real face keyed to a weight the export's requested heading
    // weight (500) has to *match by nearness*, not exactly — the render-side recovery's job (issue
    // #2906). The stand-in bytes carry a real family name the export reads back and names `<text>`.
    val faceBytes = readFixtureFont()
    File(cacheDir, GoogleFontKey("Lato", 700, false).fileName()).writeBytes(faceBytes)
    val embeddedFamily = awtFamilyOf(faceBytes)

    val priors =
      mapOf(
        RenderEngine.OUTPUT_DIR_PROP to outputDir.absolutePath,
        "roborazzi.test.record" to "true",
        "composeai.fonts.cacheDir" to cacheDir.absolutePath,
        // Closed egress + a warm cache is the catalog-render case from the issue: the export's WOFF2
        // resolver must not reach the network, so the only way an `@font-face` can appear is the
        // render-side recovery embedding the cached TTF.
        "composeai.fonts.offline" to "true",
        // The provider requests the default face weight, which isn't the warmed 700, so it falls
        // back for the pixels (the sandbox can't build the file-backed face — see the class KDoc).
        // Keep that fallback a warning, not a render failure, so the assertion is on the SVG.
        "composeai.fonts.failOnFallback" to "false",
        "composeai.svg.embedFonts" to "true",
      )
    val restore = priors.keys.associateWith { System.getProperty(it) }
    priors.forEach { (k, v) -> System.setProperty(k, v) }
    // The registry is process-wide by design; clear it so a sibling test's "Lato" can't stand in for
    // the registration this test is meant to prove happens.
    FigmaResourceFonts.clear()

    val previewId = "jetlagged-lato-heading"
    val manifest =
      PreviewManifest(
        previews =
          listOf(
            PreviewManifestEntry(
              id = previewId,
              className = "ee.schimke.composeai.daemon.BrandedDownloadableFontPreviewKt",
              functionName = "JetLaggedHeadingText",
              widthPx = 240,
              heightPx = 64,
              density = 1.0f,
              outputBaseName = previewId,
            )
          )
      )
    val host = PreviewManifestRouter(manifest = manifest)
    host.start()
    try {
      host.submit(RenderRequest.Render(payload = "previewId=$previewId"), timeoutMs = 120_000)

      val previewDataDir = outputDir.parentFile!!.resolve("data").resolve(previewId)
      val svgFile = previewDataDir.resolve("compose-figma.svg")
      assertTrue("figma SVG must be produced: ${svgFile.absolutePath}", svgFile.exists())
      val svg = svgFile.readText()

      assertTrue("export must carry the heading text", svg.contains("AVE TIME IN BED"))
      // The heart of #2906: the resolved downloadable face is embedded, not dropped for a bare
      // sans-serif fallback with no `@font-face`.
      assertTrue(
        "the SVG must embed an @font-face for the downloadable face, got:\n" + fontLinesOf(svg),
        svg.contains("@font-face"),
      )
      // A `format('truetype')` src is the render's own cached TTF bytes — the file-path embed path,
      // not a WOFF2 fetched by name (which offline mode forbids anyway).
      assertTrue(
        "the embedded face must be the render's cached TTF (format('truetype')), got:\n" +
          fontLinesOf(svg),
        svg.contains("format('truetype')"),
      )
      // The `<text>` and the `@font-face` name the real face read out of the cached bytes, so a
      // browser resolves the embedded face instead of collapsing to the platform sans-serif.
      assertTrue(
        "the @font-face must name the embedded family '$embeddedFamily', got:\n" + fontLinesOf(svg),
        svg.contains("font-family:'$embeddedFamily'"),
      )
      assertTrue(
        "the <text> must name the embedded family '$embeddedFamily', got:\n" + fontLinesOf(svg),
        svg.contains("font-family=\"$embeddedFamily"),
      )
      // A healthy embed leaves no font-warnings sidecar behind (that is the degraded-export marker).
      assertFalse(
        "no font-warnings sidecar should be written for a fully-embedded export",
        previewDataDir.resolve(ComposeFigmaSvgDataProducer.FILE_FONT_WARNINGS).exists(),
      )
    } finally {
      host.shutdown()
      FigmaResourceFonts.clear()
      restore.forEach { (k, v) ->
        if (v == null) System.clearProperty(k) else System.setProperty(k, v)
      }
    }
  }

  /** The distinctive stand-in downloadable face bundled as a test resource. */
  private fun readFixtureFont(): ByteArray {
    val url =
      checkNotNull(javaClass.getResource("/fonts/warm-cache-face.ttf")) {
        "missing test font resource /fonts/warm-cache-face.ttf"
      }
    return url.openStream().use { it.readBytes() }
  }

  /** The font's real family name, read exactly the way the export reads it. */
  private fun awtFamilyOf(bytes: ByteArray): String =
    java.awt.Font.createFont(java.awt.Font.TRUETYPE_FONT, ByteArrayInputStream(bytes)).family

  private fun fontLinesOf(svg: String): String =
    svg.lines().filter { it.contains("<text") || it.contains("font-face") }.joinToString("\n")
}
