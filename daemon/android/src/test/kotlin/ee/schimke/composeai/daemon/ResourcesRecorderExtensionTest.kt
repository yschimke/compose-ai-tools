package ee.schimke.composeai.daemon

import androidx.test.core.app.ApplicationProvider
import ee.schimke.composeai.data.render.extensions.DataExtensionHookKind
import ee.schimke.composeai.data.render.extensions.DataExtensionTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ResourcesRecorderExtensionTest {

  @Test
  fun `extension declares around-composable plus after-capture hooks`() {
    val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    val extension = ResourcesRecorderExtension(context)
    assertEquals("resources/used", extension.id.value)
    assertEquals(
      setOf(DataExtensionHookKind.AroundComposable, DataExtensionHookKind.AfterCapture),
      extension.hooks,
    )
    assertEquals(setOf(DataExtensionTarget.Android), extension.targets)
    assertEquals(ResourcesRecorderExtension.ID, extension.id)
  }

  @Test
  fun `factory builds a fresh recorder on every render`() {
    val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    val first = ResourcesRecorderExtension.factory.create(context)
    val second = ResourcesRecorderExtension.factory.create(context)
    assertEquals(first.id, second.id)
    assertTrue("each render must own its own recorder lifecycle", first !== second)
  }
}
