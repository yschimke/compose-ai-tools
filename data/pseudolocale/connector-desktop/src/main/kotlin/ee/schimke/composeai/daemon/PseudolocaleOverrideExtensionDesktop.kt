package ee.schimke.composeai.daemon

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
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
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.LocalResourceReader

/**
 * Desktop counterpart to the Android `PseudolocaleOverrideExtension`.
 *
 * **Scope.** Two halves of pseudolocale support run here:
 * - `LocalLayoutDirection = Rtl` for `ar-XB` so the captured PNG flips. Same as Android.
 * - `LocalResourceReader` wrapped with [PseudolocalizingResourceReader] so every
 *   `org.jetbrains.compose.resources.stringResource(...)` lookup returns the pseudolocalised accent
 *   / RLO-PDF bidi form. CMP Desktop's `stringResource` doesn't walk `LocalContext.resources`, so
 *   the Android `Resources.getText` interception trick doesn't apply — instead we intercept at the
 *   byte-level `ResourceReader` that the resource-loading machinery ultimately calls. See
 *   [PseudolocalizingResourceReader] for the record-format details.
 *
 * **Why not the env swap.** The issue spec preferred a `ComposeEnvironment` / `ResourceEnvironment`
 * swap. At Compose Multiplatform 1.10.3 both `ComposeEnvironment` (interface) and
 * `LocalComposeEnvironment` (`StaticCompositionLocalOf`) are declared `internal`, and the
 * `ResourceEnvironment` constructor itself is internal — none of the three are reachable from
 * outside `org.jetbrains.compose.resources`. The env also only selects which qualifier-keyed
 * resource bundle to read; it doesn't transform output. `LocalResourceReader` is public (marked
 * `@ExperimentalResourceApi`) and is the one published handle that can change what
 * `stringResource(...)` returns.
 *
 * **Cache invalidation.** Compose Resources keeps a process-wide `stringItemsCache` keyed by
 * `path/offset-size`, populated by the *first* reader to answer a given key. Without intervention,
 * a prior non-pseudo render in the same JVM warms the cache with plain strings; the next pseudo
 * render then receives those plain strings from the cache instead of going through the wrapped
 * reader, and pseudolocalization silently no-ops. To eliminate this race, the around-composable
 * reflectively clears the cache on every composition entry via
 * [PseudolocaleResourceCache.clearStringResourcesCacheBestEffort]. The clear is best-effort: if a
 * future CMP version moves the internal field, we fall back to the documented "restart the JVM
 * between modes" caveat that [PseudolocalizingResourceReader] still mentions for defence in depth.
 *
 * **Locale-list rewrite.** The `en-XA` / `ar-XB` BCP-47 tag isn't a real locale to the JVM, so the
 * desktop renderer rewrites it to the base tag (`en` / `ar`) before threading through the
 * `LocaleList` provider — see `RenderEngine.localeProviders` in `:daemon:desktop`. Doing the
 * rewrite there (not here) keeps the around-composable focused on Compose-side providers and leaves
 * the renderer in charge of pre-composition state.
 */
@OptIn(ExperimentalResourceApi::class)
class PseudolocaleOverrideExtensionDesktop(private val mode: Pseudolocale) :
  AroundComposableExtension(
    id = ID,
    constraints = DataExtensionConstraints(phase = DataExtensionPhase.OuterEnvironment),
  ) {
  @Composable
  override fun AroundComposable(content: @Composable () -> Unit) {
    // Clear the process-wide string-item cache before the inner content composes so any
    // `stringResource(...)` lookup goes through our wrapped reader (and gets pseudolocalised),
    // rather than returning the value cached by a prior non-pseudo render. `remember(this)` keys on
    // this extension instance — the planner allocates one per render, so the clear runs exactly
    // once per composition entry. See [PseudolocaleResourceCache] for the reflective machinery and
    // the failure-mode fallback. The value (`Unit`) is unused; this is a side-effect-only remember.
    remember(this) { PseudolocaleResourceCache.clearStringResourcesCacheBestEffort() }

    val baseReader = LocalResourceReader.current
    val wrappedReader =
      remember(baseReader, mode) { PseudolocalizingResourceReader(baseReader, mode) }
    if (mode.isRtl) {
      CompositionLocalProvider(
        LocalLayoutDirection provides LayoutDirection.Rtl,
        LocalResourceReader provides wrappedReader,
      ) {
        content()
      }
    } else {
      CompositionLocalProvider(LocalResourceReader provides wrappedReader) { content() }
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
