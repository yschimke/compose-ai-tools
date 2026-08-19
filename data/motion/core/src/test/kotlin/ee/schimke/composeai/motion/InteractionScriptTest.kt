package ee.schimke.composeai.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InteractionScriptTest {

  @Test
  fun `a tap expands to a press and a release around the lead-in`() {
    val timeline =
      InteractionScript.timeline(
        gesture = MotionGesture.TAP,
        targets = listOf(0),
        holdMs = 600,
        gapMs = 700,
        leadInMs = 250,
      )

    assertEquals(
      listOf(
        InteractionPointerEvent(atMs = 250, target = 0, down = true),
        InteractionPointerEvent(atMs = 250 + TAP_PRESS_MS, target = 0, down = false),
      ),
      timeline.events,
    )
    // Lead-in + one press + one settle window — derived, never declared.
    assertEquals(250 + TAP_PRESS_MS + 700, timeline.durationMs)
  }

  @Test
  fun `a press-and-hold dwells for holdMs rather than the tap press`() {
    val timeline =
      InteractionScript.timeline(
        gesture = MotionGesture.PRESS_AND_HOLD,
        targets = listOf(1),
        holdMs = 600,
        gapMs = 700,
        leadInMs = 250,
      )

    assertEquals(250, timeline.events[0].atMs)
    assertEquals(850, timeline.events[1].atMs)
    assertEquals(1, timeline.events[1].target)
    assertEquals(250 + 600 + 700, timeline.durationMs)
  }

  @Test
  fun `repeating a target repeats the gesture on it, which is how a toggle is spelled`() {
    val timeline =
      InteractionScript.timeline(
        gesture = MotionGesture.TAP,
        targets = listOf(0, 0),
        holdMs = 600,
        gapMs = 700,
        leadInMs = 250,
      )

    assertEquals(4, timeline.events.size)
    assertTrue(timeline.events.all { it.target == 0 })
    // The second press opens only after the first has been released and settled.
    assertEquals(250 + TAP_PRESS_MS + 700, timeline.events[2].atMs)
  }

  @Test
  fun `a script longer than the cap still reports what it asked for`() {
    val timeline =
      InteractionScript.timeline(
        gesture = MotionGesture.PRESS_AND_HOLD,
        targets = List(20) { 0 },
        holdMs = 600,
        gapMs = 700,
        leadInMs = 250,
      )

    assertTrue(timeline.durationMs > MAX_INTERACTION_DURATION_MS)
    assertEquals(MAX_INTERACTION_DURATION_MS, timeline.cappedDurationMs)
  }

  @Test
  fun `the canonical frame rates snap to exact rationals and everything else stays literal`() {
    assertEquals(1.toShort() to 60.toShort(), apngDelayFor(16))
    assertEquals(1.toShort() to 60.toShort(), apngDelayFor(17))
    assertEquals(1.toShort() to 50.toShort(), apngDelayFor(20))
    assertEquals(1.toShort() to 30.toShort(), apngDelayFor(33))
    assertEquals(1.toShort() to 25.toShort(), apngDelayFor(40))
    // 50ms is not a canonical rate — it is exactly representable as ms/1000, so it stays literal.
    assertEquals(50.toShort() to 1000.toShort(), apngDelayFor(50))
  }
}
