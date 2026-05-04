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

  private class SimpleBackgroundExtension :
    AroundComposableExtension(DataExtensionId("render-device-background")) {
    @Composable
    override fun AroundComposable(content: @Composable () -> Unit) {
      content()
    }
  }
}
