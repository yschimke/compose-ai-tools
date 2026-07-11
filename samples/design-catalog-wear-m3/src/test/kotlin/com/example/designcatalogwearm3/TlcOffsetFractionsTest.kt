package com.example.designcatalogwearm3

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The single-knob → two-fraction derivation that lets [previewTlcScaling] feed a lone component into
 * the real Wear [androidx.wear.compose.material3.lazy.TransformationSpec]. Pure arithmetic, so it's
 * checked here without a render: the caller gives one number (the centre position) and the companion
 * edge fraction is computed from it plus the measured height, matching
 * `TransformingLazyColumnItemScrollProgress`'s own `top = offset/H`, `bottom = (offset + height)/H`.
 */
class TlcOffsetFractionsTest {

  @Test
  fun `centre knob expands to top and bottom edges half a height apart`() {
    // A 40px item on a 200px screen occupies 0.2 of the screen; its edges sit ±0.1 from the centre.
    val (top, bottom) = tlcOffsetFractions(centerFraction = 0.5f, measuredHeightPx = 40, screenHeightPx = 200)
    assertEquals(0.4f, top, 1e-6f)
    assertEquals(0.6f, bottom, 1e-6f)
    // The two fractions are always exactly the item's screen-relative height apart.
    assertEquals(40f / 200f, bottom - top, 1e-6f)
  }

  @Test
  fun `centred item straddles the screen midpoint symmetrically`() {
    val (top, bottom) = tlcOffsetFractions(centerFraction = 0.5f, measuredHeightPx = 100, screenHeightPx = 200)
    // Symmetric about 0.5 — the fully-on-screen, full-scale resting state.
    assertEquals(0.5f - (bottom - 0.5f), top, 1e-6f)
  }

  @Test
  fun `an item near the top edge reports a negative top fraction`() {
    // Centre at 5% of the screen: the top edge has ridden off the top (negative), the state the
    // TransformationSpec renders as heavily scaled away.
    val (top, bottom) = tlcOffsetFractions(centerFraction = 0.05f, measuredHeightPx = 80, screenHeightPx = 200)
    assertEquals(0.05f - 0.2f, top, 1e-6f)
    assertEquals(0.05f + 0.2f, bottom, 1e-6f)
    assert(top < 0f) { "top edge should be off screen (got $top)" }
  }

  @Test
  fun `sweep steps from full scale down to the most-scaled position, eased toward the edge`() {
    val sweep = tlcSweepFractions(frames = 4, minCenterFraction = 0.07f)
    assertEquals(4, sweep.size)
    // Endpoints: unscaled (centred) first, most-scaled last.
    assertEquals(0.5f, sweep.first(), 1e-4f)
    assertEquals(0.07f, sweep.last(), 1e-4f)
    // Monotonically decreasing (never steps back toward centre).
    assertTrue(sweep.zipWithNext().all { (a, b) -> b < a })
    // Eased toward the edge: the first step off centre is larger than a linear step would be, so the
    // middle frames land in the visible scaling band rather than bunching at full scale.
    val linearFirstStep = (0.5f - 0.07f) / 3f
    assertTrue("first step must be eased past linear", 0.5f - sweep[1] > linearFirstStep)
  }

  @Test
  fun `sweep clamps a degenerate frame count to the two endpoints`() {
    val sweep = tlcSweepFractions(frames = 1, minCenterFraction = 0.1f)
    assertEquals(2, sweep.size)
    assertEquals(0.5f, sweep[0], 1e-4f)
    assertEquals(0.1f, sweep[1], 1e-4f)
  }

  @Test
  fun `a zero or unknown screen height degrades to no scaling offset`() {
    // Guard against a divide-by-zero before the screen is measured: both edges collapse onto the
    // centre, i.e. a zero-height item → the spec leaves it at full scale rather than NaN.
    val (top, bottom) = tlcOffsetFractions(centerFraction = 0.5f, measuredHeightPx = 80, screenHeightPx = 0)
    assertEquals(0.5f, top, 1e-6f)
    assertEquals(0.5f, bottom, 1e-6f)
  }
}
