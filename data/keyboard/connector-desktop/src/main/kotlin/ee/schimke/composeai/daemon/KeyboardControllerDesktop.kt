package ee.schimke.composeai.daemon

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import ee.schimke.composeai.daemon.protocol.KeyboardOverride

/**
 * Process-static state holder for the fake soft-keyboard (IME) band.
 *
 * Desktop counterpart of `:data-keyboard-connector`'s [KeyboardController]. The state machine,
 * "press implies visible" rule, and clear-on-dispose semantics match — see the Android module for
 * the full rationale.
 */
object KeyboardController {

  private val naturalVisible: MutableState<Boolean> = mutableStateOf(false)
  private val forcedVisible: MutableState<Boolean?> = mutableStateOf(null)
  private val pressedKeyState: MutableState<String?> = mutableStateOf(null)

  val softInputVisible: State<Boolean> =
    object : State<Boolean> {
      override val value: Boolean
        get() = forcedVisible.value ?: (naturalVisible.value || pressedKeyState.value != null)
    }

  /**
   * The explicitly *requested* band visibility — a daemon-side `KeyboardOverride(visible = …)` — or
   * `null` when nobody pinned it and [softInputVisible] is inferred from app-side IME calls.
   *
   * Consumers that gate the band on anything of their own (issue #3491's device-vs-component rule)
   * must let a non-null value here win: an explicit request is a caller saying "render this with
   * the keyboard up", and a heuristic must not overrule it.
   */
  val requestedVisible: State<Boolean?>
    get() = forcedVisible

  val pressedKey: State<String?>
    get() = pressedKeyState

  fun notifyImeVisibility(visible: Boolean) {
    naturalVisible.value = visible
  }

  fun notifyKeyDown(label: String) {
    pressedKeyState.value = label
  }

  fun notifyKeyUp(label: String? = null) {
    if (label == null || pressedKeyState.value == label) {
      pressedKeyState.value = null
    }
  }

  fun seed(override: KeyboardOverride?) {
    forcedVisible.value = override?.visible
    val pressed = override?.pressedKey
    if (pressed != null) pressedKeyState.value = pressed
  }

  fun clearOverride() {
    forcedVisible.value = null
  }

  fun resetForNewSession() {
    naturalVisible.value = false
    forcedVisible.value = null
    pressedKeyState.value = null
  }
}
