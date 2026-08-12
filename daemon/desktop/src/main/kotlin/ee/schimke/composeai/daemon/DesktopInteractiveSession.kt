@file:OptIn(
  androidx.compose.ui.InternalComposeUiApi::class,
  androidx.compose.ui.ExperimentalComposeUiApi::class,
)

package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.InteractiveInputKind
import ee.schimke.composeai.daemon.protocol.InteractiveInputParams
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
 * - `KEY_DOWN` / `KEY_UP` → [SceneKeyDispatch] — wire `keyCode` is the decimal-string Android
 *   `KEYCODE_*` value (issue #1203), joined by the `text` the key typed (issue #3491). Unmapped
 *   codes with nothing typeable drop silently so a forward-looking client can't crash the dispatch
 *   loop.
 * - `ROTARY_SCROLL` → `Scroll` pointer event at the supplied pixel coords with `scrollDelta.y =
 *   scrollDeltaY`. Reuses the existing pointer pipeline; positive deltaY means wheel-down (same
 *   convention as a browser wheel).
 *
 * **Pixel coords.** `interactive/input` carries image-natural pixel coords (the same pixel space
 * the renderer renders to — see `INTERACTIVE.md § 6/§ 7`). `ImageComposeScene.sendPointerEvent`
 * also takes physical scene pixels; density only controls dp-to-pixel layout inside the scene and
 * must not be applied to pointer coordinates a second time. Null coords (e.g. for keyboard events,
 * which we no-op anyway) skip the dispatch.
 *
 * **Shared with the recording lane.** Pointer and key translation live in `DesktopSceneInput.kt`
 * ([ScenePointerDispatch] / [SceneKeyDispatch]) and are used verbatim by [DesktopRecordingSession],
 * so the two lanes cannot drift apart the way they did before issue #3545.
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
   * Pointer translation + multi-pointer bookkeeping, shared with [DesktopRecordingSession] so both
   * lanes synthesise pointers identically (issue #3545). Touched only from [sceneExecutor]'s
   * thread.
   *
   * Its wall-clock default timestamp reads [RenderEngine.currentFrameNanoTime] so the event
   * timeline matches what `render(useWallClockFrameTime = true)` exposes to the composition. CLICK
   * passes explicit values instead, so its synthetic Press and Release land at a predictable Δt.
   */
  private val pointers: ScenePointerDispatch =
    ScenePointerDispatch(
      scene = { state.scene },
      defaultTimeMillis = { engine.currentFrameNanoTime() / 1_000_000L },
      defaultFrameNanos = { engine.currentFrameNanoTime() },
      settleFrame = { nanoTime -> engine.renderSettlingFrame(state, nanoTime) },
    )

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
        // Press → render-tick → Release. [ScenePointerDispatch.press] runs the render tick between
        // the two dispatches, which gives Compose's gesture-detector coroutine a chance to observe
        // the down event before the up arrives — without it, `Modifier.clickable {}`'s
        // `detectTapGestures` can race the two events and miss the tap. The pattern matches what
        // the Compose UI test harness's `performClick` does internally.
        // Goes through the same multi-pointer path as POINTER_* so a click dispatched while
        // another pointer is already down still carries the other finger in its event.
        val nowNs = engine.currentFrameNanoTime()
        val nowMs = nowNs / 1_000_000L
        pointers.press(id, offset, deviceType, timeMillis = nowMs, frameNanos = nowNs)
        pointers.release(id, offset, deviceType, timeMillis = nowMs + CLICK_HOLD_MS)
      }
      InteractiveInputKind.POINTER_DOWN -> {
        if (px == null || py == null) return
        pointers.press(input.pointerId ?: 0, sceneOffset(px, py), deviceType)
      }
      InteractiveInputKind.POINTER_MOVE -> {
        if (px == null || py == null) return
        pointers.move(input.pointerId ?: 0, sceneOffset(px, py), deviceType)
      }
      InteractiveInputKind.POINTER_UP -> {
        if (px == null || py == null) return
        pointers.release(input.pointerId ?: 0, sceneOffset(px, py), deviceType)
      }
      InteractiveInputKind.ROTARY_SCROLL -> {
        if (px == null || py == null) return
        val deltaY = input.scrollDeltaY ?: return
        pointers.scroll(sceneOffset(px, py), deltaY)
      }
      // A key the translation table doesn't know AND no printable text is nothing we can dispatch —
      // dropped silently here (interactive/input is fire-and-forget); the recording lane reports
      // the
      // same condition as `unsupported` evidence.
      InteractiveInputKind.KEY_DOWN ->
        SceneKeyDispatch.keyDown(state.scene, input.keyCode, input.text)
      InteractiveInputKind.KEY_UP -> SceneKeyDispatch.keyUp(state.scene, input.keyCode)
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
   * Image-natural pixels and `ImageComposeScene` pointer positions share one physical-pixel space.
   */
  private fun sceneOffset(px: Int, py: Int): androidx.compose.ui.geometry.Offset {
    return androidx.compose.ui.geometry.Offset(px.toFloat(), py.toFloat())
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

    /**
     * Bound on how long [close] waits for [sceneExecutor] to drain. Generous — a single tear-down
     * is normally sub-second, but a recompose stuck behind a slow `LaunchedEffect` could
     * conceivably take longer. Logging-then-continuing past the bound is better than hanging the
     * JSON-RPC read thread indefinitely.
     */
    private const val EXECUTOR_SHUTDOWN_TIMEOUT_SECONDS: Long = 5L
  }
}
