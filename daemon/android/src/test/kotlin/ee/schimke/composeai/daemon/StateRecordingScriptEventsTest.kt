package ee.schimke.composeai.daemon

import ee.schimke.composeai.data.render.extensions.RecordingScriptDataExtensions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins [StateRecordingScriptEvents] — the host-owned descriptor that
 * `RobolectricHost.recordingScriptEventDescriptors()` advertises as `supported = true` for the
 * three state-related script events: `state.recreate`, `state.save`, `state.restore`.
 *
 * `state.recreate` is the single-event round-trip primitive (collapses save+restore into one
 * event). The pair `state.save` / `state.restore` is the named-checkpoint model — multiple
 * bundles, restore an arbitrary one by `checkpointId`. Both rely on the same
 * `SaveableStateRegistry` bridge in [RobolectricHost].
 */
class StateRecordingScriptEventsTest {

  @Test
  fun `descriptor advertises state events with supported = true`() {
    val descriptor = StateRecordingScriptEvents.descriptor
    assertEquals("state", descriptor.id.value)
    val ids = descriptor.recordingScriptEvents.map { it.id }.toSet()
    assertEquals(
      setOf(
        StateRecordingScriptEvents.STATE_RECREATE_EVENT,
        StateRecordingScriptEvents.STATE_SAVE_EVENT,
        StateRecordingScriptEvents.STATE_RESTORE_EVENT,
      ),
      ids,
    )
    val allSupported = descriptor.recordingScriptEvents.all { it.supported }
    assertTrue(
      "every state event must advertise supported = true so record_preview accepts it on Android",
      allSupported,
    )
  }

  @Test
  fun `save and restore wire-name ids match the renderer-agnostic constants`() {
    // The wire-name strings are shared with `RecordingScriptDataExtensions.STATE_SAVE_EVENT` /
    // `STATE_RESTORE_EVENT` so older clients (pre-this-PR) and newer clients hit the same id.
    // Drift would silently route to the registry's "no handler" branch.
    assertEquals(
      RecordingScriptDataExtensions.STATE_SAVE_EVENT,
      StateRecordingScriptEvents.STATE_SAVE_EVENT,
    )
    assertEquals(
      RecordingScriptDataExtensions.STATE_RESTORE_EVENT,
      StateRecordingScriptEvents.STATE_RESTORE_EVENT,
    )
  }

  @Test
  fun `descriptors convenience list wraps the single descriptor`() {
    assertEquals(
      listOf(StateRecordingScriptEvents.descriptor),
      StateRecordingScriptEvents.descriptors,
    )
  }
}
