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
 * for. The downloadable provider is requested at — and the cache holds — the *declared face* weight,
 * while the export's `<text>` asks about the *heading* weight, so the recovery has to bridge the two
 * by the matched face rather than by reconstructing the heading weight's filename. The old
 * exact-weight lookup couldn't, so it dropped the `@font-face` entirely.
 *
 * The warmed face is a distinctive stand-in TTF (Orbitron — the assertions read its real family out
 * of the bytes exactly as the export does, so the test never depends on which stand-in warms it). It
 * is warmed at the declared face weight the provider actually requests and caches, so the recovery
 * embeds the exact face the render resolved — never a nearby weight the shared cache happens to hold
 * but this render never drew.
 *
 * This test cannot be executed in the daemon's Robolectric unit tier (SDK 35 / JDK 17): building a
 * file-backed downloadable `Typeface` there NPEs, and the pre-existing sibling
 * [FigmaSvgDownloadableFontFamilyTest] fails identically in the same sandbox — a limitation of that
 * tier's Robolectric graphics, not of the export. It renders on the catalog tier (SDK 36 / JDK 21).
 * The heading weight ([FontWeight.Medium]) is kept below Compose's fake-bold threshold (600) so the
 * resolved face is drawn straight, matching what the `@font-face` embeds.
 */
class FigmaSvgDownloadableFontEmbedTest {

  @get:Rule val tempFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun `figma svg embeds the downloadable face recovered for a non-default heading weight`() {
    val outputDir = tempFolder.newFolder("renders")
    val cacheDir = tempFolder.newFolder("font-cache")
    // Warm the downloadable cache at the *declared face* weight (400) the provider requests and
    // caches — the face the render resolves. The heading draws at 500, so the export asks about 500
    // and the recovery has to bridge it to this 400 file via the matched face (issue #2906). The
    // stand-in bytes carry a real family name the export reads back and names `<text>` after.
    val faceBytes = readFixtureFont()
    File(cacheDir, GoogleFontKey("Lato", 400, false).fileName()).writeBytes(faceBytes)
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
        // On the render tier the warmed face resolves cleanly, so no fallback is expected; keep any
        // stray fallback a warning rather than a render failure so the assertion stays on the SVG.
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

  /**
   * The distinctive stand-in downloadable face bundled as a test resource.
   *
   * Deliberately **not** under `/fonts/` on the test classpath. Robolectric's
   * `DefaultNativeRuntimeLoader` extracts the native runtime's system fonts from the `fonts`
   * resource *directory*, resolved through the classloader — so the first `fonts/` root on the
   * classpath wins outright. A module's own `src/test/resources/fonts/` sorts ahead of
   * `nativeruntime-dist-compat`, which shadowed `fonts/fonts.xml` and the ~200 system faces with
   * this single TTF. `Typeface.loadPreinstalledSystemFontMap()` then built a font map with no
   * `sans-serif` entry and `setSystemFontMap` NPE'd on the null family, taking down *every*
   * sandbox bootstrap in this module (see #3086). Keep test font fixtures out of `/fonts/`.
   */
  private fun readFixtureFont(): ByteArray {
    val url =
      checkNotNull(javaClass.getResource("/composeai-test-fonts/warm-cache-face.ttf")) {
        "missing test font resource /composeai-test-fonts/warm-cache-face.ttf"
      }
    return url.openStream().use { it.readBytes() }
  }

  /** The font's real family name, read exactly the way the export reads it. */
  private fun awtFamilyOf(bytes: ByteArray): String =
    java.awt.Font.createFont(java.awt.Font.TRUETYPE_FONT, ByteArrayInputStream(bytes)).family

  private fun fontLinesOf(svg: String): String =
    svg.lines().filter { it.contains("<text") || it.contains("font-face") }.joinToString("\n")
}
