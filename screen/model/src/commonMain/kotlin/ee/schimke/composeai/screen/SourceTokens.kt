package ee.schimke.composeai.screen

/**
 * Highlighting categories for a run of generated source, not Kotlin token types (`true` is a
 * [KEYWORD]; `.dp` after a number is [PLAIN]). Produced only by [SourceHighlighter].
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
