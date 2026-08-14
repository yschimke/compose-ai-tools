package ee.schimke.composeai.usagepsi

/**
 * A minimal JSON writer, so this module's only runtime need is the Kotlin stdlib.
 *
 * The module is loaded into an isolated classloader holding the Kotlin frontend and nothing else;
 * adding a serialization library would mean staging it beside the frontend and keeping the two
 * classpaths in step. The schema written here is a handful of fields, and it is consumed by exactly
 * one reader (`UsageSourceFacts` in `:cli`), so hand-writing it is the smaller commitment.
 *
 * Escaping is the part worth getting right, since the values are arbitrary Kotlin source: quotes,
 * backslashes, newlines and control characters all reach this from a catalog's own code.
 */
internal class JsonWriter {
  private val out = StringBuilder()
  private var needsComma = false

  fun field(name: String, value: String?) {
    separator()
    out.append('"').append(escape(name)).append("\":")
    if (value == null) out.append("null") else out.append('"').append(escape(value)).append('"')
  }

  fun number(name: String, value: Int) {
    separator()
    out.append('"').append(escape(name)).append("\":").append(value)
  }

  fun <T> arrayField(name: String, items: Collection<T>, write: JsonWriter.(T) -> Unit) {
    separator()
    out.append('"').append(escape(name)).append("\":[")
    var first = true
    for (item in items) {
      if (!first) out.append(',')
      first = false
      val nested = JsonWriter()
      nested.write(item)
      val body = nested.finishRaw()
      // A writer whose `write` block emitted a bare value (`raw`) rather than fields is spliced as
      // that value; otherwise it is an object.
      if (nested.wroteRaw) out.append(body) else out.append('{').append(body).append('}')
    }
    out.append(']')
  }

  fun raw(value: String) {
    wroteRaw = true
    out.append(value)
  }

  private var wroteRaw = false

  private fun separator() {
    if (needsComma) out.append(',')
    needsComma = true
  }

  private fun finishRaw(): String = out.toString()

  fun finish(): String = "{" + out + "}"
}

internal fun json(build: JsonWriter.() -> Unit): String = JsonWriter().apply(build).finish()

internal fun escape(value: String): String {
  val out = StringBuilder(value.length + 16)
  for (ch in value) {
    when (ch) {
      '"' -> out.append("\\\"")
      '\\' -> out.append("\\\\")
      '\n' -> out.append("\\n")
      '\r' -> out.append("\\r")
      '\t' -> out.append("\\t")
      '\b' -> out.append("\\b")
      '' -> out.append("\\f")
      else -> if (ch < ' ') out.append("\\u%04x".format(ch.code)) else out.append(ch)
    }
  }
  return out.toString()
}
