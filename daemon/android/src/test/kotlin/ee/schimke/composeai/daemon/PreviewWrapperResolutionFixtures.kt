package ee.schimke.composeai.daemon

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.PreviewWrapper
import androidx.compose.ui.unit.dp

/**
 * Wrapper class for [PreviewWrapperResolutionTest] and [WrappedPreviewRenderTest]. Mirrors the
 * shape of upstream's `PreviewWrapperProvider`: a public no-arg ctor + a
 * `@Composable fun Wrap(content)` method that `getDeclaredComposableMethod("Wrap",
 * Function2::class.java)` can resolve.
 *
 * Paints an opaque green border around [content] (8.dp padding inside a `fillMaxSize` green Box)
 * so [WrappedPreviewRenderTest] can pixel-assert the wrapper actually composed around the body —
 * if `RenderEngine.setContent` ever stops routing through `InvokeWithOptionalWrapper`, the body
 * renders raw and the edge pixels come out body-colour instead of green. The body shape is fine
 * for [PreviewWrapperResolutionTest] too because that test only inspects the resolved JVM
 * `Method`, never invokes the composable.
 */
class GreenBorderWrapper {
  @Composable
  fun Wrap(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF1B5E20))) {
      Box(modifier = Modifier.fillMaxSize().padding(8.dp)) { content() }
    }
  }
}

@PreviewWrapper(GreenBorderWrapper::class)
@Composable
fun WrappedFixturePreview() {
  Box(modifier = Modifier.fillMaxSize().background(Color(0xFFEF5350)))
}

@Composable
fun UnwrappedFixturePreview() {
  Box(modifier = Modifier)
}
