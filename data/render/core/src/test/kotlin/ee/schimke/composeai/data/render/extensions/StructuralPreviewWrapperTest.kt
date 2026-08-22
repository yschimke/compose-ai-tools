package ee.schimke.composeai.data.render.extensions

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards [isStructuralPreviewWrapper] — the check that keeps a `themeProvider` override from
 * replacing a wrapper the preview body cannot compose without.
 *
 * The built-in entry (upstream `RemotePreviewWrapper`) has to answer true with no connector on the
 * classpath, because the crash it prevents — `IllegalStateException: Invalid applier` out of every
 * RemoteCompose widget preview rendered under a declared theme — does not depend on the connector
 * being present. The SPI half is covered by the fake provider registered through this module's test
 * `META-INF/services/...PreviewWrapperSubstitutionProvider` resource, exactly as
 * `:data-remotecompose-connector` registers its real one.
 */
class StructuralPreviewWrapperTest {

  @Test
  fun `upstream RemotePreviewWrapper is structural without any connector`() {
    assertTrue(
      isStructuralPreviewWrapper(
        "androidx.compose.remote.tooling.preview.RemotePreviewWrapper",
        javaClass.classLoader,
      )
    )
  }

  @Test
  fun `a registered provider can declare its own wrapper structural`() {
    assertTrue(
      isStructuralPreviewWrapper(
        "ee.schimke.composeai.data.render.extensions.FakeStructuralWrapper",
        javaClass.classLoader,
      )
    )
  }

  @Test
  fun `an ordinary theme wrapper is not structural`() {
    // The common case: an app's @ThemeCatalog provider, which a themeProvider override is
    // *supposed* to replace. Answering true here would silently disable the theme selector by
    // letting the preview's own theme shadow the chosen one.
    assertFalse(
      isStructuralPreviewWrapper("com.example.AppLightThemeProvider", javaClass.classLoader)
    )
  }
}

/**
 * Registered via `src/test/resources/META-INF/services/...PreviewWrapperSubstitutionProvider`.
 * Substitutes nothing — it exists only to prove the structural half of the SPI is consulted.
 */
class FakeStructuralWrapperProvider : PreviewWrapperSubstitutionProvider {
  override fun substituteFor(originalWrapperFqn: String): Class<*>? = null

  override fun isStructural(wrapperFqn: String): Boolean =
    wrapperFqn == "ee.schimke.composeai.data.render.extensions.FakeStructuralWrapper"
}
