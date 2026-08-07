package ee.schimke.composeai.daemon

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * End-to-end guard for the Material-icon reference path: a real render of
 * [MaterialIconRowPreview] — real `Icon`s, real `VectorPainter`s — must emit a `compose/figma-svg`
 * whose icon layers carry their canonical fonts.google.com identity and share one `<defs>` entry
 * per drawing.
 *
 * This is the half no model-level test can cover. `FigmaSvgMaterialIconRefTest` drives the emitter
 * from a synthetic payload with the name already set; the *name* itself comes from reflecting
 * `VectorPainter.vector.name` off live Compose, so only a real render proves that field still
 * exists and still holds `"Filled.Menu"` on the Compose version in use. If AndroidX ever renames or
 * drops it, the capture degrades silently (icons keep exporting, just unnamed) — and this test is
 * what makes that visible instead of invisible.
 *
 * The SVG is dumped to `-Dcomposeai.test.figmaSvgDumpDir` when set, matching
 * [FigmaSvgPerVariantTest], so the vector can be eyeballed rather than only asserted.
 */
class MaterialIconRefE2ETest {

  @get:Rule val tempFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun `rendered material icons export as named references sharing one definition`() {
    val outputDir = tempFolder.newFolder("renders-material-icon-ref")
    System.setProperty(RenderEngine.OUTPUT_DIR_PROP, outputDir.absolutePath)
    System.setProperty("roborazzi.test.record", "true")
    val manifest =
      PreviewManifest(
        previews =
          listOf(
            PreviewManifestEntry(
              id = "material-icon-row",
              className = "ee.schimke.composeai.daemon.RedFixturePreviewsKt",
              functionName = "MaterialIconRowPreview",
              widthPx = 160,
              heightPx = 48,
              density = 1.0f,
              outputBaseName = "material-icon-row",
            )
          )
      )
    val host = PreviewManifestRouter(manifest = manifest)
    host.start()
    try {
      host.submit(RenderRequest.Render(payload = "previewId=material-icon-row"), timeoutMs = 120_000)

      val svgFile =
        outputDir.parentFile!!.resolve("data").resolve("material-icon-row").resolve(
          "compose-figma.svg"
        )
      assertTrue("figma SVG must be produced: ${svgFile.absolutePath}", svgFile.exists())
      val svg = svgFile.readText()

      (System.getProperty("composeai.test.figmaSvgDumpDir") ?: System.getenv("FIGMA_SVG_DUMP_DIR"))
        ?.let { dump ->
          File(dump).apply { mkdirs() }
          svgFile.copyTo(File(dump, "material-icon-row.svg"), overwrite = true)
          // The render the SVG is supposed to reproduce, alongside it — the pair is what makes an
          // export regression eyeballable rather than only assertable.
          outputDir.walkTopDown().firstOrNull { it.name.endsWith(".png") }?.copyTo(
            File(dump, "material-icon-row.png"),
            overwrite = true,
          )
        }

      // The identity, recovered from the live painter — not from anything the test supplied.
      assertTrue(
        "Filled.Menu must resolve to its canonical name:\n$svg",
        svg.contains("""data-material-icon="menu""""),
      )
      assertTrue(
        "…and to the exact drawing on Google's icon CDN",
        svg.contains(
          """data-material-icon-url="https://fonts.gstatic.com/s/i/materialicons/menu/v1/24px.svg""""
        ),
      )
      // A different style must resolve to a different CDN family, not collapse onto `materialicons`.
      assertTrue(
        "Outlined.AccountCircle keeps its own style",
        svg.contains(
          "data-material-icon-url=\"https://fonts.gstatic.com/s/i/materialiconsoutlined/" +
            "account_circle/v1/24px.svg\""
        ),
      )
      assertTrue(
        "an AutoMirrored icon is flagged as one",
        svg.contains("""data-material-icon-auto-mirrored="true""""),
      )

      // Two `Icons.Filled.Menu` at the same tint share a single definition, referenced twice.
      assertEquals(
        "the repeated icon defines its geometry once",
        1,
        Regex("""<g id="material-icon-materialicons-menu">""").findAll(svg).count(),
      )
      assertEquals(
        "…and every icon placement is a reference",
        4,
        Regex("""<use href="#material-icon-""").findAll(svg).count(),
      )
      assertTrue(
        "references need their namespace declared",
        svg.contains("""xmlns:xlink="http://www.w3.org/1999/xlink""""),
      )
    } finally {
      host.shutdown()
    }
  }
}
