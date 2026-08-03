package ee.schimke.composeai.rcplayer.runtime

import ee.schimke.composeai.rcplayer.protocol.RcFloatWord
import ee.schimke.composeai.rcplayer.protocol.RcTouchExpression
import kotlin.math.abs
import kotlin.math.roundToInt

/** Stateful, platform-neutral evaluation of AndroidX touch expressions. */
public class RcTouchExpressionRuntime(
  private val operation: RcTouchExpression,
  arrays: (Int) -> FloatArray? = { null },
) {
  private val evaluator = RcFloatExpressionEvaluator(arrays)
  private var valueAtDown: Float = 0f
  private var expressionAtDown: Float = 0f

  public fun onDown(currentValue: Float, x: Float, y: Float, resolve: (RcFloatWord) -> Float) {
    valueAtDown = currentValue
    expressionAtDown = evaluate(x, y, 0f, 0f, resolve)
  }

  public fun onDrag(
    x: Float,
    y: Float,
    velocityX: Float,
    velocityY: Float,
    resolve: (RcFloatWord) -> Float,
  ): Float {
    val evaluated = evaluate(x, y, velocityX, velocityY, resolve)
    val value =
      if (operation.stopMode == RcTouchExpression.STOP_ABSOLUTE_POS) evaluated
      else valueAtDown + evaluated - expressionAtDown
    return constrain(value, resolve)
  }

  public fun stopTarget(
    currentValue: Float,
    minimum: Float,
    maximum: Float,
    resolve: (RcFloatWord) -> Float,
  ): Float {
    val specs = operation.stopSpec.map(resolve)
    val target =
      when (operation.stopMode) {
        RcTouchExpression.STOP_ENDS ->
          if (currentValue > (minimum + maximum) / 2f) maximum else minimum
        RcTouchExpression.STOP_NOTCHES_EVEN,
        RcTouchExpression.STOP_NOTCHES_SINGLE_EVEN -> {
          val count = specs.firstOrNull()?.toInt()?.coerceAtLeast(1) ?: 1
          val notchMaximum = specs.getOrNull(1) ?: maximum
          val step = (notchMaximum - minimum) / count
          if (step == 0f) minimum
          else {
            var snapped = minimum + ((currentValue - minimum) / step).roundToInt() * step
            if (operation.stopMode == RcTouchExpression.STOP_NOTCHES_SINGLE_EVEN) {
              snapped = snapped.coerceIn(valueAtDown - step, valueAtDown + step)
            }
            snapped
          }
        }
        RcTouchExpression.STOP_NOTCHES_PERCENTS ->
          specs.map { minimum + it * (maximum - minimum) }.minByOrNull { abs(it - currentValue) }
            ?: currentValue
        RcTouchExpression.STOP_NOTCHES_ABSOLUTE ->
          specs.minByOrNull { abs(it - currentValue) } ?: currentValue
        else -> currentValue
      }
    return target.coerceIn(minimum, maximum)
  }

  private fun evaluate(
    x: Float,
    y: Float,
    velocityX: Float,
    velocityY: Float,
    resolve: (RcFloatWord) -> Float,
  ): Float =
    evaluator.evaluate(operation.expression) { word ->
      when (word.referencedId) {
        ID_TOUCH_POS_X -> x
        ID_TOUCH_POS_Y -> y
        ID_TOUCH_VEL_X -> velocityX
        ID_TOUCH_VEL_Y -> velocityY
        else -> resolve(word)
      }
    }

  private fun constrain(value: Float, resolve: (RcFloatWord) -> Float): Float {
    val maximum = resolve(operation.max)
    val minimum = resolve(operation.min)
    if (operation.min.isNaNEncoded && operation.min.referencedId == 0) {
      val range = maximum
      if (range == 0f) return 0f
      return ((value % range) + range) % range
    }
    return value.coerceIn(minimum, maximum)
  }

  public companion object {
    public const val ID_TOUCH_POS_X: Int = 13
    public const val ID_TOUCH_POS_Y: Int = 14
    public const val ID_TOUCH_VEL_X: Int = 15
    public const val ID_TOUCH_VEL_Y: Int = 16
  }
}
