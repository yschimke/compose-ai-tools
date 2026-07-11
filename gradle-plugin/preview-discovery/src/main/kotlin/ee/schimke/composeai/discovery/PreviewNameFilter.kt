package ee.schimke.composeai.discovery

/**
 * Name-based `@Preview` selector used by `composePreviewRender --preview` /
 * `-PcomposePreview.filter` (issue #2066). Narrows a discovered manifest to the preview functions a
 * caller names, so a tight iteration loop can re-render a single screen instead of the whole module
 * — and so an unrelated broken preview never poisons a filtered run (it's simply not scheduled).
 *
 * A preview matches a pattern against two candidate names:
 * - its **simple** function name (e.g. `ExportHelpDialogPreview`), and
 * - its **package-qualified** name (`<package>.<functionName>`, e.g.
 *   `com.example.preview.ExportHelpDialogPreview`) — the FQN a user reads off the source, derived
 *   from the owning class's package rather than the synthetic `…Kt` holder class.
 *
 * Two matching styles, chosen per pattern:
 * - **Glob** — a pattern containing `*` (any run) or `?` (one char) is anchored and full-matched
 *   against either candidate: `*ExportHelpDialogPreview`, `Export*Preview`, `com.example.*.Foo`.
 * - **Plain** — a pattern with no glob chars matches on equality *or substring* against either
 *   candidate, so `ExportHelpDialog` finds `ExportHelpDialogPreview` and a full FQN matches
 *   exactly.
 *
 * Matching is case-sensitive (Kotlin function names are), and any pattern matching keeps the
 * preview (OR across the pattern list). An empty pattern list matches everything — the historical
 * "render every preview" behaviour.
 */
object PreviewNameFilter {

  /**
   * True when [functionName] (owned by [className]) matches at least one of [patterns], or when
   * [patterns] is empty (no filter → keep everything). Blank patterns are ignored so a stray comma
   * in `-PcomposePreview.filter=Foo,` can't silently match nothing-or-everything.
   */
  fun matches(patterns: Collection<String>, functionName: String, className: String): Boolean {
    val cleaned = patterns.map(String::trim).filter(String::isNotEmpty)
    if (cleaned.isEmpty()) return true
    val fqName = fqName(className, functionName)
    return cleaned.any { matchOne(it, functionName, fqName) }
  }

  /**
   * The package-qualified name a user reads off the source: `<package>.<functionName>`. [className]
   * is the owning class FQN (a synthetic `…Kt` holder for top-level functions), so only its package
   * segment is meaningful for naming a preview. Falls back to the bare function name when the class
   * has no package (default package).
   */
  fun fqName(className: String, functionName: String): String {
    val pkg = className.substringBeforeLast('.', "")
    return if (pkg.isEmpty()) functionName else "$pkg.$functionName"
  }

  private fun matchOne(pattern: String, simpleName: String, fqName: String): Boolean =
    if (pattern.any { it == '*' || it == '?' }) {
      val regex = globToRegex(pattern)
      regex.matches(simpleName) || regex.matches(fqName)
    } else {
      simpleName == pattern ||
        fqName == pattern ||
        simpleName.contains(pattern) ||
        fqName.contains(pattern)
    }

  /**
   * Translates a `*`/`?` glob into an anchored regex, escaping every other character so a `.` in an
   * FQN is a literal dot rather than "any char". Literal runs are batched through [Regex.escape] so
   * regex metacharacters in a package name can never leak into the pattern.
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
    return Regex(out.toString())
  }
}
