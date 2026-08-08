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
 * Three matching styles, chosen per pattern:
 * - **Anchored** — a pattern prefixed with `=` matches that id or FQN and nothing else:
 *   `=FilledButton_Light` will not touch `FilledButton_Light_VARIANT_off`. See [ANCHOR] for why
 *   any generated id list needs this.
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
   * Prefix that makes a pattern an **exact** match rather than a substring one.
   *
   * Plain substring matching is safe on the *include* axis — over-matching only renders more than
   * asked — and unsafe on the *exclude* axis, where over-matching silently deletes work. Preview
   * ids are hierarchical (`<base>_<variant>`, `<base>_<row>`), so a base id is **always** a
   * substring of its own fan-out members: excluding `FilledButton_Light` also excluded
   * `FilledButton_Light_VARIANT_off`.
   *
   * That made substring exclusion unusable with any *generated* id list, which is exactly what a
   * render sharder produces — a shard excluding the other shards' ids also deleted the variants it
   * was itself assigned. m3-catalog hit this at scale: 267 captured of 1095 assigned, on a green
   * run, which is why its `render-shards` has been pinned to 1 (issue #3559).
   *
   * `=` is chosen because no discovered id can begin with it (ids derive from Kotlin identifiers
   * and are path-sanitised), so adding this cannot change the meaning of any existing pattern.
   */
  const val ANCHOR: String = "="


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

  /**
   * True when a preview **id** matches at least one of [patterns], or when [patterns] is empty.
   *
   * The id-level counterpart of [matches], for selecting individual members of one `@Preview`
   * function's fan-out (issue #2966). A multipreview member or `@PreviewParameter` row is its own
   * `PreviewInfo` with a distinct `id` (`FilledButton_Light` / `FilledButton_Dark`) but the *same*
   * `functionName`, so [matches] can only ever keep or drop the whole function — which is why a
   * catalog's `modePriority` could thin its published sticker set but not its render.
   *
   * Ids are opaque strings rather than named code, so only one candidate is compared (no FQN
   * variant), but the glob-vs-substring rules are deliberately identical to [matches] so a single
   * documented pattern syntax covers both filters.
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
