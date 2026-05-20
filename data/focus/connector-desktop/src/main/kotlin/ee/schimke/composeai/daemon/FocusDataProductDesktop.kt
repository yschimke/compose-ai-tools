package ee.schimke.composeai.daemon

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusDirection as ComposeFocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalInputModeManager
import ee.schimke.composeai.daemon.protocol.FocusOverride
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.data.focus.Material3FocusProduct
import ee.schimke.composeai.data.render.extensions.DataExtension
import ee.schimke.composeai.data.render.extensions.DataExtensionCapability
import ee.schimke.composeai.data.render.extensions.DataExtensionConstraints
import ee.schimke.composeai.data.render.extensions.DataExtensionId
import ee.schimke.composeai.data.render.extensions.DataExtensionPhase
import ee.schimke.composeai.data.render.extensions.PlannedDataExtension
import ee.schimke.composeai.data.render.extensions.compose.AroundComposableExtension

/**
 * `AroundComposable` extension that owns the focus / keyboard-traversal around-composable concerns
 * on Compose Multiplatform Desktop. Desktop counterpart of `:data-focus-connector`'s
 * [FocusOverrideExtension]: installs `LocalInputModeManager provides KeyboardInputModeManager` and
 * a `LaunchedEffect`-driven focus walk that observes [FocusController.activeFocus] and dispatches
 * `FocusManager.moveFocus(...)` on every transition.
 *
 * Only the daemon path (`renderNow.overrides.focus`) wires through here today — CMP Desktop doesn't
 * have a per-capture `@FocusedPreview` plugin path (issue #1205). The constructor seeds the
 * controller via a `DisposableEffect` so a single-frame render driven by the daemon picks up the
 * requested focus target.
 *
 * Runs in the [DataExtensionPhase.OuterEnvironment] phase so the input-mode flip happens before the
 * user-environment phase reaches preview content.
 */
class FocusOverrideExtension(private val seed: FocusOverride? = null) :
  AroundComposableExtension(
    id = ID,
    constraints =
      DataExtensionConstraints(
        phase = DataExtensionPhase.OuterEnvironment,
        provides = setOf(DataExtensionCapability(Material3FocusProduct.KIND)),
      ),
  ) {
  @Composable
  override fun AroundComposable(content: @Composable () -> Unit) {
    if (seed != null) {
      DisposableEffect(seed) {
        FocusController.set(seed)
        onDispose { FocusController.set(null) }
      }
    }
    CompositionLocalProvider(LocalInputModeManager provides KeyboardInputModeManager) {
      val focusManager = LocalFocusManager.current
      val active by FocusController.activeFocus
      val lastIndex = remember { mutableIntStateOf(-1) }
      val entered = remember { mutableStateOf(false) }
      LaunchedEffect(active) {
        val cap = active ?: return@LaunchedEffect
        val direction = cap.direction
        val tabIndex = cap.tabIndex
        if (direction != null) {
          if (!entered.value) {
            focusManager.moveFocus(ComposeFocusDirection.Enter)
            entered.value = true
          }
          focusManager.moveFocus(direction.toCompose())
        } else if (tabIndex != null) {
          // `moveFocus(Enter)` lands the owner on an internal root that sits *before* the first
          // focusable, so the first walk needs `tabIndex + 1` Next steps to land on button
          // `tabIndex`. Subsequent calls walk only the delta.
          val from = lastIndex.value
          if (from < 0) {
            focusManager.moveFocus(ComposeFocusDirection.Enter)
            repeat(tabIndex + 1) { focusManager.moveFocus(ComposeFocusDirection.Next) }
          } else if (tabIndex > from) {
            repeat(tabIndex - from) { focusManager.moveFocus(ComposeFocusDirection.Next) }
          }
          lastIndex.value = tabIndex
        }
      }
      content()
    }
  }

  companion object {
    val ID: DataExtensionId = DataExtensionId(Material3FocusProduct.KIND)
  }
}

/**
 * Planner that maps `renderNow.overrides.focus` to a [FocusOverrideExtension]. No-op when the field
 * is null — matches the wallpaper / theme / pseudolocale-desktop planners.
 */
class FocusPreviewOverrideExtension : DataExtension<PreviewOverrides> {
  override val id: DataExtensionId = FocusOverrideExtension.ID

  override fun plan(request: PreviewOverrides): PlannedDataExtension? =
    request.focus?.let(::FocusOverrideExtension)
}
