package ee.schimke.composeai.daemon

import androidx.compose.runtime.reflect.getDeclaredComposableMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Regression guard for the `@PreviewWrapper` annotation reading + wrapper-class resolution path
 * the daemon's [RenderEngine] uses. Before this guard existed the daemon ignored
 * `@PreviewWrapper(SomeProvider::class)` entirely, so samples relying on a custom applier (e.g.
 * `@PreviewWrapper(RemotePreviewWrapper::class)` in `samples/remotecompose`) crashed with
 * `IllegalStateException: Invalid applier`.
 *
 * `:renderer-android` has a parallel test ([ee.schimke.composeai.renderer.PreviewWrapperTest]) for
 * its own `resolveWrapper`; this one covers the duplicated daemon path that the v2 reconciliation
 * (see [RenderEngine] kdoc) eventually folds back into a shared helper.
 *
 * The daemon's `:compose-bom-compat` floor (1.9.5) doesn't ship the real
 * `androidx.compose.ui.tooling.preview.PreviewWrapper` annotation — that landed in 1.11.0-beta+ —
 * so a same-FQN stand-in lives next to this test (see
 * `PreviewWrapperStandIn.kt`). The daemon's resolver matches by FQN string, so the synthetic
 * annotation is indistinguishable from the real one at runtime for the resolution path.
 */
class PreviewWrapperResolutionTest {

  @Test
  fun `resolveWrapperOrNull returns wrapper Wrap method when annotation is present`() {
    val method =
      Class.forName("ee.schimke.composeai.daemon.PreviewWrapperResolutionFixturesKt")
        .getDeclaredComposableMethod("WrappedFixturePreview")

    val resolved = resolveWrapperOrNull(method)

    assertNotNull("wrapper must resolve when @PreviewWrapper is present", resolved)
    val (wrapMethod, instance) = resolved!!
    assertSame(GreenBorderWrapper::class.java, instance.javaClass)
    assertEquals("Wrap", wrapMethod.asMethod().name)
  }

  @Test
  fun `resolveWrapperOrNull returns null when annotation is absent`() {
    val method =
      Class.forName("ee.schimke.composeai.daemon.PreviewWrapperResolutionFixturesKt")
        .getDeclaredComposableMethod("UnwrappedFixturePreview")

    assertNull(resolveWrapperOrNull(method))
  }
}
