@file:OptIn(androidx.compose.runtime.ExperimentalComposeRuntimeApi::class)

package ee.schimke.composeai.daemon

import androidx.compose.runtime.RecomposeScope
import androidx.compose.runtime.tooling.CompositionObserverHandle
import androidx.compose.ui.ImageComposeScene
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `compose/recomposition` against a *transient* render scene.
 *
 * Lives here rather than beside the registry because it needs a real [ImageComposeScene], which
 * needs skiko's natives — the connector module's own tests deliberately stay fake-only so they run
 * anywhere. `:daemon:desktop` already renders real scenes throughout.
 */
class RecompositionRenderSceneTest {

  /** Registry whose observer install is a no-op, so mode and lifecycle can be asserted alone. */
  private class RecordingRegistry : RecompositionDataProductRegistry() {
    var installs: Int = 0
    var disposals: Int = 0

    override fun installObserver(
      scene: ImageComposeScene,
      onScopeRecomposed: (RecomposeScope) -> Unit,
      onScopeInvalidatedByState: (RecomposeScope) -> Unit,
      onScopeDisposed: (RecomposeScope) -> Unit,
    ): CompositionObserverHandle {
      installs++
      return object : CompositionObserverHandle {
        override fun dispose() {
          disposals++
        }
      }
    }
  }

  private fun subscribeParams(frameStreamId: String, mode: String): JsonObject = buildJsonObject {
    put("frameStreamId", JsonPrimitive(frameStreamId))
    put("mode", JsonPrimitive(mode))
  }

  @Test
  fun ordinaryRenderSceneInstallsWithoutPromotingSnapshotToDelta() {
    // A one-shot render's scene is gone before the payload is built, so reporting the result as a
    // per-input `delta` — which is what the held-session hook promotes to — would misdescribe an
    // initial-composition snapshot.
    val registry = RecordingRegistry()
    registry.onSubscribe(
      previewId = "p",
      kind = RecompositionDataProductRegistry.KIND,
      params = subscribeParams(frameStreamId = "f1", mode = "snapshot"),
    )
    val scene = ImageComposeScene(width = 4, height = 4)
    try {
      registry.onRenderSceneLifecycle("p", scene)
      assertEquals(1, registry.installs)

      // Teardown disposes the observer but keeps the counters: `attachmentsFor` runs after
      // teardown and still has to report the render it just measured.
      registry.onRenderSceneLifecycle("p", null)
      assertEquals(1, registry.disposals)

      val payload =
        registry
          .attachmentsFor("p", setOf(RecompositionDataProductRegistry.KIND))
          .single()
          .payload
          .toString()
      assertTrue(payload, payload.contains("\"mode\":\"snapshot\""))
    } finally {
      scene.close()
    }
  }

  @Test
  fun ordinaryRenderSceneDoesNotRegisterAsALiveSession() {
    // `liveScenes` answers "is there a live interactive session?" at subscribe time. A scene that
    // will not outlive the render must not answer yes, or a later delta subscribe believes it can
    // honour deltas when nothing is held.
    val registry = RecordingRegistry()
    val scene = ImageComposeScene(width = 4, height = 4)
    try {
      registry.onRenderSceneLifecycle("p", scene)

      registry.onSubscribe(
        previewId = "p",
        kind = RecompositionDataProductRegistry.KIND,
        params = subscribeParams(frameStreamId = "f1", mode = "delta"),
      )

      val payload =
        registry
          .attachmentsFor("p", setOf(RecompositionDataProductRegistry.KIND))
          .single()
          .payload
          .toString()
      assertTrue(payload, payload.contains("\"mode\":\"snapshot\""))
      assertEquals(0, registry.installs)
    } finally {
      scene.close()
    }
  }
}
