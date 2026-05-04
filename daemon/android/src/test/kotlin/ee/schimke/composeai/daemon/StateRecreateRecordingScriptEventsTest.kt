package ee.schimke.composeai.daemon

import ee.schimke.composeai.data.render.extensions.RecordingScriptDataExtensions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins [StateRecreateRecordingScriptEvents] — the host-owned descriptor that
 * `RobolectricHost.recordingScriptEventDescriptors()` advertises as `supported = true` for the
 * `state.recreate` script event. Distinct from the still-roadmap `state.save` / `state.restore`
 * pair (the principled multi-checkpoint model) — `state.recreate` is the simpler single-event
 * round-trip primitive.
 */
class StateRecreateRecordingScriptEventsTest {

  @Test
  fun `descriptor advertises state recreate with supported = true`() {
    val descriptor = StateRecreateRecordingScriptEvents.descriptor
    assertEquals("state.recreate", descriptor.id.value)
    val events = descriptor.recordingScriptEvents
    assertEquals(1, events.size)
    val recreate = events.single()
    assertEquals(StateRecreateRecordingScriptEvents.STATE_RECREATE_EVENT, recreate.id)
    assertTrue(
      "state.recreate must advertise supported = true so record_preview accepts it on Android",
      recreate.supported,
    )
  }

  @Test
  fun `wire-name id is distinct from state save and state restore`() {
    // `state.recreate` is its own primitive — collapsing save+restore into one round-trip event.
    // The roadmap `state.save` / `state.restore` constants advertise the future named-checkpoint
    // model and must keep their own ids.
    assertNotEquals(
      StateRecreateRecordingScriptEvents.STATE_RECREATE_EVENT,
      RecordingScriptDataExtensions.STATE_SAVE_EVENT,
    )
    assertNotEquals(
      StateRecreateRecordingScriptEvents.STATE_RECREATE_EVENT,
      RecordingScriptDataExtensions.STATE_RESTORE_EVENT,
    )
  }

  @Test
  fun `descriptors convenience list wraps the single descriptor`() {
    assertEquals(
      listOf(StateRecreateRecordingScriptEvents.descriptor),
      StateRecreateRecordingScriptEvents.descriptors,
    )
  }
}
