package ee.schimke.composeai.cli

/**
 * `bundle pack --exclude-preview-id` (issue #2966): the preview **ids** a pack must neither render
 * nor semantics-capture.
 *
 * Two consumers, one list, which is the point of collecting it here:
 * - the **render**, via `-PcomposePreview.idExclude` on the Gradle invocation (the
 *   `composePreviewRender` task's `--exclude-preview-id` convention);
 * - the **semantics capture**, which the CLI drives itself over the daemon — a Gradle property
 *   can't reach it, so filtering the render alone would leave that pass at full width and only half
 *   the deferral saving would land.
 *
 * Matching mirrors the plugin's `PreviewNameFilter.matchesId` — the authority, since it is what
 * actually skips the render: an anchored `*`/`?` glob when the pattern carries one, else equality
 * OR substring, case-sensitive. The two MUST agree, or a plain pattern would thin the render and
 * not the semantics pass (or the reverse). The logic is restated here rather than shared because
 * the plugin's `preview-discovery` module lives in a separate Gradle build the CLI does not depend
 * on — the same split the VS Code extension's `previewFilter.ts` lives with. Keep them in step:
 * both are covered by unit tests that spell the semantics out.
 */
internal object PackPreviewIdExclusions {

  /** The Gradle property `composePreviewRender` reads the same patterns from. */
  const val GRADLE_PROPERTY = "composePreview.idExclude"

  /** Env form of [GRADLE_PROPERTY] — how Gradle sources project properties from the environment. */
  const val ENV_VAR = "ORG_GRADLE_PROJECT_$GRADLE_PROPERTY"

  /**
   * The patterns for this invocation: every `--exclude-preview-id` value (repeatable, and each
   * value may itself be comma-separated), or — when the flag is absent entirely — the [ENV_VAR]
   * value.
   *
   * The env fallback exists because a caller can already thin the *render* by exporting [ENV_VAR]
   * alone (Gradle picks it up inside the pack's own invocation) and would otherwise silently keep
   * paying for the full semantics pass. An explicit flag wins outright rather than merging, so a
   * command line can narrow an inherited environment.
   */
  fun fromArgs(args: List<String>, env: (String) -> String? = System::getenv): List<String> =
    fileFromArgs(args)?.let(::linesOf) ?: patternsFor(args, "--exclude-preview-id", ENV_VAR, env)

  /** The Gradle property carrying a PATH to a newline-delimited exclusion list. */
  const val FILE_GRADLE_PROPERTY = "composePreview.idExcludeFile"

  /**
   * `--exclude-preview-id-file <path>`, the delimiter-free form of `--exclude-preview-id`.
   *
   * A preview id may contain a comma — `@Preview(widthDp = …, heightDp = …)` mints
   * `…CustomShapeRemoteButton_width=227dp, height=100dp, dpi=320` — so the comma-separated flag
   * cannot carry one. Joining and re-splitting shatters each id into fragments, and because a plain
   * pattern matches on **substring**, a fragment like `dpi=320` matches every preview in the
   * module: a list deferring 47 of 58 previews excluded all 58 and the render died with "nothing
   * would render". One pattern per line has no such ambiguity, because a line break cannot occur
   * inside an id.
   *
   * Returns the file, not its contents, because both consumers need it: the semantics capture reads
   * the lines here, and the render is handed the PATH (via [FILE_GRADLE_PROPERTY]) so nothing
   * re-joins them downstream.
   */
  fun fileFromArgs(args: List<String>): java.io.File? =
    args
      .flagValuesAll("--exclude-preview-id-file")
      .lastOrNull()
      ?.trim()
      ?.takeIf(String::isNotEmpty)
      ?.let { java.io.File(it) }

  /**
   * The ids in [file], one per line, blanks dropped. Never empty.
   *
   * An unreadable file throws rather than yielding an empty list, and so does a file that is
   * present but carries no ids. Both would otherwise become "no selection", and for BOTH flags that
   * reads as *everything* rather than nothing:
   *
   * * `--id-file` — an empty list leaves `-PbundlePreviewIds` unset, and `bundle pack` then packs
   *   the whole catalog. A generated shard file that came out empty would silently pack every
   *   preview instead of its slice, on every shard.
   * * `--exclude-preview-id-file` — an empty list excludes nothing, so the whole sheet renders.
   *
   * Either way the run reports success while having done the opposite of what the file asked. A
   * caller with legitimately nothing to select should not pass the flag; that is what the `[ -s …
   * ]` / non-zero-count guards on the calling side already express.
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
   * `bundle pack --id-file <path>`: the previews to PACK, one per line.
   *
   * The include-side twin of [fileFromArgs], and needed for the same reason. `--id` comma-splits
   * every value, so an id containing a comma — `@Preview(widthDp = …, heightDp = …)` mints
   * `…AppCardRemote_width=227dp,height=200dp,dpi=320` — is shattered into three.
   * `composePreviewRender` survives that because it matches ids by SUBSTRING, so the fragments
   * still select something; `composePreviewBundle` matches EXACTLY and fails with `preview id not
   * found: …AppCardRemote_width=227dp`, naming the first fragment. Escaping does not help:
   * `encodePreviewId` protects commas on the Gradle transport, but by then the id is already in
   * pieces.
   *
   * A line break cannot occur inside an id, so a file has no such ambiguity. When present it
   * REPLACES `--id` rather than adding to it.
   */
  fun idFileFromArgs(args: List<String>): java.io.File? =
    args.flagValuesAll("--id-file").lastOrNull()?.trim()?.takeIf(String::isNotEmpty)?.let {
      java.io.File(it)
    }

  /** The Gradle property carrying `@PreviewParameter` **row** label exclusions. */
  const val ROW_GRADLE_PROPERTY = "composePreview.rowExclude"

  /** Env form of [ROW_GRADLE_PROPERTY]. */
  const val ROW_ENV_VAR = "ORG_GRADLE_PROJECT_$ROW_GRADLE_PROPERTY"

  /**
   * `--exclude-preview-row` labels, same flag-then-env resolution as [fromArgs].
   *
   * These are forwarded to the render and nowhere else: unlike an id exclusion, a row exclusion
   * needs no matching skip in the semantics pass, because that capture is driven per *preview* over
   * the daemon — a parameterized preview yields one capture whatever its provider fans out to, so
   * there is no per-row cost there to save.
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

  /** [ids] with every entry matching [patterns] removed. An empty pattern list keeps everything. */
  fun retain(ids: List<String>, patterns: List<String>): List<String> {
    val cleaned = patterns.map { it.trim() }.filter { it.isNotEmpty() }
    if (cleaned.isEmpty()) return ids
    return ids.filterNot { id -> cleaned.any { matches(it, id) } }
  }

  private fun matches(pattern: String, id: String): Boolean =
    when {
      // Exact, deliberately not substring: a base id is a substring of its own fan-out members, so
      // `FilledButton_Light` would otherwise also drop `FilledButton_Light_VARIANT_off`. See
      // `PreviewNameFilter.ANCHOR`.
      pattern.startsWith(ANCHOR) -> id == pattern.substring(ANCHOR.length)
      pattern.any { it == '*' || it == '?' } -> globToRegex(pattern).matches(id)
      else -> id == pattern || id.contains(pattern)
    }

  /** Mirror of `PreviewNameFilter.ANCHOR`. */
  const val ANCHOR: String = "="

  /**
   * Anchored regex for a `*`/`?` glob, escaping every other character so a `.` in a
   * package-qualified id is a literal dot. Literal runs go through [Regex.escape] so a
   * metacharacter in an id can never leak into the pattern.
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
