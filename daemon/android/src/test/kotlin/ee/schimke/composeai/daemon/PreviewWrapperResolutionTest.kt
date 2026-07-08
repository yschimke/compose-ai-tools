package ee.schimke.composeai.daemon

import androidx.compose.runtime.reflect.getDeclaredComposableMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Regression guard for the `@PreviewWrapper` wrapper-class resolution path the daemon's
 * [RenderEngine] uses. Before this guard existed the daemon ignored
 * `@PreviewWrapper(SomeProvider::class)` entirely, so samples relying on a custom applier (e.g.
 * `@PreviewWrapper(RemotePreviewWrapper::class)` in `samples/remotecompose`) crashed with
 * `IllegalStateException: Invalid applier`.
 *
 * **Why the spec-driven path is the production path** (issue #1440). The upstream
 * `androidx.compose.ui.tooling.preview.PreviewWrapper` annotation has `AnnotationRetention.BINARY`,
 * so `Method.annotations` never returns it at runtime — the `@Method.annotations`-based fallback
 * inside [resolveWrapperOrNull] is a best-effort backup for direct-payload callers and **does not**
 * fire in real-world preview renders. The gradle plugin's `extractWrapperFqn` reads the FQN from
 * the class-file annotation tables and writes it into `previews.json` (where the annotation IS
 * still visible); the daemon threads it into [RenderSpec.wrapperClassName] via
 * [PreviewManifestRouter]. The first test below covers that spec-driven production path; the second
 * covers the reflection fallback by stamping the same stand-in annotation on a fixture (note the
 * stand-in deliberately uses `AnnotationRetention.BINARY` now to mirror upstream — see
 * `PreviewWrapperStandIn.kt`, so the fallback path actually misses for the binary annotation, and
 * the spec-driven path is the only one that resolves).
 *
 * `:renderer-android` has a parallel test ([ee.schimke.composeai.renderer.PreviewWrapperTest]) for
 * its own `resolveWrapper`; this one covers the duplicated daemon path that the v2 reconciliation
 * (see [RenderEngine] kdoc) eventually folds back into a shared helper.
 */
class PreviewWrapperResolutionTest {

  @Test
  fun `resolveWrapperOrNull with spec FQN returns wrapper Wrap method`() {
    val method =
      Class.forName("ee.schimke.composeai.daemon.PreviewWrapperResolutionFixturesKt")
        .getDeclaredComposableMethod("WrappedFixturePreview")

    val resolved = resolveWrapperOrNull(method, GreenBorderWrapper::class.java.name)

    assertNotNull("wrapper must resolve when spec FQN is present", resolved)
    val (wrapMethod, instance) = resolved!!
    assertSame(GreenBorderWrapper::class.java, instance.javaClass)
    assertEquals("Wrap", wrapMethod.asMethod().name)
  }

  @Test
  fun `resolveWrapperOrNull returns null when spec FQN is absent and annotation has binary retention`() {
    // `PreviewWrapperStandIn.kt`'s `@PreviewWrapper` mirrors upstream's
    // `AnnotationRetention.BINARY`,
    // so `Method.annotations` won't include it. This guards issue #1440's first regression: the
    // pre-fix code relied on runtime reflection, which silently no-ops in production.
    val method =
      Class.forName("ee.schimke.composeai.daemon.PreviewWrapperResolutionFixturesKt")
        .getDeclaredComposableMethod("WrappedFixturePreview")

    assertNull(
      "binary-retained @PreviewWrapper must NOT resolve via reflection — spec FQN is the only path",
      resolveWrapperOrNull(method, wrapperFqnFromSpec = null),
    )
  }

  @Test
  fun `resolveWrapperOrNull returns null when no annotation is present and no FQN supplied`() {
    val method =
      Class.forName("ee.schimke.composeai.daemon.PreviewWrapperResolutionFixturesKt")
        .getDeclaredComposableMethod("UnwrappedFixturePreview")

    assertNull(resolveWrapperOrNull(method))
  }
}
