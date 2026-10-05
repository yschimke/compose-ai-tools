package ee.schimke.composeai.cli.serve

import java.io.File

/**
 * Per-preview render history, read off a baseline delivery branch (`compose-preview/main`,
 * `design-artifacts/<system>`) whose commits are full snapshots of the rendered output.
 *
 * Adjacent commits with identical render bytes collapse into one [Version], and a preview that
 * keeps returning to earlier bytes is [Timeline.unstable]; trimming those to one entry per state is
 * where the real reduction comes from (on `compose-preview/main`, 5 previews accounted for a 40%
 * cut).
 *
 * Pure apart from [read], so the collapse rules are unit-testable without a repo.
 */
public object PreviewHistory {

  /**
   * The `git log` arguments this parser expects, over [pathspec] on [ref].
   *
   * `--raw --no-abbrev` yields each touched file's post-image blob sha, so one invocation covers
   * the whole branch. `%x01`/`%x1f` delimit header fields because they can't appear in a sha, date
   * or path. `core.quotePath=false` keeps non-ASCII paths (em-dashes do occur) unquoted;
   * [unquotePath] handles what is still quoted.
   */
  public fun logArgs(ref: String, pathspec: String): List<String> =
    listOf(
      "-c",
      "core.quotePath=false",
      "log",
      "--format=%x01%H%x1f%aI%x1f%s",
      "--raw",
      "--no-abbrev",
      "--no-renames",
      ref,
      "--",
      pathspec,
    )

  /**
   * Decode a git C-quoted pathname (still used for quotes, backslashes and control characters), or
   * return [raw] unchanged when unquoted. Octal escapes are bytes, so they are buffered and decoded
   * as UTF-8 at the end.
   */
  internal fun unquotePath(raw: String): String {
    if (raw.length < 2 || !raw.startsWith('"') || !raw.endsWith('"')) return raw
    val body = raw.substring(1, raw.length - 1)
    val bytes = java.io.ByteArrayOutputStream(body.length)
    var i = 0
    while (i < body.length) {
      val ch = body[i]
      if (ch != '\\') {
        bytes.write(ch.toString().toByteArray(Charsets.UTF_8))
        i++
        continue
      }
      i++
      if (i >= body.length) break
      when (val esc = body[i]) {
        'a' -> bytes.write(0x07).also { i++ }
        'b' -> bytes.write(0x08).also { i++ }
        'f' -> bytes.write(0x0C).also { i++ }
        'n' -> bytes.write(0x0A).also { i++ }
        'r' -> bytes.write(0x0D).also { i++ }
        't' -> bytes.write(0x09).also { i++ }
        'v' -> bytes.write(0x0B).also { i++ }
        '\\',
        '"' -> bytes.write(esc.code).also { i++ }
        in '0'..'7' -> {
          var value = 0
          var digits = 0
          while (i < body.length && digits < 3 && body[i] in '0'..'7') {
            value = value * 8 + (body[i] - '0')
            i++
            digits++
          }
          bytes.write(value and 0xFF)
        }
        // Unknown escape: keep the escaped character verbatim rather than dropping it.
        else -> bytes.write(esc.toString().toByteArray(Charsets.UTF_8)).also { i++ }
      }
    }
    return String(bytes.toByteArray(), Charsets.UTF_8)
  }

  /** One commit on the delivery branch in which a given render had particular bytes. */
  public data class Observation(
    /** Delivery-branch commit sha. */
    val commit: String,
    /** Author date, ISO-8601, as git emitted it. */
    val date: String,
    /** The commit subject, kept so [sourceSha] can be re-derived and for display. */
    val subject: String,
    /** Content sha of the render *at* this commit — the post-image blob. */
    val blob: String,
    /** True when this commit deleted the render (post-image is the all-zero sha). */
    val deleted: Boolean,
  ) {
    /**
     * The source commit this snapshot was rendered from, parsed from the publish subject; null when
     * the subject is unstamped.
     */
    val sourceSha: String?
      get() = SOURCE_SHA.find(subject)?.groupValues?.get(1)
  }

  /**
   * A maximal run of consecutive commits whose render bytes were identical — one *visible* version
   * of a preview, however many publishes it survived.
   */
  public data class Version(
    /** Content sha of the render. */
    val blob: String,
    /** Path on the delivery branch, e.g. `renders/samples:wear/Foo.png`. */
    val path: String,
    /** The commit that introduced these bytes (oldest in the run). */
    val since: Observation,
    /** The newest commit still carrying these bytes. */
    val until: Observation,
    /** How many publishes carried them. */
    val commits: Int,
    /** How many separate runs had these bytes; > 1 only on a trimmed unstable timeline. */
    val occurrences: Int = 1,
  ) {
    /** True when these bytes recurred — the state was returned to after changing away. */
    val recurring: Boolean
      get() = occurrences > 1
  }

  /** The full history of one render path, newest version first. */
  public data class Timeline(
    val path: String,
    /** Newest first. */
    val versions: List<Version>,
    /** Raw commits touching this path, before collapsing. */
    val observations: Int,
  ) {
    /** Distinct render bytes ever seen. */
    val distinctBlobs: Int
      get() = versions.map { it.blob }.toSet().size

    /**
     * How many times the render returned to bytes it had already moved away from (`runs -
     * distinctBlobs`). A legitimate revert scores 1, which is why [unstable] needs more.
     */
    val flapCount: Int
      get() = versions.size - distinctBlobs

    /**
     * True when the render keeps reverting to earlier bytes: the signature of non-determinism, not
     * of a preview that legitimately changed a lot.
     */
    val unstable: Boolean
      get() = flapCount >= UNSTABLE_FLAP_THRESHOLD

    /** The states this preview keeps flipping between: bytes that occupy more than one run. */
    val recurringBlobs: Set<String>
      get() = versions.groupingBy { it.blob }.eachCount().filterValues { it > 1 }.keys

    /**
     * The timeline to display: [versions] when stable, otherwise one entry per distinct state (see
     * [trimRecurring]). The raw counts stay on [observations] and [flapCount].
     */
    val displayVersions: List<Version>
      get() = if (!unstable) versions else trimRecurring(versions)
  }

  /**
   * Collapse an unstable timeline's runs to one [Version] per distinct blob, ordered by most recent
   * appearance and spanning every run of that state.
   */
  private fun trimRecurring(versions: List<Version>): List<Version> {
    val byBlob = LinkedHashMap<String, MutableList<Version>>()
    versions.forEach { byBlob.getOrPut(it.blob) { mutableListOf() }.add(it) }
    return byBlob.values.map { runs ->
      // `versions` is newest-first, so runs.first() is the most recent appearance.
      runs
        .first()
        .copy(
          since = runs.last().since,
          commits = runs.sumOf { it.commits },
          occurrences = runs.size,
        )
    }
  }

  /**
   * Collapse raw newest-first [observations] per path into [Timeline]s. A deletion ends a path's
   * history so a re-added preview doesn't score a flap.
   */
  public fun collapse(observations: Map<String, List<Observation>>): Map<String, Timeline> =
    observations.mapValues { (path, rows) ->
      collapseOne(path, rows)
    }

  private fun collapseOne(path: String, rows: List<Observation>): Timeline {
    val live = rows.takeWhile { !it.deleted }
    val versions = mutableListOf<Version>()
    var run = mutableListOf<Observation>()
    for (row in live) {
      if (run.isNotEmpty() && run.first().blob != row.blob) {
        versions += toVersion(path, run)
        run = mutableListOf()
      }
      run += row
    }
    if (run.isNotEmpty()) versions += toVersion(path, run)
    return Timeline(path = path, versions = versions, observations = live.size)
  }

  /** [run] is newest-first, so its last entry is the commit that introduced the bytes. */
  private fun toVersion(path: String, run: List<Observation>) =
    Version(
      blob = run.first().blob,
      path = path,
      since = run.last(),
      until = run.first(),
      commits = run.size,
    )

  /**
   * Parse [logArgs] output into newest-first observations per path. Lines that don't fit are
   * skipped rather than failing the whole history.
   */
  public fun parseGitLog(output: String): Map<String, List<Observation>> {
    val byPath = LinkedHashMap<String, MutableList<Observation>>()
    var commit: String? = null
    var date = ""
    var subject = ""
    for (line in output.lineSequence()) {
      if (line.startsWith(HEADER_MARK)) {
        val fields = line.substring(1).split(FIELD_SEP)
        if (fields.size >= 3) {
          commit = fields[0]
          date = fields[1]
          subject = fields[2]
        } else {
          commit = null
        }
        continue
      }
      if (!line.startsWith(':') || commit == null) continue
      val tab = line.indexOf('\t')
      if (tab < 0) continue
      // ":<srcmode> <dstmode> <srcsha> <dstsha> <status>\t<path>" — split() over the whole
      // whitespace run so a status like "M100" or a mode column width change can't shift indices.
      val meta = line.substring(1, tab).split(' ').filter { it.isNotEmpty() }
      if (meta.size < 5) continue
      val blob = meta[3]
      val path = unquotePath(line.substring(tab + 1))
      byPath
        .getOrPut(path) { mutableListOf() }
        .add(
          Observation(
            commit = commit,
            date = date,
            subject = subject,
            blob = blob,
            deleted = blob == NULL_SHA,
          )
        )
    }
    return byPath
  }

  /**
   * Read the history of [pathspec] on [ref] from [repoRoot]; empty when the ref is absent, since
   * history is additive.
   */
  public fun read(
    repoRoot: File,
    ref: String,
    pathspec: String = "renders",
    git: GitRunner = GitWorktrees.RealGitRunner,
  ): Map<String, Timeline> {
    val result = git.run(repoRoot, logArgs(ref, pathspec))
    if (!result.ok) return emptyMap()
    return collapse(parseGitLog(result.stdout))
  }

  /** Two returns to previously-seen bytes before a preview is called unstable. See [Timeline]. */
  public const val UNSTABLE_FLAP_THRESHOLD: Int = 2

  private const val HEADER_MARK = "\u0001"
  private const val FIELD_SEP = "\u001F"
  private const val NULL_SHA = "0000000000000000000000000000000000000000"

  /** The source sha in a publish subject: `…baselines from <sha>` or the `(<date>, <sha>)` tail. */
  private val SOURCE_SHA = Regex("(?:from|,)\\s+([0-9a-f]{7,40})\\b")
}
