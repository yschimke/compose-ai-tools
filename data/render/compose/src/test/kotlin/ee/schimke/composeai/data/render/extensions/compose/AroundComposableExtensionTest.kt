package ee.schimke.composeai.data.render.extensions.compose

import androidx.compose.runtime.Composable
import ee.schimke.composeai.data.render.extensions.DataExtensionHookKind
import ee.schimke.composeai.data.render.extensions.DataExtensionId
import org.junit.Assert.assertEquals
import org.junit.Test

class AroundComposableExtensionTest {
  @Test
  fun declaresAroundComposableHookForSimpleWrapperExtensions() {
    val extension = SimpleBackgroundExtension()
    val hook: AroundComposableHook = extension

    assertEquals(DataExtensionId("render-device-background"), extension.id)
    assertEquals(setOf(DataExtensionHookKind.AroundComposable), extension.hooks)
    assertEquals(extension, hook)
  }

  @Test
  fun declaresComposableExtractorHookForSimpleExtractorExtensions() {
    val extension = SimpleThemeExtractorExtension()
    val hook: ComposableExtractorHook = extension

    assertEquals(DataExtensionId("compose-theme"), extension.id)
    assertEquals(setOf(DataExtensionHookKind.ComposableExtractor), extension.hooks)
    assertEquals(extension, hook)
  }

  @Test
  fun declaresCompositionObserverHookForSimpleObserverExtensions() {
    val extension = SimpleRecompositionObserverExtension()
    val hook: CompositionObserverHook = extension

    assertEquals(DataExtensionId("compose-recomposition"), extension.id)
    assertEquals(setOf(DataExtensionHookKind.CompositionObserver), extension.hooks)
    assertEquals(extension, hook)
  }

  private class SimpleBackgroundExtension :
    AroundComposableExtension(DataExtensionId("render-device-background")) {
    @Composable
    override fun AroundComposable(content: @Composable () -> Unit) {
      content()
    }
  }

  private class SimpleThemeExtractorExtension :
    ComposableExtractorExtension(DataExtensionId("compose-theme")) {
    @Composable
    override fun Extract(sink: ExtensionCompositionSink) {
      sink.put(id, "theme", "material")
    }
  }

  private class SimpleRecompositionObserverExtension :
    CompositionObserverExtension(DataExtensionId("compose-recomposition")) {
    @Composable
    override fun Observe(sink: ExtensionCompositionSink) {
      sink.put(id, "observer", "installed")
    }
  }
}
