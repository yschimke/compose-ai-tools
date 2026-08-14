package ee.schimke.composeai.daemon

import ee.schimke.composeai.data.layoutinspector.ComposeSemanticsNode
import ee.schimke.composeai.data.layoutinspector.ComposeSemanticsPayload
import java.io.File
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * End-to-end guard for issue #3024: on a `fontScale != 1` render the `compose/figma-svg` export
 * must size text at the px the **render resolved**, not at `sp × density × fontScale`.
 *
 * Compose resolves `sp` through the platform `FontScaleConverter` on API 34+, whose curve is
 * non-linear in the font scale: body sizes take the full multiplier, display sizes flatten toward
 * identity. The exporter's linear conversion therefore matched the body and over-sized the heading
 * — by 50% on JetNews's `fontScale = 1.5` article title — so the captured line breaks stopped
 * fitting the bounds they were measured in and the last line overflowed its card. The seam tests
 * (`FigmaSvgResolvedTextMetricsTest`) pin the export's preference for the captured px on a
 * synthetic payload; this drives a real scaled render so a regression in the *capture* (which is
 * what has to read the render's own `Density`) can't pass unnoticed.
 *
 * The assertions are written to hold whether or not the sandbox's API level applies the curve: what
 * they pin is that the exported size is the one the capture resolved, and never larger than the
 * linear prediction. On a converter-applying platform those differ and the old behaviour fails
 * here; on one without a converter they coincide and the plumbing is still covered.
 */
class ScaledTypographyExportTest {

  @get:Rule val tempFolder: TemporaryFolder = TemporaryFolder()

  private val json = Json { ignoreUnknownKeys = true }

  @Test
  fun `scaled render exports the resolved text size, not sp times density times fontScale`() {
    val outputDir = tempFolder.newFolder("renders")
    val previewId = "scaled-heading-paragraph"
    val density = 2.625f
    val fontScale = 1.5f

    val restore = System.getProperty(RenderEngine.OUTPUT_DIR_PROP)
    System.setProperty(RenderEngine.OUTPUT_DIR_PROP, outputDir.absolutePath)
    System.setProperty("roborazzi.test.record", "true")
    val manifest =
      PreviewManifest(
        previews =
          listOf(
            PreviewManifestEntry(
              id = previewId,
              className = "ee.schimke.composeai.daemon.ScaledTypographyPreviewKt",
              functionName = "ScaledHeadingParagraph",
              widthPx = (PARAGRAPH_WIDTH_DP * density).toInt(),
              heightPx = 600,
              density = density,
              outputBaseName = previewId,
            )
          )
      )
    val host = PreviewManifestRouter(manifest = manifest)
    host.start()
    try {
      host.submit(
        RenderRequest.Render(payload = "previewId=$previewId;fontScale=$fontScale"),
        timeoutMs = 120_000,
      )

      val dataDir = outputDir.parentFile!!.resolve("data").resolve(previewId)
      val svg = dataDir.resolve("compose-figma.svg").also { assertTrue(it.exists()) }.readText()
      val semantics = readSemantics(dataDir)

      val heading = findText(semantics.root) { it.startsWith("From Java") }
      val body = findText(semantics.root) { it.startsWith("Learn how") }

      // The capture resolves both sizes through the render's own Density, so they are present and
      // are what the export must use.
      val headingPx = heading.typography?.fontSizePx
      val bodyPx = body.typography?.fontSizePx
      assertNotNull("capture must carry the heading's resolved px", headingPx)
      assertNotNull("capture must carry the body's resolved px", bodyPx)

      // Never larger than the linear prediction — the converter only ever flattens the scale, so a
      // size above this means the exporter recomputed it the old way.
      val linearHeading = HEADING_SP * density * fontScale
      assertTrue(
        "resolved heading px ($headingPx) must not exceed the linear $linearHeading",
        headingPx!! <= linearHeading + 0.5,
      )

      // The export emits exactly those px.
      assertEquals(
        "the <text> font-size must be the resolved px",
        headingPx,
        fontSizeNear(svg, headingPx),
        0.01,
      )
      assertEquals(
        "the <text> font-size must be the resolved px",
        bodyPx!!,
        fontSizeNear(svg, bodyPx),
        0.01,
      )

      // Both runs wrap at this width, and every wrapped line carries the width the render measured
      // it at — so the viewer lays each line out to the render's own advances rather than its own.
      val lineWidths =
        Regex("""textLength="([0-9]+)"""").findAll(svg).map { it.groupValues[1].toInt() }.toList()
      assertTrue("wrapped lines must carry their measured width", lineWidths.isNotEmpty())
      assertTrue(
        "lengths pin spacing only, never the glyphs",
        svg.contains("""lengthAdjust="spacing""""),
      )
      // The regression in one line: no line may be wider than the box it was measured in.
      val available = PARAGRAPH_WIDTH_DP * density
      assertTrue(
        "no line may exceed the paragraph width ($available px), got $lineWidths",
        lineWidths.all { it <= available },
      )
    } finally {
      host.shutdown()
      if (restore == null) System.clearProperty(RenderEngine.OUTPUT_DIR_PROP)
      else System.setProperty(RenderEngine.OUTPUT_DIR_PROP, restore)
    }
  }

  private fun readSemantics(dataDir: File): ComposeSemanticsPayload {
    val file = dataDir.resolve("compose-semantics.json")
    assertTrue("semantics must be produced: ${file.absolutePath}", file.exists())
    return json.decodeFromString(ComposeSemanticsPayload.serializer(), file.readText())
  }

  private fun findText(
    node: ComposeSemanticsNode,
    match: (String) -> Boolean,
  ): ComposeSemanticsNode {
    fun walk(n: ComposeSemanticsNode): ComposeSemanticsNode? {
      if (n.text?.let(match) == true && n.typography != null) return n
      n.children.forEach { child ->
        walk(child)?.let {
          return it
        }
      }
      return null
    }
    return checkNotNull(walk(node)) { "no matching text node in the captured semantics" }
  }

  /** The emitted `font-size` closest to [expected], so the two runs are told apart robustly. */
  private fun fontSizeNear(svg: String, expected: Double): Double =
    Regex("""<text\b[^>]*\bfont-size="([0-9.]+)"""")
      .findAll(svg)
      .map { it.groupValues[1].toDouble() }
      .minByOrNull { kotlin.math.abs(it - expected) } ?: Double.NaN
}
