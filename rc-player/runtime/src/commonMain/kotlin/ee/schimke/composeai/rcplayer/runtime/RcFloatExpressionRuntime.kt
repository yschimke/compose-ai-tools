package ee.schimke.composeai.rcplayer.runtime

import ee.schimke.composeai.rcplayer.protocol.RcFloatExpression
import ee.schimke.composeai.rcplayer.protocol.RcFloatWord

/** Stateful frame evaluator for one AndroidX `FloatExpression`. */
internal class RcFloatExpressionRuntime(
  private val operation: RcFloatExpression,
  arrays: (Int) -> FloatArray?,
) {
  private val evaluator = RcFloatExpressionEvaluator(arrays)
  private val tween = operation.animation?.takeUnless(::isSpring)?.let(::RcFloatAnimation)
  private val spring = operation.animation?.takeIf(::isSpring)?.let(::RcSpringAnimation)
  private var lastTarget = Float.NaN
  private var lastChange = Float.NaN

  fun evaluate(timeSeconds: Float, resolve: (RcFloatWord) -> Float): Float {
    val target = evaluator.evaluate(operation.expression, resolve = resolve)
    if (tween == null && spring == null) return target
    if (target != lastTarget) {
      if (lastTarget.isNaN()) {
        tween?.let { animation ->
          animation.setTarget(target)
          if (animation.initialValue.isNaN()) animation.setInitial(target)
        }
      } else {
        tween?.setInitial(tween.targetValue)
        tween?.setTarget(target)
      }
      spring?.setTarget(target)
      lastTarget = target
      lastChange = timeSeconds
    }
    if (lastChange.isNaN()) lastChange = timeSeconds
    return tween?.value(timeSeconds - lastChange) ?: spring!!.value(timeSeconds)
  }

  private fun isSpring(words: List<RcFloatWord>): Boolean = words.size > 4 && words[0].value == 0f
}
