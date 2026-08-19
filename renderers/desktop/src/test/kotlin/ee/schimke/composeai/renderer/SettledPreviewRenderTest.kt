package ee.schimke.composeai.renderer

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * `@SettledPreview` on the desktop still path (issue #4202).
 *
 * The report's core claim is that a time-driven reveal captures as its first frame and no
 * annotation can move the shutter. These tests hold both halves: the unsettled capture is still the
 * empty frame (so the regression stays visible if the settle is ever wired on by default), and the
 * settled one carries the arrived content.
 */
class SettledPreviewRenderTest {

  @get:Rule val tempFolder: TemporaryFolder = TemporaryFolder()

  private val fixtureClass = "ee.schimke.composeai.renderer.SettledPreviewFixturesKt"

  @Test
  fun `an unsettled capture is the empty first frame`() {
    val image = render("unsettled", "DelayedReveal")
    // Fully transparent: the reveal's LaunchedEffect is still inside its delay.
    assertEquals(0, alphaAt(image))
  }

  @Test
  fun `auto settle waits out the delay and the fade`() {
    val image = render("auto", "DelayedReveal", settleAfterMs = 0, settleMaxMs = 1000)
    assertEquals(REVEAL_ARGB, image.getRGB(image.width / 2, image.height / 2))
  }

  @Test
  fun `an explicit window captures at the coordinate it names`() {
    // Half-way through the fade, 200ms delay + 150ms of a 300ms linear tween: neither the empty
    // first frame nor the arrived one, which is what proves the number is honoured rather than
    // rounded up to "settled".
    val image = render("exact", "DelayedReveal", settleAfterMs = 350, settleMaxMs = 1000)
    val alpha = alphaAt(image)
    assertTrue("expected a mid-fade alpha, got $alpha", alpha in 100..180)
  }

  @Test
  fun `a shorter window than the delay still captures the empty frame`() {
    // The documented failure mode for a window that is too small — a capture, not a hang or a
    // silent stretch of the bound the author asked for.
    val image = render("short", "DelayedReveal", settleAfterMs = 0, settleMaxMs = 100)
    assertEquals(0, alphaAt(image))
  }

  @Test
  fun `an animation that never settles runs out the window and still captures`() {
    val image = render("infinite", "NeverSettles", settleAfterMs = 0, settleMaxMs = 200)
    // 200ms into a 400ms linear 0→1 ramp.
    val alpha = alphaAt(image)
    assertTrue("expected a mid-ramp alpha, got $alpha", alpha in 100..160)
  }

  private fun render(
    name: String,
    function: String,
    settleAfterMs: Int = -1,
    settleMaxMs: Int = 0,
  ): BufferedImage {
    val out = File(tempFolder.newFolder(name), "$name.png")
    renderPreview(
      className = fixtureClass,
      functionName = function,
      widthPx = 32,
      heightPx = 32,
      density = 1.0f,
      showBackground = false,
      backgroundColor = 0L,
      outputFile = out,
      wrapperClassName = null,
      wrapWidth = false,
      wrapHeight = false,
      previewArgs = emptyList(),
      localeTag = null,
      settleAfterMs = settleAfterMs,
      settleMaxMs = settleMaxMs,
    )
    return ImageIO.read(out)
  }

  private fun alphaAt(image: BufferedImage): Int =
    (image.getRGB(image.width / 2, image.height / 2) ushr 24) and 0xFF
}
