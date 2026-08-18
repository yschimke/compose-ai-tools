package ee.schimke.composeai.rcplayer.compose

/**
 * A minimal JSON reader, scoped to the font manifest.
 *
 * **Why not `kotlinx.serialization`.** `:rc-player-compose` is published, and the manifest is one
 * fixed-shape object read once per host. Adding a serialization runtime to every consumer's
 * classpath — and the compiler plugin to this module's build — to read
 * `{"families":[{"name":…,"fonts":[…]}]}` is a poor trade for a library whose selling point is that
 * it carries the player and little else. The rest of this stack already hand-decodes its wire
 * format for the same reason.
 *
 * It is a *complete* reader for the JSON value grammar rather than a pattern-matcher — escapes
 * included — because the input is a file on someone's server and half-parsing it silently would
 * surface as a missing font rather than as an error. Anything malformed throws [RcJsonException];
 * [rcParseJson] is the entry point.
 */
internal sealed interface RcJsonValue {
  data class Obj(val entries: Map<String, RcJsonValue>) : RcJsonValue

  data class Arr(val items: List<RcJsonValue>) : RcJsonValue

  data class Str(val value: String) : RcJsonValue

  data class Num(val value: Double) : RcJsonValue

  data class Bool(val value: Boolean) : RcJsonValue

  data object Null : RcJsonValue
}

internal class RcJsonException(message: String) : IllegalArgumentException(message)

internal fun rcParseJson(text: String): RcJsonValue {
  val reader = RcJsonReader(text)
  val value = reader.readValue()
  reader.skipWhitespace()
  if (!reader.atEnd) throw RcJsonException("Trailing content at ${reader.index}")
  return value
}

/** The string at [key], or null when absent or not a string. */
internal fun RcJsonValue.string(key: String): String? =
  ((this as? RcJsonValue.Obj)?.entries?.get(key) as? RcJsonValue.Str)?.value

/** The number at [key] as an `Int`, or null when absent or not a number. */
internal fun RcJsonValue.int(key: String): Int? =
  ((this as? RcJsonValue.Obj)?.entries?.get(key) as? RcJsonValue.Num)?.value?.toInt()

/** The array at [key], or empty when absent or not an array. */
internal fun RcJsonValue.array(key: String): List<RcJsonValue> =
  ((this as? RcJsonValue.Obj)?.entries?.get(key) as? RcJsonValue.Arr)?.items.orEmpty()

private class RcJsonReader(private val text: String) {
  var index: Int = 0
    private set

  val atEnd: Boolean
    get() = index >= text.length

  fun skipWhitespace() {
    while (index < text.length && text[index].isJsonWhitespace()) index++
  }

  fun readValue(): RcJsonValue {
    skipWhitespace()
    if (atEnd) throw RcJsonException("Unexpected end of input")
    return when (val character = text[index]) {
      '{' -> readObject()
      '[' -> readArray()
      '"' -> RcJsonValue.Str(readString())
      't' -> readLiteral("true", RcJsonValue.Bool(true))
      'f' -> readLiteral("false", RcJsonValue.Bool(false))
      'n' -> readLiteral("null", RcJsonValue.Null)
      else ->
        if (character == '-' || character in '0'..'9') readNumber()
        else throw RcJsonException("Unexpected '$character' at $index")
    }
  }

  private fun readObject(): RcJsonValue.Obj {
    index++ // '{'
    val entries = LinkedHashMap<String, RcJsonValue>()
    skipWhitespace()
    if (!atEnd && text[index] == '}') {
      index++
      return RcJsonValue.Obj(entries)
    }
    while (true) {
      skipWhitespace()
      val key = readString()
      skipWhitespace()
      expect(':')
      entries[key] = readValue()
      skipWhitespace()
      when {
        atEnd -> throw RcJsonException("Unterminated object")
        text[index] == ',' -> index++
        text[index] == '}' -> {
          index++
          return RcJsonValue.Obj(entries)
        }
        else -> throw RcJsonException("Expected ',' or '}' at $index")
      }
    }
  }

  private fun readArray(): RcJsonValue.Arr {
    index++ // '['
    val items = mutableListOf<RcJsonValue>()
    skipWhitespace()
    if (!atEnd && text[index] == ']') {
      index++
      return RcJsonValue.Arr(items)
    }
    while (true) {
      items += readValue()
      skipWhitespace()
      when {
        atEnd -> throw RcJsonException("Unterminated array")
        text[index] == ',' -> index++
        text[index] == ']' -> {
          index++
          return RcJsonValue.Arr(items)
        }
        else -> throw RcJsonException("Expected ',' or ']' at $index")
      }
    }
  }

  private fun readString(): String {
    expect('"')
    val builder = StringBuilder()
    while (true) {
      if (atEnd) throw RcJsonException("Unterminated string")
      when (val character = text[index++]) {
        '"' -> return builder.toString()
        '\\' -> builder.append(readEscape())
        else -> builder.append(character)
      }
    }
  }

  private fun readEscape(): Char {
    if (atEnd) throw RcJsonException("Unterminated escape")
    return when (val escape = text[index++]) {
      '"' -> '"'
      '\\' -> '\\'
      '/' -> '/'
      'b' -> '\b'
      'f' -> ''
      'n' -> '\n'
      'r' -> '\r'
      't' -> '\t'
      'u' -> {
        if (index + 4 > text.length) throw RcJsonException("Truncated unicode escape")
        val code =
          text.substring(index, index + 4).toIntOrNull(16)
            ?: throw RcJsonException("Bad unicode escape at $index")
        index += 4
        // A surrogate pair arrives as two escapes; appending each half as-is reassembles it,
        // because Kotlin strings are UTF-16 exactly as JSON's escapes are.
        code.toChar()
      }
      else -> throw RcJsonException("Unknown escape at ${index - 1}: $escape")
    }
  }

  private fun readNumber(): RcJsonValue.Num {
    val start = index
    if (!atEnd && text[index] == '-') index++
    while (!atEnd && (text[index] in '0'..'9' || text[index] in ".eE+-")) index++
    val value =
      text.substring(start, index).toDoubleOrNull() ?: throw RcJsonException("Bad number at $start")
    return RcJsonValue.Num(value)
  }

  private fun readLiteral(literal: String, value: RcJsonValue): RcJsonValue {
    if (!text.startsWith(literal, index)) throw RcJsonException("Bad literal at $index")
    index += literal.length
    return value
  }

  private fun expect(character: Char) {
    if (atEnd || text[index] != character) throw RcJsonException("Expected '$character' at $index")
    index++
  }
}

private fun Char.isJsonWhitespace(): Boolean =
  this == ' ' || this == '\t' || this == '\n' || this == '\r'
