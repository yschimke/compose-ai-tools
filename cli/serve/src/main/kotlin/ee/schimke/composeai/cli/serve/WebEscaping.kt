package ee.schimke.composeai.cli.serve

/**
 * Tiny, dependency-free HTML / URL escaping helpers shared by the in-code web surfaces ([ServeWeb]
 * for the live `compose-preview serve` server and [ee.schimke.composeai.cli.WebEmbed] for the
 * static offline gallery). Both bake preview ids — which can carry `#`, `?`, `/`, and spaces (see
 * [urlEncodeSegment]) — into HTML attributes and URLs, so the same escaping has to be applied in
 * both places; promoting it here keeps a single implementation rather than two drifting copies.
 */
internal object WebEscaping {

  /**
   * A percentage as the viewer prints it: fixed decimals, locale-independent.
   *
   * `Locale.ROOT` is the whole point — a box with a comma decimal separator would render "99,7%
   * match" on a page whose readout, computed in the browser by `toFixed`, says "99.7%". The two
   * numbers are the same comparison and must not be spelled differently.
   */
  fun formatPercent(value: Double, decimals: Int = 1): String =
    String.format(java.util.Locale.ROOT, "%.${decimals}f%%", value)

  /** Escape HTML-significant characters for safe interpolation into text or quoted attributes. */
  fun htmlEscape(s: String): String =
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
  fun jsString(s: String): String =
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
   * Percent-encode [s] for safe use as a single URL path segment (RFC 3986): every byte outside the
   * unreserved set becomes `%XX`. A preview id can contain `#`, `?`, `&`, or a space, any of which
   * would otherwise be parsed as URL structure rather than as part of the id.
   */
  fun urlEncodeSegment(s: String): String =
    buildString(s.length) {
      for (b in s.toByteArray(Charsets.UTF_8)) {
        val c = (b.toInt() and 0xff)
        if (isUrlUnreserved(c.toChar())) append(c.toChar())
        else append('%').append("%02X".format(c))
      }
    }

  /**
   * Width/height from a PNG's IHDR chunk (the first chunk after the 8-byte signature: 4-byte width,
   * 4-byte height, big-endian). Returns `0 to 0` when the bytes aren't a PNG we can read, in which
   * case callers fall back to the image's intrinsic size at render time.
   */
  fun pngDimensions(bytes: ByteArray): Pair<Int, Int> {
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
