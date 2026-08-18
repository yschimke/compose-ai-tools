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
 * Pins [SharedElementFilmstripPreview] to the fractions its labels claim (issue #4097).
 *
 * The filmstrip's whole value is being the *static, diffable* counterpart to the GIF preview, and
 * it lost that: each panel used to seek its own `SeekableTransitionState` to a constant fraction,
 * and `seekTo` takes a fraction of a **total duration that shared-element transitions keep
 * changing** — so every run froze the panels somewhere slightly different and the visual-diff bot
 * reported a change on PRs that touch nothing near this file. Ten renders across five unrelated
 * PRs, and run-to-run variation (up to 11% of the image) larger than the base→head difference the
 * bot attributed to the PR.
 *
 * What is asserted is the invariant that broke, not the bytes: the five container widths must sit
 * where the transition's own easing puts them at 0/25/50/75/100% of the way through. A panel frozen
 * at the wrong point in the transition — the failure mode — moves its container by far more than
 * [WIDTH_TOLERANCE_PX], while an intentional restyle of the filmstrip moves *every* panel and is
 * meant to be reviewed as a diff rather than caught here.
 *
 * Reads the PNG `:samples:android:composePreviewRenderAll` produced (`renderBeforeUnitTests`), the
 * same way the other pixel tests in this module do.
 */
class SharedElementFilmstripPixelTest {

  private val rendersDir = File("build/compose-previews/renders")

  /**
   * The capture is time-pinned, so its filename carries the structural `_TIME_<ms>ms` suffix — the
   * evidence in the render tree that this preview is frozen by the clock rather than by a seek.
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
     * How far a panel's container may sit from where the easing puts it, in device pixels.
     *
     * The image is 893px wide at 2.625x density, and the seek-driven version moved panels by 100px
     * and more between runs of the same commit. 14px is roughly 3% of the collapsed→expanded
     * travel: wide enough for the antialiased edge the scan below picks up and for a rounding hair
     * in the easing, far too narrow for a panel frozen at the wrong point.
     */
    const val WIDTH_TOLERANCE_PX = 14

    /** `Color(0xFFE8DEF8)` — the container fill both poses paint. */
    val CONTAINER_RGB = Triple(0xE8, 0xDE, 0xF8)

    /**
     * Channel slack for the antialiased edges and the cross-fade the panels are captured mid-way
     * through.
     */
    const val CHANNEL_TOLERANCE = 6

    /** A row with fewer container pixels than this is a label row or the gap between panels. */
    const val MIN_RUN_PX = 20
  }

  /**
   * Width of each panel's container, top to bottom.
   *
   * Scans for rows carrying the container fill and groups contiguous rows into panels — the panels
   * are separated by bands of pure surface, so the grouping is unambiguous without knowing any
   * panel's y coordinate. The widest row of a band is the container's own width (a narrower row
   * would be one clipped by the rounded corners).
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
