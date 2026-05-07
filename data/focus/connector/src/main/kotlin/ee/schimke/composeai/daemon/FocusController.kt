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
 * The flow is:
 *
 * 1. Plugin path (`@FocusedPreview` / `renderAllPreviews`): the renderer's per-capture loop calls
 *    [set] before each capture so the around-composable observes the next requested target.
 * 2. Daemon path (`renderNow.overrides.focus`): [FocusOverrideExtension.AroundComposable] seeds
 *    the controller from its constructor argument, the around-composable observes the same state
 *    via [activeFocus]'s snapshot read.
 *
 * Both paths share the [activeFocus] state and the [SETTLE_MS] settle window so the
 * `LaunchedEffect`-driven walk + the renderer's per-capture clock advance stay aligned without
 * either side needing to know about the other.
 */
object FocusController {

  private val state: MutableState<FocusOverride?> = mutableStateOf(null)

  /**
   * Snapshot-state read by [FocusOverrideExtension.AroundComposable]'s `LaunchedEffect`. Driving
   * focus from inside composition (rather than from the outer per-capture loop via a
   * `runOnUiThread`-posted `moveFocus`) is what makes the post-state emit + indication redraw land
   * before the next capture; the outer-loop path leaves a one-frame off-by-one that no amount of
   * `mainClock.advanceTimeBy` flushes.
   */
  val activeFocus: State<FocusOverride?>
    get() = state

  /**
   * Per-capture settle window, in ms. After [FocusManager.moveFocus(...)] the FocusableNode emits
   * `FocusInteraction.{Focus, Unfocus}` to the interaction sources; the ripple's `IndicationNode`
   * collects the events and runs a fade animation (Material's focus indicator uses an
   * `Animatable<Float>` that crossfades over ~150ms). The paused-clock test environment doesn't
   * auto-advance these animations, so we need enough virtual time to (a) emit the interactions,
   * (b) crossfade out the previous capture's highlight, and (c) crossfade in the new one. 250ms ≈
   * 16 frames at 16ms — a comfortable margin around Material's default highlight duration.
   */
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
 * [androidx.compose.ui.platform.LocalInputModeManager] so previews that call
 * `FocusRequester.requestFocus()` actually receive focus. Compose's `Modifier.clickable` registers
 * its focusable with `Focusability.SystemDefined`, which refuses focus while the host's input mode
 * is `InputMode.Touch` — Robolectric's permanent default. Forcing keyboard mode for the duration
 * of any focus-driven render unblocks the focus walk.
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
