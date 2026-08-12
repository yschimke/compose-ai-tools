@file:OptIn(
  androidx.compose.ui.InternalComposeUiApi::class,
  androidx.compose.ui.ExperimentalComposeUiApi::class,
)

package ee.schimke.composeai.daemon

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.scene.ComposeScenePointer
import ee.schimke.composeai.daemon.protocol.InteractivePointerType

/**
 * The one desktop implementation of "turn a wire input into a scene event", shared by
 * [DesktopInteractiveSession] (the live `interactive/input` lane) and [DesktopRecordingSession]
 * (the `recording/script` + live-tick lane).
 *
 * The two lanes used to carry their own copies. That is how the recording lane ended up unable to
 * type or mouse-select long after both were fixed for interactive input: the fixes landed in one
 * copy (issues #3491 / #3504) and the other kept dispatching a code-point-less `KeyEvent` through a
 * pointer path pinned to [PointerType.Touch] (issue #3545). With one implementation, a fix to key
 * or pointer translation is a fix to both lanes by construction.
 *
 * Everything here is scene-thread confined — the callers already pin their scene touches (the
 * interactive session to its `sceneExecutor`, the recording session to its playback / tick thread),
 * so nothing in this file synchronises.
 */

/** A pointer's scene-space position plus the device class it is being synthesised as. */
internal data class ScenePointer(val offset: Offset, val type: PointerType)

/**
 * Multi-pointer dispatch over a held [ImageComposeScene], tracking each pressed pointer by id so
 * every `sendPointerEvent` carries the full set of currently-down fingers.
 *
 * That grouping is the load-bearing part: Skiko's `List<ComposeScenePointer>` overload is what
 * gives `Modifier.transformable {}` its rotation / zoom / pan callbacks, and the detector only
 * fires when it sees ≥ 2 pointers *in a single event*. Dispatching each finger separately reads as
 * two independent drags.
 *
 * @param scene the held scene, read lazily so a session can hand over its `state.scene` without
 *   this class holding the session's `RenderEngine.SceneState`.
 * @param defaultTimeMillis event timestamp used when a call site passes `timeMillis = null` — the
 *   interactive lane's wall-clock frame time. Scripted playback always passes its virtual `tMs`.
 * @param defaultFrameNanos frame clock used for the settling render [press] runs when a call site
 *   passes `frameNanos = null`. Same contract as [defaultTimeMillis]: wall clock live, virtual
 *   `tNanos` under scripted playback.
 * @param settleFrame renders the throwaway frame that settles a [press]. A lambda rather than a
 *   `scene().render()` here because that frame has to carry the session's whole render discipline —
 *   the `localeTag` JVM-default-`Locale` scope, and closing the snapshot it allocates. See
 *   [RenderEngine.renderSettlingFrame].
 */
internal class ScenePointerDispatch(
  private val scene: () -> ImageComposeScene,
  private val defaultTimeMillis: () -> Long,
  private val defaultFrameNanos: () -> Long,
  private val settleFrame: (nanoTime: Long) -> Unit,
) {

  /**
   * Per-pointer-id active state. Read and written only from the caller's scene thread, so a plain
   * [MutableMap] is safe. Cleared per id on release.
   */
  private val active: MutableMap<Int, ScenePointer> = mutableMapOf()

  /**
   * The device class pointer [id] was pressed as, or [fallback] when it isn't currently down.
   *
   * A drag's moves and its release must stay the same device Compose saw go down, or the selection
   * gesture the press started is handed a foreign device mid-stream and drops it.
   */
  fun heldTypeOr(id: Int, fallback: PointerType): PointerType = active[id]?.type ?: fallback

  /**
   * Press pointer [id] at [offset] and **settle the press with one render** before returning.
   *
   * The settling frame is load-bearing, not a nicety. Compose's gesture detectors are coroutines
   * suspended in `awaitPointerEventScope`; the press only *becomes* the anchor of a gesture once
   * that coroutine has run. Dispatching a Move into the same scene touch — which is exactly what a
   * browser drag does, since the viewer defers the press until the first move and then sends both
   * in one tick — hands Compose a moving pointer whose down it has not processed yet. For a text
   * field that means the mouse-selection observer never gets `onStart(pressPosition)`, so the drag
   * extends from whatever the caret happened to be on instead of from where the user pressed
   * (issue #3697): a drag that ends past the end of the text then paints no selection at all.
   *
   * The click fast-paths already did this by hand between their press and release, for the same
   * reason `Modifier.clickable {}` needs it; doing it inside [press] makes every press — click,
   * live drag, scripted drag — carry the guarantee.
   */
  fun press(
    id: Int,
    offset: Offset,
    type: PointerType,
    timeMillis: Long? = null,
    frameNanos: Long? = null,
  ) {
    active[id] = ScenePointer(offset, type)
    send(PointerEventType.Press, timeMillis)
    settleFrame(frameNanos ?: defaultFrameNanos())
  }

  fun move(id: Int, offset: Offset, type: PointerType, timeMillis: Long? = null) {
    active[id] = ScenePointer(offset, heldTypeOr(id, type))
    send(PointerEventType.Move, timeMillis)
  }

  /**
   * Drop pointer [id] and dispatch the release.
   *
   * The released pointer is removed from [active] *before* the dispatch but passed in explicitly,
   * so Compose sees it with `pressed = false` alongside any still-down fingers. Dropping it and
   * dispatching only the remaining actives would deliver a Move-shaped event and the gesture
   * detector would never see the "finger lifted" signal.
   */
  fun release(id: Int, offset: Offset, type: PointerType, timeMillis: Long? = null) {
    val held = heldTypeOr(id, type)
    active.remove(id)
    send(PointerEventType.Release, timeMillis, ScenePointer(offset, held) to id)
  }

  /**
   * Wheel / rotary scroll at [offset]. Positive [deltaY] means wheel-down (the browser convention).
   *
   * [timeMillis] is honoured only when non-null: the interactive lane leaves Skiko's own default in
   * place, which is what it did before this class existed.
   */
  fun scroll(offset: Offset, deltaY: Float, timeMillis: Long? = null) {
    if (timeMillis == null) {
      scene()
        .sendPointerEvent(
          eventType = PointerEventType.Scroll,
          position = offset,
          scrollDelta = Offset(0f, deltaY),
        )
    } else {
      scene()
        .sendPointerEvent(
          eventType = PointerEventType.Scroll,
          position = offset,
          timeMillis = timeMillis,
          scrollDelta = Offset(0f, deltaY),
        )
    }
  }

  private fun send(
    eventType: PointerEventType,
    timeMillis: Long?,
    releasedPointer: Pair<ScenePointer, Int>? = null,
  ) {
    val pointers = buildList {
      for ((pid, pointer) in active) {
        add(
          ComposeScenePointer(
            id = PointerId(pid.toLong()),
            position = pointer.offset,
            pressed = true,
            type = pointer.type,
          )
        )
      }
      if (releasedPointer != null) {
        val (pointer, pid) = releasedPointer
        add(
          ComposeScenePointer(
            id = PointerId(pid.toLong()),
            position = pointer.offset,
            pressed = false,
            type = pointer.type,
          )
        )
      }
    }
    // Defensive — every call site provides either an active set, a released pointer, or both.
    if (pointers.isEmpty()) return
    val anyPressed = pointers.any { it.pressed }
    scene()
      .sendPointerEvent(
        eventType = eventType,
        pointers = pointers,
        buttons = PointerButtons(isPrimaryPressed = anyPressed),
        timeMillis = timeMillis ?: defaultTimeMillis(),
        button = if (eventType == PointerEventType.Press) PointerButton.Primary else null,
      )
  }
}

/**
 * The one desktop key-dispatch implementation, shared by the interactive and recording lanes.
 *
 * Both halves of a keystroke go out: the physical [Key] (so a consumer's `Modifier.onKeyEvent` sees
 * the key it expects, and the command keys — arrows, Backspace, Delete, Home/End — that map off
 * `Key` alone keep working) and the *typed character*, which is the only half that can actually
 * insert into a `TextField`.
 */
internal object SceneKeyDispatch {

  /**
   * Dispatch a key-down carrying [keyCode] (decimal-string Android `KEYCODE_*`) and/or the literal
   * [text] it typed. Returns `false` when neither half is dispatchable — an unmapped keycode with
   * no printable text — so the interactive lane can drop it silently while the recording lane
   * reports `unsupported` evidence.
   */
  fun keyDown(scene: ImageComposeScene, keyCode: String?, text: String?): Boolean {
    val key = androidKeycodeToComposeKey(keyCode)
    val typed = printableText(text)
    if (key == null && typed == null) return false
    // Mirror the press into the soft-keyboard band so an agent driving keyboard input sees the
    // matching cap light up. The band's "press implies visible" rule in
    // `KeyboardController.softInputVisible` also raises the band even if the consumer never called
    // `keyboardController.show()`.
    KeyboardBandLabels.fromAndroidKeycode(keyCode)?.let(KeyboardController::notifyKeyDown)
    if (key != null) scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyDown))
    // Then the typed character, as a real AWT `KEY_TYPED` event. This second dispatch is what makes
    // typing work: Compose desktop's `KeyEvent.isTypedEvent` — the gate on `KeyCommand.TYPE`, i.e.
    // "insert this character into the text field" — asks the event for its backing AWT event and
    // requires `id == KEY_TYPED` with a printable `keyChar`. The synthesised `KeyEvent(key,
    // KeyDown)` above has no AWT event at all, so it can only ever drive the *command* keys. That
    // asymmetry is exactly why caret movement and deletion worked while typing did nothing.
    // One event per UTF-16 unit: an AWT `KEY_TYPED` carries a single `char`, so an astral code
    // point (an emoji) travels as its surrogate pair, which is exactly how AWT delivers one.
    // Compose appends each unit and the pair lands as the one character it is.
    typed?.forEach { scene.sendKeyEvent(typedKeyEvent(key, it)) }
    return true
  }

  /**
   * Dispatch a key-up for [keyCode]. Returns `false` for an unmapped code.
   *
   * No typed-character counterpart: AWT emits `KEY_TYPED` only between press and release, and
   * Compose only inserts on `KeyDown`.
   */
  fun keyUp(scene: ImageComposeScene, keyCode: String?): Boolean {
    val key = androidKeycodeToComposeKey(keyCode) ?: return false
    KeyboardBandLabels.fromAndroidKeycode(keyCode)?.let(KeyboardController::notifyKeyUp)
    scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyUp))
    return true
  }
}

/**
 * The wire's `pointerType` as the Compose device class Skiko dispatches with. Absent / unrecognised
 * ⇒ [PointerType.Touch], the behaviour every client had before the field existed.
 */
internal fun composePointerType(wire: String?): PointerType =
  when (InteractivePointerType.parse(wire)) {
    InteractivePointerType.MOUSE -> PointerType.Mouse
    InteractivePointerType.PEN -> PointerType.Stylus
    InteractivePointerType.TOUCH -> PointerType.Touch
  }

/**
 * The text [text] types, or `null` when there is nothing typeable in it — absent, empty, more than
 * one code point, or a non-printing one (control characters, and the `Shift` / `ArrowLeft` style
 * key *names* the browser also puts in `KeyboardEvent.key`).
 *
 * One *code point*, which is not the same as one `Char`: an emoji is a single character the client
 * will happily send (its `Array.from(key).length` is 1) but two UTF-16 units, and measuring in
 * `Char`s would drop it on the floor here while the Android lane inserted it. The returned string
 * is one code point, so it is either one `Char` or a surrogate pair.
 */
internal fun printableText(text: String?): String? {
  if (text.isNullOrEmpty()) return null
  if (text.codePointCount(0, text.length) != 1) return null
  val codePoint = text.codePointAt(0)
  if (Character.isISOControl(codePoint)) return null
  val block = Character.UnicodeBlock.of(codePoint)
  if (block == null || block == Character.UnicodeBlock.SPECIALS) return null
  return text
}

/**
 * A Compose `KeyDown` carrying [ch] as typed text: the code point Compose inserts, plus a synthetic
 * AWT `KEY_TYPED` as the event's `nativeEvent`. The AWT event is the load-bearing half —
 * `isTypedEvent` reaches through to it and requires `id == KEY_TYPED` with a printable `keyChar`
 * before it will map the event to `KeyCommand.TYPE`.
 *
 * [key] is the physical key when the wire named one, so a consumer's `Modifier.onKeyEvent` still
 * sees a coherent event; typing works either way, since the mapping falls through to `isTypedEvent`
 * for any key that carries no unmodified command of its own.
 *
 * The AWT source component is a bare [java.awt.Canvas], never shown or added to a hierarchy — AWT
 * only refuses a *null* source. `KEY_TYPED` carries no key code by definition (`VK_UNDEFINED`); the
 * character is the whole payload.
 */
internal fun typedKeyEvent(key: Key?, ch: Char): KeyEvent =
  KeyEvent(
    key = key ?: Key.Unknown,
    type = KeyEventType.KeyDown,
    codePoint = ch.code,
    nativeEvent =
      java.awt.event.KeyEvent(
        typedEventSource,
        java.awt.event.KeyEvent.KEY_TYPED,
        System.currentTimeMillis(),
        0,
        java.awt.event.KeyEvent.VK_UNDEFINED,
        ch,
      ),
  )

/** Lazily built so a process that never types never touches AWT component construction. */
private val typedEventSource: java.awt.Component by lazy { java.awt.Canvas() }
