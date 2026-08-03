package ee.schimke.composeai.rcplayer.runtime

import ee.schimke.composeai.rcplayer.protocol.RcFloatWord
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** Platform-neutral VM for AndroidX's NaN-boxed, reverse-Polish float expression language. */
public class RcFloatExpressionEvaluator(private val arrays: (Int) -> FloatArray? = { null }) {
  private val registers = FloatArray(4)

  public fun evaluate(
    expression: List<RcFloatWord>,
    variables: FloatArray = FloatArray(0),
    resolve: (RcFloatWord) -> Float = { it.value },
  ): Float {
    val stack = FloatArray(128)
    var sp = -1
    expression.forEach { word ->
      val id = word.bits and NAN_PAYLOAD_MASK
      when {
        !word.isNaNEncoded -> stack[++sp] = word.value
        id and ID_REGION_MASK == ID_REGION_ARRAY -> stack[++sp] = word.value
        id in (OFFSET + 1)..LAST_OP -> sp = evaluateOperator(stack, sp, id, variables)
        else -> stack[++sp] = resolve(word)
      }
    }
    return stack[sp]
  }

  private fun evaluateOperator(
    stack: FloatArray,
    initialSp: Int,
    id: Int,
    variables: FloatArray,
  ): Int {
    var sp = initialSp
    fun arrayId(index: Int): Int = stack[index].toRawBits() and NAN_PAYLOAD_MASK
    fun arrayAt(index: Int): FloatArray = arrays(arrayId(index)) ?: FloatArray(0)

    when (id - OFFSET) {
      1 -> {
        stack[sp - 1] += stack[sp]
        sp--
      }
      2 -> {
        stack[sp - 1] -= stack[sp]
        sp--
      }
      3 -> {
        stack[sp - 1] *= stack[sp]
        sp--
      }
      4 -> {
        stack[sp - 1] /= stack[sp]
        sp--
      }
      5 -> {
        stack[sp - 1] %= stack[sp]
        sp--
      }
      6 -> {
        stack[sp - 1] = min(stack[sp - 1], stack[sp])
        sp--
      }
      7 -> {
        stack[sp - 1] = max(stack[sp - 1], stack[sp])
        sp--
      }
      8 -> {
        stack[sp - 1] = stack[sp - 1].pow(stack[sp])
        sp--
      }
      9 -> stack[sp] = sqrt(stack[sp])
      10 -> stack[sp] = abs(stack[sp])
      11 -> stack[sp] = sign(stack[sp])
      12 -> {
        val magnitude = abs(stack[sp - 1])
        stack[sp - 1] = if (stack[sp].toRawBits() < 0) -magnitude else magnitude
        sp--
      }
      13 -> stack[sp] = exp(stack[sp])
      14 -> stack[sp] = floor(stack[sp])
      15 -> stack[sp] = log10(stack[sp])
      16 -> stack[sp] = ln(stack[sp])
      17 -> stack[sp] = javaRound(stack[sp]).toFloat()
      18 -> stack[sp] = sin(stack[sp])
      19 -> stack[sp] = cos(stack[sp])
      20 -> stack[sp] = tan(stack[sp])
      21 -> stack[sp] = asin(stack[sp])
      22 -> stack[sp] = acos(stack[sp])
      23 -> stack[sp] = atan(stack[sp])
      24 -> {
        stack[sp - 1] = atan2(stack[sp - 1], stack[sp])
        sp--
      }
      25 -> {
        stack[sp - 2] = stack[sp] + stack[sp - 1] * stack[sp - 2]
        sp -= 2
      }
      26 -> {
        stack[sp - 2] = if (stack[sp] > 0f) stack[sp - 1] else stack[sp - 2]
        sp -= 2
      }
      27 -> {
        stack[sp - 2] = min(max(stack[sp - 2], stack[sp]), stack[sp - 1])
        sp -= 2
      }
      28 -> stack[sp] = stack[sp].pow(1f / 3f)
      29 -> stack[sp] *= 57.29578f
      30 -> stack[sp] *= 0.017453292f
      31 -> stack[sp] = ceil(stack[sp])
      32 -> {
        stack[sp - 1] = arrays(arrayId(sp - 1))?.get(stack[sp].toInt()) ?: 0f
        sp--
      }
      33 -> stack[sp] = arrayAt(sp).maxOrNull() ?: 0f
      34 -> stack[sp] = arrayAt(sp).minOrNull() ?: 0f
      35 -> stack[sp] = arrayAt(sp).sum()
      36 -> arrayAt(sp).let { stack[sp] = if (it.isEmpty()) 0f else it.sum() / it.size }
      37 -> stack[sp] = arrayAt(sp).size.toFloat()
      38 -> {
        stack[sp - 1] = monotonicSpline(arrayAt(sp - 1), stack[sp])
        sp--
      }
      39 -> stack[++sp] = sharedRandom.nextFloat()
      40 -> {
        val seed = stack[sp]
        sharedRandom =
          if (seed == 0f) JavaRandom(UNSEEDED_FALLBACK) else JavaRandom(seed.toRawBits().toLong())
        sp--
      }
      41 -> {
        var x = stack[sp].toRawBits()
        x = (x shl 13) xor x
        val scrambled = (x * (x * x * 15731 + 789221) + 1376312589) and 0x7fffffff
        stack[sp] = 1f - scrambled / 1.0737418E9f
      }
      42 -> stack[sp] = sharedRandom.nextFloat() * (stack[sp] - stack[sp - 1]) + stack[sp - 1]
      43 -> {
        stack[sp - 1] = stack[sp - 1] * stack[sp - 1] + stack[sp] * stack[sp]
        sp--
      }
      44 -> {
        stack[sp - 1] = if (stack[sp - 1] > stack[sp]) 1f else 0f
        sp--
      }
      45 -> stack[sp] *= stack[sp]
      46 -> {
        stack[sp + 1] = stack[sp]
        sp++
      }
      47 -> {
        stack[sp - 1] = hypot(stack[sp - 1], stack[sp])
        sp--
      }
      48 -> {
        val value = stack[sp - 1]
        stack[sp - 1] = stack[sp]
        stack[sp] = value
      }
      49 -> {
        stack[sp - 2] += (stack[sp - 1] - stack[sp - 2]) * stack[sp]
        sp -= 2
      }
      50 -> {
        val value = stack[sp - 2]
        val maximum = stack[sp - 1]
        val minimum = stack[sp]
        stack[sp - 2] =
          when {
            value < minimum -> 0f
            value > maximum -> 1f
            else -> ((value - minimum) / (maximum - minimum)).let { it * it * (3f - 2f * it) }
          }
        sp -= 2
      }
      51 -> stack[sp] = ln(stack[sp]) / ln(2f)
      52 -> stack[sp] = 1f / stack[sp]
      53 -> stack[sp] -= stack[sp].toInt()
      54 -> {
        val doubled = stack[sp] * 2f
        val value = stack[sp - 1] % doubled
        stack[sp - 1] = if (value < stack[sp]) value else doubled - value
        sp--
      }
      55 -> Unit
      in 56..59 -> {
        registers[id - OFFSET - 56] = stack[sp]
        sp--
      }
      in 60..63 -> stack[++sp] = registers[id - OFFSET - 60]
      in 64..69 -> Unit // AndroidX reserves CMD1..CMD4 and the two following slots.
      in 70..72 -> stack[++sp] = variables[id - OFFSET - 70]
      73 -> stack[sp] = -stack[sp]
      74 -> {
        stack[sp - 4] =
          cubicEasing(stack[sp - 4], stack[sp - 3], stack[sp - 2], stack[sp - 1], stack[sp])
        sp -= 4
      }
      75 -> {
        var position = stack[sp] - stack[sp].toInt()
        if (position < 0f) position += 1f
        stack[sp - 1] = monotonicSpline(arrayAt(sp - 1), position)
        sp--
      }
      76 -> {
        val last = stack[sp].toInt()
        require(last <= 10_000) { "Too many iterations in A_SUM_TILL" }
        val values = arrayAt(sp - 1)
        var total = 0f
        for (index in 0..last) total += values[index]
        stack[sp - 1] = total
        sp--
      }
      77 -> {
        val x = arrayAt(sp - 1)
        val y = arrayAt(sp)
        var total = 0f
        x.indices.forEach { total += x[it] * y[it] }
        stack[sp - 1] = total
        sp--
      }
      78 -> stack[sp] = arrayAt(sp).sumOfSquares()
      79 -> {
        val values = arrayAt(sp - 1)
        val position = stack[sp] * (values.size - 1)
        val index = position.toInt()
        stack[sp - 1] =
          when {
            index < 0 -> values[0]
            index >= values.lastIndex -> values.last()
            else -> values[index] + (position - index) * (values[index + 1] - values[index])
          }
        sp--
      }
    }
    return sp
  }

  private fun monotonicSpline(values: FloatArray, position: Float): Float {
    if (values.isEmpty()) return position
    if (values.size == 1) return values[0]
    val n = values.size
    val slopes = FloatArray(n - 1) { (values[it + 1] - values[it]) * (n - 1) }
    val tangents = FloatArray(n)
    slopes.forEachIndexed { index, slope ->
      tangents[index] = if (index == 0) slope else (slopes[index - 1] + slope) * 0.5f
    }
    tangents[n - 1] = slopes[n - 2]
    slopes.indices.forEach { index ->
      if (slopes[index] == 0f) {
        tangents[index] = 0f
        tangents[index + 1] = 0f
      } else {
        val a = tangents[index] / slopes[index]
        val b = tangents[index + 1] / slopes[index]
        val h = hypot(a, b)
        if (h > 9f) {
          val scale = 3f / h
          tangents[index] = scale * a * slopes[index]
          tangents[index + 1] = scale * b * slopes[index]
        }
      }
    }
    val step = 1f / (n - 1)
    val clampedPosition = position.coerceIn(0f, 1f)
    val segment = min((clampedPosition / step).toInt(), n - 2)
    val x = (clampedPosition - segment * step) / step
    val x2 = x * x
    val x3 = x2 * x
    val interpolated =
      -2 * x3 * values[segment + 1] + 3 * x2 * values[segment + 1] + 2 * x3 * values[segment] -
        3 * x2 * values[segment] +
        values[segment] +
        step * tangents[segment + 1] * x3 +
        step * tangents[segment] * x3 -
        step * tangents[segment + 1] * x2 -
        2 * step * tangents[segment] * x2 + step * tangents[segment] * x
    if (position in 0f..1f) return interpolated
    val slope = if (position < 0f) tangents[0] else tangents[n - 1]
    val edgePosition = if (position < 0f) 0f else 1f
    val edgeValue = if (position < 0f) values[0] else values[n - 1]
    return edgeValue + (position - edgePosition) * slope
  }

  private fun cubicEasing(x1: Float, y1: Float, x2: Float, y2: Float, x: Float): Float {
    if (x <= 0f) return 0f
    if (x >= 1f) return 1f
    fun coordinate(t: Float, first: Float, second: Float): Float {
      val oneMinus = 1f - t
      return first * 3f * oneMinus * oneMinus * t + second * 3f * oneMinus * t * t + t * t * t
    }
    var t = 0.5f
    var range = 0.5f
    while (range > 0.01f) {
      val tx = coordinate(t, x1, x2)
      range *= 0.5f
      if (tx < x) t += range else t -= range
    }
    val lowerX = coordinate(t - range, x1, x2)
    val upperX = coordinate(t + range, x1, x2)
    val lowerY = coordinate(t - range, y1, y2)
    val upperY = coordinate(t + range, y1, y2)
    return (upperY - lowerY) * (x - lowerX) / (upperX - lowerX) + lowerY
  }

  private fun javaRound(value: Float): Int =
    when {
      value.isNaN() -> 0
      value >= Int.MAX_VALUE -> Int.MAX_VALUE
      value <= Int.MIN_VALUE -> Int.MIN_VALUE
      else -> floor(value + 0.5f).toInt()
    }

  private fun FloatArray.sumOfSquares(): Float {
    var total = 0f
    forEach { total += it * it }
    return total
  }

  private class JavaRandom(seed: Long) {
    private var state: Long = (seed xor MULTIPLIER) and MASK

    fun nextFloat(): Float = next(24) / (1 shl 24).toFloat()

    private fun next(bits: Int): Int {
      state = (state * MULTIPLIER + ADDEND) and MASK
      return (state ushr (48 - bits)).toInt()
    }
  }

  public companion object {
    public const val OFFSET: Int = 0x310000
    public const val LAST_OP: Int = OFFSET + 79
    public const val VAR1: Int = OFFSET + 70
    private const val NAN_PAYLOAD_MASK: Int = 0x7fffff
    private const val ID_REGION_MASK: Int = 0x700000
    private const val ID_REGION_ARRAY: Int = 0x200000
    private const val MULTIPLIER: Long = 0x5DEECE66DL
    private const val ADDEND: Long = 0xBL
    private const val MASK: Long = (1L shl 48) - 1
    private const val UNSEEDED_FALLBACK: Long = 0x5243504c41594552L
    private var sharedRandom: JavaRandom = JavaRandom(UNSEEDED_FALLBACK)

    public fun operatorWord(operator: Int): RcFloatWord =
      RcFloatWord(0xff800000.toInt() or operator)
  }
}
