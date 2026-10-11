package ee.schimke.composeai.web

/**
 * Dependency-free HTML / URL escaping shared by the serve web pages and the static offline gallery
 * ([ee.schimke.composeai.cli.WebEmbed]), which both bake preview ids (possibly containing `#`, `?`,
 * `/` and spaces) into attributes and URLs.
 */
public object WebEscaping {

  /**
   * A percentage as the viewer prints it, with `Locale.ROOT` so it matches the browser's `toFixed`
   * ("99.7%", never "99,7%").
   */
  public fun formatPercent(value: Double, decimals: Int = 1): String =
    String.format(java.util.Locale.ROOT, "%.${decimals}f%%", value)

  /** Escape HTML-significant characters for safe interpolation into text or quoted attributes. */
  public fun htmlEscape(s: String): String =
    buildString(s.length) {
      for (c in s) {
        when (c) {
          '&' -> append("&amp;")
          '<' -> append("&lt;")
          '>' -> append("&gt;")
          '"' -> append("&quot;")
          '\'' -> append("&#39;")
          else -> append(c)
        }
      }
    }

  /**
   * A double-quoted **JavaScript string literal** for [s], safe to interpolate into an inline
   * `<script>`: quotes / backslashes / newlines escaped, and `<` and `&` written as `\uXXXX` so the
   * literal can never close the script element or be read as markup by the HTML parser.
   */
  public fun jsString(s: String): String =
    buildString(s.length + 2) {
      append('"')
      for (c in s) {
        when (c) {
          '"' -> append("\\\"")
          '\\' -> append("\\\\")
          '\n' -> append("\\n")
          '\r' -> append("\\r")
          '<' -> append("\\u003c")
          '>' -> append("\\u003e")
          '&' -> append("\\u0026")
          else -> append(c)
        }
      }
      append('"')
    }

  /** Unreserved URL characters (RFC 3986 §2.3) — left as-is when encoding a path segment. */
  private fun isUrlUnreserved(c: Char): Boolean =
    c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '_' || c == '.' || c == '~'

  /**
   * Percent-encode [s] as a single URL path segment (RFC 3986), so `#`, `?`, `&` or spaces in an id
   * aren't parsed as URL structure.
   */
  public fun urlEncodeSegment(s: String): String =
    buildString(s.length) {
      for (b in s.toByteArray(Charsets.UTF_8)) {
        val c = (b.toInt() and 0xff)
        if (isUrlUnreserved(c.toChar())) append(c.toChar())
        else append('%').append("%02X".format(c))
      }
    }

  /**
   * Width/height from a PNG's IHDR chunk, or `0 to 0` if unreadable (callers then use the intrinsic
   * size).
   */
  public fun pngDimensions(bytes: ByteArray): Pair<Int, Int> {
    // 8 (sig) + 4 (len) + 4 ("IHDR") + 4 (w) + 4 (h) = need at least 24 bytes.
    if (bytes.size < 24) return 0 to 0
    if (bytes[12] != 'I'.code.toByte() || bytes[13] != 'H'.code.toByte()) return 0 to 0
    fun be(off: Int) =
      ((bytes[off].toInt() and 0xff) shl 24) or
        ((bytes[off + 1].toInt() and 0xff) shl 16) or
        ((bytes[off + 2].toInt() and 0xff) shl 8) or
        (bytes[off + 3].toInt() and 0xff)
    return be(16) to be(20)
  }
}
