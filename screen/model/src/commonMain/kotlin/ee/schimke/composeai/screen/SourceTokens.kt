package ee.schimke.composeai.screen

/**
 * What one run of generated source *is* — enough for a renderer to colour it, and nothing more.
 *
 * Deliberately not a Kotlin token type. These are the categories a highlighter distinguishes, so
 * `true` is a [KEYWORD] alongside `import` and `fun` even though the language calls it a literal,
 * and `.dp` after a number is [PLAIN] rather than a property reference. Codegen knows which of
 * these it is writing at the moment it writes it; a lexer would have to work it out again.
 */
public enum class SourceTokenKind {
  /** `import`, `fun`, `true`, `false`. */
  KEYWORD,
  /** `@Composable`. */
  ANNOTATION,
  /**
   * A composable being called — `Button`, `LazyColumn`, `Text`, `Color` — and the declared name.
   */
  CALL,
  /** A string literal **including** its quotes. */
  STRING,
  /** A numeric literal: `4.0` of `4.0.dp`, `0xFF2196F3` of `Color(0xFF2196F3)`. */
  NUMBER,
  /** A `// TODO …` line, or the `/* TODO … */` beside a value that was not valid. */
  COMMENT,
  /** Everything else: punctuation, whitespace, parameter names, import paths. */
  PLAIN,
}

/**
 * A half-open range `[start, end)` into [GeneratedScreen.source] and what it is.
 *
 * @property start inclusive offset into the source.
 * @property end exclusive offset into the source.
 */
public data class SourceToken(val start: Int, val end: Int, val kind: SourceTokenKind)

/**
 * Accumulates generated text **and** the spans that describe it, so the two cannot drift.
 *
 * The offsets are just the builder's own length before and after each fragment — the reason
 * highlighting needs no lexer. [splice] exists because codegen does not write the file front to
 * back: a slot's content is generated into its own builder and only later placed inside an argument
 * list, so its spans are recorded relative to that builder and relocated when it lands.
 *
 * [plain] records nothing. The gaps it leaves become [SourceTokenKind.PLAIN] tokens in [tile],
 * which is what makes them maximal runs rather than one token per `append`.
 */
internal class SpanBuilder {
  private val text = StringBuilder()
  private val spans = ArrayList<SourceToken>()

  val length: Int
    get() = text.length

  fun isEmpty(): Boolean = text.isEmpty()

  fun plain(fragment: String) {
    text.append(fragment)
  }

  fun plain(fragment: Char) {
    text.append(fragment)
  }

  fun token(fragment: String, kind: SourceTokenKind) {
    val start = text.length
    text.append(fragment)
    spans += SourceToken(start, text.length, kind)
  }

  /** Appends [other]'s text and adopts its spans, shifted to where they now sit. */
  fun splice(other: SpanBuilder) {
    val offset = text.length
    text.append(other.text)
    other.spans.forEach { spans += SourceToken(it.start + offset, it.end + offset, it.kind) }
  }

  fun build(): String = text.toString()

  /**
   * The recorded spans with every gap filled by a [SourceTokenKind.PLAIN] run, so the result
   * **tiles the text exactly**: sorted, non-overlapping, and covering every offset once.
   *
   * A renderer can then walk the list and emit each token in order with no gap handling — and an
   * off-by-one in codegen fails the tiling assertion instead of showing up as a single wrongly
   * coloured character that nobody notices.
   */
  fun tile(): List<SourceToken> {
    val tiled = ArrayList<SourceToken>(spans.size * 2 + 1)
    var at = 0
    spans.forEach { span ->
      if (span.start > at) tiled += SourceToken(at, span.start, SourceTokenKind.PLAIN)
      tiled += span
      at = span.end
    }
    if (at < text.length) tiled += SourceToken(at, text.length, SourceTokenKind.PLAIN)
    return tiled
  }
}
