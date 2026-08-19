package ee.schimke.composeai.daemon

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * `@SettledPreview` through the **desktop daemon** — the `compose-preview serve` path
 * (issue #4238).
 *
 * Both batch renderers honoured the annotation, and so did the Android daemon, so a live
 * Robolectric frame already agreed with its published PNG. The desktop daemon did not: a settled
 * CMP preview served live showed its first frame while the PNG beside it showed the settled one —
 * the same disagreement #4202 was about, moved one lane over.
 *
 * [TimedRevealPreview] is black until its `delay` fires and then paints a green square in the
 * middle, so each assertion here is about one pixel rather than about a heuristic. `delay` is the
 * load-bearing part: `scene.render(nanoTime)` drives Compose's frame clock but not
 * `kotlinx.coroutines.delay`, so raising the frame timestamp alone leaves the fixture black — only
 * a scene built on a `DesktopSettleClock` reveals it.
 */
class RenderEngineSettleTest {

  @get:Rule val tempFolder: TemporaryFolder = TemporaryFolder()

  private var previousIndexProp: String? = null

  @After
  fun restoreIndexProperty() {
    if (previousIndexProp == null) System.clearProperty(PreviewIndex.PREVIEWS_JSON_PATH_PROP)
    else System.setProperty(PreviewIndex.PREVIEWS_JSON_PATH_PROP, previousIndexProp!!)
  }

  /**
   * A `previews.json` whose single preview carries [settleJson] as its capture's settle block — the
   * shape discovery writes for `@SettledPreview`, and the only thing `staticSettleFor` consults.
   */
  private fun installPreviewIndex(previewId: String, settleJson: String?) {
    val settle = if (settleJson == null) "" else ""","settle":$settleJson"""
    val json =
      """
      {
        "previews": [
          {
            "id": "$previewId",
            "className": "ee.schimke.composeai.daemon.RedFixturePreviewsKt",
            "functionName": "TimedRevealPreview",
            "sourceFile": "RedFixturePreviews.kt",
            "captures": [
              {"renderOutput": "renders/$previewId.png"$settle}
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

  private fun render(previewId: String): BufferedImage {
    val outputDir = tempFolder.newFolder("renders-$previewId")
    val engine = RenderEngine(outputDir = outputDir)
    val result =
      engine.render(
        RenderSpec(
          previewId = previewId,
          className = "ee.schimke.composeai.daemon.RedFixturePreviewsKt",
          functionName = "TimedRevealPreview",
          widthPx = 100,
          heightPx = 100,
          density = 1.0f,
          showBackground = true,
          outputBaseName = previewId,
        ),
        requestId = 1L,
        classLoader = javaClass.classLoader,
      )
    val png = File(result.pngPath!!)
    assertTrue("PNG must be produced: ${png.absolutePath}", png.exists())
    // Best-effort: keep a copy outside the auto-cleaned TemporaryFolder so the frames can be
    // inspected — and embedded as evidence — after the run. Never fails the test.
    runCatching {
      val keep = File("build/settle-daemon-evidence").also { it.mkdirs() }
      png.copyTo(File(keep, "$previewId.png"), overwrite = true)
    }
    return ImageIO.read(png)
  }

  /** Whether the fixture's green reveal square is painted at the centre of [image]. */
  private fun revealed(image: BufferedImage): Boolean =
    (image.getRGB(image.width / 2, image.height / 2) and 0xFFFFFF) == 0x00C853

  @Test
  fun `an auto settle reveals a time-driven component`() {
    installPreviewIndex("AutoSettled", """{"afterMs":0,"maxMs":1000}""")
    assertTrue(
      "the auto walk must run the fixture's 200ms delay out before capturing",
      revealed(render("AutoSettled")),
    )
  }

  @Test
  fun `an exact settle past the reveal captures the revealed component`() {
    installPreviewIndex("ExactSettled", """{"afterMs":400,"maxMs":1000}""")
    assertTrue(revealed(render("ExactSettled")))
  }

  /**
   * The other half of "exact means exact": a coordinate *before* the reveal has to capture the
   * frame before it, not walk on to a lull. If this passed for the same reason the auto case does,
   * the exact mode would be a bound rather than a coordinate.
   */
  @Test
  fun `an exact settle short of the reveal captures the frame before it`() {
    installPreviewIndex("ExactEarly", """{"afterMs":100,"maxMs":1000}""")
    assertFalse(revealed(render("ExactEarly")))
  }

  /** The control, and the regression the fix is measured against: no settle, no reveal. */
  @Test
  fun `a preview with no settle keeps its first frame`() {
    installPreviewIndex("Unsettled", settleJson = null)
    val image = render("Unsettled")
    assertFalse("without a settle the daemon must capture the pre-reveal frame", revealed(image))
    // …and that frame is the fixture's black container, not an empty or transparent one — proof
    // the render itself is sound and only the reveal is missing.
    assertEquals(0x000000, image.getRGB(image.width / 2, image.height / 2) and 0xFFFFFF)
  }
}
