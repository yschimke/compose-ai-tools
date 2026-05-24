package ee.schimke.composeai.daemon

import ee.schimke.composeai.data.render.extensions.PreviewWrapperSubstitutionProvider
import ee.schimke.composeai.data.render.extensions.loadPreviewWrapperClass
import java.util.ServiceLoader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the wrapper-substitution contract that lets preview authors keep their existing
 * `@PreviewWrapper(RemotePreviewWrapper::class)` annotation untouched when the connector is on
 * the classpath. Covers the provider's matching logic, the META-INF service registration, and
 * the renderer-facing [loadPreviewWrapperClass] helper that both `:renderer-android` and
 * `:renderer-desktop` call from their `resolveWrapper`s.
 */
class RemoteComposeWrapperSubstitutionTest {

  @Test
  fun `substitutes upstream RemotePreviewWrapper for the override-aware wrapper`() {
    val provider = RemoteComposeWrapperSubstitution()
    val substitute =
      provider.substituteFor("androidx.compose.remote.tooling.preview.RemotePreviewWrapper")
    assertNotNull("connector must substitute upstream wrapper", substitute)
    assertSame(RemoteOverridablePreviewWrapper::class.java, substitute)
  }

  @Test
  fun `leaves unrelated wrapper FQNs alone`() {
    val provider = RemoteComposeWrapperSubstitution()
    assertNull(provider.substituteFor("com.example.MyOwnWrapper"))
    assertNull(provider.substituteFor("androidx.compose.ui.tooling.preview.OtherWrapper"))
  }

  @Test
  fun `service file registers the provider on the classpath`() {
    val providers =
      ServiceLoader.load(PreviewWrapperSubstitutionProvider::class.java).toList()
    assertTrue(
      "META-INF/services must list RemoteComposeWrapperSubstitution; found $providers",
      providers.any { it is RemoteComposeWrapperSubstitution },
    )
  }

  @Test
  fun `loadPreviewWrapperClass routes the upstream FQN through the connector substitute`() {
    val resolved =
      loadPreviewWrapperClass("androidx.compose.remote.tooling.preview.RemotePreviewWrapper")
    assertSame(RemoteOverridablePreviewWrapper::class.java, resolved)
  }

  @Test
  fun `loadPreviewWrapperClass falls back to Class-forName for unsubstituted FQNs`() {
    // `String` is a stand-in — any always-loadable class works as the "fall through" probe.
    val resolved = loadPreviewWrapperClass("java.lang.String")
    assertEquals(String::class.java, resolved)
  }
}
