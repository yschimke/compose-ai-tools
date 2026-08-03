package ee.schimke.composeai.rcplayer.runtime

import ee.schimke.composeai.rcplayer.protocol.RcFloatWord
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin

/** Decoder and evaluator for AndroidX's packed `FloatAnimation` description. */
internal class RcFloatAnimation(description: List<RcFloatWord>) {
  val duration: Float = description.firstOrNull()?.value ?: 1f
  private val bits = description.getOrNull(1)?.bits ?: TYPE_STANDARD
  private val type = bits and 0xff
  private val hasWrap = (bits ushr 8) and 1 != 0
  private val hasInitial = (bits ushr 8) and 2 != 0
  private val directional = (bits ushr 10) and 3
  private val parameterCount = bits ushr 16 and 0xffff
  private val parameters = description.drop(2).take(parameterCount).map(RcFloatWord::value)
  private val easing: (Float) -> Float = easing(type, parameters)
  private val tail = 2 + parameterCount
  private val wrap: Float =
    if (hasWrap) description[tail + if (hasInitial) 1 else 0].value else Float.NaN

  var initialValue: Float = if (hasInitial) description[tail].value else Float.NaN
    private set

  var targetValue: Float = Float.NaN
    private set

  fun setInitial(value: Float) {
    initialValue = if (wrap.isNaN()) value else value % wrap
  }

  fun setTarget(value: Float) {
    targetValue = value
    if (!wrap.isNaN()) {
      initialValue = positiveWrap(wrap, initialValue)
      targetValue = positiveWrap(wrap, targetValue)
      if (initialValue.isNaN()) initialValue = targetValue
      val distance = wrapDistance(wrap, initialValue, targetValue)
      if (distance > 0f && targetValue < initialValue) {
        targetValue += wrap
      } else if (distance < 0f && directional != 0) {
        if (directional == 1 && targetValue > initialValue) initialValue = targetValue
        if (directional == 2 && targetValue < initialValue) initialValue = targetValue
        targetValue -= wrap
      }
    }
  }

  fun value(elapsedSeconds: Float): Float {
    if (directional == 1 && targetValue < initialValue) {
      initialValue = targetValue
      return targetValue
    }
    if (directional == 2 && targetValue > initialValue) {
      initialValue = targetValue
      return targetValue
    }
    return easing(elapsedSeconds / duration) * (targetValue - initialValue) + initialValue
  }

  private fun easing(type: Int, parameters: List<Float>): (Float) -> Float =
    when (type) {
      TYPE_STANDARD -> cubic(.4f, 0f, .2f, 1f)
      TYPE_ACCELERATE -> cubic(.4f, .05f, .8f, .7f)
      TYPE_DECELERATE -> cubic(0f, 0f, .2f, .95f)
      TYPE_LINEAR -> cubic(1f, 1f, 0f, 0f)
      TYPE_ANTICIPATE -> cubic(.36f, 0f, .66f, -.56f)
      TYPE_OVERSHOOT -> cubic(.34f, 1.56f, .64f, 1f)
      TYPE_CUSTOM -> cubic(parameters[0], parameters[1], parameters[2], parameters[3])
      TYPE_SPLINE -> stepCurve(parameters)
      TYPE_BOUNCE -> ::bounce
      TYPE_ELASTIC -> ::elastic
      else -> error("Unknown AndroidX easing type $type")
    }

  private fun cubic(x1: Float, y1: Float, x2: Float, y2: Float): (Float) -> Float = { x ->
    when {
      x <= 0f -> 0f
      x >= 1f -> 1f
      else -> {
        fun coordinate(t: Float, first: Float, second: Float): Float {
          val oneMinus = 1f - t
          return first * 3f * oneMinus * oneMinus * t + second * 3f * oneMinus * t * t + t * t * t
        }
        var t = .5f
        var range = .5f
        while (range > .01f) {
          val tx = coordinate(t, x1, x2)
          range *= .5f
          if (tx < x) t += range else t -= range
        }
        val lowerX = coordinate(t - range, x1, x2)
        val upperX = coordinate(t + range, x1, x2)
        val lowerY = coordinate(t - range, y1, y2)
        val upperY = coordinate(t + range, y1, y2)
        (upperY - lowerY) * (x - lowerX) / (upperX - lowerX) + lowerY
      }
    }
  }

  private fun bounce(x: Float): Float {
    val n = 7.5625f
    val d = 2.75f
    var t = x
    return when {
      t < 0f -> 0f
      t < 1f / d -> 1f / (1f + 1f / d) * (n * t * t + t)
      t < 2f / d -> {
        t -= 1.5f / d
        n * t * t + .75f
      }
      t < 2.5f / d -> {
        t -= 2.25f / d
        n * t * t + .9375f
      }
      t <= 1f -> {
        t -= 2.625f / d
        n * t * t + .984375f
      }
      else -> 1f
    }
  }

  private fun elastic(x: Float): Float =
    when {
      x <= 0f -> 0f
      x >= 1f -> 1f
      else -> 2f.pow(-10f * x) * sin((x * 10f - .75f) * (2f * PI.toFloat() / 3f)) + 1f
    }

  private fun stepCurve(values: List<Float>): (Float) -> Float {
    require(values.size >= 2) { "AndroidX spline easing requires at least two values" }
    val sourceCount = values.size
    val pointCount = sourceCount * 3 - 2
    val offset = sourceCount - 1
    val gap = 1.0 / offset
    val times = DoubleArray(pointCount)
    val points = DoubleArray(pointCount)
    values.forEachIndexed { index, value ->
      points[index + offset] = value.toDouble()
      times[index + offset] = index * gap
      if (index > 0) {
        points[index + offset * 2] = value + 1.0
        times[index + offset * 2] = index * gap + 1.0
        points[index - 1] = value - 1.0 - gap
        times[index - 1] = index * gap - 1.0 - gap
      }
    }
    val curve = MonotonicCurve(times, points)
    return { x ->
      when {
        x < 0f -> 0f
        x > 1f -> 1f
        else -> curve.value(x.toDouble()).toFloat()
      }
    }
  }

  private class MonotonicCurve(private val time: DoubleArray, private val values: DoubleArray) {
    private val tangent = DoubleArray(values.size)

    init {
      val slope = DoubleArray(values.size - 1)
      slope.indices.forEach { index ->
        slope[index] = (values[index + 1] - values[index]) / (time[index + 1] - time[index])
        tangent[index] = if (index == 0) slope[index] else (slope[index - 1] + slope[index]) * .5
      }
      tangent[tangent.lastIndex] = slope.last()
      slope.indices.forEach { index ->
        if (slope[index] == 0.0) {
          tangent[index] = 0.0
          tangent[index + 1] = 0.0
        } else {
          val a = tangent[index] / slope[index]
          val b = tangent[index + 1] / slope[index]
          val h = hypot(a, b)
          if (h > 9.0) {
            val scale = 3.0 / h
            tangent[index] = scale * a * slope[index]
            tangent[index + 1] = scale * b * slope[index]
          }
        }
      }
    }

    fun value(position: Double): Double {
      if (position <= time[0]) return values[0] + (position - time[0]) * tangent[0]
      if (position >= time.last()) return values.last() + (position - time.last()) * tangent.last()
      val index = (0 until time.lastIndex).first { position < time[it + 1] }
      val h = time[index + 1] - time[index]
      val x = (position - time[index]) / h
      val x2 = x * x
      val x3 = x2 * x
      return -2 * x3 * values[index + 1] + 3 * x2 * values[index + 1] + 2 * x3 * values[index] -
        3 * x2 * values[index] +
        values[index] +
        h * tangent[index + 1] * x3 +
        h * tangent[index] * x3 - h * tangent[index + 1] * x2 - 2 * h * tangent[index] * x2 +
        h * tangent[index] * x
    }
  }

  private fun positiveWrap(wrap: Float, value: Float): Float {
    var result = value % wrap
    if (result < 0f) result += wrap
    return result
  }

  private fun wrapDistance(wrap: Float, from: Float, to: Float): Float {
    var delta = (to - from) % 360f
    if (delta < -wrap / 2f) delta += wrap else if (delta > wrap / 2f) delta -= wrap
    return delta
  }

  private companion object {
    const val TYPE_STANDARD = 1
    const val TYPE_ACCELERATE = 2
    const val TYPE_DECELERATE = 3
    const val TYPE_LINEAR = 4
    const val TYPE_ANTICIPATE = 5
    const val TYPE_OVERSHOOT = 6
    const val TYPE_CUSTOM = 11
    const val TYPE_SPLINE = 12
    const val TYPE_BOUNCE = 13
    const val TYPE_ELASTIC = 14
  }
}

/** AndroidX spring integrator used by the alternate five-word animation format. */
internal class RcSpringAnimation(description: List<RcFloatWord>) {
  private val stiffness = description[1].value.toDouble()
  private val damping = description[2].value.toDouble()
  private val stopThreshold = description[3].value
  private val boundaryMode = description[4].bits
  private val mass = 1f
  private var target = 0.0
  private var lastTime = 0f
  private var position = 0f
  private var velocity = 0f

  init {
    require(description[0].value == 0f) { "Spring animation parameter[0] must be 0" }
    require(stiffness.isFinite() && stiffness > 0) { "stiffness must be finite and positive" }
    require(stopThreshold.isFinite() && stopThreshold > 0) {
      "stopThreshold must be finite and positive"
    }
  }

  fun setTarget(value: Float) {
    target = value.toDouble()
  }

  fun target(): Float = target.toFloat()

  fun value(time: Float): Float {
    compute((time - lastTime).toDouble())
    lastTime = time
    if (isStopped()) position = target.toFloat()
    return position
  }

  private fun isStopped(): Boolean {
    val displacement = position - target
    val energy = velocity * velocity * mass + stiffness * displacement * displacement
    return kotlin.math.sqrt(energy / stiffness) <= stopThreshold
  }

  private fun compute(deltaSeconds: Double) {
    if (deltaSeconds <= 0) return
    var steps = (1 + 9 / (kotlin.math.sqrt(stiffness / mass) * deltaSeconds * 4)).toInt()
    steps = steps.coerceAtMost(1000)
    val dt = deltaSeconds / steps
    repeat(steps) {
      val displacement = position - target
      var acceleration = (-stiffness * displacement - damping * velocity) / mass
      var averageVelocity = velocity + acceleration * dt / 2
      val averageDisplacement = position + dt * averageVelocity / 2 - target
      acceleration = (-averageDisplacement * stiffness - averageVelocity * damping) / mass
      val velocityDelta = acceleration * dt
      averageVelocity = velocity + velocityDelta / 2
      velocity += velocityDelta.toFloat()
      position += (averageVelocity * dt).toFloat()
      if (boundaryMode and 1 != 0 && position < 0f) {
        position = -position
        velocity = -velocity
      }
      if (boundaryMode and 2 != 0 && position > 1f) {
        position = 2f - position
        velocity = -velocity
      }
    }
  }
}
