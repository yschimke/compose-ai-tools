package ee.schimke.composeai.renderer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import java.io.File
import javax.imageio.ImageIO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A list whose first and last screens are unmistakably different colours: 20 red rows, then one
 * green row at the very bottom. Green pixels exist only past the end of the scroll, so "did the
 * renderer actually drive the scrollable" is a question about one pixel rather than about glyphs.
 */
@Composable
fun ColourBandedListFixture() {
  LazyColumn(modifier = Modifier.fillMaxSize().background(Color.White)) {
    items(20) { Box(modifier = Modifier.fillMaxWidth().height(40.dp).background(Color.Red)) }
    item { Box(modifier = Modifier.fillMaxWidth().height(40.dp).background(Color.Green)) }
  }
}

/** No scrollable anywhere, so the END drive has nothing to find and must decline. */
@Composable
fun UnscrollableRedFixture() {
  Box(modifier = Modifier.fillMaxSize().background(Color.Red))
}

/**
 * `@ScrollingPreview(modes = [END])` on Compose Desktop. The renderer used to have no way to drive
 * a scrollable outside the LONG / GIF code path, so an END capture was byte-identical to TOP — the
 * resting first viewport, missing whatever the screen only reveals once its list settles.
 */
class ScrollEndCaptureTest {

  @get:Rule val tempFolder: TemporaryFolder = TemporaryFolder()

  private val fixtureClass = "ee.schimke.composeai.renderer.ScrollEndCaptureTestKt"

  private fun rendererArgs(
    function: String,
    outputFile: File,
    scrollMode: String,
    maxScrollPx: String = "0",
  ): Array<String> =
    arrayOf(
      fixtureClass,
      function,
      "100",
      "160",
      "1.0",
      "true",
      "0",
      outputFile.absolutePath,
      "", // wrapperClassName
      "false", // wrapWidth
      "false", // wrapHeight
      "", // previewParameterProviderFqn
      "0", // previewParameterLimit
      "", // localeTag
      scrollMode,
      "VERTICAL",
      maxScrollPx,
      "0", // scrollFrameIntervalMs
    )

  private fun render(
    function: String,
    scrollMode: String,
    maxScrollPx: String = "0",
  ): java.awt.image.BufferedImage {
    val out = tempFolder.newFolder(function + scrollMode + maxScrollPx).resolve("capture.png")
    main(rendererArgs(function, out, scrollMode, maxScrollPx))
    assertTrue("a capture must be written to $out", out.exists())
    return ImageIO.read(out) ?: error("capture was not decodable: $out")
  }

  /** Whether any pixel in the frame is (close to) the fixture's bottom-marker green. */
  private fun java.awt.image.BufferedImage.hasGreenBand(): Boolean {
    for (y in 0 until height) {
      for (x in 0 until width) {
        val rgb = getRGB(x, y)
        val r = (rgb shr 16) and 0xFF
        val g = (rgb shr 8) and 0xFF
        val b = rgb and 0xFF
        if (g > 180 && r < 80 && b < 80) return true
      }
    }
    return false
  }

  @Test
  fun `an END capture reaches the bottom of the list`() {
    assertTrue(
      "the green bottom row must be on screen after the drive",
      render("ColourBandedListFixture", scrollMode = "END").hasGreenBand(),
    )
  }

  /** The control: without the drive the same list shows only its red top. */
  @Test
  fun `a TOP capture stays at the resting top`() {
    assertTrue(
      "the unscrolled frame must not reach the green bottom row",
      !render("ColourBandedListFixture", scrollMode = "TOP").hasGreenBand(),
    )
  }

  /**
   * `@ScrollingPreview(maxScrollPx = …)` caps the drive, so a cap far short of the content end
   * leaves the bottom row off screen. Guards the cap against being ignored once the loop started
   * measuring its own progress rather than trusting the requested step.
   */
  @Test
  fun `maxScrollPx bounds the drive`() {
    assertTrue(
      "a 40px cap must not reach the bottom of a 21-row list",
      !render("ColourBandedListFixture", scrollMode = "END", maxScrollPx = "40").hasGreenBand(),
    )
  }

  /** No scrollable ⇒ decline, and the caller's fall-through renders the ordinary frame. */
  @Test
  fun `an END capture of a non-scrolling screen falls back to its top frame`() {
    val image = render("UnscrollableRedFixture", scrollMode = "END")
    assertEquals("the fall-through capture keeps the requested width", 100, image.width)
    val rgb = image.getRGB(image.width / 2, image.height / 2)
    assertEquals(
      "the fixture's red fill must survive the fall-through",
      0xFF,
      (rgb shr 16) and 0xFF,
    )
  }
}
