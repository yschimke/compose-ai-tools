package com.example.designcatalogwearm3

import com.google.common.truth.Truth.assertThat
import java.io.File
import javax.imageio.ImageIO
import org.junit.Test

/** End-to-end guard that the documented Wear pressed specimen is not a focus-only capture. */
class WearFocusedPressPixelTest {

  private val rendersDir = File("build/compose-previews/renders")

  @Test
  fun `pressed capture changes the button container beyond the focused state`() {
    val pressed = uniqueRender("ButtonPressed")
    val focused = uniqueRender("ButtonFocused")
    val pressedImage = ImageIO.read(pressed)
    val focusedImage = ImageIO.read(focused)

    // This point is inside the rounded container and outside both labels. A synthetic focused key
    // press does not reliably reach Wear M3's combinedClickable under Robolectric, producing the
    // same #D4C8EC pixel in both captures. The catalog's seeded press must keep them distinct.
    val x = 30
    val y = 68
    assertThat(pressedImage.getRGB(x, y)).isNotEqualTo(focusedImage.getRGB(x, y))
  }

  private fun uniqueRender(functionName: String): File {
    val matches = rendersDir.listFiles { file ->
      file.isFile && file.name.startsWith("$functionName-") && file.extension == "png"
    }
    assertThat(matches).isNotNull()
    assertThat(matches!!.asList()).hasSize(1)
    return matches.single()
  }
}
