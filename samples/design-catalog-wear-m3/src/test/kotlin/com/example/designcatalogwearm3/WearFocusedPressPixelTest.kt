package com.example.designcatalogwearm3

import com.google.common.truth.Truth.assertThat
import java.io.File
import javax.imageio.ImageIO
import org.junit.Test

/** End-to-end guard for the focused-key press fallback used by Wear M3 `combinedClickable`. */
class WearFocusedPressPixelTest {

  private val rendersDir = File("build/compose-previews/renders")

  @Test
  fun `pressed capture changes the button container beyond the focused state`() {
    val pressed = uniqueRender("ButtonPressed")
    val focused = uniqueRender("ButtonFocused")
    val pressedImage = ImageIO.read(pressed)
    val focusedImage = ImageIO.read(focused)

    // This point is inside the rounded container and outside both labels. Before the renderer's
    // DPAD_CENTER fallback, both pixels were #D4C8EC: the requested Press never reached Wear M3's
    // combinedClickable interaction source, so `pressed = true` only captured focus.
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
