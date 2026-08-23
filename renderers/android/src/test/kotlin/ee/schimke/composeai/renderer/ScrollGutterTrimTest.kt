package ee.schimke.composeai.renderer

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `@CaptureGutter`'s contract excludes every `@ScrollingPreview` product (issue #4467).
 *
 * A gutter says "the component draws this far past its own bounds", which only means anything where
 * the capture bounds ARE the component's. A LONG capture's are the stitched scroll extent and a GIF
 * frame's the declared viewport — no component edge for a gutter to sit on. CMP Desktop implements
 * that by handing `renderScrollPreview` no gutter at all; Android grows **one** window per preview
 * and captures every job in it, so the still keeps the gutter and the scroll products trim it back
 * off here.
 *
 * Round devices, `showSystemUi` frames and dialog-hosted scrollables are **unsupported** in this
 * combination and keep the gutter — a baked-in circular mask and window-edge system chrome both
 * make a post-hoc trim worse than none, and a dialog frame is cropped to its own window rect before
 * the hosting-window trim would ever see it. `END` is out of scope too: its product is an ordinary
 * still sharing the whole still post-capture chain, so it keeps the gutter on this lane — see
 * `@CaptureGutter`'s kdoc and issue #4467.
 */
class ScrollGutterTrimTest {

  private val gutter =
    DialogWindowCapture.DialogCropGutter(leftPx = 8, topPx = 8, rightPx = 8, bottomPx = 10)

  @Test
  fun `an empty trim is a no-op, whatever the frame`() {
    val trim = DialogWindowCapture.GutterTrim()
    assertTrue(trim.isEmpty())
    val file = png(200, 400)
    val before = file.readBytes().size
    trim.applyTo(file)
    assertEquals(200 to 400, size(file))
    assertEquals(before, file.readBytes().size)
  }

  @Test
  fun `a fixed axis trims to the frame it declared, not to what the qualifier grew`() {
    // The window grew by whole dp, so the capture can be a pixel or two wider than the gutter
    // resolves to. LONG's stitcher plans against `heightDp * density` EXACTLY — a viewport off by
    // that quantized remainder would drift the seam on every slice — so the un-guttered frame is
    // named rather than inferred by subtracting the gutter.
    val trim =
      DialogWindowCapture.GutterTrim(gutter = gutter, fixedWidthPx = 200, fixedHeightPx = 400)
    val file = png(210, 419) // 200 + 8 + 8 + 2px of dp overshoot; 400 + 8 + 10 + 1
    trim.applyTo(file)
    assertEquals(200 to 400, size(file))
  }

  @Test
  fun `a wrapped axis simply loses its two gutter edges`() {
    val trim = DialogWindowCapture.GutterTrim(gutter = gutter)
    val file = png(216, 418)
    trim.applyTo(file)
    assertEquals(216 - 16 to 418 - 18, size(file))
  }

  @Test
  fun `the trimmed rect starts at the leading gutter, so the component is what survives`() {
    // The content box places the child inset by the leading gutter, so trimming from (0,0) would
    // keep the empty margin and cut the component's own trailing pixels off instead.
    val file = png(216, 418)
    paint(file, x = 8, y = 8, width = 200, height = 400, color = Color.RED)
    DialogWindowCapture.GutterTrim(gutter = gutter, fixedWidthPx = 200, fixedHeightPx = 400)
      .applyTo(file)
    val img = ImageIO.read(file)
    assertEquals(200 to 400, img.width to img.height)
    // Every corner of the survivor is component, not margin.
    assertEquals(Color.RED.rgb, img.getRGB(0, 0))
    assertEquals(Color.RED.rgb, img.getRGB(199, 399))
  }

  @Test
  fun `a capture smaller than expected loses less rather than running off the image`() {
    val trim =
      DialogWindowCapture.GutterTrim(gutter = gutter, fixedWidthPx = 200, fixedHeightPx = 400)
    val file = png(12, 12)
    trim.applyTo(file)
    val (w, h) = size(file)
    assertTrue("width must stay on the image: $w", w in 1..12)
    assertTrue("height must stay on the image: $h", h in 1..12)
  }

  @Test
  fun `a trim that would change nothing reports no rect`() {
    val file = png(200, 400)
    assertNull(
      DialogWindowCapture.GutterTrim(
          gutter = DialogWindowCapture.DialogCropGutter(),
          fixedWidthPx = 200,
          fixedHeightPx = 400,
        )
        .trimRect(file)
    )
  }

  @Test
  fun `the rtl decision is the dialog crop's, because both work in rendered pixels`() {
    // `start`/`end` are layout edges; a trim rect is over an already-mirrored capture, so the
    // leading edge is on the right under RTL. Sharing `dialogCropGutter` is what keeps the two
    // from disagreeing about which side to eat.
    val ltr =
      dialogCropGutter(CaptureGutterDp(start = 2, top = 3, end = 8, bottom = 5), 2.0f, false)
    val rtl = dialogCropGutter(CaptureGutterDp(start = 2, top = 3, end = 8, bottom = 5), 2.0f, true)
    assertEquals(4, ltr.leftPx)
    assertEquals(16, rtl.leftPx)
    assertFalse(DialogWindowCapture.GutterTrim(gutter = rtl).isEmpty())
  }

  @Test
  fun `a device axis gets an exact target, not the capture minus the gutter`() {
    // The window grew by whole dp on top of an already-resolved device frame, so at a fractional
    // density `capture - gutter` keeps the quantization remainder — which on a round watch leaves
    // a supposedly square frame uneven, and the re-mask an ellipse.
    val trim =
      DialogWindowCapture.GutterTrim(
        gutter =
          DialogWindowCapture.DialogCropGutter(
            leftPx = 11,
            topPx = 11,
            rightPx = 11,
            bottomPx = 11,
          ),
        fixedWidthPx = 300,
        fixedHeightPx = 300,
      )
    // 300 + 11 + 11 = 322, plus a pixel of dp-rounding overshoot on each axis.
    val file = png(323, 323)
    trim.applyTo(file)
    val (w, h) = size(file)
    assertEquals("a round frame must come back square", w, h)
    assertEquals(300 to 300, w to h)
  }

  private fun png(width: Int, height: Int): File {
    val file = Files.createTempFile("capture", ".png").toFile()
    file.deleteOnExit()
    ImageIO.write(BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB), "PNG", file)
    return file
  }

  private fun paint(file: File, x: Int, y: Int, width: Int, height: Int, color: Color) {
    val img = ImageIO.read(file)
    val g = img.createGraphics()
    try {
      g.color = color
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
}
