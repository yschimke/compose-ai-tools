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
    val resting = uniqueRender("FilledButton")
    val pressedImage = ImageIO.read(pressed)
    val focusedImage = ImageIO.read(focused)
    val restingImage = ImageIO.read(resting)

    // This point is inside the rounded container of all three captures and outside every label, so
    // it reads the container fill rather than glyph pixels. The three states must land on three
    // different fills: resting #E9DDFF, focused #D4C8EC (Compose draws the focus state layer
    // itself), pressed #C2B5DB (Wear M3's only press affordance is `material-ripple`, a platform
    // `RippleDrawable` the renderer settles only on a `@FocusedPreview(pressed = true)` capture —
    // a hand-seeded `PressInteraction` renders as #E9DDFF, i.e. as no press at all).
    val x = 30
    val y = 68
    assertThat(pressedImage.getRGB(x, y)).isNotEqualTo(focusedImage.getRGB(x, y))
    assertThat(pressedImage.getRGB(x, y)).isNotEqualTo(restingImage.getRGB(x, y))
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
