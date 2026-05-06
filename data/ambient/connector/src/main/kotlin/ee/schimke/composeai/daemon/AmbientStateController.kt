package ee.schimke.composeai.daemon

import androidx.wear.ambient.AmbientLifecycleObserver
import ee.schimke.composeai.daemon.protocol.AmbientOverride
import ee.schimke.composeai.daemon.protocol.AmbientStateOverride
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Process-static state holder + fan-out for the Wear OS ambient connector.
 *
 * The flow is:
 *
 * 1. [AmbientOverrideExtension] calls [set] inside its `AroundComposable` body, before the
 *    consumer's `AmbientAware { ... }` reaches the registered `AmbientLifecycleObserver`.
 * 2. Robolectric's `ShadowAmbientLifecycleObserver` constructor calls [registerCallback] so the
 *    consumer's callback joins the fan-out list. `isAmbient()` reads [current].
 * 3. During an interactive recording session, [AmbientInputDispatchObserver] calls
 *    [notifyUserInput] on activating gestures (touch click / pointer-down, RSB rotary scroll) — the
 *    controller flips to [AmbientStateOverride.INTERACTIVE] immediately and schedules a restoration
 *    of the override's requested state after the configured idle timeout.
 *
 * **Threading.** [set], [registerCallback], [unregisterCallback], [notifyUserInput], and
 * [resetForNewSession] synchronise through a single mutex. Callbacks fire on the calling thread
 * (Robolectric's main thread for composition; the recording-session dispatch thread for input
 * wakes; the scheduler thread for timeout restoration). The single shared idle-timer
 * `ScheduledExecutorService` means a fresh [set] cancels any pending idle restoration.
 */
object AmbientStateController {

  private val lock = Any()

  private val callbacks: MutableList<AmbientLifecycleObserver.AmbientLifecycleCallback> =
    CopyOnWriteArrayList()

  @Volatile private var override: AmbientOverride? = null

  @Volatile private var currentState: AmbientStateOverride = AmbientStateOverride.INACTIVE

  private val scheduler: ScheduledExecutorService =
    Executors.newSingleThreadScheduledExecutor { r ->
      Thread(r, "compose-ai-daemon-ambient-idle").apply { isDaemon = true }
    }

  @Volatile private var idleRestore: ScheduledFuture<*>? = null

  /** Default idle timeout before restoring the override's requested state. Matches Wear OS. */
  const val DEFAULT_IDLE_TIMEOUT_MS: Long = 5_000L

  /**
   * Replace the active override. Cancels any pending idle restoration (a fresh `renderNow` is the
   * source of truth for "current state"; an in-flight wake-recover should not overwrite it). Fans
   * out the resulting state transition to every registered callback.
   *
   * `null` clears the override and transitions to [AmbientStateOverride.INACTIVE] — matches the
   * shadow's pre-wired default and the value horologist's `AmbientAware` falls back to.
   */
  fun set(override: AmbientOverride?) {
    val transition: StateTransition
    synchronized(lock) {
      idleRestore?.cancel(false)
      idleRestore = null
      this.override = override
      val target = override?.state ?: AmbientStateOverride.INACTIVE
      transition = computeTransition(currentState, target)
      currentState = target
    }
    fireTransition(transition)
  }

  /** Current ambient state. Read by [ShadowAmbientLifecycleObserver.isAmbient]. */
  fun current(): AmbientStateOverride = currentState

  /**
   * Active override snapshot — exposed for the data-product registry's payload capture so an
   * agent's `data/fetch?kind=compose/ambient` reflects the override's burn-in / low-bit flags.
   */
  fun currentOverride(): AmbientOverride? = override

  fun registerCallback(callback: AmbientLifecycleObserver.AmbientLifecycleCallback) {
    synchronized(lock) {
      callbacks.add(callback)
      // Prime the late joiner with the current state so a callback registered after `set(...)`
      // still observes the ambient transition. Matches the AOSP observer's lifecycle semantics —
      // the host activity's onResume drives an `onEnterAmbient` for an already-ambient device.
      val stateNow = currentState
      val ov = override
      if (stateNow == AmbientStateOverride.AMBIENT) {
        callback.onEnterAmbient(detailsOf(ov))
      }
    }
  }

  fun unregisterCallback(callback: AmbientLifecycleObserver.AmbientLifecycleCallback) {
    synchronized(lock) { callbacks.remove(callback) }
  }

  /**
   * Wake on an activating input gesture. Flips state to [AmbientStateOverride.INTERACTIVE] and
   * schedules a restoration of the override's requested state after [AmbientOverride.idleTimeoutMs]
   * (or [DEFAULT_IDLE_TIMEOUT_MS] when null). The wake fires `onExitAmbient()` on registered
   * callbacks; the restoration fires `onEnterAmbient(...)` if the override was AMBIENT.
   *
   * No-op when there's no active override (no override → no ambient state to wake from).
   *
   * `tNanos` is the dispatch context's frame nanoTime — accepted for symmetry with
   * [RecordingScriptDispatchObserver.beforeDispatch] but not used today; the idle timer runs on a
   * wall-clock executor.
   */
  @Suppress("UNUSED_PARAMETER")
  fun notifyUserInput(tNanos: Long) {
    val transitionToInteractive: StateTransition
    val activeOverride: AmbientOverride
    synchronized(lock) {
      val ov = override ?: return
      activeOverride = ov
      idleRestore?.cancel(false)
      idleRestore = null
      transitionToInteractive = computeTransition(currentState, AmbientStateOverride.INTERACTIVE)
      currentState = AmbientStateOverride.INTERACTIVE
    }
    fireTransition(transitionToInteractive)
    val timeoutMs = activeOverride.idleTimeoutMs ?: DEFAULT_IDLE_TIMEOUT_MS
    if (timeoutMs <= 0L || activeOverride.state == AmbientStateOverride.INTERACTIVE) return
    val target = activeOverride.state
    idleRestore =
      scheduler.schedule(
        {
          val transition: StateTransition?
          synchronized(lock) {
            // If the override changed mid-flight (a fresh `renderNow` arrived), drop the
            // restoration — the new override owns the state.
            if (override !== activeOverride) return@schedule
            transition = computeTransition(currentState, target)
            currentState = target
            idleRestore = null
          }
          transition?.let { fireTransition(it) }
        },
        timeoutMs,
        TimeUnit.MILLISECONDS,
      )
  }

  /** Cleanup hook for per-session reset (recording stop / interactive close). */
  fun resetForNewSession() {
    synchronized(lock) {
      idleRestore?.cancel(false)
      idleRestore = null
      override = null
      currentState = AmbientStateOverride.INACTIVE
    }
  }

  private data class StateTransition(
    val from: AmbientStateOverride,
    val to: AmbientStateOverride,
    val details: AmbientLifecycleObserver.AmbientDetails,
  )

  private fun computeTransition(
    from: AmbientStateOverride,
    to: AmbientStateOverride,
  ): StateTransition = StateTransition(from, to, detailsOf(override))

  private fun fireTransition(t: StateTransition) {
    if (t.from == t.to) return
    val snapshot = callbacks.toList()
    when (t.to) {
      AmbientStateOverride.AMBIENT -> snapshot.forEach { it.onEnterAmbient(t.details) }
      AmbientStateOverride.INTERACTIVE,
      AmbientStateOverride.INACTIVE -> {
        if (t.from == AmbientStateOverride.AMBIENT) {
          snapshot.forEach { it.onExitAmbient() }
        }
      }
    }
  }

  private fun detailsOf(ov: AmbientOverride?): AmbientLifecycleObserver.AmbientDetails =
    AmbientLifecycleObserver.AmbientDetails(
      ov?.burnInProtectionRequired ?: false,
      ov?.deviceHasLowBitAmbient ?: false,
    )
}
