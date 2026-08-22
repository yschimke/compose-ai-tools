package ee.schimke.composeai.daemon

import ee.schimke.composeai.data.render.extensions.PreviewWrapperSubstitutionProvider

/**
 * Declares [RequiredEnvironmentWrapper] **structural** for the desktop daemon's test classpath —
 * registered through `src/test/resources/META-INF/services/...PreviewWrapperSubstitutionProvider`,
 * the same SPI `:data-remotecompose-connector` uses for the RemoteCompose wrappers.
 *
 * It stands in for `RemotePreviewWrapper`, which this classpath doesn't carry. Substitutes nothing,
 * so no other test's wrapper resolution changes.
 */
class FixtureStructuralWrapperProvider : PreviewWrapperSubstitutionProvider {
  override fun substituteFor(originalWrapperFqn: String): Class<*>? = null

  override fun isStructural(wrapperFqn: String): Boolean =
    wrapperFqn == "ee.schimke.composeai.daemon.RequiredEnvironmentWrapper"
}
