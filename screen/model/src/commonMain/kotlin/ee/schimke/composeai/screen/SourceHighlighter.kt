package ee.schimke.composeai.screen

/**
 * Categorises generated Kotlin into [SourceToken]s for the builder's source pane.
 *
 * A small lexer rather than spans emitted by codegen: generation is
 * [ee.schimke.composeai.discovery.ScreenGenerator], shared with the Gradle plugin and server, which
 * shouldn't carry a browser rendering concern. Kept honest by the tiling invariant.
 *
 * Not a parser: no scope, type or resolution — an identifier followed by `(` is a
 * [SourceTokenKind.CALL], which is enough to colour a pane.
 */
public object SourceHighlighter {

  /** The words the pane colours as keywords. `true`/`false` included — see [SourceTokenKind]. */
  private val KEYWORDS =
    setOf(
      "import",
      "package",
      "fun",
      "val",
      "var",
      "true",
      "false",
      "null",
      "return",
      "if",
      "else",
      "object",
      "class",
      "private",
      "internal",
      "public",
    )

  /**
   * [source] as tokens that tile it exactly: sorted, non-overlapping, covering every offset once —
   * so renderers need no gap handling and an off-by-one fails a cheap test.
   */
  public fun tokenize(source: String): List<SourceToken> {
    if (source.isEmpty()) return emptyList()
    val out = ArrayList<SourceToken>()
    var at = 0
    var plainFrom = 0

    fun flushPlain(upTo: Int) {
      if (upTo > plainFrom) out += SourceToken(plainFrom, upTo, SourceTokenKind.PLAIN)
    }

    fun emit(start: Int, end: Int, kind: SourceTokenKind) {
      flushPlain(start)
      out += SourceToken(start, end, kind)
      plainFrom = end
    }

    while (at < source.length) {
      val c = source[at]
      when {
        // A line comment runs to the newline, which stays outside it.
        c == '/' && at + 1 < source.length && source[at + 1] == '/' -> {
          var end = at
          while (end < source.length && source[end] != '\n') end++
          emit(at, end, SourceTokenKind.COMMENT)
          at = end
        }
        // A block comment — the `/* … */` the generator writes beside a value it could not prove.
        c == '/' && at + 1 < source.length && source[at + 1] == '*' -> {
          var end = at + 2
          while (end + 1 < source.length && !(source[end] == '*' && source[end + 1] == '/')) end++
          val stop = minOf(source.length, end + 2)
          emit(at, stop, SourceTokenKind.COMMENT)
          at = stop
        }
        // The quotes are part of the literal, so a renderer colours them with it. A backslash
        // escapes the next character, which is what stops `"a \" b"` ending early.
        c == '"' -> {
          var end = at + 1
          while (end < source.length && source[end] != '"') {
            if (source[end] == '\\') end++
            end++
          }
          val stop = minOf(source.length, end + 1)
          emit(at, stop, SourceTokenKind.STRING)
          at = stop
        }
        c.isDigit() -> {
          var end = at
          while (end < source.length && (source[end].isLetterOrDigit() || source[end] == '.')) {
            // `4.0.dp` is a number followed by a plain `.dp`: stop at the dot that begins a
            // non-digit, or the property read would be swallowed into the literal.
            if (source[end] == '.' && (end + 1 >= source.length || !source[end + 1].isDigit()))
              break
            end++
          }
          emit(at, end, SourceTokenKind.NUMBER)
          at = end
        }
        c == '@' -> {
          var end = at + 1
          while (end < source.length && (source[end].isLetterOrDigit() || source[end] == '_')) end++
          emit(at, end, SourceTokenKind.ANNOTATION)
          at = end
        }
        c.isLetter() || c == '_' -> {
          var end = at
          while (end < source.length && (source[end].isLetterOrDigit() || source[end] == '_')) end++
          val word = source.substring(at, end)
          // A call is an identifier immediately followed by `(` — enough to colour, and all a
          // highlighter can honestly claim without resolution.
          val kind =
            when {
              word in KEYWORDS -> SourceTokenKind.KEYWORD
              end < source.length && source[end] == '(' -> SourceTokenKind.CALL
              else -> null
            }
          if (kind != null) emit(at, end, kind)
          at = end
        }
        else -> at++
      }
    }
    flushPlain(source.length)
    return out
  }
}
