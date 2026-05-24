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
}

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
