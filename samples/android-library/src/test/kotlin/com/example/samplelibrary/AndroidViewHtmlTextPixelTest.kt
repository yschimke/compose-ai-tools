package com.example.samplelibrary

import com.google.common.truth.Truth.assertThat
import java.io.File
import javax.imageio.ImageIO
import org.junit.Test

/**
 * A plain `@Preview` whose body is an `AndroidView` must emit a PNG from a library module (which
 * needs `composePreview { hostTheme }`; see [HtmlShowNotes]). Asserts the PNG exists and that the
 * hosted `TextView` drew near-black text, so a blank PNG doesn't pass.
 */
class AndroidViewHtmlTextPixelTest {

  private val rendersDir = File("build/compose-previews/renders")
  private val pngStem = "HtmlShowNotesPreview_HTML_show_notes"

  @Test
  fun `AndroidView-hosted preview renders a static PNG with drawn text`() {
    val file = renderFile(rendersDir, pngStem)
    assertThat(file.exists()).isTrue()

    val img = ImageIO.read(file)
    assertThat(img.width).isAtLeast(100)
    assertThat(img.height).isAtLeast(100)

    // The `TextView` sits below the "Show notes" title, in the lower two thirds of the frame.
    // Count near-black pixels there: a render that composed but drew no hosted View comes back as
    // a flat light surface and fails here.
    var darkPixels = 0
    for (y in img.height / 3 until img.height) {
      for (x in 0 until img.width) {
        val rgb = img.getRGB(x, y)
        val r = (rgb shr 16) and 0xff
        val g = (rgb shr 8) and 0xff
        val b = rgb and 0xff
        if (r < 96 && g < 96 && b < 96) darkPixels++
      }
    }
    assertThat(darkPixels).isGreaterThan(50)
  }
}
