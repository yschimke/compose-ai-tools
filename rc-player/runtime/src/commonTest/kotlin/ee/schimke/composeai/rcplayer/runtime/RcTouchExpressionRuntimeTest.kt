package ee.schimke.composeai.rcplayer.runtime

import ee.schimke.composeai.rcplayer.protocol.RcFloatWord
import ee.schimke.composeai.rcplayer.protocol.RcTouchExpression
import kotlin.test.Test
import kotlin.test.assertEquals

class RcTouchExpressionRuntimeTest {
  @Test
  fun evaluatesDeltaTouchCoordinatesAgainstValueAtDown() {
    val operation = touch(RcTouchExpression.STOP_INSTANTLY, expression = listOf(reference(13)))
    val runtime = RcTouchExpressionRuntime(operation)

    runtime.onDown(currentValue = 20f, x = 100f, y = 0f, ::resolve)

    assertEquals(5f, runtime.onDrag(85f, 0f, 0f, 0f, ::resolve))
  }

  @Test
  fun snapsAllAuthoritativeNotchKinds() {
    val even = RcTouchExpressionRuntime(touch(RcTouchExpression.STOP_NOTCHES_EVEN, listOf(4f)))
    assertEquals(75f, even.stopTarget(68f, 0f, 100f, ::resolve))

    val percent =
      RcTouchExpressionRuntime(
        touch(RcTouchExpression.STOP_NOTCHES_PERCENTS, listOf(.1f, .6f, .9f))
      )
    assertEquals(60f, percent.stopTarget(68f, 0f, 100f, ::resolve), absoluteTolerance = .001f)

    val absolute =
      RcTouchExpressionRuntime(
        touch(RcTouchExpression.STOP_NOTCHES_ABSOLUTE, listOf(12f, 48f, 92f))
      )
    assertEquals(48f, absolute.stopTarget(55f, 0f, 100f, ::resolve))
  }

  private fun touch(
    stopMode: Int,
    stopSpec: List<Float> = emptyList(),
    expression: List<RcFloatWord> = emptyList(),
  ) =
    RcTouchExpression(
      id = 41,
      defaultValue = RcFloatWord.literal(0f),
      min = RcFloatWord.literal(0f),
      max = RcFloatWord.literal(100f),
      velocityId = RcFloatWord.literal(Float.NaN),
      touchEffects = 0,
      expression = expression,
      stopMode = stopMode,
      stopSpec = stopSpec.map(RcFloatWord::literal),
      easingSpec = emptyList(),
    )

  private fun reference(id: Int) = RcFloatWord(0x7fc00000 or id)

  private fun resolve(word: RcFloatWord): Float = word.value
}
