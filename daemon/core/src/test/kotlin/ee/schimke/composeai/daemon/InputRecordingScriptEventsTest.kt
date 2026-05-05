package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.InteractiveInputKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins [InputTouchRecordingScriptEvents] and [InputKeyboardRecordingScriptEvents] — the
 * renderer-agnostic input-event descriptors that hosts advertise from
 * `recordingScriptEventDescriptors()`. The Wear-only `input.rsb` extension is pinned in its own
 * Android-side test (`InputRsbRecordingScriptEventsTest`).
 *
 * The wire-name strings here also have to match the keys of [INTERACTIVE_INPUT_KIND_BY_WIRE_NAME]
 * so the live-mode `RecordingInputParams` synthesis path translates the typed
 * [InteractiveInputKind] enum to the same id the descriptor advertises.
 */
class InputRecordingScriptEventsTest {

  @Test
  fun `input touch advertises four pointer events as supported`() {
    val descriptor = InputTouchRecordingScriptEvents.descriptor
    assertEquals("input.touch", descriptor.id.value)
    val ids = descriptor.recordingScriptEvents.map { it.id }.toSet()
    assertEquals(
      setOf(
        InputTouchRecordingScriptEvents.CLICK_EVENT,
        InputTouchRecordingScriptEvents.POINTER_DOWN_EVENT,
        InputTouchRecordingScriptEvents.POINTER_MOVE_EVENT,
        InputTouchRecordingScriptEvents.POINTER_UP_EVENT,
      ),
      ids,
    )
    val allSupported = descriptor.recordingScriptEvents.all { it.supported }
    assertTrue(
      "every input.touch entry must advertise supported = true on every host that advertises this",
      allSupported,
    )
  }

  @Test
  fun `input touch wire-name set is the public WIRED_EVENTS`() {
    assertEquals(
      InputTouchRecordingScriptEvents.descriptor.recordingScriptEvents.map { it.id }.toSet(),
      InputTouchRecordingScriptEvents.WIRED_EVENTS,
    )
  }

  @Test
  fun `input keyboard advertises both keys as roadmap`() {
    val descriptor = InputKeyboardRecordingScriptEvents.descriptor
    assertEquals("input.keyboard", descriptor.id.value)
    val ids = descriptor.recordingScriptEvents.map { it.id }.toSet()
    assertEquals(
      setOf(
        InputKeyboardRecordingScriptEvents.KEY_DOWN_EVENT,
        InputKeyboardRecordingScriptEvents.KEY_UP_EVENT,
      ),
      ids,
    )
    val anySupported = descriptor.recordingScriptEvents.any { it.supported }
    assertFalse(
      "input.keyboard must stay supported = false until key dispatch lands on either backend",
      anySupported,
    )
  }

  @Test
  fun `wire-name table aligns with input descriptor ids for every typed enum`() {
    // Live mode synthesises a recording-script event from a typed `RecordingInputParams` via
    // `kind.wireName()`. Each enum entry must map to a wire-name string the descriptor side
    // also advertises — otherwise the registry's lookup misses and the event silently falls
    // through to the unsupported branch.
    val advertisedTouchIds = InputTouchRecordingScriptEvents.WIRED_EVENTS
    val advertisedKeyboardIds =
      InputKeyboardRecordingScriptEvents.descriptor.recordingScriptEvents.map { it.id }.toSet()
    // input.rotaryScroll is advertised by the Android-only `input.rsb` extension; check it via
    // the wire-name table without depending on the Android module here.
    val rotaryWireName = "input.rotaryScroll"
    val totalWireNames = advertisedTouchIds + advertisedKeyboardIds + rotaryWireName
    assertEquals(
      "every InteractiveInputKind enum must have a matching wire name advertised by an input " +
        "extension",
      InteractiveInputKind.entries.map { it.wireName() }.toSet(),
      totalWireNames,
    )
  }
}
