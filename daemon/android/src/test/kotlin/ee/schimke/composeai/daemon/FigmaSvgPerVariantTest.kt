package ee.schimke.composeai.daemon

import java.io.File
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The per-variant guarantee the wear-m3 SVG lane relies on (#2460): the always-on
 * `compose/figma-svg` export ([ComposeFigmaSvgExtension]) is a pure function of *this render's*
 * captured trees, so two renders of the **same preview function** under **different overrides**
 * emit **byte-different** SVGs.
 *
 * This is the mechanism that fixes the wear state-variant collapse. The baked lane keys one
 * `figma/<slug>.svg` per component slug, so every state/selection variant of a slug shared one
 * vector (button `disabled` == `filled`, checkbox `unchecked` == `checked`, switch `off` == `on`).
 * Routing wear through the Android daemon makes each variant its own render → its own SVG. Here we
 * drive the same engine directly via [PreviewManifestRouter] (no persistent daemon, no subprocess),
 * so it runs anywhere Robolectric can render — including a container that can't cold-start the
 * persistent serve daemon.
 *
 * `DarkAwareSquare` paints white in light mode and black in dark mode; the `uiMode` override is the
 * exact `renderNow.overrides` channel a state variant travels through, so the two SVGs differ in
 * their fill exactly as `disabled` vs `filled` would.
 *
 * The two SVGs are also dumped to `-Dcomposeai.test.figmaSvgDumpDir` (when set) so a human/agent can
 * eyeball the actual vectors, not just trust the byte assertion.
 */
class FigmaSvgPerVariantTest {

  @get:Rule val tempFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun `same preview under different overrides emits byte-different figma SVGs`() {
    val outputDir = tempFolder.newFolder("renders-figma-per-variant")
    System.setProperty(RenderEngine.OUTPUT_DIR_PROP, outputDir.absolutePath)
    System.setProperty("roborazzi.test.record", "true")
    // Two manifest ids for the SAME function, so each variant writes its own
    // `data/<id>/compose-figma.svg` instead of the second overwriting the first.
    val manifest =
      PreviewManifest(
        previews =
          listOf("dark-aware-light", "dark-aware-dark").map { id ->
            PreviewManifestEntry(
              id = id,
              className = "ee.schimke.composeai.daemon.RedFixturePreviewsKt",
              functionName = "DarkAwareSquare",
              widthPx = 48,
              heightPx = 48,
              density = 1.0f,
              outputBaseName = id,
            )
          }
      )
    val host = PreviewManifestRouter(manifest = manifest)
    host.start()
    try {
      host.submit(
        RenderRequest.Render(payload = "previewId=dark-aware-light;uiMode=light"),
        timeoutMs = 120_000,
      )
      host.submit(
        RenderRequest.Render(payload = "previewId=dark-aware-dark;uiMode=dark"),
        timeoutMs = 120_000,
      )

      val dataDir = outputDir.parentFile!!.resolve("data")
      val lightSvg = dataDir.resolve("dark-aware-light").resolve("compose-figma.svg")
      val darkSvg = dataDir.resolve("dark-aware-dark").resolve("compose-figma.svg")

      assertTrue("light figma SVG must be produced: ${lightSvg.absolutePath}", lightSvg.exists())
      assertTrue("dark figma SVG must be produced: ${darkSvg.absolutePath}", darkSvg.exists())
      val lightBytes = lightSvg.readBytes()
      val darkBytes = darkSvg.readBytes()
      assertTrue("light export must be a real SVG", String(lightBytes).contains("<svg"))
      assertTrue("dark export must be a real SVG", String(darkBytes).contains("<svg"))

      // Optional dump so the vectors are observable, not just asserted.
      (System.getProperty("composeai.test.figmaSvgDumpDir")
          ?: System.getenv("FIGMA_SVG_DUMP_DIR"))
        ?.let { dump ->
        File(dump).apply { mkdirs() }
        lightSvg.copyTo(File(dump, "dark-aware-light.svg"), overwrite = true)
        darkSvg.copyTo(File(dump, "dark-aware-dark.svg"), overwrite = true)
        System.err.println(
          "FIGMA-SVG-PER-VARIANT dumped light=${lightBytes.size}B dark=${darkBytes.size}B to $dump"
        )
      }

      // The point: a state variant is not a copy of the default. If this collapses to equal, the
      // engine stopped distinguishing overrides and the wear lane would re-share one vector.
      assertNotEquals(
        "state variants must render to distinct SVGs (this is the wear-m3 collapse guard)",
        lightBytes.toList(),
        darkBytes.toList(),
      )
    } finally {
      host.shutdown()
    }
  }
}
