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
  fun fromArgs(args: List<String>, env: (String) -> String? = System::getenv): List<String> {
    val flagValues = args.flagValuesAll("--exclude-preview-id")
    val raw = if (flagValues.isNotEmpty()) flagValues else listOfNotNull(env(ENV_VAR))
    return raw.flatMap { it.split(',') }.map { it.trim() }.filter { it.isNotEmpty() }
  }

  /** [ids] with every entry matching [patterns] removed. An empty pattern list keeps everything. */
  fun retain(ids: List<String>, patterns: List<String>): List<String> {
    val cleaned = patterns.map { it.trim() }.filter { it.isNotEmpty() }
    if (cleaned.isEmpty()) return ids
    return ids.filterNot { id -> cleaned.any { matches(it, id) } }
  }

  private fun matches(pattern: String, id: String): Boolean =
    if (pattern.any { it == '*' || it == '?' }) globToRegex(pattern).matches(id)
    else id == pattern || id.contains(pattern)

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
