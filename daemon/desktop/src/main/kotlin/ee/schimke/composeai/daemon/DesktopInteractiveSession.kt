package ee.schimke.composeai.daemon

import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import ee.schimke.composeai.daemon.protocol.InteractiveInputKind
import ee.schimke.composeai.daemon.protocol.InteractiveInputParams

/**
 * Desktop concrete [InteractiveSession] holding a long-lived
 * [androidx.compose.ui.ImageComposeScene] (via [RenderEngine.SceneState]) so `remember {
 * mutableStateOf(...) }` survives across `interactive/input` notifications.
 *
 * See
 * [INTERACTIVE.md § 9](../../../../../../docs/daemon/INTERACTIVE.md#9-v2--click-dispatch-into-composition)
 * for the v2 design.
 *
 * **Wire-event translation.**
 * - `CLICK` → `Press` then `Release` at the same position. Mirrors the Compose-test convention
 *   (`SemanticsNodeInteraction.performClick`) and what a real mouse click materialises into.
 * - `POINTER_DOWN` / `POINTER_UP` → single `Press` / `Release`.
 * - `KEY_DOWN` / `KEY_UP` → no-op for v2 — `BaseComposeScene.sendKeyEvent` requires a fully
 *   constructed `KeyEvent` whose factory is platform-specific (Skiko key codes diverge from AWT).
 *   Reserved on the wire; the v2 desktop body deliberately doesn't synthesise key events. v3 takes
 *   them on with a proper key-code translation table.
 *
 * **Pixel coords.** `interactive/input` carries image-natural pixel coords (the same pixel space
 * the renderer renders to — see `INTERACTIVE.md § 6/§ 7`). `ImageComposeScene.sendPointerEvent`
 * takes scene-px which equals natural pixels at density 1.0; we divide by the held density before
 * dispatch. Null coords (e.g. for keyboard events, which we no-op anyway) skip the dispatch.
 *
 * **Threading.** Per the [InteractiveSession] contract, `JsonRpcServer` serialises calls to one
 * instance — dispatch and render run on the same fire-and-forget worker thread per input. Skiko
 * isn't thread-safe, so the contract matches the underlying constraint.
 */
class DesktopInteractiveSession(
  override val previewId: String,
  private val engine: RenderEngine,
  private val state: RenderEngine.SceneState,
  private val sandboxStats: SandboxLifecycleStats,
  /**
   * Fired exactly once after [close] flips `closed = true` and tears down the held scene. Mirrors
   * [AndroidInteractiveSession.onCloseHook] so the same JsonRpcServer-side cleanup wiring works
   * across both backends. DesktopHost has no idle-lease watchdog today (only explicit close + the
   * D5 listener wrapper drive close), so on desktop the hook is effectively the same as
   * `interactiveSessionListener.onSessionLifecycle(_, scene = null)` — but routing it through the
   * same shape as Android keeps the host-shared API uniform and gives the desktop daemon a free
   * hook point for any future async-close path.
   */
  private val onCloseHook: (() -> Unit)? = null,
) : InteractiveSession {

  @Volatile private var closed: Boolean = false

  override val isClosed: Boolean
    get() = closed

  override fun dispatch(input: InteractiveInputParams) {
    if (closed) return
    val px = input.pixelX
    val py = input.pixelY
    when (input.kind) {
      InteractiveInputKind.CLICK -> {
        if (px == null || py == null) return
        val offset = sceneOffset(px, py)
        // Press → render-tick → Release. The render tick between the two dispatches gives
        // Compose's gesture-detector coroutine a chance to observe the down event before the up
        // arrives — without it, `Modifier.clickable {}`'s `detectTapGestures` can race the two
        // events and miss the tap. The pattern matches what the Compose UI test harness's
        // `performClick` does internally.
        // Press carries `button = PointerButton.Primary` + matching `buttons` because
        // `Modifier.clickable` filters on the primary mouse button (or first finger on touch);
        // omitting these makes the detector treat the event as ambiguous and ignore it.
        val nowNs = engine.currentFrameNanoTime()
        val nowMs = nowNs / 1_000_000L
        state.scene.sendPointerEvent(
          eventType = PointerEventType.Press,
          position = offset,
          timeMillis = nowMs,
          button = PointerButton.Primary,
          buttons = PointerButtons(isPrimaryPressed = true),
        )
        state.scene.render(nanoTime = nowNs)
        state.scene.sendPointerEvent(
          eventType = PointerEventType.Release,
          position = offset,
          timeMillis = nowMs + CLICK_HOLD_MS,
          button = PointerButton.Primary,
          buttons = PointerButtons(),
        )
      }
      InteractiveInputKind.POINTER_DOWN -> {
        if (px == null || py == null) return
        state.scene.sendPointerEvent(
          eventType = PointerEventType.Press,
          position = sceneOffset(px, py),
          button = PointerButton.Primary,
          buttons = PointerButtons(isPrimaryPressed = true),
        )
      }
      InteractiveInputKind.POINTER_MOVE -> {
        if (px == null || py == null) return
        state.scene.sendPointerEvent(
          eventType = PointerEventType.Move,
          position = sceneOffset(px, py),
          button = PointerButton.Primary,
          buttons = PointerButtons(isPrimaryPressed = true),
        )
      }
      InteractiveInputKind.POINTER_UP -> {
        if (px == null || py == null) return
        state.scene.sendPointerEvent(
          eventType = PointerEventType.Release,
          position = sceneOffset(px, py),
          button = PointerButton.Primary,
          buttons = PointerButtons(),
        )
      }
      InteractiveInputKind.ROTARY_SCROLL,
      InteractiveInputKind.KEY_DOWN,
      InteractiveInputKind.KEY_UP -> {
        // No-op for v2 — see class KDoc. Wire shape accepts the input so the daemon doesn't reject
        // a forward-looking client; the dispatch is silently dropped here until v3 wires
        // sendKeyEvent.
      }
    }
  }

  override fun render(requestId: Long, advanceTimeMs: Long?): RenderResult {
    check(!closed) { "DesktopInteractiveSession.render() called after close()" }
    return engine.renderOnce(
      state,
      requestId,
      sandboxStats = sandboxStats,
      useWallClockFrameTime = true,
    )
  }

  override fun close() {
    if (closed) return
    closed = true
    engine.tearDown(state)
    if (onCloseHook != null) {
      try {
        onCloseHook.invoke()
      } catch (t: Throwable) {
        System.err.println(
          "compose-ai-daemon: DesktopInteractiveSession: onCloseHook threw " +
            "(${t.javaClass.simpleName}: ${t.message}); continuing"
        )
      }
    }
  }

  /**
   * Convert image-natural pixel coords (what `interactive/input` carries on the wire) to
   * scene-space [androidx.compose.ui.geometry.Offset] for `sendPointerEvent`. The scene's density
   * scales between the two coordinate systems.
   */
  private fun sceneOffset(px: Int, py: Int): androidx.compose.ui.geometry.Offset {
    val d = state.density.density
    return androidx.compose.ui.geometry.Offset(px.toFloat() / d, py.toFloat() / d)
  }

  /** For tests that want to peek at the held scene's identity without exposing it permanently. */
  internal fun heldScene(): androidx.compose.ui.ImageComposeScene = state.scene

  companion object {
    /**
     * Synthetic hold time between Press and Release for a CLICK. 100 ms matches what Compose's UI
     * test harness uses by default and is well above `detectTapGestures`'s long-press threshold
     * floor — long enough to register as an unambiguous tap, short enough that the click feels
     * instant to the human.
     */
    private const val CLICK_HOLD_MS: Long = 100L
  }
}
