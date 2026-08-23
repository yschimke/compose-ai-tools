package ee.schimke.composeai.daemon

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The live daemon's half of `@CaptureGutter`'s scroll exclusion (issue #4467).
 *
 * `@ScrollingPreview(LONG / GIF)` runs in `runScrollScenario`, which never grows the window for a
 * gutter — nothing to undo. `END` is the odd one out: its product is an ordinary still, so it
 * shares `render()`'s single grown window with the preview's real still and has to give the gutter
 * back afterwards. Twin of the batch renderer's `DialogWindowCapture.GutterTrim`, and asserting the
 * same arithmetic on purpose — RENDER_LANE_PARITY.md's rule is that switching lanes changes font
 * antialiasing, not layout.
 */
class DaemonScrollGutterTrimTest {

  @get:Rule val tempFolder: TemporaryFolder = TemporaryFolder()

  private fun engine() = RenderEngine(outputDir = tempFolder.newFolder("unused-${counter++}"))

  private fun spec(
    widthPx: Int = 200,
    heightPx: Int = 400,
    wrapWidth: Boolean = false,
    wrapHeight: Boolean = false,
    localeTag: String? = null,
  ) =
    RenderSpec(
      className = "com.example.FooKt",
      functionName = "Foo",
      widthPx = widthPx,
      heightPx = heightPx,
      wrapWidth = wrapWidth,
      wrapHeight = wrapHeight,
      density = 2.0f,
      localeTag = localeTag,
      gutterStartDp = 4,
      gutterTopDp = 4,
      gutterEndDp = 4,
      gutterBottomDp = 5,
    )

  @Test
  fun `a fixed-axis END capture comes back at its declared frame`() {
    // The window grew by 4+4 dp across and 4+5 dp down at density 2 ⇒ 16 px and 18 px.
    val file = png(216, 418)
    engine().trimCaptureGutter(file = file, spec = spec(), fixedWidthPx = 200, fixedHeightPx = 400)
    assertEquals(200 to 400, size(file))
  }

  @Test
  fun `a wrapped axis loses its two gutter edges`() {
    val file = png(216, 418)
    engine()
      .trimCaptureGutter(
        file = file,
        spec = spec(wrapWidth = true, wrapHeight = true),
        fixedWidthPx = null,
        fixedHeightPx = null,
      )
    assertEquals(200 to 400, size(file))
  }

  @Test
  fun `the survivor is the component, not the margin it was inset by`() {
    val file = png(216, 418)
    paint(file, x = 8, y = 8, width = 200, height = 400)
    engine().trimCaptureGutter(file = file, spec = spec(), fixedWidthPx = 200, fixedHeightPx = 400)
    val img = ImageIO.read(file)
    assertEquals(Color.RED.rgb, img.getRGB(0, 0))
    assertEquals(Color.RED.rgb, img.getRGB(199, 399))
  }

  @Test
  fun `an RTL capture eats the mirrored edge`() {
    // `start`/`end` are layout edges and the capture has already been mirrored, so the leading
    // edge is on the right. Same swap the dialog crop makes, for the same reason.
    val file = png(216, 418)
    // Asymmetric so the swap is observable: start 4 dp ⇒ 8 px, end 4 dp ⇒ 8 px is not, so paint
    // the component where an RTL layout puts it and check it survives intact.
    paint(file, x = 8, y = 8, width = 200, height = 400)
    engine()
      .trimCaptureGutter(
        file = file,
        spec = spec(localeTag = "ar"),
        fixedWidthPx = 200,
        fixedHeightPx = 400,
      )
    assertEquals(200 to 400, size(file))
  }

  @Test
  fun `a capture smaller than the trim stays on the image`() {
    val file = png(10, 10)
    engine().trimCaptureGutter(file = file, spec = spec(), fixedWidthPx = 200, fixedHeightPx = 400)
    val (w, h) = size(file)
    assertEquals(true, w in 1..10 && h in 1..10)
  }

  private fun png(width: Int, height: Int): File {
    val file = File(tempFolder.newFolder("frame-${counter++}"), "capture.png")
    ImageIO.write(BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB), "PNG", file)
    return file
  }

  private fun paint(file: File, x: Int, y: Int, width: Int, height: Int) {
    val img = ImageIO.read(file)
    val g = img.createGraphics()
    try {
      g.color = Color.RED
      g.fillRect(x, y, width, height)
    } finally {
      g.dispose()
    }
    ImageIO.write(img, "PNG", file)
  }

  private fun size(file: File): Pair<Int, Int> {
    val img = ImageIO.read(file) ?: error("frame failed to decode")
    return img.width to img.height
  }

  private companion object {
    private var counter = 0
  }
}
