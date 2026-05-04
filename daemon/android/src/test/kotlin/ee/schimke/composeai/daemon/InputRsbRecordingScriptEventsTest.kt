package ee.schimke.composeai.daemon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins [InputRsbRecordingScriptEvents] — the Wear-only rotary side-button descriptor that
 * `RobolectricHost` advertises (and `DesktopHost` doesn't). The matching wire-name id is also
 * advertised through `INTERACTIVE_INPUT_KIND_BY_WIRE_NAME` so the typed live-mode dispatch path
 * resolves to the same string.
 */
class InputRsbRecordingScriptEventsTest {

  @Test
  fun `descriptor advertises rotary scroll as supported`() {
    val descriptor = InputRsbRecordingScriptEvents.descriptor
    assertEquals("input.rsb", descriptor.id.value)
    assertEquals(1, descriptor.recordingScriptEvents.size)
    val rotary = descriptor.recordingScriptEvents.single()
    assertEquals(InputRsbRecordingScriptEvents.ROTARY_SCROLL_EVENT, rotary.id)
    assertTrue(
      "input.rotaryScroll must advertise supported = true on the Wear-capable host",
      rotary.supported,
    )
  }

  @Test
  fun `descriptors convenience list wraps the single descriptor`() {
    assertEquals(
      listOf(InputRsbRecordingScriptEvents.descriptor),
      InputRsbRecordingScriptEvents.descriptors,
    )
  }
}
