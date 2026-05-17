package ee.schimke.composeai.plugin

/**
 * Parser for the `-PbundlePreviewIds=…` Gradle property used by `composePreviewBundle`.
 *
 * The wire format is comma-separated preview ids with backslash escapes: `\,` decodes to a literal
 * `,`, `\\` decodes to a literal `\`, and any other `\<x>` keeps both characters verbatim. Escapes
 * are needed because preview ids carry the `@Preview(name = …)` suffix (e.g.
 * `com.example.Foo.Bar_Phone, dark`) — `,` is not in the discovery-time sanitiser's strip set, so
 * it does reach the bundle task. Whitespace around each entry is trimmed (so `id1, id2` and
 * `id1,id2` are equivalent); empty entries are dropped.
 *
 * Encoders (callers building `-PbundlePreviewIds=…`) should use [encode] for each id and
 * `joinToString(",")` for the list. The pairing keeps the CLI's `bundle pack --id` flow and the VS
 * Code panel's per-preview export aligned on a single transport.
 */
internal object BundlePreviewIds {
  fun parse(raw: String): List<String> {
    val out = mutableListOf<String>()
    val current = StringBuilder()
    var i = 0
    while (i < raw.length) {
      val c = raw[i]
      if (c == '\\' && i + 1 < raw.length) {
        val next = raw[i + 1]
        if (next == ',' || next == '\\') {
          current.append(next)
          i += 2
          continue
        }
      }
      if (c == ',') {
        addTrimmed(out, current)
        current.clear()
      } else {
        current.append(c)
      }
      i++
    }
    addTrimmed(out, current)
    return out
  }

  fun encode(id: String): String {
    val sb = StringBuilder(id.length)
    for (c in id) {
      when (c) {
        '\\',
        ',' -> sb.append('\\').append(c)
        else -> sb.append(c)
      }
    }
    return sb.toString()
  }

  private fun addTrimmed(out: MutableList<String>, buf: StringBuilder) {
    val trimmed = buf.toString().trim()
    if (trimmed.isNotEmpty()) out.add(trimmed)
  }
}
