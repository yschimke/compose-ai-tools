package com.example.designcatalogwearm3

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The scaling-level → scroll-offset mapping that positions the single item in [TlcScalingHost]'s real
 * `TransformingLazyColumn`. Pure arithmetic, checked here without a render.
 */
class TlcScrollOffsetTest {

  @Test
  fun `level 0 is the resting offset (no scroll, full scale)`() {
    assertEquals(0, tlcScrollOffsetPx(0f, screenHeightPx = 454))
  }

  @Test
  fun `level 1 rides to the peak offset near the top edge`() {
    // Peak is ~0.46 of the screen.
    assertEquals((454 * 0.46f).toInt(), tlcScrollOffsetPx(1f, screenHeightPx = 454))
  }

  @Test
  fun `the sweep is monotonic and eased toward the edge`() {
    val offsets = listOf(0f, 0.25f, 0.5f, 0.75f, 1f).map { tlcScrollOffsetPx(it, 454) }
    // Strictly increasing.
    assertTrue(offsets.zipWithNext().all { (a, b) -> b > a })
    // Eased: the first quarter covers more than a linear quarter of the peak (front-loaded scaling).
    val peak = offsets.last()
    assertTrue("first step must be eased past linear", offsets[1] > peak / 4)
  }

  @Test
  fun `level is clamped to the unit range`() {
    assertEquals(0, tlcScrollOffsetPx(-1f, 454))
    assertEquals(tlcScrollOffsetPx(1f, 454), tlcScrollOffsetPx(2f, 454))
  }
}
