package ee.schimke.composeai.daemon

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.PreviewWrapper
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.data.render.extensions.PreviewWrapperSubstitutionProvider

/**
 * Wrapper class for [PreviewWrapperResolutionTest] and [WrappedPreviewRenderTest]. Mirrors the
 * shape of upstream's `PreviewWrapperProvider`: a public no-arg ctor + a `@Composable fun
 * Wrap(content)` method that `getDeclaredComposableMethod("Wrap", Function2::class.java)` can
 * resolve.
 *
 * Paints an opaque green border around [content] (8.dp padding inside a `fillMaxSize` green Box) so
 * [WrappedPreviewRenderTest] can pixel-assert the wrapper actually composed around the body — if
 * `RenderEngine.setContent` ever stops routing through `InvokeWithOptionalWrapper`, the body
 * renders raw and the edge pixels come out body-colour instead of green. The body shape is fine for
 * [PreviewWrapperResolutionTest] too because that test only inspects the resolved JVM `Method`,
 * never invokes the composable.
 */
class GreenBorderWrapper {
  @Composable
  fun Wrap(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF1B5E20))) {
      Box(modifier = Modifier.fillMaxSize().padding(8.dp)) { content() }
    }
  }
}

/**
 * A second border wrapper in a colour [GreenBorderWrapper] can't be confused with, so a test that
 * composes two wrappers at once can tell which one painted which pixels. Stands in for a selected
 * `@ThemeCatalog` theme in [WrappedPreviewRenderTest]'s structural-nesting coverage.
 */
class BlueBorderWrapper {
  @Composable
  fun Wrap(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF1565C0))) {
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

/**
 * App-owned preview environment used to model required locals such as `LocalSharedTransitionScope`.
 * The renderer cannot provide an app-specific local itself; it must preserve the preview's declared
 * wrapper in every render mode.
 */
private val LocalRequiredPreviewEnvironment = staticCompositionLocalOf { false }

class RequiredCompositionLocalWrapper {
  @Composable
  fun Wrap(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalRequiredPreviewEnvironment provides true, content = content)
  }
}

/**
 * Declares [RequiredCompositionLocalWrapper] **structural** for the daemon's test classpath —
 * registered through `src/test/resources/META-INF/services/...PreviewWrapperSubstitutionProvider`,
 * the same SPI `:data-remotecompose-connector` uses to declare the RemoteCompose wrappers.
 *
 * It stands in for `RemotePreviewWrapper`, which the daemon's test classpath doesn't carry: both
 * install something the preview body cannot compose without (a required local here, the
 * RemoteCompose applier there), so a `themeProvider` override must nest around it rather than
 * replace it. Substitutes nothing, so no other test's wrapper resolution changes.
 */
class FixtureStructuralWrapperProvider : PreviewWrapperSubstitutionProvider {
  override fun substituteFor(originalWrapperFqn: String): Class<*>? = null

  override fun isStructural(wrapperFqn: String): Boolean =
    wrapperFqn == RequiredCompositionLocalWrapper::class.java.name
}

/**
 * Fails before drawing unless [RequiredCompositionLocalWrapper] ran. Used by held/live regression
 * coverage, where the wrapper FQN crosses the non-instrumented bridge as part of `Start`.
 */
@Composable
fun WrapperRequiredFixturePreview() {
  check(LocalRequiredPreviewEnvironment.current) {
    "Required app preview environment was not installed"
  }
  Box(modifier = Modifier.fillMaxSize().background(Color(0xFF66BB6A)))
}

/**
 * Scrollable twin of [WrapperRequiredFixturePreview]. The LONG/GIF path owns a separate
 * `setContent` call and historically invoked the body directly, so its wrapper contract needs an
 * independent end-to-end guard.
 */
@Composable
fun WrapperRequiredScrollableFixturePreview() {
  check(LocalRequiredPreviewEnvironment.current) {
    "Required app preview environment was not installed"
  }
  LazyColumn(modifier = Modifier.fillMaxSize()) {
    items(30) { index ->
      Box(
        modifier =
          Modifier.fillMaxWidth()
            .height(24.dp)
            .background(if (index % 2 == 0) Color(0xFFEF5350) else Color(0xFF1B5E20))
      )
    }
  }
}
