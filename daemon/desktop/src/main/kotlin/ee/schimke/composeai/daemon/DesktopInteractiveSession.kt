@file:OptIn(
  androidx.compose.ui.InternalComposeUiApi::class,
  androidx.compose.ui.ExperimentalComposeUiApi::class,
)

package ee.schimke.composeai.daemon

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
import ee.schimke.composeai.daemon.protocol.InteractiveInputKind
import ee.schimke.composeai.daemon.protocol.InteractiveInputParams
import ee.schimke.composeai.daemon.protocol.InteractivePointerType
import ee.schimke.composeai.data.layoutinspector.SemanticsTarget
import ee.schimke.composeai.data.layoutinspector.SemanticsTargets
import ee.schimke.composeai.data.layoutinspector.TargetResolution
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

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
 * - `KEY_DOWN` / `KEY_UP` → Compose `KeyEvent(key, KeyEventType.KeyDown/KeyUp)` via
 *   [androidKeycodeToComposeKey] — wire `keyCode` is the decimal-string Android `KEYCODE_*` value
 *   (issue #1203). Unmapped codes drop silently so a forward-looking client can't crash the
 *   dispatch loop.
 * - `ROTARY_SCROLL` → `Scroll` pointer event at the supplied pixel coords with `scrollDelta.y =
 *   scrollDeltaY`. Reuses the existing pointer pipeline; positive deltaY means wheel-down (same
 *   convention as a browser wheel).
 *
 * **Pixel coords.** `interactive/input` carries image-natural pixel coords (the same pixel space
 * the renderer renders to — see `INTERACTIVE.md § 6/§ 7`). `ImageComposeScene.sendPointerEvent`
 * takes scene-px which equals natural pixels at density 1.0; we divide by the held density before
 * dispatch. Null coords (e.g. for keyboard events, which we no-op anyway) skip the dispatch.
 *
 * **Threading.** Every scene touch — `setUp` (run by [DesktopHost] before construction), every
 * `dispatch`, every `render`, the optional [onSceneClose] hook, and the final `tearDown` — is
 * pinned to [sceneExecutor], a single-thread executor that this session owns and disposes on
 * [close]. That confines the recomposer's `LaunchedEffect` coroutines (which inherit the scene's
 * default `Dispatchers.Unconfined` and therefore resume on whatever thread last drove
 * recomposition) and the global `SnapshotStateObserver`'s registration to a single thread. Without
 * that pin, an `interactive/stop` running on the JSON-RPC read thread can race a `LaunchedEffect`
 * body still executing on a render worker thread — the cross-thread snapshot touch is what trips
 * `Detected multithreaded access to SnapshotStateObserver` (issue #1229) and, on Linux/Skiko, can
 * escalate to a SIGABRT inside the native scene-close path.
 */
class DesktopInteractiveSession(
  override val previewId: String,
  private val engine: RenderEngine,
  private val state: RenderEngine.SceneState,
  private val sandboxStats: SandboxLifecycleStats,
  /**
   * Single-thread executor owned by this session — every scene touch runs here. The caller
   * ([DesktopHost.acquireInteractiveSession]) is responsible for already having executed
   * [RenderEngine.setUp] on this executor before passing [state] in, so the scene is allocated on
   * the same thread that will later render / dispatch / tear it down.
   */
  private val sceneExecutor: ExecutorService,
  /**
   * Optional hook fired on [sceneExecutor] right before [RenderEngine.tearDown] during [close].
   * Used by [DesktopHost] to drive `InteractiveSessionListener.onSessionLifecycle(_, null)` — the
   * recomposition producer's observer-dispose — on the same thread the observer was installed on.
   * Failures are logged and swallowed; the scene tear-down proceeds either way.
   */
  private val onSceneClose: (() -> Unit)? = null,
  /**
   * Fired exactly once after [close] flips `closed = true`, the scene tear-down completes, and the
   * executor shuts down. Pure server-side cleanup — doesn't touch the scene, so it runs on whatever
   * thread called [close]. Mirrors [AndroidInteractiveSession.onCloseHook] so the same
   * `JsonRpcServer`-side cleanup wiring works across both backends.
   */
  private val onCloseHook: (() -> Unit)? = null,
) : InteractiveSession {

  @Volatile private var closed: Boolean = false

  /**
   * Per-pointer-id active state — keyed by [InteractiveInputParams.pointerId] (`null` collapses to
   * `0`). Updated by `POINTER_DOWN` / `POINTER_MOVE` / `POINTER_UP` so the next multi-pointer
   * dispatch sees every currently-pressed finger in a single `sendPointerEvent` call. Cleared per
   * id on `POINTER_UP`. Mirrors the same pattern `DesktopRecordingSession` uses for scripted
   * playback so an external panel sending two simultaneous `pointerDown`s actually gets a
   * `Modifier.transformable {}` zoom callback (the gating signal is ≥ 2 pointers in one event).
   *
   * Read and written only from [sceneExecutor]'s thread, so a plain `MutableMap` is safe — no
   * `ConcurrentHashMap` needed.
   */
  private val activePointers: MutableMap<Int, ActivePointer> = mutableMapOf()

  /** A pressed pointer's scene-space position plus the device class it is being synthesised as. */
  private data class ActivePointer(val offset: Offset, val type: PointerType)

  override val isClosed: Boolean
    get() = closed

  override fun dispatch(input: InteractiveInputParams) {
    if (closed) return
    runOnSceneThread {
      if (closed) return@runOnSceneThread
      dispatchOnSceneThread(input)
    }
  }

  private fun dispatchOnSceneThread(input: InteractiveInputParams) {
    val resolved = resolvePointerPixels(input)
    val px = resolved?.first
    val py = resolved?.second
    val deviceType = composePointerType(input.pointerType)
    when (input.kind) {
      InteractiveInputKind.CLICK -> {
        if (px == null || py == null) return
        val id = input.pointerId ?: 0
        val offset = sceneOffset(px, py)
        // Press → render-tick → Release. The render tick between the two dispatches gives
        // Compose's gesture-detector coroutine a chance to observe the down event before the up
        // arrives — without it, `Modifier.clickable {}`'s `detectTapGestures` can race the two
        // events and miss the tap. The pattern matches what the Compose UI test harness's
        // `performClick` does internally.
        // Goes through the same multi-pointer path as POINTER_* so a click dispatched while
        // another pointer is already down still carries the other finger in its event.
        val nowNs = engine.currentFrameNanoTime()
        val nowMs = nowNs / 1_000_000L
        activePointers[id] = ActivePointer(offset, deviceType)
        dispatchMultiPointer(eventType = PointerEventType.Press, timeMillis = nowMs)
        state.scene.render(nanoTime = nowNs)
        activePointers.remove(id)
        dispatchMultiPointer(
          eventType = PointerEventType.Release,
          timeMillis = nowMs + CLICK_HOLD_MS,
          releasedPointer = ActivePointer(offset, deviceType) to id,
        )
      }
      InteractiveInputKind.POINTER_DOWN -> {
        if (px == null || py == null) return
        val id = input.pointerId ?: 0
        activePointers[id] = ActivePointer(sceneOffset(px, py), deviceType)
        dispatchMultiPointer(eventType = PointerEventType.Press)
      }
      InteractiveInputKind.POINTER_MOVE -> {
        if (px == null || py == null) return
        val id = input.pointerId ?: 0
        // Keep the device class the press established: a drag's moves must stay the same pointer
        // Compose saw go down, or the selection gesture it started is handed a foreign device
        // mid-stream.
        val type = activePointers[id]?.type ?: deviceType
        activePointers[id] = ActivePointer(sceneOffset(px, py), type)
        dispatchMultiPointer(eventType = PointerEventType.Move)
      }
      InteractiveInputKind.POINTER_UP -> {
        if (px == null || py == null) return
        val id = input.pointerId ?: 0
        // Remove BEFORE dispatch but pass the released id+position into [dispatchMultiPointer] so
        // Compose sees the up event with `pressed = false` for this pointer alongside any
        // still-active fingers. Without the explicit released entry, dropping the pointer here and
        // dispatching only remaining actives would deliver a Move-shaped event and the gesture
        // detector would never see the "finger lifted" signal.
        val type = activePointers[id]?.type ?: deviceType
        activePointers.remove(id)
        dispatchMultiPointer(
          eventType = PointerEventType.Release,
          releasedPointer = ActivePointer(sceneOffset(px, py), type) to id,
        )
      }
      InteractiveInputKind.ROTARY_SCROLL -> {
        if (px == null || py == null) return
        val deltaY = input.scrollDeltaY ?: return
        state.scene.sendPointerEvent(
          eventType = PointerEventType.Scroll,
          position = sceneOffset(px, py),
          scrollDelta = Offset(0f, deltaY),
        )
      }
      InteractiveInputKind.KEY_DOWN -> {
        val key = androidKeycodeToComposeKey(input.keyCode)
        val typed = printableText(input.text)
        // A key the table doesn't know AND no printable text is nothing we can dispatch — drop it
        // silently, same forward-compat contract as before.
        if (key == null && typed == null) return
        // Mirror the press into the soft-keyboard band so an agent driving keyboard input through
        // `interactive/input` sees the matching cap light up. The band's "press implies visible"
        // rule in `KeyboardController.softInputVisible` also raises the band even if the consumer
        // hasn't called `keyboardController.show()`.
        KeyboardBandLabels.fromAndroidKeycode(input.keyCode)?.let(KeyboardController::notifyKeyDown)
        if (key != null) state.scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyDown))
        // Then the *typed character*, as a real AWT `KEY_TYPED` event. This second dispatch is
        // what makes typing work: Compose desktop's `KeyEvent.isTypedEvent` — the gate on
        // `KeyCommand.TYPE`, i.e. "insert this character into the text field" — asks the event for
        // its backing AWT event and requires `id == KEY_TYPED` with a printable `keyChar`. The
        // synthesised `KeyEvent(key, KeyDown)` above has no AWT event at all, so it can only ever
        // drive the *command* keys (arrows, Backspace, Delete, Home/End) that map off `Key` alone.
        // That asymmetry is exactly why caret movement and deletion worked on the live lanes while
        // typing did nothing (issue #3491).
        // One event per UTF-16 unit: an AWT `KEY_TYPED` carries a single `char`, so an astral
        // code point (an emoji) travels as its surrogate pair, which is exactly how AWT delivers
        // one. Compose appends each unit and the pair lands as the one character it is.
        typed?.forEach { state.scene.sendKeyEvent(typedKeyEvent(key, it)) }
      }
      InteractiveInputKind.KEY_UP -> {
        val key = androidKeycodeToComposeKey(input.keyCode) ?: return
        // No typed-character counterpart here: AWT emits `KEY_TYPED` only between press and
        // release, and Compose only inserts on `KeyDown`.
        KeyboardBandLabels.fromAndroidKeycode(input.keyCode)?.let(KeyboardController::notifyKeyUp)
        state.scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyUp))
      }
    }
  }

  override fun dispatchLottieProgress(progress: Float): Boolean {
    if (closed) return false
    val clamped = progress.coerceIn(0f, 1f)
    runOnSceneThread {
      if (closed) return@runOnSceneThread
      // Mutate the snapshot state `LocalLottieProgress` reads inside the held composition → the
      // scene recomposes to the new frame; the `interactive/setLottie` handler requests the
      // [render] that paints it. Also remember it per preview so a later fresh render (a save /
      // warmup re-render that bypasses this session) stays pinned at the scrubbed position.
      state.lottieProgressState.value = clamped
      state.spec.previewId?.let { LottieProgressController.remember(it, clamped) }
    }
    return !closed
  }

  override fun render(requestId: Long, advanceTimeMs: Long?): RenderResult {
    check(!closed) { "DesktopInteractiveSession.render() called after close()" }
    return runOnSceneThreadForResult {
      check(!closed) { "DesktopInteractiveSession.render() called after close()" }
      engine.renderOnce(state, requestId, sandboxStats = sandboxStats, useWallClockFrameTime = true)
    }
  }

  override fun close() {
    if (closed) return
    closed = true
    try {
      runOnSceneThread {
        if (onSceneClose != null) {
          try {
            onSceneClose.invoke()
          } catch (t: Throwable) {
            System.err.println(
              "compose-ai-daemon: DesktopInteractiveSession: onSceneClose threw " +
                "(${t.javaClass.simpleName}: ${t.message}); continuing with tearDown"
            )
          }
        }
        engine.tearDown(state)
      }
    } finally {
      sceneExecutor.shutdown()
      try {
        if (!sceneExecutor.awaitTermination(EXECUTOR_SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
          System.err.println(
            "compose-ai-daemon: DesktopInteractiveSession($previewId): sceneExecutor did not " +
              "terminate within ${EXECUTOR_SHUTDOWN_TIMEOUT_SECONDS}s; continuing"
          )
        }
      } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
      }
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
  }

  /**
   * Submit [block] to [sceneExecutor] and wait. Rejected submissions (executor already shut down
   * because a concurrent [close] won the race) are silently dropped — matches the "stale call after
   * stop" contract documented on [InteractiveSession]. Exceptions thrown inside [block] are
   * unwrapped from [ExecutionException] so callers see the original cause.
   */
  private inline fun runOnSceneThread(crossinline block: () -> Unit) {
    val future =
      try {
        sceneExecutor.submit { block() }
      } catch (_: RejectedExecutionException) {
        return
      }
    try {
      future.get()
    } catch (e: ExecutionException) {
      throw e.cause ?: e
    } catch (_: InterruptedException) {
      Thread.currentThread().interrupt()
    }
  }

  private inline fun <T> runOnSceneThreadForResult(crossinline block: () -> T): T {
    val future = sceneExecutor.submit<T> { block() }
    return try {
      future.get()
    } catch (e: ExecutionException) {
      throw e.cause ?: e
    } catch (e: InterruptedException) {
      Thread.currentThread().interrupt()
      throw e
    }
  }

  /**
   * Resolve the image-natural pixel coordinates for [input]. Explicit
   * [InteractiveInputParams.pixelX]/`pixelY` win; otherwise the [InteractiveInputParams.target]
   * semantic handle is resolved against the held scene's live semantics tree (issue #1784) and the
   * matched node's centre is used. Returns `null` — and the dispatch no-ops — when neither is
   * available or the target doesn't resolve to exactly one node (interactive/input is
   * fire-and-forget, so a miss is logged rather than reported on the wire).
   *
   * Runs on [sceneExecutor]'s thread (its only caller is [dispatchOnSceneThread]), so reaching into
   * `state.scene` for the semantics root is safe.
   */
  private fun resolvePointerPixels(input: InteractiveInputParams): Pair<Int, Int>? {
    val explicitX = input.pixelX
    val explicitY = input.pixelY
    if (explicitX != null && explicitY != null) return explicitX to explicitY
    val target = input.target?.toSemanticsTarget() ?: return null
    val root =
      state.scene.composeSemanticsRoot()
        ?: return logUnresolved(target, "no semantics root available")
    return when (val res = SemanticsTargets.resolve(root, target)) {
      is TargetResolution.Resolved -> res.point.x to res.point.y
      TargetResolution.NotFound -> logUnresolved(target, "no node matched")
      is TargetResolution.Ambiguous ->
        logUnresolved(
          target,
          "${res.candidates.size} nodes matched (refs: " +
            "${res.candidates.mapNotNull { it.ref }}); use a ref to disambiguate",
        )
    }
  }

  private fun logUnresolved(target: SemanticsTarget, reason: String): Pair<Int, Int>? {
    System.err.println(
      "compose-ai-daemon: DesktopInteractiveSession($previewId): target $target unresolved " +
        "($reason); dropping input"
    )
    return null
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

  /**
   * Dispatch one multi-pointer event carrying every currently-active pointer (and, optionally, a
   * just-released pointer with `pressed = false`). Mirrors
   * `DesktopRecordingSession.dispatchMultiPointer` — Skiko's `sendPointerEvent` overload that takes
   * `List<ComposeScenePointer>` is what gives `Modifier.transformable {}` its rotation / zoom / pan
   * callbacks: the gesture detector only fires when it sees ≥ 2 pointers in a single event.
   *
   * Falls back to a no-op when there are no pointers to dispatch — defensive, the call sites always
   * provide either an active set, a `releasedPointer`, or both.
   *
   * Wall-clock timing: when [timeMillis] is `null` (the default, used by every kind except CLICK)
   * we read [RenderEngine.currentFrameNanoTime] so the event timeline matches what
   * `render(useWallClockFrameTime = true)` exposes to the composition. CLICK passes an explicit
   * value so its synthetic Press and Release land at predictable Δt = `CLICK_HOLD_MS`.
   */
  private fun dispatchMultiPointer(
    eventType: PointerEventType,
    timeMillis: Long? = null,
    releasedPointer: Pair<ActivePointer, Int>? = null,
  ) {
    val pointers = buildList {
      for ((pid, pointer) in activePointers) {
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
    if (pointers.isEmpty()) return
    val anyPressed = pointers.any { it.pressed }
    val effectiveTimeMs = timeMillis ?: (engine.currentFrameNanoTime() / 1_000_000L)
    state.scene.sendPointerEvent(
      eventType = eventType,
      pointers = pointers,
      buttons = PointerButtons(isPrimaryPressed = anyPressed),
      timeMillis = effectiveTimeMs,
      button = if (eventType == PointerEventType.Press) PointerButton.Primary else null,
    )
  }

  /** For tests that want to peek at the held scene's identity without exposing it permanently. */
  internal fun heldScene(): androidx.compose.ui.ImageComposeScene = state.scene

  companion object {
    /**
     * The wire's `pointerType` as the Compose device class Skiko dispatches with. Absent /
     * unrecognised ⇒ [PointerType.Touch], the behaviour every client had before the field existed.
     */
    internal fun composePointerType(wire: String?): PointerType =
      when (InteractivePointerType.parse(wire)) {
        InteractivePointerType.MOUSE -> PointerType.Mouse
        InteractivePointerType.PEN -> PointerType.Stylus
        InteractivePointerType.TOUCH -> PointerType.Touch
      }

    /**
     * The text [text] types, or `null` when there is nothing typeable in it — absent, empty, more
     * than one code point, or a non-printing one (control characters, and the `Shift` / `ArrowLeft`
     * style key *names* the browser also puts in `KeyboardEvent.key`).
     *
     * One *code point*, which is not the same as one `Char`: an emoji is a single character the
     * client will happily send (its `Array.from(key).length` is 1) but two UTF-16 units, and
     * measuring in `Char`s would drop it on the floor here while the Android lane inserted it. The
     * returned string is one code point, so it is either one `Char` or a surrogate pair.
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
     * A Compose `KeyDown` carrying [ch] as typed text: the code point Compose inserts, plus a
     * synthetic AWT `KEY_TYPED` as the event's `nativeEvent`. The AWT event is the load-bearing
     * half — `isTypedEvent` reaches through to it and requires `id == KEY_TYPED` with a printable
     * `keyChar` before it will map the event to `KeyCommand.TYPE`.
     *
     * [key] is the physical key when the wire named one, so a consumer's `Modifier.onKeyEvent`
     * still sees a coherent event; typing works either way, since the mapping falls through to
     * `isTypedEvent` for any key that carries no unmodified command of its own.
     *
     * The AWT source component is a bare [java.awt.Canvas], never shown or added to a hierarchy —
     * AWT only refuses a *null* source. `KEY_TYPED` carries no key code by definition
     * (`VK_UNDEFINED`); the character is the whole payload.
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

    /** Lazily built so a session that never types never touches AWT component construction. */
    private val typedEventSource: java.awt.Component by lazy { java.awt.Canvas() }

    /**
     * Synthetic hold time between Press and Release for a CLICK. 100 ms matches what Compose's UI
     * test harness uses by default and is well above `detectTapGestures`'s long-press threshold
     * floor — long enough to register as an unambiguous tap, short enough that the click feels
     * instant to the human.
     */
    private const val CLICK_HOLD_MS: Long = 100L

    /**
     * Bound on how long [close] waits for [sceneExecutor] to drain. Generous — a single tear-down
     * is normally sub-second, but a recompose stuck behind a slow `LaunchedEffect` could
     * conceivably take longer. Logging-then-continuing past the bound is better than hanging the
     * JSON-RPC read thread indefinitely.
     */
    private const val EXECUTOR_SHUTDOWN_TIMEOUT_SECONDS: Long = 5L
  }
}
