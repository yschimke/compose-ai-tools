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
 * Desktop counterpart of `:data-keyboard-connector`'s `KeyboardOverrideExtension`. Same observer
 * shape — shadow `LocalSoftwareKeyboardController` forwarding `show()` / `hide()` into the
 * process-static [KeyboardController] — minus the Android `LocalConfiguration.uiMode` read (no
 * day/night signal on the desktop renderer, so the band always uses the light palette).
 *
 * The planner always returns a non-null extension for the same reason the Android one does — the
 * observer needs to be in place even without a `KeyboardOverride` seed so app-side
 * `LocalSoftwareKeyboardController.show()` calls reach the band.
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

    val shadow = remember { ObservingSoftwareKeyboardController() }
    CompositionLocalProvider(LocalSoftwareKeyboardController provides shadow) {
      val visible by KeyboardController.softInputVisible
      val pressedKey by KeyboardController.pressedKey
      Box(modifier = Modifier.fillMaxSize()) {
        content()
        if (visible) {
          SoftKeyboardBand(
            pressedKey = pressedKey,
            night = false,
            modifier = Modifier.align(Alignment.BottomCenter),
          )
        }
      }
    }
  }

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
  }
}

class KeyboardPreviewOverrideExtension : DataExtension<PreviewOverrides> {
  override val id: DataExtensionId = KeyboardOverrideExtension.ID

  override fun plan(request: PreviewOverrides): PlannedDataExtension =
    KeyboardOverrideExtension(seed = request.keyboard)
}
