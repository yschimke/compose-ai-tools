package ee.schimke.composeai.data.render.extensions

import java.util.ServiceLoader

/**
 * SPI for transparently rewriting `@PreviewWrapper(SomeWrapper::class)` to a connector-provided
 * substitute at preview-render time. Lets a connector wrap or replace a stock
 * `PreviewWrapperProvider` without the preview author changing the annotation on their `@Preview`
 * function. Example: `:data-remotecompose-connector` substitutes upstream
 * `androidx.compose.remote.tooling.preview.RemotePreviewWrapper` with its own
 * `RemoteOverridablePreviewWrapper` so seeded named-value overrides reach the running player.
 *
 * Discovered through `ServiceLoader`; implementations register themselves with a
 * `META-INF/services/ee.schimke.composeai.data.render.extensions.PreviewWrapperSubstitutionProvider`
 * file shipped in the connector's resources. Substitution is "first wins" — the renderer asks each
 * provider in classpath/service order and uses the first non-null result.
 *
 * Substitutes must themselves be `PreviewWrapperProvider`s with a public no-arg ctor and a
 * `Wrap(content: @Composable () -> Unit)` method, because the renderer constructs and invokes them
 * the same way it would the original.
 */
interface PreviewWrapperSubstitutionProvider {
  /**
   * Return the class to instantiate in place of [originalWrapperFqn], or null to leave the
   * resolution to the next provider (or fall through to a direct `Class.forName` on the original).
   */
  fun substituteFor(originalWrapperFqn: String): Class<*>?

  /**
   * Whether [wrapperFqn] is a **structural** wrapper — see [isStructuralPreviewWrapper]. Providers
   * that only rewrite a wrapper class leave this at the default; a connector that owns a wrapper
   * installing its own applier / capture surface (the RemoteCompose one) declares it here so a
   * `themeProvider` override nests around it instead of replacing it.
   */
  fun isStructural(wrapperFqn: String): Boolean = false
}

/**
 * Wrappers known to be structural without any connector on the classpath.
 *
 * Upstream `RemotePreviewWrapper` is the load-bearing entry: it captures its content into a
 * RemoteCompose document, so the content composes against the RemoteCompose applier rather than the
 * UI one. Named here (rather than only in `:data-remotecompose-connector`) because the crash it
 * prevents happens whether or not the connector is present — the connector's substitute is declared
 * structural by the connector itself.
 */
private val BUILT_IN_STRUCTURAL_WRAPPER_FQNS =
  setOf("androidx.compose.remote.tooling.preview.RemotePreviewWrapper")

/**
 * Whether a `@PreviewWrapper` provider is **structural** — it installs the surface its preview body
 * is composed against (an applier, a capture, a player) rather than merely dressing the body in a
 * look.
 *
 * The distinction exists because of the viewer's declared-theme selector. A `themeProvider`
 * override normally replaces the preview's own `@PreviewWrapper`: a declared wrapper is nearly
 * always the preview's theme, and swapping it is the whole point of the selector (keeping both
 * would let the inner theme shadow the chosen one — the failure
 * `ee.schimke.composeai.discovery.PreviewThemeShadowing` warns about).
 *
 * A structural wrapper is not a look and cannot be swapped out. Dropping
 * `@PreviewWrapper(RemotePreviewWrapper::class)` leaves a `RemoteBox` / `RemoteColumn` /
 * `RemoteRow` body composing against the plain UI applier, which throws `IllegalStateException:
 * Invalid applier` before a single pixel is drawn — as it did for every widget preview in the
 * `meshcore-mobile` catalog once the serve theme optimiser started rendering them under each
 * declared `@ThemeCatalog` theme. Renderers therefore **nest** a theme override around a structural
 * wrapper (theme outside, structural wrapper inside) instead of replacing it.
 *
 * Consults [PreviewWrapperSubstitutionProvider.isStructural] on every registered service, so a
 * connector's own wrapper classes count too, then falls back to [BUILT_IN_STRUCTURAL_WRAPPER_FQNS].
 */
fun isStructuralPreviewWrapper(
  wrapperFqn: String,
  classLoader: ClassLoader =
    Thread.currentThread().contextClassLoader
      ?: PreviewWrapperSubstitutionProvider::class.java.classLoader,
): Boolean =
  wrapperFqn in BUILT_IN_STRUCTURAL_WRAPPER_FQNS ||
    ServiceLoader.load(PreviewWrapperSubstitutionProvider::class.java, classLoader)
      .asSequence()
      .any { it.isStructural(wrapperFqn) }

/**
 * Resolves a preview-wrapper FQN to the class the renderer should actually instantiate.
 *
 * Consults [PreviewWrapperSubstitutionProvider] services first; if none substitutes, falls back to
 * `Class.forName(originalWrapperFqn, true, classLoader)`. Renderers (Robolectric, desktop) call
 * this from their `resolveWrapper` implementations so substitution behaves identically across
 * backends.
 */
fun loadPreviewWrapperClass(
  originalWrapperFqn: String,
  classLoader: ClassLoader =
    Thread.currentThread().contextClassLoader
      ?: PreviewWrapperSubstitutionProvider::class.java.classLoader,
): Class<*> {
  val substitute =
    ServiceLoader.load(PreviewWrapperSubstitutionProvider::class.java, classLoader)
      .asSequence()
      .firstNotNullOfOrNull { it.substituteFor(originalWrapperFqn) }
  return substitute ?: Class.forName(originalWrapperFqn, true, classLoader)
}
