package ee.schimke.composeai.daemon

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.data.pseudolocale.Pseudolocale
import ee.schimke.composeai.data.render.extensions.DataExtension
import ee.schimke.composeai.data.render.extensions.DataExtensionConstraints
import ee.schimke.composeai.data.render.extensions.DataExtensionId
import ee.schimke.composeai.data.render.extensions.DataExtensionPhase
import ee.schimke.composeai.data.render.extensions.PlannedDataExtension
import ee.schimke.composeai.data.render.extensions.compose.AroundComposableExtension

/**
 * `AroundComposable` extension that wraps `LocalContext` with [PseudolocaleContext] so every
 * `stringResource(...)` lookup the preview makes returns the pseudolocalised template, and (for
 * `ar-XB`) flips `LocalLayoutDirection` to RTL.
 *
 * Runs in the [DataExtensionPhase.OuterEnvironment] phase so the wrapped `LocalContext` is in scope
 * before the user-environment phase reaches preview content — matters for any extension that itself
 * resolves string resources during composition.
 */
class PseudolocaleOverrideExtension(private val mode: Pseudolocale) :
  AroundComposableExtension(
    id = ID,
    constraints = DataExtensionConstraints(phase = DataExtensionPhase.OuterEnvironment),
  ) {
  @Composable
  override fun AroundComposable(content: @Composable () -> Unit) {
    val base = LocalContext.current
    val wrapped = remember(base, mode) { base.wrappedForPseudolocale(mode) }
    if (mode.isRtl) {
      CompositionLocalProvider(
        LocalContext provides wrapped,
        LocalLayoutDirection provides LayoutDirection.Rtl,
      ) {
        content()
      }
    } else {
      CompositionLocalProvider(LocalContext provides wrapped) { content() }
    }
  }

  companion object {
    val ID: DataExtensionId = DataExtensionId("data-pseudolocale")
  }
}

/**
 * Planner that maps `renderNow.overrides.localeTag` in {`en-XA`, `ar-XB`} to a
 * [PseudolocaleOverrideExtension]. Returns null for any other tag so non-pseudo locales keep going
 * through the standard Robolectric qualifier path untouched.
 *
 * The qualifier emission side is handled in the renderer — see
 * `RenderEngine.applyPreviewQualifiers` (Android daemon) and `RobolectricRenderTest.applyPreviewQualifiers`
 * (plugin path), both of which call into `Pseudolocale.fromTag(...)` to substitute the base locale
 * before the qualifier string reaches `RuntimeEnvironment.setQualifiers`.
 */
class PseudolocalePreviewOverrideExtension : DataExtension<PreviewOverrides> {
  override val id: DataExtensionId = PseudolocaleOverrideExtension.ID

  override fun plan(request: PreviewOverrides): PlannedDataExtension? =
    Pseudolocale.fromTag(request.localeTag)?.let(::PseudolocaleOverrideExtension)
}
