package ee.schimke.composeai.rcplayer.runtime

import ee.schimke.composeai.rcplayer.protocol.RcAnimationSpec
import ee.schimke.composeai.rcplayer.protocol.RcFloatWord
import ee.schimke.composeai.rcplayer.protocol.RcLayoutAnimation

/** The two independent AndroidX animation channels at one elapsed time. */
public data class RcAnimationProgress(val motion: Float, val visibility: Float) {
  public val isDone: Boolean
    get() = motion >= 1f && visibility >= 1f
}

/** Paint transform used by AndroidX visibility transitions; translations are parent-size ratios. */
public data class RcVisibilityTransform(
  val alpha: Float = 1f,
  val translationX: Float = 0f,
  val translationY: Float = 0f,
  val scale: Float = 1f,
  val rotationDegrees: Float = 0f,
  val paintsContent: Boolean = true,
)

/** Exact non-particle branches from AndroidX `AnimateMeasure.paint`. */
public fun RcAnimationSpec.visibilityTransform(
  entering: Boolean,
  progress: Float,
): RcVisibilityTransform {
  val p = progress
  val animation = if (entering) enterAnimation.androidXValue else exitAnimation.androidXValue
  return if (entering) {
    when (animation) {
      RcLayoutAnimation.FadeIn.wireValue -> RcVisibilityTransform(alpha = p)
      RcLayoutAnimation.FadeOut.wireValue,
      RcLayoutAnimation.Particle.wireValue -> RcVisibilityTransform(paintsContent = false)
      RcLayoutAnimation.SlideLeft.wireValue -> RcVisibilityTransform(translationX = 1f - p)
      RcLayoutAnimation.SlideRight.wireValue -> RcVisibilityTransform(translationX = -(1f - p))
      RcLayoutAnimation.SlideTop.wireValue -> RcVisibilityTransform(translationY = 1f - p)
      RcLayoutAnimation.SlideBottom.wireValue -> RcVisibilityTransform(translationY = -(1f - p))
      RcLayoutAnimation.Rotate.wireValue ->
        RcVisibilityTransform(alpha = p, scale = p, rotationDegrees = p * 360f)
      else -> error("Unreachable normalized AndroidX animation value $animation")
    }
  } else {
    when (animation) {
      RcLayoutAnimation.FadeOut.wireValue -> RcVisibilityTransform(alpha = 1f - p)
      RcLayoutAnimation.SlideLeft.wireValue -> RcVisibilityTransform(translationX = -p)
      RcLayoutAnimation.SlideRight.wireValue -> RcVisibilityTransform(translationX = p)
      RcLayoutAnimation.SlideTop.wireValue -> RcVisibilityTransform(translationY = -p)
      RcLayoutAnimation.SlideBottom.wireValue -> RcVisibilityTransform(translationY = p)
      // AndroidX routes PARTICLE, ROTATE, FADE_IN, and normalized unknowns to ParticleAnimation.
      else -> RcVisibilityTransform(paintsContent = false)
    }
  }
}

/** Whether this spec avoids AndroidX's ParticleAnimation-only exit branches. */
public val RcAnimationSpec.hasPortableVisibilityAnimation: Boolean
  get() =
    exitAnimation.androidXValue in
      RcLayoutAnimation.FadeOut.wireValue..RcLayoutAnimation.SlideBottom.wireValue

/**
 * Deterministic evaluator for `AnimateMeasure.update`.
 *
 * AndroidX stores durations in milliseconds but configures `FloatAnimation` in seconds. Keeping
 * this evaluator outside Compose lets Wasm and iOS share the same curves and makes frame-by-frame
 * conformance tests independent of a platform display link.
 */
public class RcAnimationTimeline(spec: RcAnimationSpec) {
  private val motion = animation(spec.motionDurationMillis.value, spec.motionEasingType)
  private val visibility = animation(spec.visibilityDurationMillis.value, spec.visibilityEasingType)

  public fun progress(elapsedMillis: Float): RcAnimationProgress {
    val seconds = elapsedMillis / 1_000f
    return RcAnimationProgress(motion.value(seconds), visibility.value(seconds))
  }

  private fun animation(durationMillis: Float, easingType: Int): RcFloatAnimation =
    RcFloatAnimation(listOf(RcFloatWord.literal(durationMillis / 1_000f), RcFloatWord(easingType)))
      .also {
        it.setInitial(0f)
        it.setTarget(1f)
      }
}
