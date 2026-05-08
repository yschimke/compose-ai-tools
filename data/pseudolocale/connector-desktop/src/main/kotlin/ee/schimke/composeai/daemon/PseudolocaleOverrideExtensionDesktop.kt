package ee.schimke.composeai.daemon

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
 * Desktop counterpart to the Android `PseudolocaleOverrideExtension`.
 *
 * **Scope.** Only the layout-direction half of pseudolocale support runs here — for `ar-XB` we
 * provide `LocalLayoutDirection = Rtl` so the captured PNG flips. The text-content
 * pseudolocalisation (the `[Ĥêļļö ···]` accent / RLO-PDF bidi wrap on string-resource lookups)
 * lives in the Android connector because it intercepts `Resources.getText`. CMP Desktop's
 * `org.jetbrains.compose.resources.stringResource` doesn't walk `LocalContext.resources`, so the
 * Android trick doesn't apply — see `site/reference/pseudolocale.md`'s "platform support" section.
 *
 * **Locale-list rewrite.** The `en-XA` / `ar-XB` BCP-47 tag isn't a real locale to the JVM, so the
 * desktop renderer rewrites it to the base tag (`en` / `ar`) before threading through the
 * `LocaleList` provider — see `RenderEngine.localeProviders` in `:daemon:desktop`. Doing the
 * rewrite there (not here) keeps the around-composable focused on Compose-side providers and leaves
 * the renderer in charge of pre-composition state.
 */
class PseudolocaleOverrideExtensionDesktop(private val mode: Pseudolocale) :
  AroundComposableExtension(
    id = ID,
    constraints = DataExtensionConstraints(phase = DataExtensionPhase.OuterEnvironment),
  ) {
  @Composable
  override fun AroundComposable(content: @Composable () -> Unit) {
    if (mode.isRtl) {
      CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) { content() }
    } else {
      content()
    }
  }

  companion object {
    val ID: DataExtensionId = DataExtensionId("data-pseudolocale")
  }
}

/**
 * Desktop planner mapping `renderNow.overrides.localeTag` in {`en-XA`, `ar-XB`} to a
 * [PseudolocaleOverrideExtensionDesktop]. Returns null for any other tag so non-pseudo locales pass
 * through the renderer's standard `LocaleList` path untouched.
 */
class PseudolocalePreviewOverrideExtensionDesktop : DataExtension<PreviewOverrides> {
  override val id: DataExtensionId = PseudolocaleOverrideExtensionDesktop.ID

  override fun plan(request: PreviewOverrides): PlannedDataExtension? =
    Pseudolocale.fromTag(request.localeTag)?.let(::PseudolocaleOverrideExtensionDesktop)
}
