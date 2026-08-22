package ee.schimke.composeai.daemon

import ee.schimke.composeai.data.render.extensions.PreviewWrapperSubstitutionProvider

/**
 * Substitutes upstream `androidx.compose.remote.tooling.preview.RemotePreviewWrapper` with
 * [RemoteOverridablePreviewWrapper] at preview-render time. Preview authors keep their existing
 * `@PreviewWrapper(RemotePreviewWrapper::class)` annotation untouched — when the connector is on
 * the classpath the renderer swaps the wrapper class transparently so seeded named-value overrides
 * reach the running player, and when it isn't the original upstream behaviour applies. Discovered
 * via the `META-INF/services/...PreviewWrapperSubstitutionProvider` file in this module's
 * resources.
 *
 * The same service also declares every RemoteCompose wrapper **structural** (see
 * [ee.schimke.composeai.data.render.extensions.isStructuralPreviewWrapper]): these wrappers capture
 * their content into a RemoteCompose document, so a `themeProvider` override that replaced one
 * would leave the body emitting `RemoteBox` / `RemoteColumn` / `RemoteRow` against the plain UI
 * applier and throw `IllegalStateException: Invalid applier`. Both the connector's own wrappers and
 * the upstream FQN are listed, because the check runs on the wrapper the preview *declared*, before
 * substitution.
 */
class RemoteComposeWrapperSubstitution : PreviewWrapperSubstitutionProvider {
  override fun substituteFor(originalWrapperFqn: String): Class<*>? =
    if (originalWrapperFqn == UPSTREAM_REMOTE_PREVIEW_WRAPPER_FQN) {
      RemoteOverridablePreviewWrapper::class.java
    } else null

  override fun isStructural(wrapperFqn: String): Boolean =
    wrapperFqn == UPSTREAM_REMOTE_PREVIEW_WRAPPER_FQN ||
      wrapperFqn == RemoteOverridablePreviewWrapper::class.java.name ||
      wrapperFqn == RemoteEmbeddedPreviewWrapper::class.java.name

  private companion object {
    const val UPSTREAM_REMOTE_PREVIEW_WRAPPER_FQN =
      "androidx.compose.remote.tooling.preview.RemotePreviewWrapper"
  }
}
