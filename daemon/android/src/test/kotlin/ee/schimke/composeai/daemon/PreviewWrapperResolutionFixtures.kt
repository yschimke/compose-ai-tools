package ee.schimke.composeai.daemon

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.PreviewWrapper

/**
 * Wrapper class for [PreviewWrapperResolutionTest]. Mirrors the shape of upstream's
 * `PreviewWrapperProvider`: a public no-arg ctor + a `@Composable fun Wrap(content)` method that
 * `getDeclaredComposableMethod("Wrap", Function2::class.java)` can resolve.
 *
 * Body is the simplest possible — just invokes `content()` — because the test asserts on the
 * resolved JVM `Method` shape, not on rendered pixels. The renderer-android counterpart
 * ([ee.schimke.composeai.renderer.PreviewWrapperTest]) paints a green border to verify the
 * wrapper actually composes around the body; the daemon side gets that integration coverage from
 * the planned `:samples:remotecompose` harness scenario (see the follow-up issue).
 */
class GreenBorderWrapper {
  @Composable
  fun Wrap(content: @Composable () -> Unit) {
    content()
  }
}

@PreviewWrapper(GreenBorderWrapper::class)
@Composable
fun WrappedFixturePreview() {
  Box(modifier = Modifier)
}

@Composable
fun UnwrappedFixturePreview() {
  Box(modifier = Modifier)
}
