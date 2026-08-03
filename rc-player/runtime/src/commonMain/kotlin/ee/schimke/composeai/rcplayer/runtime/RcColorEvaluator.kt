package ee.schimke.composeai.rcplayer.runtime

import kotlin.math.pow

/** Pure AndroidX alpha16 color math, kept out of the Compose/Skiko renderer. */
internal object RcColorEvaluator {
  fun interpolate(first: Int, second: Int, tween: Float): Int {
    if (tween.isNaN() || tween == 0f) return first
    if (tween == 1f) return second

    val firstRed = gamma((first shr 16) and 0xff)
    val firstGreen = gamma((first shr 8) and 0xff)
    val firstBlue = gamma(first and 0xff)
    val firstAlpha = ((first shr 24) and 0xff) / 255f
    val secondRed = gamma((second shr 16) and 0xff)
    val secondGreen = gamma((second shr 8) and 0xff)
    val secondBlue = gamma(second and 0xff)
    val secondAlpha = ((second shr 24) and 0xff) / 255f

    val red = encodeGamma(firstRed + tween * (secondRed - firstRed))
    val green = encodeGamma(firstGreen + tween * (secondGreen - firstGreen))
    val blue = encodeGamma(firstBlue + tween * (secondBlue - firstBlue))
    val alpha = clamp(((firstAlpha + tween * (secondAlpha - firstAlpha)) * 255f).toInt())
    return (alpha shl 24) or (red shl 16) or (green shl 8) or blue
  }

  fun hsv(alpha: Int, hue: Float, saturation: Float, value: Float): Int =
    (alpha shl 24) or (hsvToRgb(hue, saturation, value) and 0x00ffffff)

  fun argb(alpha: Float, red: Float, green: Float, blue: Float): Int =
    (((alpha * 255f + .5f).toInt()) shl 24) or
      (((red * 255f + .5f).toInt()) shl 16) or
      (((green * 255f + .5f).toInt()) shl 8) or
      ((blue * 255f + .5f).toInt())

  private fun hsvToRgb(hue: Float, saturation: Float, value: Float): Int {
    val section = (hue * 6f).toInt()
    val fraction = hue * 6f - section
    val p = (.5f + 255f * value * (1f - saturation)).toInt()
    val q = (.5f + 255f * value * (1f - fraction * saturation)).toInt()
    val t = (.5f + 255f * value * (1f - (1f - fraction) * saturation)).toInt()
    val v = (.5f + 255f * value).toInt()
    return when (section) {
      0 -> (v shl 16) or (t shl 8) or p
      1 -> (q shl 16) or (v shl 8) or p
      2 -> (p shl 16) or (v shl 8) or t
      3 -> (p shl 16) or (q shl 8) or v
      4 -> (t shl 16) or (p shl 8) or v
      5 -> (v shl 16) or (p shl 8) or q
      else -> 0
    }
  }

  private fun gamma(channel: Int): Float = (channel / 255f).toDouble().pow(2.2).toFloat()

  private fun encodeGamma(channel: Float): Int =
    clamp(channel.toDouble().pow(1.0 / 2.2).toFloat().times(255f).toInt())

  private fun clamp(value: Int): Int = value.coerceIn(0, 255)
}
