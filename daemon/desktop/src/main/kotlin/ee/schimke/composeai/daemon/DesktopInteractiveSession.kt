package ee.schimke.composeai.daemon

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import ee.schimke.composeai.daemon.protocol.InteractiveInputKind
import ee.schimke.composeai.daemon.protocol.InteractiveInputParams
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Compose-Desktop concrete [InteractiveSession] — the v2 click-into-composition body documented in
 * [INTERACTIVE.md § 9](../../../../../../docs/daemon/INTERACTIVE.md#9-v2--click-dispatch-into-composition).
 *
 * Owns one [HeldScene] across the session's lifetime so `remember { mutableStateOf(...) }`
 * survives between [dispatch] calls. The scene was set up with `LocalInspectionMode = false`
 * (INTERACTIVE.md § 9.5) so previews that branch on `isInspectionMode` show their non-inspection
 * ("real") behaviour and `pointerInput` modifiers actually fire.
 *
 * **Threading.** The hosting [JsonRpcServer] dispatches inputs serially per session — see the
 * threading note on [InteractiveSession]. We don't lock internally; concurrent inputs to the same
 * session are a contract violation, not a correctness concern. Each [dispatch] / [render] call
 * installs the user [HeldScene.classLoader] as the calling thread's context classloader (and
 * restores on the way out) so pointer/key dispatch on a per-input worker thread still resolves
 * user classes the same way `setUp` did.
 *
 * **Ref-counting.** The desktop host shares one `DesktopInteractiveSession` per `previewId` —
 * INTERACTIVE.md § 9.7's "shared session per previewId, ref-counted by streamId". The session
 * itself doesn't track refcounts; that's [DesktopHost]'s job. [close] is the
 * "last-stream-stopped-on-this-preview" notification — the session closes its [HeldScene] and any
 * follow-up [dispatch] is a contract violation.
 */
class DesktopInteractiveSession(
  override val previewId: String,
  private val engine: RenderEngine,
  private val held: HeldScene,
) : InteractiveSession {

  private val closed = AtomicBoolean(false)

  override fun dispatch(input: InteractiveInputParams) {
    check(!closed.get()) { "DesktopInteractiveSession($previewId) is closed" }
    withUserContextClassLoader {
      when (input.kind) {
        InteractiveInputKind.CLICK -> {
          val pos = sceneOffset(input)
          // Compose's `Modifier.clickable` consumes Press / Release through the same
          // pointer-input pipeline; for the gesture to register as a click the framework needs
          // a chance to dispatch the Press event into the composition before we send Release.
          // Render once between the two so the input handler flushes the Press; otherwise both
          // events arrive together and `clickable`'s detector treats them as cancelled.
          //
          // PointerButton.Primary is required: `clickable` only fires for primary-button
          // clicks (the desktop equivalent of touch-tap). Without it the events still
          // dispatch but the modifier's detector ignores them.
          held.scene.sendPointerEvent(
            eventType = PointerEventType.Press,
            position = pos,
            button = PointerButton.Primary,
          )
          held.scene.render()
          held.scene.sendPointerEvent(
            eventType = PointerEventType.Release,
            position = pos,
            button = PointerButton.Primary,
          )
        }
        InteractiveInputKind.POINTER_DOWN -> {
          held.scene.sendPointerEvent(
            eventType = PointerEventType.Press,
            position = sceneOffset(input),
            button = PointerButton.Primary,
          )
        }
        InteractiveInputKind.POINTER_UP -> {
          held.scene.sendPointerEvent(
            eventType = PointerEventType.Release,
            position = sceneOffset(input),
            button = PointerButton.Primary,
          )
        }
        InteractiveInputKind.KEY_DOWN -> dispatchKey(input, awtPressed = true)
        InteractiveInputKind.KEY_UP -> dispatchKey(input, awtPressed = false)
      }
    }
  }

  /**
   * Convert the wire's image-natural pixel coordinates to a Compose [Offset] in scene-px. Compose's
   * `Offset` lives in scene-px which equals natural pixels at density 1.0; for non-default
   * densities the wire coords are divided by the spec's density (INTERACTIVE.md § 9.4).
   */
  private fun sceneOffset(input: InteractiveInputParams): Offset {
    val density = held.spec.density.takeIf { it > 0f } ?: 1f
    val px = (input.pixelX ?: 0).toFloat()
    val py = (input.pixelY ?: 0).toFloat()
    return Offset(px / density, py / density)
  }

  private fun dispatchKey(input: InteractiveInputParams, awtPressed: Boolean) {
    val keyCode = input.keyCode ?: return
    val awtCode = parseAwtKeyCode(keyCode) ?: return
    val component = dummyAwtComponent ?: return
    // ImageComposeScene.sendKeyEvent takes a androidx.compose.ui.input.key.KeyEvent wrapping a
    // native AWT KeyEvent on Desktop. The v2 wire shape only carries a string keycode, so we
    // fall back to the smallest viable mapping (a-z / 0-9 / a few named keys). Richer key
    // dispatch can land in a follow-up; for v2 click-only the path is exercised structurally
    // but pointer events are the common case.
    held.scene.sendKeyEvent(
      androidx.compose.ui.input.key.KeyEvent(
        nativeKeyEvent =
          java.awt.event.KeyEvent(
            component,
            if (awtPressed) java.awt.event.KeyEvent.KEY_PRESSED
            else java.awt.event.KeyEvent.KEY_RELEASED,
            System.currentTimeMillis(),
            0,
            awtCode,
            keyCode.firstOrNull() ?: java.awt.event.KeyEvent.CHAR_UNDEFINED,
          )
      )
    )
  }

  override fun render(requestId: Long): RenderResult {
    check(!closed.get()) { "DesktopInteractiveSession($previewId) is closed" }
    // Two render() calls — same heuristic as RenderEngine.render's one-shot path: gives any
    // LaunchedEffect / recomposition triggered by the just-dispatched input a tick to settle
    // before we encode the PNG (INTERACTIVE.md § 9.2 sketch). renderOnce already calls
    // scene.render() twice; the dispatch() path additionally settled the post-input frame on
    // the way in.
    return withUserContextClassLoader { engine.renderOnce(held, requestId) }
  }

  override fun close() {
    if (!closed.compareAndSet(false, true)) return
    engine.tearDown(held)
  }

  /**
   * Run [block] with the user classloader installed as the calling thread's context classloader,
   * restoring the previous value on exit. The session lives across multiple [JsonRpcServer]
   * worker threads (a fresh thread spins up per `interactive/input`), so we can't rely on a
   * single setUp/tearDown to cover the install — every dispatch / render does its own.
   */
  private inline fun <R> withUserContextClassLoader(block: () -> R): R {
    val previous = Thread.currentThread().contextClassLoader
    Thread.currentThread().contextClassLoader = held.classLoader
    return try {
      block()
    } finally {
      Thread.currentThread().contextClassLoader = previous
    }
  }

  private companion object {
    /**
     * Stand-in AWT component for the synthesized key events — only used as the event source.
     * Lazy because constructing an AWT [java.awt.Label] in a headless JVM throws
     * [java.awt.HeadlessException]; key dispatch is best-effort anyway and the click-only path
     * doesn't touch this. We resolve the component at first use and tolerate the headless
     * case by silently dropping key events.
     */
    private val dummyAwtComponent: java.awt.Component? by lazy {
      try {
        java.awt.Canvas()
      } catch (_: Throwable) {
        null
      }
    }

    /**
     * Best-effort string → AWT virtual-keycode mapping. Recognises single ASCII letters/digits and
     * a small pinned set of named keys; returns `null` for anything we don't recognise so the
     * caller drops the key event silently rather than synthesising a wrong dispatch. We go straight
     * to AWT virtual codes rather than through `androidx.compose.ui.input.key.Key` so we don't
     * couple to Key's (target-specific, Long-typed) constructor surface.
     */
    private fun parseAwtKeyCode(code: String): Int? {
      val trimmed = code.trim()
      if (trimmed.isEmpty()) return null
      if (trimmed.length == 1) {
        val ch = trimmed.first().uppercaseChar()
        return when {
          ch in 'A'..'Z' -> java.awt.event.KeyEvent.VK_A + (ch - 'A')
          ch in '0'..'9' -> java.awt.event.KeyEvent.VK_0 + (ch - '0')
          else -> null
        }
      }
      return when (trimmed.lowercase()) {
        "enter", "return" -> java.awt.event.KeyEvent.VK_ENTER
        "tab" -> java.awt.event.KeyEvent.VK_TAB
        "space" -> java.awt.event.KeyEvent.VK_SPACE
        "escape", "esc" -> java.awt.event.KeyEvent.VK_ESCAPE
        "backspace" -> java.awt.event.KeyEvent.VK_BACK_SPACE
        "delete", "del" -> java.awt.event.KeyEvent.VK_DELETE
        "arrowup", "up" -> java.awt.event.KeyEvent.VK_UP
        "arrowdown", "down" -> java.awt.event.KeyEvent.VK_DOWN
        "arrowleft", "left" -> java.awt.event.KeyEvent.VK_LEFT
        "arrowright", "right" -> java.awt.event.KeyEvent.VK_RIGHT
        else -> null
      }
    }
  }
}
