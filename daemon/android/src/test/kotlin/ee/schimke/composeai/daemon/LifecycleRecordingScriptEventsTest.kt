package ee.schimke.composeai.daemon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins [LifecycleRecordingScriptEvents] — the Android-only lifecycle-driven script-event
 * descriptor that `RobolectricHost` advertises from `recordingScriptEventDescriptors()`.
 *
 * Each supported transition has its own event id (`lifecycle.pause` / `lifecycle.resume` /
 * `lifecycle.stop`) so the MCP validator can reject unknown transitions at the kind level. The
 * dispatch routing is pinned in [AndroidRecordingSessionTest]; this test keeps the descriptor
 * side honest.
 */
class LifecycleRecordingScriptEventsTest {

  @Test
  fun `descriptor advertises one lifecycle event id per wired transition`() {
    val descriptor = LifecycleRecordingScriptEvents.descriptor
    assertEquals("lifecycle", descriptor.id.value)
    val ids = descriptor.recordingScriptEvents.map { it.id }.toSet()
    assertEquals(
      setOf(
        LifecycleRecordingScriptEvents.LIFECYCLE_PAUSE_EVENT,
        LifecycleRecordingScriptEvents.LIFECYCLE_RESUME_EVENT,
        LifecycleRecordingScriptEvents.LIFECYCLE_STOP_EVENT,
      ),
      ids,
    )
    val allSupported = descriptor.recordingScriptEvents.all { it.supported }
    assertTrue(
      "every wired lifecycle event id must advertise supported = true",
      allSupported,
    )
  }

  @Test
  fun `wired event set covers pause resume stop without destroy`() {
    assertEquals(
      setOf("lifecycle.pause", "lifecycle.resume", "lifecycle.stop"),
      LifecycleRecordingScriptEvents.WIRED_EVENTS,
    )
    assertTrue(
      "destroy must stay out of v1 — moving the scenario to DESTROYED breaks subsequent renders",
      "lifecycle.destroy" !in LifecycleRecordingScriptEvents.WIRED_EVENTS,
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
