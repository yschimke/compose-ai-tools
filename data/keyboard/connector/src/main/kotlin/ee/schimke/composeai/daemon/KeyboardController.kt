package ee.schimke.composeai.daemon

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import ee.schimke.composeai.daemon.protocol.KeyboardOverride

/**
 * Process-static state holder for the fake soft-keyboard (IME) band.
 *
 * Three writers, one reader:
 *
 * 1. **Around-composable observer** ([KeyboardOverrideExtension.AroundComposable]) — installs a
 *    shadow `LocalSoftwareKeyboardController` and listens for `show()` / `hide()` from app code
 *    (focused `BasicTextField`, explicit `keyboardController.show()`). Calls
 *    [notifyImeVisibility] on every transition.
 * 2. **Interactive dispatch** (`AndroidInteractiveSession.dispatch` /
 *    `DesktopInteractiveSession.dispatch`) — `KEY_DOWN` / `KEY_UP` from a daemon client are also
 *    forwarded into [notifyKeyDown] / [notifyKeyUp] so an agent driving keyboard input lights the
 *    matching cap *and* implicitly raises the band.
 * 3. **`KeyboardOverride` seed** — `renderNow.overrides.keyboard` (`KeyboardOverride(visible = …,
 *    pressedKey = …)`) wins over the natural state; cleared on dispose so the next preview starts
 *    fresh.
 *
 * Reads happen only from inside [KeyboardOverrideExtension.AroundComposable], which composes the
 * band on top of the wrapped content. Snapshot-state reads ensure each writer triggers a
 * recomposition without the band needing to know which path drove the change.
 */
object KeyboardController {

  /**
   * "Natural" IME-up signal coming from app-side APIs (shadow `LocalSoftwareKeyboardController`).
   * Distinct from [forcedVisible] so a per-render `KeyboardOverride(visible = …)` can pin the band
   * without clobbering the underlying observed state when the override clears.
   */
  private val naturalVisible: MutableState<Boolean> = mutableStateOf(false)

  /**
   * Optional `KeyboardOverride.visible` from a daemon call. `true` / `false` pin the band on / off;
   * `null` falls back to [naturalVisible] (plus the press-implies-visible rule below).
   */
  private val forcedVisible: MutableState<Boolean?> = mutableStateOf(null)

  /** Currently held key, written by `interactive/input` `KEY_*` and `KeyboardOverride.pressedKey`. */
  private val pressedKeyState: MutableState<String?> = mutableStateOf(null)

  /**
   * Effective "should the band render" signal — `forcedVisible` if set, else `naturalVisible OR a
   * key is currently pressed`. Pressing a key implicitly raises the band so an agent dispatching
   * `KEY_DOWN` against a fresh preview sees something — without this, an `interactive/input` typing
   * sequence against a preview the app hasn't focused would update [pressedKeyState] but never
   * make the band appear.
   */
  val softInputVisible: State<Boolean> =
    object : State<Boolean> {
      override val value: Boolean
        get() = forcedVisible.value ?: (naturalVisible.value || pressedKeyState.value != null)
    }

  /** Snapshot read of the currently highlighted key, or `null` when no key is held. */
  val pressedKey: State<String?>
    get() = pressedKeyState

  /** App-side observer (`LocalSoftwareKeyboardController` shadow) signals the natural IME state. */
  fun notifyImeVisibility(visible: Boolean) {
    naturalVisible.value = visible
  }

  /**
   * Mark [label] as currently pressed. Called from the daemon's interactive session
   * (`KEY_DOWN` dispatch) and from `KeyboardOverride.pressedKey` seeding. Label format matches the
   * band's key tokens (single lowercase letter or `"space"`/`"enter"`/`"shift"`/`"backspace"`).
   */
  fun notifyKeyDown(label: String) {
    pressedKeyState.value = label
  }

  /**
   * Release the current press. If [label] is supplied and doesn't match the held key the call is
   * silently dropped — protects against an out-of-order `KEY_UP` from a different key clearing a
   * still-held cap. Pass `null` to force-clear regardless.
   */
  fun notifyKeyUp(label: String? = null) {
    if (label == null || pressedKeyState.value == label) {
      pressedKeyState.value = null
    }
  }

  /**
   * Apply a `KeyboardOverride` from `renderNow.overrides.keyboard`. Sets both facets in one shot;
   * either field can be `null` to leave that facet on its natural / dispatched value.
   */
  fun seed(override: KeyboardOverride?) {
    forcedVisible.value = override?.visible
    val pressed = override?.pressedKey
    if (pressed != null) pressedKeyState.value = pressed
  }

  /** Clear the override side without disturbing observed natural state. Called on dispose. */
  fun clearOverride() {
    forcedVisible.value = null
  }

  /** Cleanup hook for per-session reset, matches [FocusController.resetForNewSession]. */
  fun resetForNewSession() {
    naturalVisible.value = false
    forcedVisible.value = null
    pressedKeyState.value = null
  }
}
