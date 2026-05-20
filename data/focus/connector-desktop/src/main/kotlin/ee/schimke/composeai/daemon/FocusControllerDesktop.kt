package ee.schimke.composeai.daemon

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.focus.FocusDirection as ComposeFocusDirection
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import ee.schimke.composeai.daemon.protocol.FocusDirection
import ee.schimke.composeai.daemon.protocol.FocusOverride

/**
 * Process-static state holder for the active focus override.
 *
 * Desktop counterpart of `:data-focus-connector`'s [FocusController]. The wire-shape, settle
 * window, and the snapshot-state semantics match — see the Android module for the full rationale
 * (the `LaunchedEffect`-vs-outer-loop comment, why 250ms covers the ripple crossfade, etc.).
 * Desktop's daemon path only seeds the controller from [FocusOverrideExtension]'s constructor;
 * there is no per-capture `@FocusedPreview` plugin loop on CMP Desktop today, so the renderer-side
 * `applyFocusOverride(...)` helper isn't shipped here.
 */
object FocusController {

  private val state: MutableState<FocusOverride?> = mutableStateOf(null)

  val activeFocus: State<FocusOverride?>
    get() = state

  /** Per-capture settle window in ms — matches the Android connector for parity. */
  const val SETTLE_MS: Long = 250L

  /** Replace the active override. `null` clears the state and disables focus driving. */
  fun set(override: FocusOverride?) {
    state.value = override
  }

  fun current(): FocusOverride? = state.value

  /** Cleanup hook for per-session reset. */
  fun resetForNewSession() {
    state.value = null
  }
}

/**
 * [InputModeManager] that always reports [InputMode.Keyboard] — provided via
 * `androidx.compose.ui.platform.LocalInputModeManager` so previews that call
 * `FocusRequester.requestFocus()` actually receive focus. Compose Multiplatform Desktop's default
 * host treats focus much like Robolectric: `Modifier.clickable` registers its focusable with
 * `Focusability.SystemDefined`, which refuses focus while the input mode is `InputMode.Touch`.
 * Forcing keyboard mode for the duration of any focus-driven render unblocks the focus walk.
 */
object KeyboardInputModeManager : InputModeManager {
  override val inputMode: InputMode = InputMode.Keyboard

  override fun requestInputMode(inputMode: InputMode): Boolean = false
}

/** Maps the wire-shape [FocusDirection] enum onto Compose's `FocusDirection` value class. */
fun FocusDirection.toCompose(): ComposeFocusDirection =
  when (this) {
    FocusDirection.Next -> ComposeFocusDirection.Next
    FocusDirection.Previous -> ComposeFocusDirection.Previous
    FocusDirection.Up -> ComposeFocusDirection.Up
    FocusDirection.Down -> ComposeFocusDirection.Down
    FocusDirection.Left -> ComposeFocusDirection.Left
    FocusDirection.Right -> ComposeFocusDirection.Right
  }
