package ee.schimke.composeai.daemon

import ee.schimke.composeai.data.render.extensions.DataExtensionHookKind
import ee.schimke.composeai.data.render.extensions.DataExtensionId
import ee.schimke.composeai.data.render.extensions.DataExtensionLifecycle
import ee.schimke.composeai.data.render.extensions.DataExtensionPhase
import ee.schimke.composeai.data.render.extensions.compose.CompositionObserverHook
import ee.schimke.composeai.data.render.extensions.compose.hasCompositionObserverHook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopSceneRecomposerTest {
  @Test
  fun recompositionObserverExtensionDeclaresCompositionObserverHook() {
    val extension = RecompositionObserverExtension()
    val hook: CompositionObserverHook = extension

    assertEquals(DataExtensionId(RecompositionDataProductRegistry.KIND), extension.id)
    assertEquals(setOf(DataExtensionHookKind.CompositionObserver), extension.hooks)
    assertEquals(DataExtensionPhase.Instrumentation, extension.constraints.phase)
    assertEquals(DataExtensionLifecycle.Subscribed, extension.constraints.lifecycle)
    assertTrue(extension.hasCompositionObserverHook)
    assertEquals(extension, hook)
  }

  @Test
  fun readsCurrentRecomposerFromSceneHandle() {
    val recomposer = Any()
    val scene = ImageSceneLike(ComposeSceneLike(SceneRecomposerLike(recomposer)))

    val result = DesktopSceneRecomposer.currentFromSceneHandle(scene)

    assertSame(recomposer, result)
  }

  @Test(expected = NoSuchFieldException::class)
  fun failsClearlyForUnsupportedSceneHandles() {
    DesktopSceneRecomposer.currentFromSceneHandle(Any())
  }

  private class ImageSceneLike(private val scene: ComposeSceneLike)

  private class ComposeSceneLike(private val recomposer: SceneRecomposerLike)

  private class SceneRecomposerLike(private val recomposer: Any)
}
