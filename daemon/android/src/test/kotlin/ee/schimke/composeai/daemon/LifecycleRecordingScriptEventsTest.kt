package ee.schimke.composeai.daemon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins [LifecycleRecordingScriptEvents] — the Android-only lifecycle-driven script-event
 * descriptor that `RobolectricHost` advertises from `recordingScriptEventDescriptors()`.
 *
 * The split between supported transitions and roadmap (`destroy`) is also pinned in
 * [AndroidRecordingSessionTest] via `lifecycleEventReportsUnsupportedForUnknownTransition`; this
 * test keeps the descriptor side honest.
 */
class LifecycleRecordingScriptEventsTest {

  @Test
  fun `descriptor advertises lifecycle event with supported = true`() {
    val descriptor = LifecycleRecordingScriptEvents.descriptor
    assertEquals("lifecycle", descriptor.id.value)
    val events = descriptor.recordingScriptEvents
    assertEquals(1, events.size)
    val lifecycleEvent = events.single()
    assertEquals(LifecycleRecordingScriptEvents.LIFECYCLE_EVENT, lifecycleEvent.id)
    assertTrue(
      "lifecycle.event must advertise supported = true so record_preview accepts it on Android",
      lifecycleEvent.supported,
    )
  }

  @Test
  fun `supported transitions cover pause resume stop without destroy`() {
    val supported = LifecycleRecordingScriptEvents.SUPPORTED_LIFECYCLE_EVENTS
    assertEquals(setOf("pause", "resume", "stop"), supported)
    assertFalse(
      "destroy must stay out of v1 — moving the scenario to DESTROYED breaks subsequent renders",
      "destroy" in supported,
    )
  }

  @Test
  fun `descriptors convenience list wraps the single descriptor`() {
    assertEquals(
      listOf(LifecycleRecordingScriptEvents.descriptor),
      LifecycleRecordingScriptEvents.descriptors,
    )
  }
}
