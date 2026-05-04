package ee.schimke.composeai.daemon

import ee.schimke.composeai.data.render.extensions.RecordingScriptDataExtensions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins [PreviewReloadRecordingScriptEvents] — the host-owned descriptor that
 * `RobolectricHost.recordingScriptEventDescriptors()` advertises as `supported = true` for the
 * `preview.reload` script event.
 */
class PreviewReloadRecordingScriptEventsTest {

  @Test
  fun `descriptor advertises preview reload with supported = true`() {
    val descriptor = PreviewReloadRecordingScriptEvents.descriptor
    assertEquals("preview", descriptor.id.value)
    val events = descriptor.recordingScriptEvents
    assertEquals(1, events.size)
    val reload = events.single()
    assertEquals(PreviewReloadRecordingScriptEvents.PREVIEW_RELOAD_EVENT, reload.id)
    assertTrue(
      "preview.reload must advertise supported = true so record_preview accepts it on Android",
      reload.supported,
    )
  }

  @Test
  fun `wire-name id matches the renderer-agnostic constant`() {
    // The wire-name string is shared with `RecordingScriptDataExtensions.PREVIEW_RELOAD_EVENT`
    // so older clients (pre-this-PR) and newer clients hit the same id. Drift would silently
    // route to the registry's "no handler" branch.
    assertEquals(
      RecordingScriptDataExtensions.PREVIEW_RELOAD_EVENT,
      PreviewReloadRecordingScriptEvents.PREVIEW_RELOAD_EVENT,
    )
  }

  @Test
  fun `descriptors convenience list wraps the single descriptor`() {
    assertEquals(
      listOf(PreviewReloadRecordingScriptEvents.descriptor),
      PreviewReloadRecordingScriptEvents.descriptors,
    )
  }
}
