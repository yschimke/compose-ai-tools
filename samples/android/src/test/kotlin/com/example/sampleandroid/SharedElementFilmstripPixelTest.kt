package com.example.sampleandroid

import androidx.compose.animation.core.FastOutSlowInEasing
import com.google.common.truth.Truth.assertThat
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Test

/**
 * Pins [SharedElementFilmstripPreview] to the fractions its labels claim. Asserts the invariant,
 * not the bytes: the five container widths sit where the transition's easing puts them at
 * 0/25/50/75/100%. A panel frozen at the wrong point moves far more than [WIDTH_TOLERANCE_PX]; an
 * intentional restyle moves every panel and belongs in a reviewed diff. Reads the rendered PNG
 * (`renderBeforeUnitTests`).
 */
class SharedElementFilmstripPixelTest {

  private val rendersDir = File("build/compose-previews/renders")

  /**
   * The capture is time-pinned, so its filename carries the `_TIME_<ms>ms` suffix.
   */
  private val timeSuffix = "_TIME_${FILMSTRIP_CAPTURE_MS}ms"

  @Test
  fun `each panel is frozen at its labelled fraction of the container transform`() {
    val file =
      renderFile(rendersDir, "SharedElementFilmstripPreview_Shared_Element_Filmstrip", timeSuffix)
    assertThat(file.exists()).isTrue()

    val widths = containerWidths(ImageIO.read(file))
    assertThat(widths).hasSize(FILMSTRIP_FRACTIONS.size)

    // The 0% and 100% panels are the transition's own endpoints, so they define the scale the
    // in-between panels are measured against rather than being asserted against a literal.
    val collapsed = widths.first().toFloat()
    val expanded = widths.last().toFloat()
    assertThat(expanded).isGreaterThan(collapsed * 1.5f)

    FILMSTRIP_FRACTIONS.forEachIndexed { index, fraction ->
      val expected =
        (collapsed + (expanded - collapsed) * FastOutSlowInEasing.transform(fraction)).roundToInt()
      assertThat(abs(widths[index] - expected)).isAtMost(WIDTH_TOLERANCE_PX)
    }
  }

  @Test
  fun `panel durations put each fraction at the pinned capture instant`() {
    // duration = window / fraction, so window / duration == fraction at the capture. The 0% panel
    // never starts a transition and is not in this table.
    FILMSTRIP_FRACTIONS.filter { it > 0f }
      .forEach { fraction ->
        val duration = panelDurationMillis(fraction)
        assertThat(FILMSTRIP_WINDOW_MS.toFloat() / duration).isWithin(0.001f).of(fraction)
      }
  }

  private companion object {
    /**
     * How far a panel's container may sit from its eased position, in device pixels: ~3% of the
     * collapsed→expanded travel, enough for antialiasing and rounding, far too little for a panel
     * frozen at the wrong point.
     */
    const val WIDTH_TOLERANCE_PX = 14

    /** `Color(0xFFE8DEF8)` — the container fill both poses paint. */
    val CONTAINER_RGB = Triple(0xE8, 0xDE, 0xF8)

    /** Channel slack for antialiased edges and the mid-capture cross-fade. */
    const val CHANNEL_TOLERANCE = 6

    /** A row with fewer container pixels than this is a label row or the gap between panels. */
    const val MIN_RUN_PX = 20
  }

  /**
   * Width of each panel's container, top to bottom: rows carrying the container fill grouped into
   * contiguous bands (panels are separated by plain surface); the widest row of a band avoids
   * rounded-corner rows.
   */
  private fun containerWidths(image: BufferedImage): List<Int> {
    val widths = mutableListOf<Int>()
    var currentMax = 0
    for (y in 0 until image.height) {
      val run = (0 until image.width).count { x -> isContainer(image.getRGB(x, y)) }
      if (run > MIN_RUN_PX) {
        currentMax = maxOf(currentMax, run)
      } else if (currentMax > 0) {
        widths += currentMax
        currentMax = 0
      }
    }
    if (currentMax > 0) widths += currentMax
    return widths
  }

  private fun isContainer(argb: Int): Boolean {
    val (r, g, b) = CONTAINER_RGB
    return abs(((argb shr 16) and 0xff) - r) <= CHANNEL_TOLERANCE &&
      abs(((argb shr 8) and 0xff) - g) <= CHANNEL_TOLERANCE &&
      abs((argb and 0xff) - b) <= CHANNEL_TOLERANCE
  }
}
