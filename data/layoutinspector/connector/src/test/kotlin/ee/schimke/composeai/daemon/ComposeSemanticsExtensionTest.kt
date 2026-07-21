package ee.schimke.composeai.daemon

import ee.schimke.composeai.data.render.extensions.DataExtensionHookKind
import ee.schimke.composeai.data.render.extensions.DataExtensionTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the portability contract of the shared [ComposeSemanticsExtension]: it is an after-capture
 * processor targeting *both* backends, so the Android daemon (via a factory wrapper) and the
 * Desktop daemon (constructed directly) register the same class instead of each carrying their own
 * `compose/semantics` production.
 */
class ComposeSemanticsExtensionTest {
  @Test
  fun `id is the compose semantics data-product kind`() {
    assertEquals(ComposeSemanticsDataProducer.KIND, ComposeSemanticsExtension().id.value)
  }

  @Test
  fun `is an after-capture processor targeting both android and desktop`() {
    val ext = ComposeSemanticsExtension()
    assertTrue(DataExtensionHookKind.AfterCapture in ext.hooks)
    assertEquals(setOf(DataExtensionTarget.Android, DataExtensionTarget.Desktop), ext.targets)
  }
}
