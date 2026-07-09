package ee.schimke.composeai.daemon

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import ee.schimke.composeai.daemon.protocol.GestureKindOverride
import ee.schimke.composeai.daemon.protocol.GestureOverride
import ee.schimke.composeai.data.gestures.GesturePayload
import ee.schimke.composeai.data.gestures.RegisteredGesture
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Process-static state holder + registry for the Wear OS one-handed-gesture connector.
 *
 * The Wear gesture framework (`Modifier.oneHandedGesture` in `wear-compose 1.7.0-alpha`) registers
 * handlers with an **internal**, on-device-only `GestureManager`; off a Pixel Watch it silently
 * no-ops and nothing is observable. This controller is the connector's parallel, observable registry
 * — a preview opts in by wiring [reportedOneHandedGesture] (which applies the real modifier *and*
 * reports here) so the `compose/gestures` data product can list the handlers and invoke them without
 * the hardware.
 *
 * The flow mirrors [AmbientStateController]:
 * 1. [reportedOneHandedGesture]'s `DisposableEffect` calls [register] during composition and
 *    [unregister] on dispose, so [registered] tracks exactly the handlers live in the current
 *    composition (self-cleaning across previews via `onDispose`).
 * 2. [GestureOverrideExtension] calls [set] in its `AroundComposable` body to apply
 *    `renderNow.overrides.gestures` — flipping [hintsShownState] (read by [GestureHint] to force the
 *    real `OneHandedGestureIndicator` visible) and [enabled], and invoking a handler when the
 *    override requests it. `set(null)` on dispose restores defaults so hints/enabled don't leak into
 *    the next render.
 * 3. [GestureInputDispatchObserver] calls [invoke] on an `input.gesture` recording-script event so an
 *    interactive session fires a registered handler's `onGesture` before the next frame captures.
 *
 * **Threading.** [register], [unregister], [set], [invoke], and [resetForNewSession] synchronise
 * through a single lock. [hintsShownState] is a snapshot state written under the lock and read from
 * composition. Handler callbacks fire on the calling thread (Robolectric's main thread for
 * composition-time invokes; the recording dispatch thread for `input.gesture`).
 */
object GestureStateController {

  private val lock = Any()

  /** One registered handler. [invoke] runs the preview's `onGesture` on its remembered scope. */
  private class Entry(
    val type: GestureKindOverride,
    val label: String,
    val hintAvailable: Boolean,
    val invoke: () -> Unit,
  )

  private val entries: MutableList<Entry> = CopyOnWriteArrayList()

  @Volatile private var enabled: Boolean = true

  @Volatile private var lastInvoked: String? = null

  /**
   * Snapshot mirror of the override's `showHints`. [GestureHint] reads it so a daemon render with
   * `overrides.gestures.showHints = true` force-shows the real gesture indicator without the caller
   * threading a flag through — recomposes every registered hint when [set] flips it.
   */
  private val _hintsShown: MutableState<Boolean> = mutableStateOf(false)
  val hintsShownState: State<Boolean>
    get() = _hintsShown

  /** Register a handler. Keyed by (type,label); a duplicate replaces the prior entry. */
  fun register(
    type: GestureKindOverride,
    label: String,
    hintAvailable: Boolean,
    invoke: () -> Unit,
  ) {
    synchronized(lock) {
      entries.removeAll { it.type == type && it.label == label }
      entries.add(Entry(type, label, hintAvailable, invoke))
    }
  }

  fun unregister(type: GestureKindOverride, label: String) {
    synchronized(lock) { entries.removeAll { it.type == type && it.label == label } }
  }

  /**
   * Apply an override. `null` restores defaults (enabled, hints off) — called on the extension's
   * dispose so a render without a gesture override doesn't inherit the previous render's hint state.
   * Does not touch [entries] (composition-scoped) or [lastInvoked] (session-scoped).
   */
  fun set(override: GestureOverride?) {
    synchronized(lock) {
      enabled = override?.enabled ?: true
      _hintsShown.value = override?.showHints ?: false
    }
  }

  /** Mirrors `LocalOneHandedGestureEnabled` the extension should provide. */
  fun enabled(): Boolean = enabled

  /**
   * Invoke registered handlers of [kind], optionally scoped to a single [label]. Runs each match's
   * `onGesture` and records [label] (or the kind's wire name) as [lastInvoked]. Returns the number
   * of handlers fired.
   */
  fun invoke(kind: GestureKindOverride, label: String? = null): Int {
    val matches: List<Entry>
    synchronized(lock) {
      matches = entries.filter { it.type == kind && (label == null || it.label == label) }
      if (matches.isNotEmpty()) lastInvoked = label ?: matches.first().label
    }
    matches.forEach { it.invoke() }
    return matches.size
  }

  /** Immutable view of the current registry state, captured by the data-product registry. */
  fun snapshot(): GesturePayload =
    synchronized(lock) {
      GesturePayload(
        enabled = enabled,
        hintsShown = _hintsShown.value,
        lastInvoked = lastInvoked,
        registered =
          entries.map {
            RegisteredGesture(
              type = it.type.wireName(),
              label = it.label,
              hintAvailable = it.hintAvailable,
            )
          },
      )
    }

  /** Cleanup hook for per-session reset (recording stop / interactive close). */
  fun resetForNewSession() {
    synchronized(lock) {
      entries.clear()
      enabled = true
      lastInvoked = null
      _hintsShown.value = false
    }
  }

  private fun GestureKindOverride.wireName(): String =
    when (this) {
      GestureKindOverride.PRIMARY -> "primary"
      GestureKindOverride.DISMISS -> "dismiss"
      GestureKindOverride.SCROLL -> "scroll"
      GestureKindOverride.PAGE -> "page"
    }
}
