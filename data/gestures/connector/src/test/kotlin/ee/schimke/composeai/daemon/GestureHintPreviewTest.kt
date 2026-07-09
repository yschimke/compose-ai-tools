package ee.schimke.composeai.daemon

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the shape of the gesture-hint capture API that `GestureHintShowcase` and the sample previews
 * depend on: the three capture modes and the pulse-timing contract a `@AnimatedPreview(durationMs =
 * GESTURE_HINT_PULSE_CYCLE_MS)` relies on. A silent change to either would desync the animated
 * capture window from the pulse the showcase drives.
 */
class GestureHintPreviewTest {

  @Test
  fun `capture modes are the documented three`() {
    assertEquals(
      listOf("HIDDEN", "SHOWN", "ANIMATED"),
      GestureHintCapture.entries.map { it.name },
    )
  }

  @Test
  fun `pulse cycle is one full grow-then-shrink of the half-period`() {
    // The `@AnimatedPreview` window must span a full Reverse cycle (grow + shrink), i.e. twice the
    // tween's half-period, so the captured GIF shows the complete show -> hide motion once.
    assertEquals(GESTURE_HINT_PULSE_MS * 2, GESTURE_HINT_PULSE_CYCLE_MS)
  }
}
