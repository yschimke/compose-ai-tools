package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.RecordingScriptEvent

/**
 * `RecordingScriptDispatchObserver` that wakes [AmbientStateController] on activating input
 * gestures. Mirrors the AOSP `AmbientLifecycleObserver` wake semantics — only the gestures the
 * Wear OS system itself wakes on are listed:
 *
 * - `input.click` / `input.pointerDown` — touch-driven activation.
 * - `input.rotaryScroll` — rotary side-button activation.
 *
 * `pointerMove` / `pointerUp` / keyboard kinds are intentionally absent so a multi-pointer drag
 * inside ambient mode doesn't flip state away on its own intermediate events.
 */
class AmbientInputDispatchObserver : RecordingScriptDispatchObserver {
  override fun beforeDispatch(event: RecordingScriptEvent, ctx: RecordingDispatchContext) {
    if (event.kind in WAKE_KINDS) AmbientStateController.notifyUserInput(ctx.tNanos)
  }

  companion object {
    val WAKE_KINDS: Set<String> =
      setOf(
        InputTouchRecordingScriptEvents.CLICK_EVENT,
        InputTouchRecordingScriptEvents.POINTER_DOWN_EVENT,
        // `input.rotaryScroll` lives on `InputRsbRecordingScriptEvents` in `:daemon:android`,
        // which the connector can't depend on (circular). The wire string is stable per the
        // descriptor's `id` field, so we hard-code it here — kept in sync via
        // `AmbientInputDispatchObserverTest`'s round-trip assertion.
        "input.rotaryScroll",
      )
  }
}
