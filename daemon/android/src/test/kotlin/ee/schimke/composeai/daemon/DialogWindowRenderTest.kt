package ee.schimke.composeai.daemon

import java.io.File
import javax.imageio.ImageIO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Regression coverage for previews whose whole content composes into their own window — `Dialog`,
 * `AlertDialog`, `ModalBottomSheet` (issue #3048).
 *
 * These leave the activity's Compose root present but empty. Selecting it — which
 * [selectRenderedSurfaceSemanticsRoot] used to do unconditionally — exports no semantics tree at
 * all, so the preview is reported under `no semantics for: …` and the completeness gate refuses to
 * publish the whole system.
 */
class DialogWindowRenderTest {

  @get:Rule val tempFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun rendersAndExportsTheDialogWindowForAFixedSizePreview() {
    assertDialogWindowIsTheSubject(
      previewId = "dialog-window-fixed",
      params = PreviewParamsEntry(widthDp = 96, heightDp = 96, density = 1.0f),
    )
  }

  /**
   * The shape real component previews are declared in — no `widthDp` / `heightDp`, so both axes
   * wrap. With the activity root selected the wrap crop measures the *empty* activity content, which
   * is how these previews reached the published bundle as blank stickers.
   */
  @Test
  fun rendersAndExportsTheDialogWindowForAWrapContentPreview() {
    assertDialogWindowIsTheSubject(
      previewId = "dialog-window-wrap",
      params = PreviewParamsEntry(density = 1.0f),
    )
  }

  /**
   * A `Popup` over an activity surface that renders content but declares no semantics must not
   * become the subject. Only a *dialog* window displaces the activity preference — "no semantic
   * descendants" is not the same as "nothing rendered", and a popup adds an owner but never a
   * dialog window.
   */
  @Test
  fun keepsTheActivitySurfaceWhenAPopupSitsOverSemanticsFreeContent() {
    val previewId = "popup-over-visual-only"
    val outputDir = tempFolder.newFolder("renders-$previewId")
    System.setProperty(RenderEngine.OUTPUT_DIR_PROP, outputDir.absolutePath)
    System.setProperty("roborazzi.test.record", "true")
    val host =
      PreviewManifestRouter(
        manifest =
          PreviewManifest(
            previews =
              listOf(
                PreviewManifestEntry(
                  id = previewId,
                  className = "ee.schimke.composeai.daemon.RedFixturePreviewsKt",
                  functionName = "VisualOnlySurfaceWithPopup",
                  params = PreviewParamsEntry(widthDp = 96, heightDp = 96, density = 1.0f),
                )
              )
          )
      )

    host.start()
    try {
      val result =
        host.submit(RenderRequest.Render(payload = "previewId=$previewId"), timeoutMs = 120_000)
      assertNotNull("PNG path must be populated", result.pngPath)

      val semantics =
        outputDir.parentFile!!.resolve("data/$previewId").resolve("compose-semantics.json")
      assertTrue("compose-semantics.json must be written", semantics.isFile)
      assertTrue(
        "the popup must not displace the activity surface: ${semantics.readText()}",
        "popup-surface" !in semantics.readText(),
      )
    } finally {
      host.shutdown()
    }
  }

  private fun assertDialogWindowIsTheSubject(previewId: String, params: PreviewParamsEntry) {
    val outputDir = tempFolder.newFolder("renders-$previewId")
    System.setProperty(RenderEngine.OUTPUT_DIR_PROP, outputDir.absolutePath)
    System.setProperty("roborazzi.test.record", "true")
    val host =
      PreviewManifestRouter(
        manifest =
          PreviewManifest(
            previews =
              listOf(
                PreviewManifestEntry(
                  id = previewId,
                  className = "ee.schimke.composeai.daemon.RedFixturePreviewsKt",
                  functionName = "DialogWindowSurface",
                  params = params,
                )
              )
          )
      )

    host.start()
    try {
      val result =
        host.submit(RenderRequest.Render(payload = "previewId=$previewId"), timeoutMs = 120_000)
      assertNotNull("PNG path must be populated", result.pngPath)
      val png = File(result.pngPath!!)
      assertTrue("rendered PNG must exist", png.isFile)

      // The activity window paints an opaque backdrop, so "not fully transparent" is not enough —
      // a blank capture passes that. Assert the dialog's own fill actually reaches the PNG.
      assertTrue(
        "the dialog's fill must reach the PNG; it captured the (empty) activity window instead",
        png.containsColor(DIALOG_FILL_ARGB),
      )

      // …and that the sticker is framed to the dialog, not to the window it floats in. The fixture's
      // dialog wraps a 64 dp box at density 1.
      val size = ImageIO.read(png)
      assertEquals("captured width must be the dialog's", 64, size.width)
      assertEquals("captured height must be the dialog's", 64, size.height)

      val previewDataDir = outputDir.parentFile!!.resolve("data/$previewId")
      val semantics = previewDataDir.resolve("compose-semantics.json")
      assertTrue(
        "compose-semantics.json must be written; wrote ${previewDataDir.list()?.toList()}",
        semantics.isFile,
      )
      assertTrue(
        "the selected tree must be the dialog surface: ${semantics.readText()}",
        "dialog-surface" in semantics.readText(),
      )
    } finally {
      host.shutdown()
    }
  }
}

/** The blue [RedFixturePreviews]' `DialogWindowSurface` fills its box with. */
private const val DIALOG_FILL_ARGB = 0xFF42A5F5.toInt()

/** Whether any pixel in [this] PNG is exactly [argb]. */
internal fun File.containsColor(argb: Int): Boolean {
  val image = ImageIO.read(this) ?: return false
  for (y in 0 until image.height) {
    for (x in 0 until image.width) {
      if (image.getRGB(x, y) == argb) return true
    }
  }
  return false
}
