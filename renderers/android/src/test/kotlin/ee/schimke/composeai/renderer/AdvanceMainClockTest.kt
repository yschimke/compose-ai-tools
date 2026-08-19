package ee.schimke.composeai.renderer

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the frame split [advanceMainClockBy] drives (issue #4247): whole frames through the ordinary
 * rounded advance, the sub-frame remainder through `ignoreFrameDuration` so an exact coordinate
 * lands on `N` instead of the next 16ms boundary past it.
 */
class AdvanceMainClockTest {

  @Test
  fun `a frame-aligned advance has no remainder`() {
    assertEquals(512L to 0L, splitOwedAdvance(512L))
    assertEquals(32L to 0L, splitOwedAdvance(CAPTURE_ADVANCE_MS_FOR_TEST))
    assertEquals(16L to 0L, splitOwedAdvance(16L))
  }

  @Test
  fun `a non-aligned advance keeps its whole frames and its remainder apart`() {
    // The values this repo actually ships through the exact path: `SharedElementFilmstripPreview`
    // (600), `SpinnerTimelinePreview` (500 / 1500).
    assertEquals(592L to 8L, splitOwedAdvance(600L))
    assertEquals(496L to 4L, splitOwedAdvance(500L))
    assertEquals(1488L to 12L, splitOwedAdvance(1500L))
    // The sum is always the requested advance — that is what makes the landing exact.
    listOf(600L, 500L, 1500L, 150L, 1L, 17L).forEach {
      val (frames, remainder) = splitOwedAdvance(it)
      assertEquals(it, frames + remainder)
    }
  }

  @Test
  fun `a sub-frame advance is all remainder`() {
    assertEquals(0L to 1L, splitOwedAdvance(1L))
    assertEquals(0L to 15L, splitOwedAdvance(15L))
  }

  @Test
  fun `a non-positive advance is a no-op`() {
    assertEquals(0L to 0L, splitOwedAdvance(0L))
    assertEquals(0L to 0L, splitOwedAdvance(-40L))
  }

  private companion object {
    // `RobolectricRenderTest.CAPTURE_ADVANCE_MS` is private to the render loop; restated here so a
    // change to the default advance shows up as a failure to reconcile rather than silently.
    const val CAPTURE_ADVANCE_MS_FOR_TEST = 32L
  }
}
