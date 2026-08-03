package ee.schimke.composeai.rcplayer.runtime

import ee.schimke.composeai.rcplayer.protocol.RcFloatWord
import ee.schimke.composeai.rcplayer.protocol.RcPathCommands
import ee.schimke.composeai.rcplayer.protocol.RcPathData
import ee.schimke.composeai.rcplayer.protocol.RcPathExpression
import kotlin.math.hypot
import kotlin.math.sign

/** Samples an AndroidX `PathExpression` and serializes its cubic path representation. */
internal class RcPathExpressionGenerator(
  private val expressionEvaluator: RcFloatExpressionEvaluator
) {
  fun generate(operation: RcPathExpression, resolve: (RcFloatWord) -> Float): RcPathData {
    val count = resolve(operation.count).toInt()
    require(count != 0) { "path length must be > 1" }
    val loop = operation.flags and RcPathExpression.LOOP != 0
    val minimum = resolve(operation.min)
    val maximum = resolve(operation.max)
    val step = if (loop) (maximum - minimum) / count else (maximum - minimum) / (count - 1)
    val x = FloatArray(count)
    val y = FloatArray(count)
    if (operation.flags and RcPathExpression.POLAR != 0) {
      val centerX = resolveExpressionLiteral(operation.expressionY[0], resolve)
      val centerY = resolveExpressionLiteral(operation.expressionY[1], resolve)
      repeat(count) { index ->
        val angle = minimum + index * step
        val radius =
          expressionEvaluator.evaluate(operation.expressionX, floatArrayOf(angle), resolve)
        x[index] = centerX + radius * kotlin.math.cos(angle)
        y[index] = centerY + radius * kotlin.math.sin(angle)
      }
    } else {
      repeat(count) { index ->
        val value = minimum + index * step
        x[index] = expressionEvaluator.evaluate(operation.expressionX, floatArrayOf(value), resolve)
        y[index] = expressionEvaluator.evaluate(operation.expressionY, floatArrayOf(value), resolve)
      }
    }
    val mode = operation.flags and 0x6
    val words =
      when (mode) {
        RcPathExpression.LINEAR -> linear(x, y, loop)
        RcPathExpression.MONOTONIC -> curved(x, y, loop, monotonic = true)
        else -> curved(x, y, loop, monotonic = false)
      }
    val winding = (operation.flags and RcPathExpression.WINDING_MASK) shr 24
    return RcPathData(operation.id or (winding shl 24), words)
  }

  private fun resolveExpressionLiteral(word: RcFloatWord, resolve: (RcFloatWord) -> Float): Float {
    val payload = word.bits and 0x7fffff
    return if (
      word.isNaNEncoded &&
        payload !in (RcFloatExpressionEvaluator.OFFSET + 1)..RcFloatExpressionEvaluator.LAST_OP
    ) {
      resolve(word)
    } else {
      word.value
    }
  }

  private fun linear(x: FloatArray, y: FloatArray, loop: Boolean): List<RcFloatWord> {
    val path = PathWords()
    if (x.isEmpty()) return path.words
    path.moveTo(x[0], y[0])
    if (x.size == 1 && !loop) return path.words
    val segments = if (loop) x.size else x.size - 1
    repeat(segments) { from ->
      val to = (from + 1) % x.size
      path.cubicTo(x[from], y[from], x[to], y[to], x[to], y[to])
    }
    if (loop) path.close()
    return path.words
  }

  private fun curved(
    x: FloatArray,
    y: FloatArray,
    loop: Boolean,
    monotonic: Boolean,
  ): List<RcFloatWord> {
    val path = PathWords()
    if (x.isEmpty()) return path.words
    path.moveTo(x[0], y[0])
    if (x.size == 1 && !loop) return path.words
    val segments = if (loop) x.size else x.size - 1
    val lengths = FloatArray(segments)
    val xSlopes = FloatArray(segments)
    val ySlopes = FloatArray(segments)
    repeat(segments) { from ->
      val to = (from + 1) % x.size
      val dx = x[to] - x[from]
      val dy = y[to] - y[from]
      val distance = hypot(dx, dy).let { if (it == 0f) 1e-12f else it }
      lengths[from] = distance
      xSlopes[from] = dx / distance
      ySlopes[from] = dy / distance
    }
    val xTangents =
      if (monotonic) monotonicTangents(xSlopes, lengths, loop)
      else smoothTangents(xSlopes, lengths, loop)
    val yTangents =
      if (monotonic) monotonicTangents(ySlopes, lengths, loop)
      else smoothTangents(ySlopes, lengths, loop)
    repeat(segments) { from ->
      val to = (from + 1) % x.size
      val scale = lengths[from] / 3f
      path.cubicTo(
        x[from] + xTangents[from] * scale,
        y[from] + yTangents[from] * scale,
        x[to] - xTangents[to] * scale,
        y[to] - yTangents[to] * scale,
        x[to],
        y[to],
      )
    }
    if (loop) path.close()
    return path.words
  }

  private fun smoothTangents(slopes: FloatArray, lengths: FloatArray, loop: Boolean): FloatArray {
    val count = if (loop) slopes.size else slopes.size + 1
    val tangent = FloatArray(count)
    if (loop) {
      repeat(count) { index ->
        val previous = (index - 1 + slopes.size) % slopes.size
        val next = index % slopes.size
        tangent[index] =
          (lengths[previous] * slopes[next] + lengths[next] * slopes[previous]) /
            (lengths[previous] + lengths[next])
      }
    } else {
      tangent[0] = slopes[0]
      tangent[count - 1] = slopes.last()
      for (index in 1 until count - 1) {
        tangent[index] =
          (lengths[index - 1] * slopes[index] + lengths[index] * slopes[index - 1]) /
            (lengths[index - 1] + lengths[index])
      }
    }
    return tangent
  }

  private fun monotonicTangents(
    slopes: FloatArray,
    lengths: FloatArray,
    loop: Boolean,
  ): FloatArray {
    val segments = slopes.size
    val count = if (loop) segments else segments + 1
    val tangent = FloatArray(count)
    repeat(count) { index ->
      val previous = (index - 1 + segments) % segments
      val next = index % segments
      tangent[index] =
        when {
          !loop && index == 0 -> slopes[0]
          !loop && index == count - 1 -> slopes.last()
          slopes[previous] == 0f ||
            slopes[next] == 0f ||
            sign(slopes[previous]) != sign(slopes[next]) -> 0f
          else -> {
            val w1 = 2f * lengths[next] + lengths[previous]
            val w2 = lengths[next] + 2f * lengths[previous]
            (w1 + w2) / (w1 / slopes[previous] + w2 / slopes[next])
          }
        }
    }
    repeat(segments) { index ->
      if (slopes[index] == 0f) {
        tangent[index] = 0f
        tangent[(index + 1) % count] = 0f
      } else {
        val a = tangent[index] / slopes[index]
        val b = tangent[(index + 1) % count] / slopes[index]
        val square = a * a + b * b
        if (square > 9f) {
          val scale = 3f / kotlin.math.sqrt(square)
          tangent[index] = scale * a * slopes[index]
          tangent[(index + 1) % count] = scale * b * slopes[index]
        }
      }
    }
    return tangent
  }

  private class PathWords {
    val words = mutableListOf<RcFloatWord>()
    private var currentX = 0f
    private var currentY = 0f

    fun moveTo(x: Float, y: Float) {
      words += command(RcPathCommands.MOVE)
      words += RcFloatWord.literal(x)
      words += RcFloatWord.literal(y)
      currentX = x
      currentY = y
    }

    fun cubicTo(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) {
      words += command(RcPathCommands.CUBIC)
      words += RcFloatWord.literal(currentX)
      words += RcFloatWord.literal(currentY)
      listOf(x1, y1, x2, y2, x3, y3).forEach { words += RcFloatWord.literal(it) }
      currentX = x3
      currentY = y3
    }

    fun close() {
      words += command(RcPathCommands.CLOSE)
    }

    private fun command(id: Int): RcFloatWord = RcFloatWord(0xff800000.toInt() or id)
  }
}
