package com.example.samplexrglimmer

import com.google.common.truth.Truth.assertThat
import ee.schimke.composeai.glimmer.GlimmerEnvironmentCompositor
import javax.imageio.ImageIO
import org.junit.Test

/**
 * Calibrates the Glimmer sample against Studio's two quantitative rules (see [GlimmerContrast]): 30
 * PPD / 0.6° = 18px sizing and the ≥70 HCT tone-difference bar. Computes the additive composite
 * from the source backdrops directly, so it doesn't need the SDK-37 render path.
 *
 * Pinned measured gaps: only additive-zero (pure black) clears 70; Dark is the most legible real
 * backdrop but below the bar; Busy and VeniceCanalCats are far below. A change to the `surface`
 * token, a backdrop, or the additive blend trips a bound.
 */
class GlimmerContrastTest {

  private fun backdropToneGap(name: String): Double {
    val image =
      GlimmerEnvironmentCompositor::class
        .java
        .getResourceAsStream("/glimmer-environments/$name")
        ?.use(ImageIO::read)
    assertThat(image).isNotNull()
    return GlimmerContrast.meanTextToneGap(image!!)
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
