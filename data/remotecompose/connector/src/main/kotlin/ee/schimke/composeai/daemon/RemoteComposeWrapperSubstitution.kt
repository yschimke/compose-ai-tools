package ee.schimke.composeai.daemon

import ee.schimke.composeai.data.render.extensions.PreviewWrapperSubstitutionProvider

/**
 * Substitutes upstream `androidx.compose.remote.tooling.preview.RemotePreviewWrapper` with
 * [RemoteOverridablePreviewWrapper] at preview-render time. Preview authors keep their existing
 * `@PreviewWrapper(RemotePreviewWrapper::class)` annotation untouched — when the connector is on
 * the classpath the renderer swaps the wrapper class transparently so seeded named-value
 * overrides reach the running player, and when it isn't the original upstream behaviour
 * applies. Discovered via the `META-INF/services/...PreviewWrapperSubstitutionProvider` file in
 * this module's resources.
 */
class RemoteComposeWrapperSubstitution : PreviewWrapperSubstitutionProvider {
  override fun substituteFor(originalWrapperFqn: String): Class<*>? =
    if (originalWrapperFqn == UPSTREAM_REMOTE_PREVIEW_WRAPPER_FQN) {
      RemoteOverridablePreviewWrapper::class.java
    } else null

  private companion object {
    const val UPSTREAM_REMOTE_PREVIEW_WRAPPER_FQN =
      "androidx.compose.remote.tooling.preview.RemotePreviewWrapper"
  }
}
