package ee.schimke.composeai.screen

/**
 * What one run of generated source *is* — enough for a renderer to colour it, and nothing more.
 *
 * Deliberately not a Kotlin token type. These are the categories a highlighter distinguishes, so
 * `true` is a [KEYWORD] alongside `import` and `fun` even though the language calls it a literal,
 * and `.dp` after a number is [PLAIN] rather than a property reference. [SourceHighlighter] is the
 * one thing that produces them, and it says why it lexes rather than being told.
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
 * A half-open range `[start, end)` into the source and what it is.
 *
 * @property start inclusive offset into the source.
 * @property end exclusive offset into the source.
 */
public data class SourceToken(val start: Int, val end: Int, val kind: SourceTokenKind)
