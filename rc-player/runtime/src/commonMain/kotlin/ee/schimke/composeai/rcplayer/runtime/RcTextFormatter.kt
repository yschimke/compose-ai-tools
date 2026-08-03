package ee.schimke.composeai.rcplayer.runtime

import kotlin.math.floor
import kotlin.math.min

/** Pure KMP implementation of AndroidX alpha16 StringUtils formatting flags. */
public object RcTextFormatter {
  public fun format(input: Float, digitsBefore: Int, digitsAfter: Int, flags: Int): String {
    if (flags and FULL_FORMAT != 0) return input.toString()
    val post =
      when (flags and 3) {
        1 -> null
        3 -> '0'
        else -> ' '
      }
    val pre =
      when (flags and (3 shl 2)) {
        4 -> null
        12 -> '0'
        else -> ' '
      }
    if (flags and LEGACY_MODE != 0) {
      return legacyFormat(input, digitsBefore, digitsAfter, pre, post)
    }
    return modernFormat(
      input,
      digitsBefore,
      digitsAfter,
      pre,
      post,
      separator = (flags ushr 6) and 3,
      grouping = (flags ushr 4) and 3,
      options = (flags ushr 8) and 3,
    )
  }

  private fun legacyFormat(input: Float, before: Int, after: Int, pre: Char?, post: Char?): String {
    var value = input
    val negative = value < 0
    if (negative) value = -value
    var integer = value.toInt().toString()
    integer =
      when {
        integer.length < before && pre != null ->
          pre.toString().repeat(before - integer.length) + integer
        integer.length > before -> integer.takeLast(before)
        else -> integer
      }
    if (after == 0) return (if (negative) "-" else "") + integer
    var fraction = value % 1f
    repeat(after) { fraction *= 10f }
    fraction = javaRound(fraction).toFloat()
    repeat(after) { fraction *= .1f }
    var text = fraction.toString()
    text = text.substring(2, min(text.length, after + 2)).trimEnd('0')
    if (post != null && text.length < after) text += post.toString().repeat(after - text.length)
    return (if (negative) "-" else "") + integer + "." + text
  }

  private fun modernFormat(
    input: Float,
    before: Int,
    requestedAfter: Int,
    pre: Char?,
    post: Char?,
    separator: Int,
    grouping: Int,
    options: Int,
  ): String {
    val (groupSeparator, decimalSeparator) =
      when (separator) {
        1 -> '.' to ','
        2 -> ' ' to ','
        3 -> '_' to '.'
        else -> ',' to '.'
      }
    var value = input
    val negative = value < 0
    if (negative) value = -value
    val rounded = options and 2 != 0
    val raw = toChars(value, before, requestedAfter, rounded)
    var integer = raw.substringBefore('.')
    integer = group(integer, grouping, groupSeparator)
    val integerLength = integer.length
    integer =
      when {
        integerLength < before && pre != null ->
          pre.toString().repeat(before - integerLength) + integer
        integerLength > before -> integer.takeLast(before)
        else -> integer
      }
    val trimAfter =
      if (integerLength + requestedAfter > 9) maxOf(1, 9 - integerLength) else requestedAfter
    val after = if (post == null) trimAfter else requestedAfter
    val parentheses = options and 1 != 0
    if (after == 0) return sign(integer, negative, parentheses)
    var fraction = value % 1f
    repeat(trimAfter) { fraction *= 10f }
    fraction = javaRound(fraction).toFloat()
    repeat(trimAfter) { fraction *= .1f }
    var text = fraction.toString()
    text = text.substring(2, min(text.length, after + 2))
    while (text.length > 1 && text.last() == '0') text = text.dropLast(1)
    if (post != null && text.length < after) text += post.toString().repeat(after - text.length)
    return sign(integer + decimalSeparator + text, negative, parentheses)
  }

  private fun group(value: String, grouping: Int, separator: Char): String {
    if (grouping == 0) return value
    var result = value
    val step = if (grouping == 2) 4 else if (grouping == 3) 2 else 3
    var index = value.length - if (grouping == 2) 4 else 3
    while (index > 0) {
      result = result.substring(0, index) + separator + result.substring(index)
      index -= step
    }
    return result
  }

  private fun sign(value: String, negative: Boolean, parentheses: Boolean): String =
    when {
      !negative -> value
      parentheses -> "($value)"
      else -> "-$value"
    }

  private fun toChars(value: Float, before: Int, after: Int, rounding: Boolean): String {
    var adjusted = value
    var power = 1L
    repeat(after) { power *= 10 }
    if (rounding) {
      var factor = .5f
      repeat(after) { factor /= 10f }
      adjusted += factor
    }
    val integer = adjusted.toLong()
    val integerText = integer.toString().takeLast(min(before, integer.toString().length))
    var fractional = ((adjusted - integer) * power).toLong()
    var fractionLength = 0
    if (after > 0) {
      if (fractional == 0L) fractionLength = 1
      else {
        var trimmed = fractional
        while (trimmed > 0 && trimmed % 10L == 0L) trimmed /= 10L
        while (trimmed > 0) {
          trimmed /= 10L
          fractionLength++
        }
      }
    }
    val actualAfter = min(after, fractionLength)
    val chars = CharArray(actualAfter)
    for (index in actualAfter - 1 downTo 0) {
      chars[index] = ('0'.code + (fractional % 10L).toInt()).toChar()
      fractional /= 10L
    }
    return integerText + "." + chars.concatToString()
  }

  private fun javaRound(value: Float): Int = floor(value + .5f).toInt()

  private const val LEGACY_MODE = 1 shl 10
  private const val FULL_FORMAT = 1 shl 12
}
