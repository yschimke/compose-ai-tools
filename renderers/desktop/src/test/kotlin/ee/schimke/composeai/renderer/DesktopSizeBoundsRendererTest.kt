package ee.schimke.composeai.renderer

import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Standalone-renderer counterpart of the daemon's `RenderEngineWrapContentTest` size-bound cases —
 * proves the `compose-preview bundle render` / `composePreviewRenderAll` renderer honours the
 * wrapped-axis content-size bounds (`minWidthPx` / `minHeightPx` / `maxWidthPx` / `maxHeightPx`,
 * the Max / Min / Within size modes) the same way `compose-preview serve`'s desktop daemon does.
 *
 * Before the fix the standalone [renderPreview] wrap measure used `minWidth = 0` / `maxWidth =
 * sandbox` with no bound plumbing at all, so a bundle could carry a size-mode request but the
 * export dropped it. [WrapContentSticker]'s intrinsic size is 176 px (56 dp badge + 16 dp padding
 * each side, × density 2), so each bound visibly reshapes the crop.
 */
class DesktopSizeBoundsRendererTest {

  @get:Rule val tempFolder: TemporaryFolder = TemporaryFolder()

  private val stickerClass = "ee.schimke.composeai.renderer.SizeBoundsRenderTestFixturesKt"

  private fun dims(file: File): Pair<Int, Int> {
    assertTrue("rendered PNG must exist: ${file.absolutePath}", file.exists() && file.length() > 0)
    val img = ByteArrayInputStream(file.readBytes()).use { ImageIO.read(it) } ?: error("no decode")
    // Keep the size-mode render for the PR's visual evidence (build dir, not committed).
    File("build/size-evidence").apply { mkdirs() }.let { file.copyTo(File(it, file.name), true) }
    return img.width to img.height
  }

  private fun renderSticker(
    base: String,
    minWidthPx: Int? = null,
    minHeightPx: Int? = null,
    maxWidthPx: Int? = null,
    maxHeightPx: Int? = null,
    functionName: String = "WrapContentSticker",
  ): File {
    val out = File(tempFolder.newFolder(base), "$base.png")
    renderPreview(
      className = stickerClass,
      functionName = functionName,
      // Generous 800×1600 px wrap sandbox (like the daemon test) so the *bound*, not the frame,
      // decides the measured intrinsic size the crop keeps.
      widthPx = 800,
      heightPx = 1600,
      density = 2.0f,
      showBackground = true,
      backgroundColor = 0L,
      outputFile = out,
      wrapperClassName = null,
      wrapWidth = true,
      wrapHeight = true,
      previewArgs = emptyList(),
      localeTag = null,
      minWidthPx = minWidthPx,
      minHeightPx = minHeightPx,
      maxWidthPx = maxWidthPx,
      maxHeightPx = maxHeightPx,
    )
    return out
  }

  @Test
  fun maxBoundCapsTheWrapCropBelowTheComponentsIntrinsicSize() {
    val (w, h) = dims(renderSticker("desktop-size-max-100", maxWidthPx = 100, maxHeightPx = 100))
    assertEquals("max width bound caps the crop", 100, w)
    assertEquals("max height bound caps the crop", 100, h)
  }

  @Test
  fun minBoundForcesTheWrapCropAboveTheComponentsIntrinsicSize() {
    val (w, h) = dims(renderSticker("desktop-size-min-400", minWidthPx = 400, minHeightPx = 400))
    assertEquals("min width bound raises the crop floor", 400, w)
    assertEquals("min height bound raises the crop floor", 400, h)
  }

  /**
   * The min bound must reach the *component*, not just the wrapping box. [MinFillSticker]'s root is
   * a wrap-content green box (56.dp intrinsic); with the min propagated it fills the 400×400 crop,
   * so a pixel deep inside the frame is the component's green. Before the fix the min landed only
   * on the renderer's wrapper and that pixel was the white harness background — the "wrapper box
   * takes the size but the component stays unsized" bug.
   */
  @Test
  fun minBoundReachesTheComponentNotJustTheWrapperBox() {
    val out =
      renderSticker(
        "desktop-size-min-fill-400",
        minWidthPx = 400,
        minHeightPx = 400,
        functionName = "MinFillSticker",
      )
    val (w, h) = dims(out)
    assertEquals("min width bound raises the crop floor", 400, w)
    assertEquals("min height bound raises the crop floor", 400, h)
    val img = ByteArrayInputStream(out.readBytes()).use { ImageIO.read(it) } ?: error("no decode")
    // Deep inside the frame (350,350), well past the 112 px badge in the corner: green means the
    // component itself took the min size; white would mean only the wrapper box did.
    val argb = img.getRGB(350, 350)
    val r = argb shr 16 and 0xFF
    val g = argb shr 8 and 0xFF
    val b = argb and 0xFF
    assertTrue(
      "component must fill the min area — expected green (#1B5E20-ish) at (350,350), got " +
        "rgb($r,$g,$b)",
      g > r + 40 && g > b + 40,
    )
  }

  @Test
  fun withinBoundKeepsTheCropInsideTheMinMaxRange() {
    val (w, h) =
      dims(
        renderSticker(
          "desktop-size-within-120-400",
          minWidthPx = 120,
          minHeightPx = 120,
          maxWidthPx = 400,
          maxHeightPx = 400,
        )
      )
    assertTrue("width stays within the range (got $w)", w in 120..400)
    assertTrue("height stays within the range (got $h)", h in 120..400)
    assertEquals("unconstrained intrinsic is preserved inside the range", 176, w)
  }

  /**
   * Locks the `main()` positional-arg wiring: the wrapped-axis bounds live at indices 28–31 with
   * the intervening optional slots (14–27) padded. An off-by-one in that padding would silently
   * drop the bound, so drive the real entry point and assert the max bound still caps the crop.
   */
  @Test
  fun mainAppliesBoundsAtTheirPositionalArgIndices() {
    val out = File(tempFolder.newFolder("main-args"), "sticker.png")
    val args = MutableList(32) { "" }
    args[0] = stickerClass
    args[1] = "WrapContentSticker"
    args[2] = "800" // widthPx
    args[3] = "1600" // heightPx
    args[4] = "2.0" // density
    args[5] = "true" // showBackground
    args[6] = "0" // backgroundColor
    args[7] = out.absolutePath
    args[9] = "true" // wrapWidth
    args[10] = "true" // wrapHeight
    args[12] = "0" // previewParameterLimit
    args[28] = "100" // minWidthPx — not exceeded, so no floor effect
    args[30] = "100" // maxWidthPx — caps the width crop at 100
    args[31] = "100" // maxHeightPx — caps the height crop at 100
    main(args.toTypedArray())
    val (w, h) = dims(out)
    assertEquals("main() must thread maxWidthPx (arg 30) into the crop", 100, w)
    assertEquals("main() must thread maxHeightPx (arg 31) into the crop", 100, h)
  }
}
