package com.example.designcatalogwearm3

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The three [TlcScalePosition] key points [TlcScalingHost] scrolls its item to. */
class TlcScrollOffsetTest {

  @Test
  fun `middle is the resting position (no scroll, full scale)`() {
    assertEquals(0f, TlcScalePosition.Middle.scrollFraction, 0f)
  }

  @Test
  fun `positions ramp middle to starting to edge`() {
    val fractions = TlcScalePosition.entries.map { it.scrollFraction }
    assertEquals(listOf(TlcScalePosition.Middle, TlcScalePosition.Starting, TlcScalePosition.Edge), TlcScalePosition.entries)
    assertTrue("scroll must increase middle -> starting -> edge", fractions.zipWithNext().all { (a, b) -> b > a })
  }

  @Test
  fun `edge stays on screen (scroll fraction below half a screen)`() {
    // A > 0.5 fraction would scroll the item's centre past the top edge and off screen.
    assertTrue(TlcScalePosition.Edge.scrollFraction < 0.5f)
  }
}
