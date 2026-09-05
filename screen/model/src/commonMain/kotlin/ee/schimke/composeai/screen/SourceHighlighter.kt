package ee.schimke.composeai.screen

/**
 * Categorises generated Kotlin into [SourceToken]s for the builder's source pane.
 *
 * ### Why this lexes, when the spec said not to
 *
 * The advice it reverses was: *do not re-lex your own output — codegen knows what every token is at
 * the moment it writes it, and a lexer is a parser that can disagree with the thing it parses.*
 * That was right while the builder generated the source. It no longer does. Generation is
 * [ee.schimke.composeai.discovery.ScreenGenerator], which is shared source compiled into the
 * published `preview-discovery` jar and used by the Gradle plugin and the server — instrumenting it
 * with span emission for a browser highlighter would push a rendering concern into the one place
 * that must stay about correctness.
 *
 * So the premise changed rather than the reasoning being wrong, and this is the trade that follows:
 * a small lexer here, kept honest by the same **tiling** invariant the span-emitting version had.
 *
 * ### What it deliberately does not do
 *
 * It is not a Kotlin parser and cannot become one. It has no notion of scope, type or resolution:
 * an identifier followed by `(` reads as a [SourceTokenKind.CALL] whether it is a composable, a
 * constructor or a local function. That is exactly enough to colour a pane and exactly as much as a
 * highlighter should claim.
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
   * [source] as tokens that **tile it exactly**: sorted, non-overlapping, and covering every offset
   * once.
   *
   * Tiling is the property a renderer relies on to walk the list with no gap handling, and the one
   * a test can assert cheaply — an off-by-one fails it rather than showing up as a single wrongly
   * coloured character nobody notices.
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
