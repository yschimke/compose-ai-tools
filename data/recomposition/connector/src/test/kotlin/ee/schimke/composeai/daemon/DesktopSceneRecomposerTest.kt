@file:OptIn(androidx.compose.runtime.ExperimentalComposeRuntimeApi::class)

package ee.schimke.composeai.daemon

import androidx.compose.runtime.RecomposeScope
import androidx.compose.runtime.tooling.CompositionObserverHandle
import androidx.compose.ui.ImageComposeScene
import ee.schimke.composeai.data.render.extensions.DataExtensionHookKind
import ee.schimke.composeai.data.render.extensions.DataExtensionId
import ee.schimke.composeai.data.render.extensions.DataExtensionLifecycle
import ee.schimke.composeai.data.render.extensions.DataExtensionPhase
import ee.schimke.composeai.data.render.extensions.compose.CompositionObserverHook
import ee.schimke.composeai.data.render.extensions.compose.hasCompositionObserverHook
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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

  @Test
  fun attachmentsFor_after_globally_unavailable_returns_no_attachment() {
    val registry = RecompositionDataProductRegistry()
    registry.onSubscribe(
      previewId = "p",
      kind = RecompositionDataProductRegistry.KIND,
      params = subscribeParams(frameStreamId = "f1", mode = "snapshot"),
    )

    val before = registry.attachmentsFor("p", setOf(RecompositionDataProductRegistry.KIND))
    assertEquals(1, before.size)

    registry.markGloballyUnavailableForTesting()

    val after = registry.attachmentsFor("p", setOf(RecompositionDataProductRegistry.KIND))
    assertTrue(after.isEmpty())
  }

  @Test
  fun subscribe_after_globally_unavailable_marks_state_unavailable_without_install() {
    val registry =
      object : RecompositionDataProductRegistry() {
        var installAttempts: Int = 0

        override fun installObserver(
          scene: ImageComposeScene,
          onScopeRecomposed: (RecomposeScope) -> Unit,
          onScopeDisposed: (RecomposeScope) -> Unit,
        ): CompositionObserverHandle {
          installAttempts++
          return object : CompositionObserverHandle {
            override fun dispose() {}
          }
        }
      }
    registry.markGloballyUnavailableForTesting()

    registry.onSubscribe(
      previewId = "p",
      kind = RecompositionDataProductRegistry.KIND,
      params = subscribeParams(frameStreamId = "f1", mode = "delta"),
    )

    // No install attempt was made even though the requested mode was delta.
    assertEquals(0, registry.installAttempts)

    // attachmentsFor reflects the unavailable signal — empty list rather than empty payload.
    val attachments = registry.attachmentsFor("p", setOf(RecompositionDataProductRegistry.KIND))
    assertTrue(attachments.isEmpty())
  }

  private fun subscribeParams(frameStreamId: String, mode: String): JsonObject = buildJsonObject {
    put("frameStreamId", JsonPrimitive(frameStreamId))
    put("mode", JsonPrimitive(mode))
  }

  private class ImageSceneLike(private val scene: ComposeSceneLike)

  private class ComposeSceneLike(private val recomposer: SceneRecomposerLike)

  private class SceneRecomposerLike(private val recomposer: Any)
}
