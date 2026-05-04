package ee.schimke.composeai.daemon

import org.junit.Assert.assertSame
import org.junit.Test

class DesktopSceneRecomposerTest {
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
