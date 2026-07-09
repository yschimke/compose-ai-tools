package ee.schimke.composeai.daemon

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import ee.schimke.composeai.daemon.protocol.GestureKindOverride
import ee.schimke.composeai.daemon.protocol.GestureOverride
import ee.schimke.composeai.data.gestures.GesturePayload
import ee.schimke.composeai.data.gestures.RegisteredGesture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Process-static state holder + registry for the Wear OS one-handed-gesture connector.
 *
 * The Wear gesture framework (`Modifier.oneHandedGesture` in `wear-compose 1.7.0-alpha`) registers
 * handlers with an **internal**, on-device-only `GestureManager`; off a Pixel Watch it silently
 * no-ops and nothing is observable. This controller makes gestures observable + drivable two ways:
 * - **Opt-in, labelled** — a preview wires [reportedOneHandedGesture], which applies the real
 *   modifier *and* reports each handler here (with the author's label).
 * - **Zero-change, framework-level** — [ShadowSdkGestureInputManager] shadows the framework's
 *   internal SDK bridge, so an unmodified app's raw `Modifier.oneHandedGesture` is detected
 *   ([detected]), its hint can be shown, and its handler invoked — no reporting seam required.
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
    /** Effective `LocalOneHandedGestureEnabled` observed where the handler was registered. */
    val enabled: Boolean,
    val invoke: () -> Unit,
  )

  private val entries: MutableList<Entry> = CopyOnWriteArrayList()

  /**
   * Gestures detected through the **real** framework registry (raw `Modifier.oneHandedGesture`, no
   * reporting seam), keyed by the SDK gesture-action int (1 = primary, 2 = dismiss). The value fires
   * the framework's captured `onGesture` callback. Populated by [ShadowSdkGestureInputManager] when
   * [detectionArmed] — this is what makes an unmodified app's gesture usage observable + invokable.
   */
  private val detected = ConcurrentHashMap<Int, () -> Unit>()

  /**
   * Whether the connector's SDK-manager shadow should report gestures as available. Armed by
   * [GestureOverrideExtension] while a gesture override is applied, so the framework's registration +
   * indicator pipeline runs under the render (off-device the real SDK is absent → the pipeline is
   * inert). Kept `false` otherwise so non-gesture renders are untouched.
   */
  @Volatile private var detectionArmed: Boolean = false

  /** Fallback enabled state for previews that set a gesture override but register no handlers. */
  @Volatile private var overrideEnabled: Boolean = true

  @Volatile private var lastInvoked: String? = null

  /**
   * Snapshot mirror of the override's `showHints`. [GestureHint] reads it so a daemon render with
   * `overrides.gestures.showHints = true` force-shows the real gesture indicator without the caller
   * threading a flag through — recomposes every registered hint when [set] flips it.
   */
  private val _hintsShown: MutableState<Boolean> = mutableStateOf(false)
  val hintsShownState: State<Boolean>
    get() = _hintsShown

  /**
   * Register a handler. Keyed by (type,label); a duplicate replaces the prior entry. [enabled] is
   * the effective `LocalOneHandedGestureEnabled` at the registration site — captured so the payload
   * reflects a preview that disables gestures inside the tree, not just via the override.
   */
  fun register(
    type: GestureKindOverride,
    label: String,
    hintAvailable: Boolean,
    enabled: Boolean,
    invoke: () -> Unit,
  ) {
    synchronized(lock) {
      entries.removeAll { it.type == type && it.label == label }
      entries.add(Entry(type, label, hintAvailable, enabled, invoke))
    }
  }

  fun unregister(type: GestureKindOverride, label: String) {
    synchronized(lock) { entries.removeAll { it.type == type && it.label == label } }
  }

  /** Arm/disarm the framework-registry shadow. See [detectionArmed]. */
  fun armDetection(armed: Boolean) {
    detectionArmed = armed
  }

  /** Read by [ShadowSdkGestureInputManager.isAvailable]. */
  fun detectionArmed(): Boolean = detectionArmed

  /**
   * Record a framework-detected gesture subscription for [sdkAction] (1 = primary, 2 = dismiss),
   * capturing the framework's `onGesture` callback so [invoke] can fire the real handler. Called by
   * [ShadowSdkGestureInputManager.subscribeToSdkGestureAction].
   */
  fun recordDetected(sdkAction: Int, invoke: () -> Unit) {
    detected[sdkAction] = invoke
  }

  /** Drop a framework-detected subscription (framework `unsubscribeFromSdkGestureAction`). */
  fun clearDetected(sdkAction: Int) {
    detected.remove(sdkAction)
  }

  /**
   * Apply an override. `null` restores defaults (enabled, hints off) — called on the extension's
   * dispose so a render without a gesture override doesn't inherit the previous render's hint state.
   * Does not touch [entries] (composition-scoped) or [lastInvoked] (session-scoped).
   */
  fun set(override: GestureOverride?) {
    synchronized(lock) {
      overrideEnabled = override?.enabled ?: true
      _hintsShown.value = override?.showHints ?: false
    }
  }

  /**
   * Effective gesture-recognition enabled state: the AND of every registered handler's observed
   * `LocalOneHandedGestureEnabled` (so an in-tree opt-out reports `false`), falling back to the
   * override default when no handler registered.
   */
  fun enabled(): Boolean =
    synchronized(lock) {
      if (entries.isEmpty()) overrideEnabled else entries.all { it.enabled }
    }

  /**
   * Invoke registered handlers of [kind], optionally scoped to a single [label]. Runs each match's
   * `onGesture` and records [label] (or the kind's wire name) as [lastInvoked]. Returns the number
   * of handlers fired.
   */
  fun invoke(kind: GestureKindOverride, label: String? = null): Int {
    val matches: List<Entry>
    // A framework-detected handler of the same action (raw `oneHandedGesture`, no reporting seam)
    // is fired too — scoped only when no [label] filter is given, since the framework registry
    // carries no label to match on.
    val detectedInvoke = if (label == null) detected[kind.toSdkAction()] else null
    synchronized(lock) {
      matches = entries.filter { it.type == kind && (label == null || it.label == label) }
      if (matches.isNotEmpty() || detectedInvoke != null) {
        lastInvoked = label ?: matches.firstOrNull()?.label ?: kind.wireName()
      }
    }
    matches.forEach { it.invoke() }
    detectedInvoke?.invoke()
    return matches.size + if (detectedInvoke != null) 1 else 0
  }

  /** Immutable view of the current registry state, captured by the data-product registry. */
  fun snapshot(): GesturePayload =
    synchronized(lock) {
      GesturePayload(
        enabled = if (entries.isEmpty()) overrideEnabled else entries.all { it.enabled },
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
        detected = detected.keys.sorted().map { sdkActionWireName(it) },
      )
    }

  /** Cleanup hook for per-session reset (recording stop / interactive close). */
  fun resetForNewSession() {
    synchronized(lock) {
      entries.clear()
      detected.clear()
      detectionArmed = false
      overrideEnabled = true
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

  /** Framework SDK gesture-action int for a [GestureKindOverride] (scroll/page ride primary). */
  private fun GestureKindOverride.toSdkAction(): Int =
    when (this) {
      GestureKindOverride.DISMISS -> SDK_ACTION_DISMISS
      else -> SDK_ACTION_PRIMARY
    }

  /** Lower-case wire spelling for an SDK gesture-action int. */
  private fun sdkActionWireName(sdkAction: Int): String =
    if (sdkAction == SDK_ACTION_DISMISS) "dismiss" else "primary"

  /**
   * SDK gesture-action ints, mirroring the library's internal `toSdkGestureAction` (primary → 1,
   * dismiss → 2). Kept in sync via [ShadowSdkGestureInputManager]'s round-trip test.
   */
  const val SDK_ACTION_PRIMARY: Int = 1
  const val SDK_ACTION_DISMISS: Int = 2
}
