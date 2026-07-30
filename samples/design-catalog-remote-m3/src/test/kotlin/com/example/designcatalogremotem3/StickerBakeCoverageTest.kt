package com.example.designcatalogremotem3

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import javax.imageio.ImageIO
import org.junit.Test

/**
 * Guards that no sticker in this sheet bakes to a **fully transparent** PNG.
 *
 * Why it needs its own test rather than being caught downstream: every parity lane that scores this
 * catalog flattens the baked capture and the player's render onto the same mid-grey before diffing
 * (the stickers are light content on transparency, so compositing over white would let them read as
 * identical to an empty canvas). A blank *reference* therefore flattens to the same pixels as a
 * player that drew nothing — the comparison is vacuous, and it scores a perfect 0.00%. `rc-compare`
 * now refuses to score such a row (#2933), which stops the false green, but that is the differ's
 * end: it reports the row as unscored and moves on. Nothing fails, and the catalog keeps shipping a
 * sticker with no pixels in it.
 *
 * This is that missing end. It fails the build instead, at the point where the capture is produced —
 * which is also the only place that can tell "this preview draws nothing" from "this preview was
 * never rendered".
 *
 * Alpha > 8 is the same threshold the differ uses for "opaque" (see
 * `scripts/design-artifacts/rc-compare-pixels.mjs`), so the two agree on what blank means.
 *
 * Deliberately *not* a minimum-coverage assertion: several stickers are legitimately sparse — a
 * one-word outlined button, a hairline card border, a single icon on a 200dp canvas all land under
 * 1% — and a floor tight enough to catch anything real would fail them. "Something was drawn" is the
 * invariant that holds for every sticker in the sheet.
 */
class StickerBakeCoverageTest {

  private val rendersDir = File("build/compose-previews/renders")

  /** Alpha above which a pixel counts as drawn; matches `rc-compare-pixels.mjs`. */
  private val opaqueAlpha = 8

  @Test
  fun `the render produced captures to check`() {
    // Without this the coverage test below would pass vacuously if the render stopped emitting PNGs
    // (the chained `renderBeforeUnitTests` in build.gradle.kts is what puts them here).
    assertThat(rendersDir.listFiles { f -> f.name.endsWith(".png") }.orEmpty()).isNotEmpty()
  }

  @Test
  fun `every baked sticker carries at least one drawn pixel`() {
    val blank =
      rendersDir
        .listFiles { f -> f.name.endsWith(".png") }
        .orEmpty()
        .sorted()
        .filterNot { png ->
          val image = ImageIO.read(png)
          (0 until image.height).any { y ->
            (0 until image.width).any { x -> (image.getRGB(x, y) ushr 24) > opaqueAlpha }
          }
        }
        .map { it.name }
    assertWithMessage("these stickers baked to a fully transparent PNG — nothing was drawn")
      .that(blank)
      .isEmpty()
  }
}
