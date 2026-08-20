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

    // This point is inside the rounded container of all three captures and outside every label, so
    // it reads the container fill rather than glyph pixels. The three states land on three
    // different fills: resting #E9DDFF, focused #D4C8EC (Compose draws the focus state layer
    // itself), pressed #C5B8DE (Wear M3's only press affordance is `material-ripple`, a platform
    // `RippleDrawable` the renderer settles only on a `@FocusedPreview(pressed = true)` capture —
    // a hand-seeded `PressInteraction` renders as #E9DDFF, i.e. as no press at all).
    val x = 30
    val y = 68
    val pressedFill = ImageIO.read(pressed).getRGB(x, y)
    val focusedFill = ImageIO.read(focused).getRGB(x, y)
    val restingFill = ImageIO.read(resting).getRGB(x, y)

    // Distance, not inequality. The failure this exists to catch does not arrive as "pressed equals
    // focused" — it arrives as a ripple that only partly settled. Before `settlePressedRipple`
    // forced the ripple's software path, the platform ripple animated on a RenderThread Robolectric
    // does not have, and how far it had got when the shutter fell was a function of how many
    // preview rows had rendered ahead of this one: measured at `shards = 1`, #C2B5DB with 3 rows
    // ahead, #D5C8EC with 11, #D4C8EC behind the full catalog. Only the last of those is equal to
    // the focused fill, so an inequality assertion passes the middle one — a capture one channel
    // step away from focus-only, published as `pressed`.
    assertChannelsApart(pressedFill, focusedFill, "pressed", "focused")
    assertChannelsApart(pressedFill, restingFill, "pressed", "resting")
  }

  /**
   * Asserts every RGB channel of [a] differs from [b] by at least [MIN_CHANNEL_DELTA].
   *
   * Reports the two fills as hex on failure — the value is the diagnosis here (a fill equal to the
   * focused one means no press at all; one a few steps from it means the ripple did not settle),
   * and Truth's own message for two packed `Int` colours is unreadable.
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
     * Minimum per-channel separation between the pressed fill and its two neighbours.
     *
     * Sized off the settled values rather than picked round: a settled press sits ~14–16 steps from
     * the focused fill (#C5B8DE vs #D4C8EC) and ~33–37 from the resting one, while the
     * under-settled capture this guards against sat 1 step away. 8 is comfortably between, so the
     * threshold separates "settled" from "barely moved" without pinning an exact colour that a
     * legitimate palette change would have to come here to update.
     */
    const val MIN_CHANNEL_DELTA = 8
  }
}
