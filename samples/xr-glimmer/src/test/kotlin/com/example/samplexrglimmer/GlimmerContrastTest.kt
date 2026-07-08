package com.example.samplexrglimmer

import com.google.common.truth.Truth.assertThat
import java.io.File
import javax.imageio.ImageIO
import org.junit.Test

/**
 * Calibrates the Glimmer sample against the two quantitative rules Android Studio's preview pane
 * checks (see [GlimmerContrast]): the **30 PPD / 0.6° = 18px** angular sizing model and the **≥70%
 * HCT tone-difference** contrast bar.
 *
 * Unlike the other tests here, this one doesn't read rendered captures — it computes the same
 * additive composite Studio approximates directly from the **source backdrops** in `src/main/res/`,
 * so it pins the calibration regardless of whether the SDK-37 render path is available. The numbers
 * it asserts are the measured tone gaps of white Glimmer text over each env; they encode, as a
 * regression gate, the qualitative finding the design doc states informally ("you see the
 * unreadable text in Busy. Good."):
 *
 * - **Additive-zero** (the SKILL-mandated `Color.Black` base) is the *only* surface that clears
 *   Studio's 70-tone bar — legibility is guaranteed only against true black.
 * - **Dark** (night cityscape) is the most legible *real* backdrop but still sits below 70 once the
 *   translucent `surface` tint lifts the panel.
 * - **Busy** (bright market) and **VeniceCanalCats** fall far below the bar — measurably
 *   unreadable, exactly what the env chips are for.
 *
 * A future regression that brightens Glimmer's `surface` token, swaps a backdrop for a darker one,
 * or breaks the additive blend shifts these gaps and trips the relevant bound below.
 */
class GlimmerContrastTest {

  private val drawables = File("src/main/res/drawable-nodpi")

  private fun backdropToneGap(name: String): Double {
    val file = File(drawables, name)
    assertThat(file.exists()).isTrue()
    return GlimmerContrast.meanTextToneGap(ImageIO.read(file))
  }

  @Test
  fun `device spec encodes Studio's 30 PPD angular model at density 1_0`() {
    // The const is the single source of truth shared by every Glimmer preview; parse it back so a
    // future edit re-checks the identities below instead of silently drifting.
    val pattern = Regex("""spec:width=(\d+),height=(\d+),dpi=(\d+)""")
    val m = pattern.matchEntire(AI_GLASSES_DEVICE_SPEC)
    assertThat(m).isNotNull()
    val (w, h, dpi) = m!!.destructured.toList().map { it.toInt() }

    // density 1.0 is what makes 18sp == 18px == 0.6° hold (the calibration's whole point).
    assertThat(dpi / 160.0).isWithin(1e-9).of(1.0)
    // 0.6° minimum text × 30 PPD == 18px, and at density 1.0 that is 18sp.
    assertThat(GlimmerContrast.minReadableTextPx()).isWithin(1e-9).of(18.0)
    // Canvas is the same 960×720 px the env backdrops are authored at, spanning 32°×24° at 30 PPD.
    assertThat(w).isEqualTo(960)
    assertThat(h).isEqualTo(720)
    assertThat(w / GlimmerContrast.PIXELS_PER_DEGREE).isWithin(1e-9).of(32.0)
    assertThat(h / GlimmerContrast.PIXELS_PER_DEGREE).isWithin(1e-9).of(24.0)
  }

  @Test
  fun `additive-zero is the only surface that clears Studio's 70 tone bar`() {
    val gap = GlimmerContrast.additiveZeroToneGap()
    assertThat(gap).isAtLeast(GlimmerContrast.STUDIO_MIN_TONE_DIFFERENCE)
    assertThat(gap).isWithin(5.0).of(85.0)
  }

  @Test
  fun `legibility degrades from dark to busy to venice, all below the bar`() {
    val dark = backdropToneGap("env_dark.jpg")
    val busy = backdropToneGap("env_busy.jpg")
    val venice = backdropToneGap("env_venice_canal_cats.jpg")
    val bar = GlimmerContrast.STUDIO_MIN_TONE_DIFFERENCE

    // Measured calibration (white text vs surface-tinted panel, mean over the whole backdrop).
    assertThat(dark).isWithin(6.0).of(64.0)
    assertThat(busy).isWithin(6.0).of(42.0)
    assertThat(venice).isWithin(6.0).of(37.0)

    // Ordering: darker, calmer backdrops are more legible; additive-zero beats them all.
    assertThat(GlimmerContrast.additiveZeroToneGap()).isGreaterThan(dark)
    assertThat(dark).isGreaterThan(busy)
    assertThat(busy).isGreaterThan(venice)

    // Studio's bar: no real backdrop clears it — busy/venice are decisively unreadable.
    assertThat(dark).isLessThan(bar)
    assertThat(busy).isLessThan(bar)
    assertThat(venice).isLessThan(bar)
  }
}
