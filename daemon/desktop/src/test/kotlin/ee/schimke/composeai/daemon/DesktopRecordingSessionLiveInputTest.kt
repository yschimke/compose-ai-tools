package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.InteractiveInputKind
import ee.schimke.composeai.daemon.protocol.RecordingInputParams
import ee.schimke.composeai.daemon.protocol.SemanticsInputTarget
import ee.schimke.composeai.data.layoutinspector.SemanticsTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Regression coverage for compose-ai-tools#1360 finding #2: live-mode multi-touch was broken
 * because `DesktopRecordingSession.toScriptEvent` collapsed every live-recorded input to pointer 0,
 * regardless of what the wire payload said. Two-finger pinches in `live = true` recordings never
 * reached Compose's gesture pipeline as simultaneous pointers, so `Modifier.transformable {}`'s
 * zoom / rotate callbacks silently no-opped despite the multi-pointer dispatch the same PR
 * advertised.
 *
 * The mapping itself is pure data, so testing it in isolation — without spinning up a
 * `RenderEngine` / `ImageComposeScene` per assertion — is the cheapest way to pin the contract. The
 * end-to-end behaviour is covered by [TouchOverlayPinchRecordingTest] in scripted mode; once the
 * data shape carries `pointerId`, the same multi-pointer dispatch path the scripted test exercises
 * runs unchanged for live mode.
 */
class DesktopRecordingSessionLiveInputTest {

  @Test
  fun `toScriptEvent threads pointerId through from RecordingInputParams to RecordingScriptEvent`() {
    val fingerA =
      RecordingInputParams(
        recordingId = "live-rec",
        kind = InteractiveInputKind.POINTER_DOWN,
        pixelX = 100,
        pixelY = 200,
        pointerId = 7,
      )
    val fingerB =
      RecordingInputParams(
        recordingId = "live-rec",
        kind = InteractiveInputKind.POINTER_DOWN,
        pixelX = 300,
        pixelY = 400,
        pointerId = 11,
      )

    val eventA = fingerA.toScriptEvent(tMs = 33L)
    val eventB = fingerB.toScriptEvent(tMs = 33L)

    assertEquals("pointerId must round-trip from RecordingInputParams", 7, eventA.pointerId)
    assertEquals("pointerId must round-trip from RecordingInputParams", 11, eventB.pointerId)
    // The load-bearing assertion: two fingers at the same virtual `tMs` must carry distinct ids so
    // the multi-pointer dispatch in `dispatchMultiPointer` keys them apart. Before the fix, both
    // would have surfaced as `pointerId = null` (→ collapsed to 0) and Compose's gesture pipeline
    // would have treated them as two updates to one finger.
    assertNotEquals(
      "pinch fingers at the same tMs must NOT collapse to the same pointer id",
      eventA.pointerId,
      eventB.pointerId,
    )
  }

  @Test
  fun `toScriptEvent passes through null pointerId for backwards compatibility`() {
    // Pre-fix wire shape (no `pointerId`) still works: `null` carries through, and the dispatch
    // path's `pointerIdOrDefault` falls back to `0` — same behaviour single-pointer scripts get.
    val singleFinger =
      RecordingInputParams(
        recordingId = "live-rec",
        kind = InteractiveInputKind.CLICK,
        pixelX = 50,
        pixelY = 75,
        // pointerId omitted on purpose — defaults to null.
      )

    val event = singleFinger.toScriptEvent(tMs = 0L)

    assertEquals(null, event.pointerId)
    assertEquals(50, event.pixelX)
    assertEquals(75, event.pixelY)
    assertEquals("input.click", event.kind)
  }

  @Test
  fun `toScriptEvent preserves non-pointer fields for keyDown and rotaryScroll events`() {
    val keyDown =
      RecordingInputParams(
        recordingId = "live-rec",
        kind = InteractiveInputKind.KEY_DOWN,
        keyCode = "29", // KEYCODE_A
      )
    val scroll =
      RecordingInputParams(
        recordingId = "live-rec",
        kind = InteractiveInputKind.ROTARY_SCROLL,
        pixelX = 100,
        pixelY = 100,
        scrollDeltaY = 1.5f,
      )

    val keyEvent = keyDown.toScriptEvent(tMs = 10L)
    val scrollEvent = scroll.toScriptEvent(tMs = 20L)

    assertEquals("29", keyEvent.keyCode)
    assertEquals("input.keyDown", keyEvent.kind)
    assertEquals(1.5f, scrollEvent.scrollDeltaY!!, 0.0001f)
    assertEquals("input.rotaryScroll", scrollEvent.kind)
  }

  @Test
  fun `toScriptEvent threads a semantic target through for agent-driven live recordings`() {
    // The record-live bridge (issue #2047): an agent driving a live recording by handle posts a
    // target on the wire, which must survive into the captured script verbatim (no pixel math).
    val byHandle =
      RecordingInputParams(
        recordingId = "live-rec",
        kind = InteractiveInputKind.CLICK,
        target = SemanticsInputTarget(testTag = "submit"),
      )

    val event = byHandle.toScriptEvent(tMs = 5L)

    assertEquals(SemanticsInputTarget(testTag = "submit"), event.target)
    assertEquals(null, event.pixelX)
    assertEquals("input.click", event.kind)
  }

  @Test
  fun `toInputTarget projects each resolved handle variant onto the wire target`() {
    assertEquals(
      SemanticsInputTarget(testTag = "submit"),
      SemanticsTarget.Tag("submit").toInputTarget(),
    )
    assertEquals(
      SemanticsInputTarget(text = "Save"),
      SemanticsTarget.RoleText(text = "Save").toInputTarget(),
    )
    assertEquals(
      SemanticsInputTarget(role = "Button", text = "Save"),
      SemanticsTarget.RoleText(role = "Button", text = "Save").toInputTarget(),
    )
    assertEquals(
      SemanticsInputTarget(ref = "r/role:Button"),
      SemanticsTarget.Ref("r/role:Button").toInputTarget(),
    )
  }
}
