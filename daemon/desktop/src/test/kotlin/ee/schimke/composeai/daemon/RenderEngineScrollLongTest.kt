package ee.schimke.composeai.daemon

import java.io.File
import javax.imageio.ImageIO
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Long-scroll screenshots through the **preview server** — the daemon path `compose-preview serve`
 * uses — over a Material 3 component that scrolls on its own.
 *
 * [DateRangePickerLongScrollPreview] is deliberately not a hand-rolled list. `DateRangePicker` lays
 * its months out as one continuously scrolling list *inside* the component, so the fixture passes
 * no scroll state, no modifier and no list: the only handle the driver has is the semantics the
 * component publishes for itself, which is what anyone screenshotting a stock M3 component faces.
 *
 * It is also the case that used to fail hardest. Before the drive measured geometry, this component
 * was reported as having nothing scrollable at all — it publishes `ScrollAxisRange(0, 0)` until its
 * months measure — and the capture came back as a single viewport.
 *
 * The daemon reaches this through `mode=scroll-long`, resolving the drive from the preview's
 * `dataProducts[].scroll` entry and delegating to `:renderer-desktop`'s `renderScrollPreview`,
 * which stitches the per-viewport slices into one tall PNG.
 *
 * **Known limitation, orthogonal to the drive:** `stitchSlices` stacks whole viewports, so any
 * *pinned* chrome repeats at every seam — a `Scaffold`'s top/bottom bars, or this component's
 * weekday header row. Worse, content that scrolled under a pinned bottom bar is missing from the
 * stitch entirely. That is a property of the stitcher, not of how far the scroll was driven, and is
 * why the fixture asserted here is a plain scrolling surface.
 */
class RenderEngineScrollLongTest {

  @get:Rule val tempFolder: TemporaryFolder = TemporaryFolder()

  private var previousIndexProp: String? = null

  @After
  fun restoreIndexProperty() {
    if (previousIndexProp == null) System.clearProperty(PreviewIndex.PREVIEWS_JSON_PATH_PROP)
    else System.setProperty(PreviewIndex.PREVIEWS_JSON_PATH_PROP, previousIndexProp!!)
  }

  private val viewportPx = 420

  /** A `previews.json` advertising the `render/scroll/long` product the daemon resolves. */
  private fun installPreviewIndex(previewId: String, functionName: String, maxScrollPx: Int) {
    val cap = if (maxScrollPx > 0) ""","maxScrollPx":$maxScrollPx""" else ""
    val json =
      """
      {
        "previews": [
          {
            "id": "$previewId",
            "className": "ee.schimke.composeai.daemon.RedFixturePreviewsKt",
            "functionName": "$functionName",
            "sourceFile": "RedFixturePreviews.kt",
            "captures": [{"renderOutput": "renders/$previewId.png"}],
            "dataProducts": [
              {
                "kind": "render/scroll/long",
                "scroll": {"mode":"LONG","axis":"VERTICAL","reduceMotion":true$cap}
              }
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

  /** Drives a `scroll-long` render through the daemon and returns the stitched PNG's height. */
  private fun stitchedHeight(previewId: String, maxScrollPx: Int = 0): Int {
    val functionName = "DateRangePickerLongScrollPreview"
    installPreviewIndex(previewId, functionName, maxScrollPx)
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
              "functionName=$functionName;" +
              "widthPx=380;heightPx=$viewportPx;density=1.0;showBackground=true;" +
              "previewId=$previewId;outputBaseName=$previewId;mode=scroll-long"
        ),
        timeoutMs = 240_000,
      )
    } finally {
      host.shutdown()
    }
    val stitched = File(File(dataDir, "render-scroll-long"), "$previewId.png")
    assertTrue("stitched long PNG must be produced: ${stitched.absolutePath}", stitched.exists())
    // Keep a copy outside the auto-cleaned TemporaryFolder so a capture can be eyeballed after a
    // run. Never fails the test.
    runCatching {
      val keep = File("build/scroll-long-captures").also { it.mkdirs() }
      stitched.copyTo(File(keep, "$previewId.png"), overwrite = true)
    }
    val image = ImageIO.read(stitched) ?: error("stitched PNG was not decodable")
    assertTrue("the stitch must keep the render width (got ${image.width})", image.width == 380)
    return image.height
  }

  /**
   * The headline: a stock `DateRangePicker`'s two-year month list captured end to end, many
   * viewports tall. The bound is deliberately far below the ~20 viewports this actually produces —
   * enough to prove the whole list was walked, loose enough to survive M3 changing its month
   * metrics.
   */
  @Test
  fun `a DateRangePicker's built-in month list is captured as one tall image`() {
    val height = stitchedHeight("DateRangePickerLong")
    assertTrue(
      "the stitched capture must span many viewports (got ${height}px, viewport $viewportPx)",
      height > viewportPx * 8,
    )
  }

  /**
   * `maxScrollPx` bounds the drive, and — because the stitch height now tracks *measured* travel —
   * a capped capture is correspondingly shorter. This is the assertion that catches the original
   * defect from the other side: when travel was mis-measured, height stopped tracking it.
   */
  @Test
  fun `a capped drive produces a correspondingly shorter capture`() {
    val capped = stitchedHeight("DateRangePickerCapped", maxScrollPx = 900)
    assertTrue(
      "a 900px cap must stay well short of the full list (got ${capped}px)",
      capped < viewportPx * 8,
    )
    assertTrue(
      "...but must still have scrolled past the first viewport (got ${capped}px)",
      capped > viewportPx,
    )
  }
}
