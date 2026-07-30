package ee.schimke.composeai.renderer

/**
 * Skips individual `@PreviewParameter` **rows** of one preview, from the
 * `composeai.preview.rowExclude` system property the Gradle plugin forwards (`composePreviewRender
 * --exclude-preview-row` / `-PcomposePreview.rowExclude`).
 *
 * Why this exists as its own axis rather than reusing the id filters (#2966): discovery emits ONE
 * `PreviewInfo` per parameterized function — it reads bytecode and can't instantiate a provider —
 * and the rows only come into existence here, when [PreviewParameterLabels] labels the values this
 * renderer just enumerated. So no id-level filter upstream can name a row, and a design system
 * whose theme axis is a `@PreviewParameter` provider (the shape that motivated #2966's measurement:
 * nine palettes on one provider) had no way to stop rendering the palettes it defers. A row is
 * addressed by its **label** — the same token that ends up in `<stem>_<label>.png`, so what a
 * caller reads off disk is what they exclude.
 *
 * Matching, per pattern: an anchored `*`/`?` glob when the pattern carries one, else equality.
 * **Case-insensitive** either way, deliberately and unlike the id filters: a label comes from user
 * data (a provider value's `name`/`toString()`, so `"Dark"`) while the pattern is usually a design
 * spec's own spelling of the same axis (`"dark"`), and nothing reconciles the two.
 * Case-insensitivity can only ever widen an *exclusion*, and [keptRows]' never-empty rule bounds
 * what that can cost.
 */
internal object PreviewRowFilter {

  /**
   * System property the plugin forwards; comma-separated, blank/absent means "render every row".
   */
  const val PROPERTY = "composeai.preview.rowExclude"

  /** Patterns from [PROPERTY] (or [raw] in tests), trimmed with blanks dropped. */
  fun patterns(raw: String? = System.getProperty(PROPERTY)): List<String> =
    raw?.split(',')?.map(String::trim)?.filter(String::isNotEmpty) ?: emptyList()

  /**
   * The indices of [suffixes] to render, in order.
   *
   * [suffixes] are [PreviewParameterLabels.suffixesFor]'s output — `"_Dark"`, or `"_PARAM_3"` for a
   * value that couldn't be labelled — and the leading `_` is stripped before matching, so a caller
   * writes `--exclude-preview-row Dark`, matching the filename they see.
   *
   * Two rules keep this from ever costing coverage silently:
   * - a preview with no fan-out (the single `""` suffix) is never filtered — it has no rows, so a
   *   row pattern must not be able to delete its only render;
   * - if every row matches, none is skipped. A preview that rendered nothing at all would publish
   *   as a component with no pixels, which is a misconfigured exclusion rather than a deferral —
   *   the same never-empty rule the design-catalog derivation applies one level up.
   */
  fun keptRows(suffixes: List<String>, patterns: List<String>): List<Int> {
    val all = suffixes.indices.toList()
    if (patterns.isEmpty()) return all
    if (suffixes.size == 1 && suffixes[0].isEmpty()) return all
    val kept = all.filterNot { matchesAny(suffixes[it].removePrefix("_"), patterns) }
    return if (kept.isEmpty()) all else kept
  }

  private fun matchesAny(label: String, patterns: List<String>): Boolean = patterns.any { pattern ->
    if (pattern.any { it == '*' || it == '?' }) globToRegex(pattern).matches(label)
    else label.equals(pattern, ignoreCase = true)
  }

  /**
   * Anchored, case-insensitive regex for a `*`/`?` glob. Literal runs go through [Regex.escape] so
   * a metacharacter in a label or pattern can't leak into the expression.
   */
  private fun globToRegex(glob: String): Regex {
    val out = StringBuilder()
    val literal = StringBuilder()
    fun flushLiteral() {
      if (literal.isNotEmpty()) {
        out.append(Regex.escape(literal.toString()))
        literal.clear()
      }
    }
    for (c in glob) {
      when (c) {
        '*' -> {
          flushLiteral()
          out.append(".*")
        }
        '?' -> {
          flushLiteral()
          out.append(".")
        }
        else -> literal.append(c)
      }
    }
    flushLiteral()
    return Regex(out.toString(), RegexOption.IGNORE_CASE)
  }
}
