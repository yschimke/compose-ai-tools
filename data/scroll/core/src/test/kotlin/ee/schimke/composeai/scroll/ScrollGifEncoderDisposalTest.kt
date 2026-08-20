package ee.schimke.composeai.scroll

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Holds [ScrollGifEncoder]'s disposal choice, which is the difference between a translucent motion
 * capture that reads and one that smears.
 *
 * A GIF frame's disposal method says what happens to the canvas BEFORE the next frame is drawn.
 * `none` leaves the frame in place, so the next one composites over it — correct and cheap for
 * opaque frames, which paint every pixel anyway, and wrong for frames with alpha, where every
 * transparent pixel lets the previous frame show through. Motion captures of component stickers are
 * exactly that second case (`@Preview(showBackground = false)` renders on transparency by design),
 * and under `none` a morphing silhouette accumulates every outline it has ever drawn.
 *
 * The tests assert on the encoded BYTES rather than on the metadata tree we handed the writer,
 * because the writer does not honour all of it: `transparentColorFlag` is requested `FALSE` and set
 * anyway for an alpha raster. Reading the Graphic Control Extension back out of the file is what
 * proves what a viewer will actually do.
 */
class ScrollGifEncoderDisposalTest {
  @get:Rule val tmp: TemporaryFolder = TemporaryFolder()

  @Test
  fun `translucent frames are written restore-to-background so they do not smear`() {
    val out = tmp.newFile("translucent.gif")
    ScrollGifEncoder.encode(frames = translucentFrames(), outputFile = out, frameDelayMs = 40)

    assertEquals(
      "translucent frames must clear the canvas between frames",
      listOf(RESTORE_TO_BACKGROUND, RESTORE_TO_BACKGROUND, RESTORE_TO_BACKGROUND),
      disposalMethods(out),
    )
  }

  @Test
  fun `opaque frames keep the cheap composite-in-place disposal`() {
    val out = tmp.newFile("opaque.gif")
    ScrollGifEncoder.encode(frames = opaqueFrames(), outputFile = out, frameDelayMs = 40)

    assertEquals(
      "opaque frames paint every pixel, so clearing between them only costs bytes",
      listOf(NO_DISPOSAL, NO_DISPOSAL, NO_DISPOSAL),
      disposalMethods(out),
    )
  }

  /**
   * The regression itself, stated as the symptom rather than as the flag: a shape that MOVES across
   * a transparent canvas must not leave the pixels it has vacated behind.
   *
   * The frames draw one opaque square stepping left to right, so every frame has the same amount of
   * ink. Decoded with disposal honoured, the last frame must still have that amount — under `none`
   * it would carry all three squares at once.
   */
  @Test
  fun `a shape moving across transparency leaves no trail behind it`() {
    val out = tmp.newFile("moving.gif")
    ScrollGifEncoder.encode(frames = translucentFrames(), outputFile = out, frameDelayMs = 40)

    val opaquePixels = decodeComposited(out).map { frame -> frame.count { it.ushr(24) > 8 } }
    assertTrue("expected three decoded frames, got ${opaquePixels.size}", opaquePixels.size == 3)
    assertTrue(
      "ink grows across the sequence, so earlier frames are still on the canvas: $opaquePixels",
      opaquePixels.all { it == opaquePixels.first() },
    )
  }

  /** One opaque 8×8 square stepping across an otherwise transparent 40×16 canvas. */
  private fun translucentFrames(): List<BufferedImage> =
    listOf(0, 12, 24).map { x ->
      BufferedImage(40, 16, BufferedImage.TYPE_INT_ARGB).also { img ->
        val g = img.createGraphics()
        g.color = Color.RED
        g.fillRect(x, 4, 8, 8)
        g.dispose()
      }
    }

  /** The same march, on a filled canvas — the scroll case this encoder was written for. */
  private fun opaqueFrames(): List<BufferedImage> =
    listOf(0, 12, 24).map { x ->
      BufferedImage(40, 16, BufferedImage.TYPE_INT_RGB).also { img ->
        val g = img.createGraphics()
        g.color = Color.WHITE
        g.fillRect(0, 0, 40, 16)
        g.color = Color.RED
        g.fillRect(x, 4, 8, 8)
        g.dispose()
      }
    }

  /**
   * The disposal method of every frame in [gif], read straight out of the file.
   *
   * A Graphic Control Extension is `0x21 0xF9 0x04` followed by a packed byte whose bits 2-4 are
   * the disposal method. Scanning for the introducer is enough here: these fixtures are tiny and
   * synthetic, so there is no colour-table data that could coincidentally spell it.
   */
  private fun disposalMethods(gif: File): List<Int> {
    val bytes = gif.readBytes()
    return bytes.indices
      .filter { i ->
        i + 3 < bytes.size &&
          bytes[i] == 0x21.toByte() &&
          bytes[i + 1] == 0xF9.toByte() &&
          bytes[i + 2] == 0x04.toByte()
      }
      .map { i -> (bytes[i + 3].toInt() shr 2) and 0x7 }
  }

  /** Each frame of [gif] as ARGB pixels, composited the way a viewer honours disposal. */
  private fun decodeComposited(gif: File): List<IntArray> {
    val reader =
      javax.imageio.ImageIO.getImageReadersByFormatName("gif").asSequence().first().apply {
        input = javax.imageio.ImageIO.createImageInputStream(gif)
      }
    val count = reader.getNumImages(true)
    val canvas = BufferedImage(40, 16, BufferedImage.TYPE_INT_ARGB)
    val disposals = disposalMethods(gif)
    return (0 until count).map { i ->
      val frame = reader.read(i)
      val g = canvas.createGraphics()
      if (disposals.getOrElse(i) { NO_DISPOSAL } == RESTORE_TO_BACKGROUND) {
        g.composite = java.awt.AlphaComposite.Clear
        g.fillRect(0, 0, canvas.width, canvas.height)
        g.composite = java.awt.AlphaComposite.SrcOver
      }
      g.drawImage(frame, 0, 0, null)
      g.dispose()
      canvas.getRGB(0, 0, canvas.width, canvas.height, null, 0, canvas.width)
    }
  }

  private companion object {
    /** GIF disposal 0: leave the frame on the canvas. */
    const val NO_DISPOSAL = 0

    /** GIF disposal 2: clear the frame's area before drawing the next. */
    const val RESTORE_TO_BACKGROUND = 2
  }
}
