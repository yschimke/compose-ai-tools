package ee.schimke.composeai.rcplayer.runtime

import ee.schimke.composeai.rcplayer.protocol.RcAnimationSpec
import ee.schimke.composeai.rcplayer.protocol.RcFloatWord
import ee.schimke.composeai.rcplayer.protocol.RcLayoutAnimation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RcAnimationTimelineTest {
  @Test
  fun evaluatesMotionAndVisibilityOnTheirIndependentAndroidXDurations() {
    val timeline =
      RcAnimationTimeline(
        RcAnimationSpec(
          animationId = 1,
          motionDurationMillis = RcFloatWord.literal(1_000f),
          motionEasingType = 4,
          visibilityDurationMillis = RcFloatWord.literal(2_000f),
          visibilityEasingType = 4,
          enterAnimation = RcLayoutAnimation.FadeIn,
          exitAnimation = RcLayoutAnimation.FadeOut,
        )
      )

    assertEquals(RcAnimationProgress(0f, 0f), timeline.progress(0f))
    val halfway = timeline.progress(1_000f)
    assertEquals(1f, halfway.motion, .0001f)
    assertEquals(.5f, halfway.visibility, .02f)
    assertFalse(halfway.isDone)
    assertTrue(timeline.progress(2_000f).isDone)
  }

  @Test
  fun reproducesEveryNonParticleAndroidXVisibilityTransform() {
    val base =
      RcAnimationSpec(
        animationId = 1,
        motionDurationMillis = RcFloatWord.literal(300f),
        motionEasingType = 1,
        visibilityDurationMillis = RcFloatWord.literal(300f),
        visibilityEasingType = 1,
        enterAnimation = RcLayoutAnimation.FadeIn,
        exitAnimation = RcLayoutAnimation.FadeOut,
      )

    assertEquals(.25f, base.visibilityTransform(entering = true, .25f).alpha)
    assertEquals(.75f, base.visibilityTransform(entering = false, .25f).alpha)
    assertEquals(
      .75f,
      base
        .copy(enterAnimation = RcLayoutAnimation.SlideLeft)
        .visibilityTransform(entering = true, .25f)
        .translationX,
    )
    assertEquals(
      -.25f,
      base
        .copy(exitAnimation = RcLayoutAnimation.SlideLeft)
        .visibilityTransform(entering = false, .25f)
        .translationX,
    )
    val rotate =
      base
        .copy(enterAnimation = RcLayoutAnimation.Rotate)
        .visibilityTransform(entering = true, .25f)
    assertEquals(.25f, rotate.alpha)
    assertEquals(.25f, rotate.scale)
    assertEquals(90f, rotate.rotationDegrees)
    assertFalse(base.copy(exitAnimation = RcLayoutAnimation.Rotate).hasPortableVisibilityAnimation)
  }
}
