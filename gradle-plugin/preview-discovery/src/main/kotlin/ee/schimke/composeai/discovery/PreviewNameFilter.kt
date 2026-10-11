package ee.schimke.composeai.discovery

/**
 * Name-based `@Preview` selector for `composePreviewRender --preview` / `-PcomposePreview.filter`
 * (#2066), so a tight loop re-renders one screen and unrelated broken previews are never scheduled.
 *
 * A pattern is matched against the **simple** function name and the **package-qualified** name
 * (`<package>.<functionName>`, from the owner's package rather than the `…Kt` holder). Per pattern:
 * - **Anchored** — `=` prefix: exact match only (see [ANCHOR]).
 * - **Glob** — contains `*` or `?`: full match against either name.
 * - **Plain** — equality or substring against either name.
 *
 * Case-sensitive; any matching pattern keeps the preview; an empty list matches everything.
 */
object PreviewNameFilter {

  /**
   * Prefix for an **exact** match. Substring matching is safe for inclusion but not exclusion: ids
   * are hierarchical (`<base>_<variant>`), so excluding `FilledButton_Light` also excluded
   * `FilledButton_Light_VARIANT_off`, which broke generated id lists such as render shards (#3559).
   * No discovered id can start with `=`, so existing patterns are unaffected.
   */
  const val ANCHOR: String = "="

  /**
   * True when [functionName] (owned by [className]) matches any of [patterns], or [patterns] is
   * empty. Blank patterns (a trailing comma) are ignored.
   */
  fun matches(patterns: Collection<String>, functionName: String, className: String): Boolean {
    val cleaned = patterns.map(String::trim).filter(String::isNotEmpty)
    if (cleaned.isEmpty()) return true
    val fqName = fqName(className, functionName)
    return cleaned.any { matchOne(it, functionName, fqName) }
  }

  /**
   * `<package>.<functionName>`, using only [className]'s package (the class is often a synthetic
   * `…Kt` holder); the bare name in the default package.
   */
  fun fqName(className: String, functionName: String): String {
    val pkg = className.substringBeforeLast('.', "")
    return if (pkg.isEmpty()) functionName else "$pkg.$functionName"
  }

  /**
   * Id-level counterpart of [matches] (#2966), for selecting individual fan-out members, which
   * share a `functionName`. One candidate (ids are opaque), with the same pattern syntax.
   */
  fun matchesId(patterns: Collection<String>, id: String): Boolean {
    val cleaned = patterns.map(String::trim).filter(String::isNotEmpty)
    if (cleaned.isEmpty()) return true
    return cleaned.any { matchOne(it, id, id) }
  }

  private fun matchOne(pattern: String, simpleName: String, fqName: String): Boolean =
    when {
      pattern.startsWith(ANCHOR) -> {
        val exact = pattern.substring(ANCHOR.length)
        simpleName == exact || fqName == exact
      }
      pattern.any { it == '*' || it == '?' } -> {
        val regex = globToRegex(pattern)
        regex.matches(simpleName) || regex.matches(fqName)
      }
      else ->
        simpleName == pattern ||
          fqName == pattern ||
          simpleName.contains(pattern) ||
          fqName.contains(pattern)
    }

  /**
   * `*`/`?` glob → anchored regex, escaping everything else via [Regex.escape] so `.` is literal.
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
