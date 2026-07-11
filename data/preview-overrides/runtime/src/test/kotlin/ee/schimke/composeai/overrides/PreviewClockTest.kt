package ee.schimke.composeai.overrides

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewClockTest {

  @Test
  fun `fixedPreviewClock reports its pinned instant`() {
    val clock = fixedPreviewClock(1_700_000_000_000)
    assertEquals(1_700_000_000_000, clock.nowEpochMillis())
    // Stable across reads — a pinned clock never advances.
    assertEquals(1_700_000_000_000, clock.nowEpochMillis())
  }

  @Test
  fun `SystemPreviewClock tracks real wall-clock time`() {
    val before = System.currentTimeMillis()
    val now = SystemPreviewClock.nowEpochMillis()
    val after = System.currentTimeMillis()
    assertTrue("system clock reads within the sampling window", now in before..after)
  }

  @Test
  fun `PreviewClock is a functional interface`() {
    val clock = PreviewClock { 42 }
    assertEquals(42, clock.nowEpochMillis())
  }
}
