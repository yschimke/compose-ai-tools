package ee.schimke.composeai.cli

/**
 * `bundle pack --exclude-preview-id`: preview ids a pack must neither render nor semantics-capture.
 * One list for two consumers: the render (`-PcomposePreview.idExclude`) and the daemon-driven
 * semantics capture, which no Gradle property reaches.
 *
 * Matching mirrors the plugin's `PreviewNameFilter.matchesId` (the authority): an anchored `*`/`?`
 * glob when present, else equality or substring, case-sensitive. Restated here because the plugin's
 * module is in a separate build; keep the two (and VS Code's `previewFilter.ts`) in step.
 */
internal object PackPreviewIdExclusions {

  /** The Gradle property `composePreviewRender` reads the same patterns from. */
  const val GRADLE_PROPERTY = "composePreview.idExclude"

  /** Env form of [GRADLE_PROPERTY] — how Gradle sources project properties from the environment. */
  const val ENV_VAR = "ORG_GRADLE_PROJECT_$GRADLE_PROPERTY"

  /**
   * This invocation's patterns: every `--exclude-preview-id` value (repeatable, comma-separated),
   * or [ENV_VAR] when the flag is absent — so exporting the variable thins the semantics pass as
   * well as the render. An explicit flag replaces the environment rather than merging.
   */
  fun fromArgs(args: List<String>, env: (String) -> String? = System::getenv): List<String> =
    fileFromArgs(args)?.let(::linesOf) ?: patternsFor(args, "--exclude-preview-id", ENV_VAR, env)

  /** The Gradle property carrying a PATH to a newline-delimited exclusion list. */
  const val FILE_GRADLE_PROPERTY = "composePreview.idExcludeFile"

  /**
   * `--exclude-preview-id-file <path>`, one pattern per line. Needed because ids can contain commas
   * (`…_width=227dp, height=100dp, dpi=320`), and re-split fragments substring-match far too much.
   * Returns the file so the render can be handed the path ([FILE_GRADLE_PROPERTY]) and nothing
   * re-joins the lines.
   */
  fun fileFromArgs(args: List<String>): java.io.File? =
    args
      .flagValuesAll("--exclude-preview-id-file")
      .lastOrNull()
      ?.trim()
      ?.takeIf(String::isNotEmpty)
      ?.let { java.io.File(it) }

  /**
   * The ids in [file], one per line, blanks dropped. Throws when unreadable or empty: for both
   * `--id-file` and `--exclude-preview-id-file` an empty list would mean "everything", the opposite
   * of what the file asked. Callers with nothing to select shouldn't pass the flag.
   */
  fun linesOf(file: java.io.File): List<String> {
    check(file.isFile) {
      "'${file.path}' is not a readable file. Refusing to fall back to an empty selection, which " +
        "would act on every preview and look like success."
    }
    val lines = file.readLines().map(String::trim).filter(String::isNotEmpty)
    check(lines.isNotEmpty()) {
      "'${file.path}' contains no preview ids. Refusing to fall back to an empty selection, which " +
        "would act on every preview and look like success. Omit the flag instead."
    }
    return lines
  }

  /**
   * `bundle pack --id-file <path>`: previews to pack, one per line. `--id` comma-splits values,
   * which shatters ids containing commas so the exact-matching bundle task fails. Replaces `--id`
   * when present.
   */
  fun idFileFromArgs(args: List<String>): java.io.File? =
    args.flagValuesAll("--id-file").lastOrNull()?.trim()?.takeIf(String::isNotEmpty)?.let {
      java.io.File(it)
    }

  /**
   * The previews `bundle pack` selects: the id file when passed, else `--id`. Extracted so tests
   * exercise the real parsing rather than restating it.
   */
  fun selectedIds(args: List<String>): List<String> =
    idFileFromArgs(args)?.let(::linesOf)
      ?: args
        .flagValuesAll("--id")
        .flatMap { it.split(',') }
        .map(String::trim)
        .filter(String::isNotEmpty)

  /** The Gradle property carrying `@PreviewParameter` **row** label exclusions. */
  const val ROW_GRADLE_PROPERTY = "composePreview.rowExclude"

  /** Env form of [ROW_GRADLE_PROPERTY]. */
  const val ROW_ENV_VAR = "ORG_GRADLE_PROJECT_$ROW_GRADLE_PROPERTY"

  /**
   * `--exclude-preview-row` labels, resolved like [fromArgs]. Render only: semantics are captured
   * per preview, so there is no per-row cost to save there.
   */
  fun rowsFromArgs(args: List<String>, env: (String) -> String? = System::getenv): List<String> =
    patternsFor(args, "--exclude-preview-row", ROW_ENV_VAR, env)

  private fun patternsFor(
    args: List<String>,
    flag: String,
    envVar: String,
    env: (String) -> String?,
  ): List<String> {
    val flagValues = args.flagValuesAll(flag)
    val raw = if (flagValues.isNotEmpty()) flagValues else listOfNotNull(env(envVar))
    return raw.flatMap { it.split(',') }.map { it.trim() }.filter { it.isNotEmpty() }
  }

  /**
   * What one pattern matched, mirroring the plugin's `PreviewIdExclusionMatch` so pack and render
   * logs read alike. Patterns may overlap, so counts need not sum to the number dropped.
   */
  data class Match(val pattern: String, val matched: Int, val total: Int) {
    val line: String
      get() =
        "--exclude-preview-id '$pattern' matched $matched of $total preview(s)" +
          if (matched == 0)
            " — nothing excluded. Check the pattern: preview ids keep spaces where render " +
              "filenames use underscores, and a plain pattern matches on substring."
          else ""
  }

  /**
   * Per-pattern match counts over [ids], in [patterns] order — separate from [retain] so a pattern
   * matching nothing (usually a typo) is visible.
   */
  fun matches(ids: List<String>, patterns: List<String>): List<Match> =
    patterns
      .map { it.trim() }
      .filter { it.isNotEmpty() }
      .map { pattern ->
        Match(
          pattern = pattern,
          matched = ids.count { matches(pattern, it) },
          total = ids.size,
        )
      }

  /** [ids] with every entry matching [patterns] removed. An empty pattern list keeps everything. */
  fun retain(ids: List<String>, patterns: List<String>): List<String> {
    val cleaned = patterns.map { it.trim() }.filter { it.isNotEmpty() }
    if (cleaned.isEmpty()) return ids
    return ids.filterNot { id -> cleaned.any { matches(it, id) } }
  }

  private fun matches(pattern: String, id: String): Boolean =
    when {
      // Exact, not substring: a base id is a substring of its fan-out ids. See
      // `PreviewNameFilter.ANCHOR`.
      pattern.startsWith(ANCHOR) -> id == pattern.substring(ANCHOR.length)
      pattern.any { it == '*' || it == '?' } -> globToRegex(pattern).matches(id)
      else -> id == pattern || id.contains(pattern)
    }

  /** Mirror of `PreviewNameFilter.ANCHOR`. */
  const val ANCHOR: String = "="

  /** Anchored regex for a `*`/`?` glob; literal runs go through [Regex.escape]. */
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
