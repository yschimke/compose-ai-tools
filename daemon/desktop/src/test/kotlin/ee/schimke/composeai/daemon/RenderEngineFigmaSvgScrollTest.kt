package ee.schimke.composeai.daemon

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Experiment for the **mobile figma-svg scroll** approach (scrolling screens in SVG).
 *
 * A `LazyColumn` is virtualised: at a normal viewport height only the on-screen rows (plus
 * LazyList's small prefetch) are composed, so the `compose/figma-svg` export today carries only
 * those rows. This test proves the "expand the device vertically" hypothesis for mobile — render
 * the same scrolling preview at an *expanded* (tall) viewport and every row lays out, so the
 * layered SVG carries the full list without any scroll-and-stitch pass.
 *
 * The fixture ([LazyColumnListPreview]) is a Material 3 `Scaffold` (pinned top app bar + bottom
 * navigation bar) around a 30-row `LazyColumn`. We render it through the real [RenderEngine] and
 * count the `Row N` text layers in the emitted `compose-figma.svg`:
 * - a short 200×520 viewport captures only a handful of rows;
 * - a tall 200×4000 viewport captures all 30, bookended by the top/bottom bars.
 */
class RenderEngineFigmaSvgScrollTest {

  @get:Rule val tempFolder: TemporaryFolder = TemporaryFolder()

  private data class SvgRender(val rows: Int, val svg: String)

  private fun render(heightPx: Int, baseName: String): SvgRender {
    val outputDir = tempFolder.newFolder("renders-$baseName")
    val dataDir = tempFolder.newFolder("data-$baseName")
    val engine = RenderEngine(outputDir = outputDir, dataDir = dataDir)
    val host = DesktopHost(engine = engine)
    host.start()
    try {
      val request =
        RenderRequest.Render(
          payload =
            "className=ee.schimke.composeai.daemon.RedFixturePreviewsKt;" +
              "functionName=LazyColumnListPreview;" +
              "widthPx=200;heightPx=$heightPx;density=1.0;" +
              "showBackground=true;" +
              "outputBaseName=$baseName"
        )
      host.submit(request, timeoutMs = 120_000)
    } finally {
      host.shutdown()
    }
    val svg = File(File(dataDir, baseName), "compose-figma.svg")
    assertTrue("figma SVG must be produced: ${svg.absolutePath}", svg.exists())
    // Best-effort: persist a copy outside the auto-cleaned TemporaryFolder so the produced SVG can
    // be inspected / rasterised for visual evidence after the run. Never fails the test.
    runCatching {
      val keep = File("build/figma-svg-scroll-experiment").also { it.mkdirs() }
      svg.copyTo(File(keep, "$baseName.svg"), overwrite = true)
    }
    val text = svg.readText()
    // Count distinct row indices so a row that appears in more than one layer (text + wrapper)
    // isn't double-counted.
    val rows = Regex("Row (\\d+)").findAll(text).map { it.groupValues[1].toInt() }.toSortedSet()
    System.err.println("[$baseName] heightPx=$heightPx rows=${rows.size} -> $rows")
    return SvgRender(rows.size, text)
  }

  @Test
  fun tallViewportCapturesAllRowsShortViewportCapturesFew() {
    val short = render(heightPx = 520, baseName = "scroll-short")
    val tall = render(heightPx = 4000, baseName = "scroll-tall")

    assertTrue(
      "short viewport should capture only a subset of the 30 rows (got ${short.rows})",
      short.rows in 1..20,
    )
    assertTrue("tall viewport should capture all 30 rows (got ${tall.rows})", tall.rows == 30)
    assertTrue(
      "tall viewport must capture strictly more rows than the short one " +
        "(short=${short.rows}, tall=${tall.rows})",
      tall.rows > short.rows,
    )
  }

  @Test
  fun sizedToContentRendersCleanFullList() {
    // Prototype the feature's sizing step: render tall so every item composes, measure the content
    // bottom from the emitted SVG, then re-render sized-to-content so the trailing background band
    // (and the gap the Scaffold leaves between the last row and the pinned bottom bar) is gone.
    val tall = render(heightPx = 4000, baseName = "scroll-measure")
    val lastRowBaseline = lastRowBaselinePx(tall.svg)
    // Add margin for the last row's descent/padding plus the pinned bottom navigation bar, so the
    // sized frame tucks the bottom bar directly under the last row. (The real feature reads the
    // bottom-bar height from the layout tree; here a generous constant keeps the evidence clean.)
    val sizedHeight = lastRowBaseline + 130
    System.err.println("measured lastRowBaseline=$lastRowBaseline -> sizedHeight=$sizedHeight")
    val sized = render(heightPx = sizedHeight, baseName = "scroll-sized")
    assertTrue(
      "sized-to-content render must still carry all 30 rows (got ${sized.rows})",
      sized.rows == 30,
    )
  }

  /**
   * The baseline y of the last `Row N` `<text>` in the SVG. Measuring the row text specifically
   * (rather than the max rect bottom) avoids picking up the Scaffold's bottom navigation bar, which
   * is pinned to the bottom of the over-tall probe frame.
   */
  private fun lastRowBaselinePx(svg: String): Int =
    Regex("<text[^>]*\\by=\"([0-9.]+)\"[^>]*>Row \\d+")
      .findAll(svg)
      .map { it.groupValues[1].toDouble() }
      .maxOrNull()
      ?.toInt() ?: error("no Row text found in SVG")
}
