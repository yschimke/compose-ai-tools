package ee.schimke.composeai.renderer

import org.junit.Assert.assertEquals
import org.junit.Test

/** Coverage for the pure frame → focus-stop mapping that drives the TalkBack walk (issue #1956). */
class TalkBackOverlayFramesTest {

  @Test
  fun dwellsOnEachStopThenAdvances() {
    val fps = 30
    val dwell = 900L
    // First ~0.9s on stop 0, next on stop 1, etc.
    assertEquals(
      0,
      TalkBackOverlayFrames.focusedStopForFrame(0, fps, stopCount = 3, dwellMs = dwell),
    )
    assertEquals(
      0,
      TalkBackOverlayFrames.focusedStopForFrame(26, fps, stopCount = 3, dwellMs = dwell),
    )
    // frame 27 → 900ms → stop 1
    assertEquals(
      1,
      TalkBackOverlayFrames.focusedStopForFrame(27, fps, stopCount = 3, dwellMs = dwell),
    )
    assertEquals(
      2,
      TalkBackOverlayFrames.focusedStopForFrame(54, fps, stopCount = 3, dwellMs = dwell),
    )
  }

  @Test
  fun holdsOnLastStopPastEndOfWalk() {
    // Way past the walk's end: clamp to the final stop, never out of range.
    assertEquals(2, TalkBackOverlayFrames.focusedStopForFrame(10_000, 30, stopCount = 3))
  }

  @Test
  fun noStopsYieldsNoFocus() {
    assertEquals(-1, TalkBackOverlayFrames.focusedStopForFrame(0, 30, stopCount = 0))
    assertEquals(0, TalkBackOverlayFrames.totalFrames(30, stopCount = 0))
  }

  @Test
  fun totalFramesCoversEveryStopPlusFinalHold() {
    // 3 stops × 900ms = 2700ms; at 30fps → 81 frames + 1 final = 82.
    assertEquals(82, TalkBackOverlayFrames.totalFrames(30, stopCount = 3, dwellMs = 900L))
  }

  @Test
  fun degenerateFpsOrDwellFallsBackToFirstStop() {
    assertEquals(0, TalkBackOverlayFrames.focusedStopForFrame(5, fps = 0, stopCount = 3))
    assertEquals(
      0,
      TalkBackOverlayFrames.focusedStopForFrame(5, fps = 30, stopCount = 3, dwellMs = 0L),
    )
  }
}
