@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package ee.schimke.composeai.daemon

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import ee.schimke.composeai.daemon.protocol.KeyboardOverride
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.data.keyboard.Material3KeyboardProduct
import ee.schimke.composeai.data.render.extensions.DataExtension
import ee.schimke.composeai.data.render.extensions.DataExtensionCapability
import ee.schimke.composeai.data.render.extensions.DataExtensionConstraints
import ee.schimke.composeai.data.render.extensions.DataExtensionId
import ee.schimke.composeai.data.render.extensions.DataExtensionPhase
import ee.schimke.composeai.data.render.extensions.PlannedDataExtension
import ee.schimke.composeai.data.render.extensions.compose.AroundComposableExtension

/**
 * `AroundComposable` extension that owns the soft-keyboard (IME) overlay. The extension is
 * **always active** — the planner emits an instance for every render, with or without a
 * `KeyboardOverride` seed, so the around-composable can observe natural IME state and surface the
 * band when the app raises it.
 *
 * Two control surfaces feed the [KeyboardController]:
 *
 * - **App-side, passive** — [KeyboardOverrideExtension.AroundComposable] installs a shadow
 *   `LocalSoftwareKeyboardController` whose `show()` / `hide()` calls land directly in
 *   [KeyboardController.notifyImeVisibility]. Compose's text input system calls `show()` on the
 *   ambient controller whenever a `BasicTextField` gains focus, so a normal focused field is
 *   enough to make the band appear. Explicit app code (`keyboardController.show()`) takes the same
 *   path.
 * - **Daemon-side, active** — `AndroidInteractiveSession.dispatch` /
 *   `DesktopInteractiveSession.dispatch` forward `KEY_DOWN` / `KEY_UP` envelopes into
 *   [KeyboardController.notifyKeyDown] / [notifyKeyUp]; `renderNow.overrides.keyboard` seeds both
 *   facets via [KeyboardController.seed]. The `KEY_*` rule "press implies visible" lives in
 *   [KeyboardController.softInputVisible] so an agent typing into a fresh preview gets a visible
 *   band even without the app focusing anything.
 *
 * Runs in [DataExtensionPhase.OuterEnvironment] so the shadow controller is in place before the
 * user-environment phase reaches preview content — text fields composed in user code see the
 * shadow rather than the platform default.
 */
class KeyboardOverrideExtension(private val seed: KeyboardOverride? = null) :
  AroundComposableExtension(
    id = ID,
    constraints =
      DataExtensionConstraints(
        phase = DataExtensionPhase.OuterEnvironment,
        provides = setOf(DataExtensionCapability(Material3KeyboardProduct.KIND)),
      ),
  ) {
  @Composable
  override fun AroundComposable(content: @Composable () -> Unit) {
    if (seed != null) {
      DisposableEffect(seed) {
        KeyboardController.seed(seed)
        onDispose { KeyboardController.clearOverride() }
      }
    }

    // Shadow `SoftwareKeyboardController` — any `show()` / `hide()` call from inside `content()`
    // routes through this instead of the platform default. Compose's `BasicTextField` calls
    // `keyboardController?.show()` from `onFocusChanged` (via `TextFieldKeyboardActionScope`),
    // which is how a focused text field naturally raises the IME on a real device. We capture
    // that same call here and surface it through [KeyboardController.notifyImeVisibility].
    val shadow = remember { ObservingSoftwareKeyboardController() }
    CompositionLocalProvider(LocalSoftwareKeyboardController provides shadow) {
      val visible by KeyboardController.softInputVisible
      val pressedKey by KeyboardController.pressedKey
      val night =
        (LocalConfiguration.current.uiMode and UI_MODE_NIGHT_MASK) == UI_MODE_NIGHT_YES
      Box(modifier = Modifier.fillMaxSize()) {
        content()
        if (visible) {
          SoftKeyboardBand(
            pressedKey = pressedKey,
            night = night,
            modifier = Modifier.align(Alignment.BottomCenter),
          )
        }
      }
    }
  }

  /**
   * `SoftwareKeyboardController` that forwards `show()` / `hide()` into [KeyboardController]. The
   * platform default fires Android's `InputMethodManager` IPC; we keep the IPC off (we don't have
   * a real IME bound during preview rendering anyway) and instead drive the synthetic band.
   *
   * Marked stable rather than experimental even though the interface itself is opt-in — Compose's
   * own platform implementations carry the same opt-in.
   */
  private class ObservingSoftwareKeyboardController : SoftwareKeyboardController {
    override fun show() {
      KeyboardController.notifyImeVisibility(true)
    }

    override fun hide() {
      KeyboardController.notifyImeVisibility(false)
    }
  }

  companion object {
    val ID: DataExtensionId = DataExtensionId(Material3KeyboardProduct.KIND)

    /**
     * `Configuration.uiMode` mask + value for "night mode is on". Duplicated from the renderer's
     * `SystemBarsFrame` rather than imported so this module doesn't take a project dep on
     * `:renderer-android`.
     */
    private const val UI_MODE_NIGHT_MASK = 0x30
    private const val UI_MODE_NIGHT_YES = 0x20
  }
}

/**
 * Planner that maps `renderNow.overrides.keyboard` to a [KeyboardOverrideExtension]. **Always**
 * returns a non-null extension — unlike the focus / wallpaper / theme planners that abstain on
 * `null`. The around-composable's observer needs to be in place for the band to react to natural
 * app-side IME state changes, not just to explicit overrides; an always-on extension is the
 * cheapest way to guarantee that without each capture-site having to opt in.
 */
class KeyboardPreviewOverrideExtension : DataExtension<PreviewOverrides> {
  override val id: DataExtensionId = KeyboardOverrideExtension.ID

  override fun plan(request: PreviewOverrides): PlannedDataExtension =
    KeyboardOverrideExtension(seed = request.keyboard)
}
