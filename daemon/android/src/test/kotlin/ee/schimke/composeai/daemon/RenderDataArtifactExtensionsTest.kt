package ee.schimke.composeai.daemon

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import ee.schimke.composeai.data.render.extensions.DataExtensionConstraints
import ee.schimke.composeai.data.render.extensions.DataExtensionHookKind
import ee.schimke.composeai.data.render.extensions.DataExtensionId
import ee.schimke.composeai.data.render.extensions.PlannedDataExtension
import ee.schimke.composeai.data.render.extensions.SimplePlannedDataExtension
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RenderDataArtifactExtensionsTest {

  @Test
  fun `empty registry produces empty list`() {
    val built = RenderDataArtifactExtensions.Empty.build(applicationContext())
    assertTrue(built.isEmpty())
  }

  @Test
  fun `factories receive the per-render context`() {
    val context = applicationContext()
    val seen = mutableListOf<Context>()
    val sentinel: PlannedDataExtension =
      SimplePlannedDataExtension(id = DataExtensionId("test/sentinel"))
    val registry =
      RenderDataArtifactExtensions(
        listOf(
          RenderDataArtifactExtensionFactory { ctx ->
            seen += ctx
            sentinel
          }
        )
      )

    val built = registry.build(context)

    assertEquals(listOf(context), seen)
    assertEquals(listOf<PlannedDataExtension>(sentinel), built)
  }

  @Test
  fun `built extensions instantiate fresh per render`() {
    val factory = RenderDataArtifactExtensionFactory { _ ->
      SimplePlannedDataExtension(
        id = DataExtensionId("test/per-render"),
        constraints = DataExtensionConstraints(),
      )
    }
    val registry = RenderDataArtifactExtensions(listOf(factory))
    val first = registry.build(applicationContext()).single()
    val second = registry.build(applicationContext()).single()
    assertEquals(first.id, second.id)
    assertTrue(
      "factory should hand back a fresh instance each render so per-render recorders cannot leak",
      first !== second,
    )
  }

  @Test
  fun `bundled factories register fonts, resources, and i18n`() {
    val context = applicationContext()
    val factories =
      listOf(
        FontsRecorderExtension.factory,
        ResourcesRecorderExtension.factory,
        I18nTranslationsExtension.factory,
      )
    val registry = RenderDataArtifactExtensions(factories)
    assertEquals(3, registry.factories.size)
    val fonts = FontsRecorderExtension.factory.create(context)
    val resources = ResourcesRecorderExtension.factory.create(context)
    val i18n = I18nTranslationsExtension.factory.create(context)
    assertEquals(FontsRecorderExtension.ID, fonts.id)
    assertEquals(ResourcesRecorderExtension.ID, resources.id)
    assertEquals(I18nTranslationsExtension.ID, i18n.id)
    assertEquals(
      "fonts factory must always return a FontsRecorderExtension",
      FontsRecorderExtension::class.java,
      fonts.javaClass,
    )
    assertTrue(DataExtensionHookKind.AroundComposable in fonts.hooks)
    assertTrue(DataExtensionHookKind.AfterCapture in fonts.hooks)
    assertTrue(DataExtensionHookKind.AroundComposable in resources.hooks)
    assertTrue(DataExtensionHookKind.AfterCapture in resources.hooks)
    assertTrue(DataExtensionHookKind.AfterCapture in i18n.hooks)
  }

  private fun applicationContext(): Context =
    ApplicationProvider.getApplicationContext<Context>()
}
