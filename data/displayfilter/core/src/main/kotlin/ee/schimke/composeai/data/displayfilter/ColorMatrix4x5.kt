package ee.schimke.composeai.data.displayfilter

/**
 * 4x5 RGBA color matrix in the same layout Android's `android.graphics.ColorMatrix` and Skia's
 * `SkColorMatrix` use. Stored row-major as 20 floats:
 * ```
 *   [ a  b  c  d  e ]   row 0 -> R'
 *   [ f  g  h  i  j ]   row 1 -> G'
 *   [ k  l  m  n  o ]   row 2 -> B'
 *   [ p  q  r  s  t ]   row 3 -> A'
 * ```
 *
 * Channels are 8-bit `[0, 255]`. Translation column (`e`, `j`, `o`, `t`) is in the same units, not
 * normalized — matches how Android matrices are expressed in published references for grayscale,
 * invert, and daltonizer.
 */
@JvmInline
value class ColorMatrix4x5(val values: FloatArray) {
  init {
    require(values.size == LENGTH) { "ColorMatrix4x5 requires $LENGTH floats; got ${values.size}." }
  }

  /** Apply this matrix to a single ARGB pixel and clamp each output channel to `[0, 255]`. */
  fun applyToArgb(argb: Int): Int {
    val a = (argb ushr 24) and 0xFF
    val r = (argb ushr 16) and 0xFF
    val g = (argb ushr 8) and 0xFF
    val b = argb and 0xFF
    val v = values
    val rr = clamp8(v[0] * r + v[1] * g + v[2] * b + v[3] * a + v[4])
    val gg = clamp8(v[5] * r + v[6] * g + v[7] * b + v[8] * a + v[9])
    val bb = clamp8(v[10] * r + v[11] * g + v[12] * b + v[13] * a + v[14])
    val aa = clamp8(v[15] * r + v[16] * g + v[17] * b + v[18] * a + v[19])
    return (aa shl 24) or (rr shl 16) or (gg shl 8) or bb
  }

  companion object {
    const val LENGTH: Int = 20

    /**
     * Build a 4x5 RGBA matrix from four 5-element row arrays. Keeps each row literal on its own
     * line — ktfmt would otherwise flatten a single 20-float `floatArrayOf(...)` call to one float
     * per line, which makes the matrix structure unreadable at the call site.
     */
    fun ofRows(
      red: FloatArray,
      green: FloatArray,
      blue: FloatArray,
      alpha: FloatArray,
    ): ColorMatrix4x5 {
      require(red.size == 5 && green.size == 5 && blue.size == 5 && alpha.size == 5) {
        "Each row must have 5 floats; got ${red.size},${green.size},${blue.size},${alpha.size}."
      }
      return ColorMatrix4x5(red + green + blue + alpha)
    }

    val Identity: ColorMatrix4x5 =
      ofRows(
        red = floatArrayOf(1f, 0f, 0f, 0f, 0f),
        green = floatArrayOf(0f, 1f, 0f, 0f, 0f),
        blue = floatArrayOf(0f, 0f, 1f, 0f, 0f),
        alpha = floatArrayOf(0f, 0f, 0f, 1f, 0f),
      )

    private fun clamp8(value: Float): Int {
      val rounded = value.toInt()
      return when {
        rounded < 0 -> 0
        rounded > 255 -> 255
        else -> rounded
      }
    }
  }
}
