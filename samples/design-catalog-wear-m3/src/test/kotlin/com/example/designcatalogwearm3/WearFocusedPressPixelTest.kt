package com.example.designcatalogwearm3

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import org.junit.Test

/** End-to-end guard that the documented Wear pressed specimen is not a focus-only capture. */
class WearFocusedPressPixelTest {

  private val rendersDir = File("build/compose-previews/renders")

  @Test
  fun `pressed capture changes the button container beyond the focused state`() {
    val pressed = uniqueRender("ButtonPressed")
    val focused = uniqueRender("ButtonFocused")
    val resting = uniqueRender("FilledButton")

    // Inside the container of all three captures and outside every label: resting #E9DDFF, focused
    // #D4C8EC, pressed #C5B8DE (Wear M3's press affordance is a platform `RippleDrawable` the
    // renderer settles only for `@FocusedPreview(pressed = true)`).
    val x = 30
    val y = 68
    val pressedFill = ImageIO.read(pressed).getRGB(x, y)
    val focusedFill = ImageIO.read(focused).getRGB(x, y)
    val restingFill = ImageIO.read(resting).getRGB(x, y)

    // Distance, not inequality: the failure mode is a partly-settled ripple a channel step or two
    // from the focused fill, which an inequality would pass.
    assertChannelsApart(pressedFill, focusedFill, "pressed", "focused")
    assertChannelsApart(pressedFill, restingFill, "pressed", "resting")
  }

  /**
   * Asserts every RGB channel of [a] differs from [b] by at least [MIN_CHANNEL_DELTA], reporting
   * both fills as hex on failure.
   */
  private fun assertChannelsApart(a: Int, b: Int, aName: String, bName: String) {
    val deltas =
      listOf(16, 8, 0).map { shift -> abs((a shr shift and 0xFF) - (b shr shift and 0xFF)) }
    assertWithMessage(
        "$aName ${a.hex()} vs $bName ${b.hex()}: per-channel deltas $deltas, " +
          "need every channel >= $MIN_CHANNEL_DELTA"
      )
      .that(deltas.min())
      .isAtLeast(MIN_CHANNEL_DELTA)
  }

  private fun Int.hex(): String = "#%06X".format(this and 0xFFFFFF)

  private fun uniqueRender(functionName: String): File {
    val matches = rendersDir.listFiles { file ->
      file.isFile && file.name.startsWith("$functionName-") && file.extension == "png"
    }
    assertThat(matches).isNotNull()
    assertThat(matches!!.asList()).hasSize(1)
    return matches.single()
  }

  private companion object {
    /**
     * Minimum per-channel separation: a settled press is ~14–16 steps from focused and ~33–37 from
     * resting, while an unsettled one was ~1 step away.
     */
    const val MIN_CHANNEL_DELTA = 8
  }
}
