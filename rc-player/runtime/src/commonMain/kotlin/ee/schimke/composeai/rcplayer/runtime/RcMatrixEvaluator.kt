package ee.schimke.composeai.rcplayer.runtime

import ee.schimke.composeai.rcplayer.protocol.RcFloatWord
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** Pure KMP evaluator matching AndroidX `MatrixOperations`' postfix instruction stream. */
public object RcMatrixEvaluator {
  public const val OPERATOR_OFFSET: Int = 0x320000
  public const val LAST_OPERATOR: Int = OPERATOR_OFFSET + 54

  public fun operator(code: Int): RcFloatWord {
    require(code in 1..54)
    return RcFloatWord((OPERATOR_OFFSET + code) or -0x800000)
  }

  public fun isOperator(word: RcFloatWord): Boolean {
    val payload = word.bits and 0x7fffff
    return word.isNaNEncoded && payload > OPERATOR_OFFSET && payload <= LAST_OPERATOR
  }

  public fun evaluate(expression: List<RcFloatWord>, resolve: (RcFloatWord) -> Float): FloatArray {
    val values = expression.map { if (isOperator(it)) it.value else resolve(it) }
    val matrices = Array(10) { identity() }
    var matrixIndex = 0
    expression.forEachIndexed { index, word ->
      if (!isOperator(word)) return@forEachIndexed
      when ((word.bits and 0x7fffff) - OPERATOR_OFFSET) {
        1 -> {
          check(matrixIndex + 1 < matrices.size) { "Matrix stack overflow" }
          matrices[++matrixIndex] = identity()
        }
        2 -> matrices[matrixIndex] = multiply(matrices[matrixIndex], rotationX(values[index - 1]))
        3 -> matrices[matrixIndex] = multiply(matrices[matrixIndex], rotationY(values[index - 1]))
        4 -> matrices[matrixIndex] = multiply(matrices[matrixIndex], rotationZ(values[index - 1]))
        5 ->
          matrices[matrixIndex] =
            multiply(matrices[matrixIndex], translation(values[index - 1], 0f, 0f))
        6 ->
          matrices[matrixIndex] =
            multiply(matrices[matrixIndex], translation(0f, values[index - 1], 0f))
        7 ->
          matrices[matrixIndex] =
            multiply(matrices[matrixIndex], translation(0f, 0f, values[index - 1]))
        8 ->
          matrices[matrixIndex] =
            multiply(matrices[matrixIndex], translation(values[index - 2], values[index - 1], 0f))
        9 ->
          matrices[matrixIndex] =
            multiply(
              matrices[matrixIndex],
              translation(values[index - 3], values[index - 2], values[index - 1]),
            )
        10 -> matrices[matrixIndex][0] *= values[index - 1]
        11 -> matrices[matrixIndex][5] *= values[index - 1]
        12 -> matrices[matrixIndex][10] *= values[index - 1]
        13 -> {
          matrices[matrixIndex][0] *= values[index - 2]
          matrices[matrixIndex][5] *= values[index - 1]
          matrices[matrixIndex][10] *= 0f
        }
        14 -> {
          matrices[matrixIndex][0] *= values[index - 3]
          matrices[matrixIndex][5] *= values[index - 2]
          matrices[matrixIndex][10] *= values[index - 1]
        }
        15 -> {
          check(matrixIndex > 0) { "Matrix stack underflow" }
          matrices[matrixIndex - 1] = multiply(matrices[matrixIndex - 1], matrices[matrixIndex])
          matrixIndex--
        }
        16 ->
          matrices[matrixIndex] =
            multiply(
              pivotRotationZ(values[index - 2], values[index - 1], values[index - 3]),
              matrices[matrixIndex],
            )
        17 ->
          matrices[matrixIndex] =
            multiply(
              axisRotation(
                values[index - 3],
                values[index - 2],
                values[index - 1],
                values[index - 4],
              ),
              matrices[matrixIndex],
            )
        18 ->
          matrices[matrixIndex] =
            multiply(
              matrices[matrixIndex],
              projection(values[index - 4], values[index - 3], values[index - 2], values[index - 1]),
            )
      }
    }
    return matrices[0]
  }

  private fun identity(): FloatArray =
    FloatArray(16).also { values ->
      values[0] = 1f
      values[5] = 1f
      values[10] = 1f
      values[15] = 1f
    }

  private fun multiply(a: FloatArray, b: FloatArray): FloatArray =
    FloatArray(16).also { out ->
      for (row in 0..3) {
        for (column in 0..3) {
          var sum = 0f
          for (inner in 0..3) sum += a[row * 4 + inner] * b[inner * 4 + column]
          out[row * 4 + column] = sum
        }
      }
    }

  private fun rotationX(degrees: Float): FloatArray =
    identity().also {
      val radians = degrees * PI.toFloat() / 180f
      val cosine = cos(radians)
      val sine = sin(radians)
      it[5] = cosine
      it[6] = -sine
      it[9] = sine
      it[10] = cosine
    }

  private fun rotationY(degrees: Float): FloatArray =
    identity().also {
      val radians = degrees * PI.toFloat() / 180f
      val cosine = cos(radians)
      val sine = sin(radians)
      it[0] = cosine
      it[2] = sine
      it[8] = -sine
      it[10] = cosine
    }

  private fun rotationZ(degrees: Float): FloatArray =
    identity().also {
      val radians = degrees * PI.toFloat() / 180f
      val cosine = cos(radians)
      val sine = sin(radians)
      it[0] = cosine
      it[1] = -sine
      it[4] = sine
      it[5] = cosine
    }

  private fun translation(x: Float, y: Float, z: Float): FloatArray =
    identity().also {
      it[3] = x
      it[7] = y
      it[11] = z
    }

  private fun pivotRotationZ(pivotX: Float, pivotY: Float, degrees: Float): FloatArray =
    rotationZ(degrees).also {
      val cosine = it[0]
      val sine = it[4]
      it[3] = pivotX * (1f - cosine) + pivotY * sine
      it[7] = pivotY * (1f - cosine) - pivotX * sine
    }

  private fun axisRotation(x: Float, y: Float, z: Float, degrees: Float): FloatArray {
    val lengthSquared = x * x + y * y + z * z
    if (lengthSquared == 0f) return identity()
    val length = sqrt(lengthSquared)
    val ux = x / length
    val uy = y / length
    val uz = z / length
    val radians = degrees * PI.toFloat() / 180f
    val cosine = cos(radians)
    val sine = sin(radians)
    val oneMinusCosine = 1f - cosine
    return identity().also {
      it[0] = cosine + ux * ux * oneMinusCosine
      it[1] = ux * uy * oneMinusCosine - uz * sine
      it[2] = ux * uz * oneMinusCosine + uy * sine
      it[4] = uy * ux * oneMinusCosine + uz * sine
      it[5] = cosine + uy * uy * oneMinusCosine
      it[6] = uy * uz * oneMinusCosine - ux * sine
      it[8] = uz * ux * oneMinusCosine - uy * sine
      it[9] = uz * uy * oneMinusCosine + ux * sine
      it[10] = cosine + uz * uz * oneMinusCosine
    }
  }

  private fun projection(
    fovDegrees: Float,
    aspectRatio: Float,
    near: Float,
    far: Float,
  ): FloatArray {
    val f = 1f / tan(fovDegrees * PI.toFloat() / 360f)
    val rangeInverse = 1f / (near - far)
    return FloatArray(16).also {
      it[0] = f / aspectRatio
      it[5] = f
      it[10] = (far + near) * rangeInverse
      it[11] = -1f
      it[14] = 2f * far * near * rangeInverse
    }
  }
}
