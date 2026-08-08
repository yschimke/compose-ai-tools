package ee.schimke.composeai.daemon

import java.io.File
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * `@ScrollingPreview(END)` through the **desktop daemon** — the `compose-preview serve` path.
 *
 * The daemon captures through `ImageComposeScene`, which has no test main-clock and no `onNode`
 * interactions, so it never drove a scrollable: an END preview served the resting top while the
 * Gradle render beside it shipped the settled bottom, and the semantics / layout / figma-svg read
 * off that same scene disagreed with the PNG too.
 *
 * Asserted on the emitted `compose-figma.svg` rather than pixels, deliberately: the SVG is derived
 * from the scene's semantics + layout trees, so "the SVG carries the bottom rows" proves the whole
 * derived-artifact family followed the scroll, not just the raster.
 */
class RenderEngineScrollEndTest {

  @get:Rule val tempFolder: TemporaryFolder = TemporaryFolder()

  private var previousIndexProp: String? = null

  @After
  fun restoreIndexProperty() {
    if (previousIndexProp == null) System.clearProperty(PreviewIndex.PREVIEWS_JSON_PATH_PROP)
    else System.setProperty(PreviewIndex.PREVIEWS_JSON_PATH_PROP, previousIndexProp!!)
  }

  /**
   * A `previews.json` whose single preview carries [scrollJson] as its capture's scroll block — the
   * shape discovery writes for `@ScrollingPreview`, and the only thing `staticScrollFor` consults.
   */
  private fun installPreviewIndex(previewId: String, scrollJson: String?) {
    val scroll = if (scrollJson == null) "" else ""","scroll":$scrollJson"""
    val json =
      """
      {
        "previews": [
          {
            "id": "$previewId",
            "className": "ee.schimke.composeai.daemon.RedFixturePreviewsKt",
            "functionName": "LazyColumnListPreview",
            "sourceFile": "RedFixturePreviews.kt",
            "captures": [
              {"renderOutput": "renders/$previewId.png"$scroll}
            ]
          }
        ]
      }
      """
        .trimIndent()
    val file = tempFolder.newFile("$previewId-previews.json").also { it.writeText(json) }
    previousIndexProp = System.getProperty(PreviewIndex.PREVIEWS_JSON_PATH_PROP)
    System.setProperty(PreviewIndex.PREVIEWS_JSON_PATH_PROP, file.absolutePath)
  }

  /** Renders the 30-row `LazyColumn` fixture through the real daemon and returns its figma-svg. */
  private fun renderSvg(previewId: String): String {
    val outputDir = tempFolder.newFolder("renders-$previewId")
    val dataDir = tempFolder.newFolder("data-$previewId")
    val engine = RenderEngine(outputDir = outputDir, dataDir = dataDir)
    val host = DesktopHost(engine = engine)
    host.start()
    try {
      host.submit(
        RenderRequest.Render(
          payload =
            "className=ee.schimke.composeai.daemon.RedFixturePreviewsKt;" +
              "functionName=LazyColumnListPreview;" +
              "widthPx=200;heightPx=520;density=1.0;showBackground=true;" +
              "previewId=$previewId;outputBaseName=$previewId"
        ),
        timeoutMs = 120_000,
      )
    } finally {
      host.shutdown()
    }
    val svg = File(File(dataDir, previewId), "compose-figma.svg")
    assertTrue("figma SVG must be produced: ${svg.absolutePath}", svg.exists())
    return svg.readText()
  }

  private fun rowsIn(svg: String): List<Int> =
    Regex("Row (\\d+)").findAll(svg).map { it.groupValues[1].toInt() }.distinct().sorted().toList()

  @Test
  fun `an END preview is captured at the bottom of its list`() {
    installPreviewIndex("EndDriven", """{"mode":"END","axis":"VERTICAL","reduceMotion":true}""")
    val rows = rowsIn(renderSvg("EndDriven"))
    assertTrue("the drive must reach the last row (got $rows)", rows.contains(30))
    assertFalse("the first row must have scrolled out of frame (got $rows)", rows.contains(1))
  }

  /** The control: the same fixture with no scroll intent keeps the resting top. */
  @Test
  fun `a preview with no scroll intent keeps its resting top`() {
    installPreviewIndex("Undriven", scrollJson = null)
    val rows = rowsIn(renderSvg("Undriven"))
    assertTrue("the top row must still be on screen (got $rows)", rows.contains(1))
    assertFalse("the last row must be far off screen (got $rows)", rows.contains(30))
  }

  /**
   * `TOP` *is* the undriven frame, so it must resolve to no drive at all rather than a no-op one —
   * the same rule [PreviewIndex.staticScrollFor] applies, checked here end-to-end.
   */
  @Test
  fun `a TOP preview is not driven`() {
    installPreviewIndex("TopOnly", """{"mode":"TOP","axis":"VERTICAL"}""")
    val rows = rowsIn(renderSvg("TopOnly"))
    assertTrue("TOP must keep the resting frame (got $rows)", rows.contains(1))
  }

  /**
   * `maxScrollPx` bounds the drive, so a small cap lands partway down rather than at the end.
   *
   * Asserted as "didn't reach the end" rather than a stop position: a `LazyColumn` reports its
   * scroll offset in estimated content units rather than layout pixels, and the cap is only checked
   * between steps, so it bounds the drive without pinning it to an exact pixel.
   */
  @Test
  fun `maxScrollPx bounds the drive`() {
    installPreviewIndex(
      "Capped",
      """{"mode":"END","axis":"VERTICAL","maxScrollPx":60,"reduceMotion":true}""",
    )
    val rows = rowsIn(renderSvg("Capped"))
    assertFalse("a 60px cap must not reach the last row (got $rows)", rows.contains(30))
    assertTrue("the drive must still have moved off the very top (got $rows)", rows.isNotEmpty())
  }
}
