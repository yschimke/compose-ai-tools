package ee.schimke.composeai.cli.serve

import java.io.File

/**
 * Per-preview render history, read off a baseline delivery branch (`compose-preview/main`,
 * `design-artifacts/<system>`) whose commits are full snapshots of the rendered output.
 *
 * The branches carry one commit per publish, so the raw commit list badly overstates how much a
 * preview actually changed: an unstable preview re-renders differently on every run and therefore
 * appears in *every* commit. The whole point of this file is the collapse — adjacent commits whose
 * render bytes are identical are one [Version], and a preview that keeps returning to a render it
 * had already moved away from is [Timeline.unstable] rather than a preview with hundreds of
 * changes.
 *
 * Measured on `compose-preview/main` at 770 commits — 811 render paths, 1375 observations:
 * - the run-collapse is a **no-op in practice**, because `git log --raw -- <path>` only reports
 *   commits in which the file actually changed, so consecutive observations for one path never
 *   share a blob. It is kept for correctness (a merge or mode-only entry can repeat a blob, and it
 *   makes [Version.commits] meaningful), not for the reduction;
 * - trimming the unstable ones ([Timeline.displayVersions]) is where the reduction comes from: 1375
 *   entries → 826, a 40% cut, from just **5** previews. The worst is 226 runs of 4 renders.
 *
 * That concentration is the argument for trimming at all: a handful of non-deterministic previews
 * would otherwise dominate every timeline in the manifest, and the two wear renders alone account
 * for over 400 of the entries removed.
 *
 * Everything here is pure — [parseGitLog] takes the text of one `git log` invocation and the git
 * call itself is isolated in [read] — so the collapse rules are unit-testable without a repo.
 */
object PreviewHistory {

  /**
   * The `git log` arguments this parser expects, over [pathspec] on [ref].
   *
   * `--raw --no-abbrev` is what makes a whole branch affordable: the raw diff line already carries
   * the post-image blob sha for every touched file, so the render bytes at each commit are known
   * without a `rev-parse <commit>:<path>` subprocess per file per commit. One invocation covers the
   * entire branch — measured at ~1.6s for 770 commits over 537 files.
   *
   * `%x01` prefixes a header line and `%x1f` separates its fields, because both are control
   * characters git will never emit inside a sha, an ISO date, or a path, while a commit *subject*
   * is free-form and could contain anything more printable.
   *
   * `-c core.quotePath=false` matters more than it looks: git's default is to C-quote and
   * octal-escape any non-ASCII path, so a preview whose name carries an em-dash — which this repo
   * really does produce, see the UTF-8 note in `design-artifacts-reusable.yml` — would key its
   * history under `"renders/…Foo\342\200\224dash.png"` and never join back to the preview it
   * belongs to. Turning quoting off is not sufficient on its own (see [unquotePath]), but it keeps
   * the common case exact rather than round-tripping every path through an unescaper.
   */
  fun logArgs(ref: String, pathspec: String): List<String> =
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
   * Decode a git-quoted pathname back to its real bytes, or return [raw] unchanged when it isn't
   * quoted.
   *
   * [logArgs] already asks git not to quote non-ASCII, but `core.quotePath=false` only covers that
   * case: a path containing a double quote, a backslash, or a control character is still wrapped
   * and escaped. Rather than leave a class of paths silently mis-keyed, decode the full C-style
   * syntax — `\\`, `\"`, the `\a \b \f \n \r \t \v` singles, and `\nnn` octal bytes.
   *
   * Octal escapes are per **byte**, so they're accumulated into a byte buffer and decoded as UTF-8
   * at the end; decoding them one-by-one as characters would mangle every multi-byte codepoint.
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
  data class Observation(
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
     * The source commit this snapshot was rendered from, recovered from the publish subject.
     *
     * Both publishers stamp it, in their own wording — `Update preview baselines from <sha>` and
     * `chore(design-artifacts): regenerate <system> catalog (<date>, <sha>)` — so the join back to
     * the change that moved a pixel is a subject parse rather than a second data source. Null when
     * the subject predates the stamping or doesn't match.
     */
    val sourceSha: String?
      get() = SOURCE_SHA.find(subject)?.groupValues?.get(1)
  }

  /**
   * A maximal run of consecutive commits whose render bytes were identical — one *visible* version
   * of a preview, however many publishes it survived.
   */
  data class Version(
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
    /**
     * How many separate runs had these bytes. Always 1 for an untrimmed run; on a trimmed unstable
     * timeline it's how many times the preview came back to this state, which is the honest way to
     * show a recurring state once without pretending it only happened once.
     */
    val occurrences: Int = 1,
  ) {
    /** True when these bytes recurred — the state was returned to after changing away. */
    val recurring: Boolean
      get() = occurrences > 1
  }

  /** The full history of one render path, newest version first. */
  data class Timeline(
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
     * How many times the render *returned* to bytes it had already moved away from.
     *
     * `runs - distinctBlobs` counts exactly the re-appearances: a preview that changes cleanly N
     * times has N runs and N distinct blobs and so scores 0, while one alternating A/B/A/B scores
     * one per flip. A legitimate revert scores 1, which is why [unstable] needs more than one.
     */
    val flapCount: Int
      get() = versions.size - distinctBlobs

    /**
     * True when the render keeps reverting to earlier bytes — the signature of a non-deterministic
     * preview (clock, animation frame, randomness), not of a preview that changed a lot.
     *
     * Deliberately not a "changed more than N times" threshold: a genuinely churny preview is
     * usually fine, whereas two returns to a previous render is already something no deterministic
     * pipeline should produce by accident.
     */
    val unstable: Boolean
      get() = flapCount >= UNSTABLE_FLAP_THRESHOLD

    /**
     * The states this preview keeps flipping between — bytes that occupy more than one run.
     *
     * This is the set a reviewer actually wants named: for the worst offender on
     * `compose-preview/main` it's 4 renders that 226 commits shuffle between, not 226 changes.
     */
    val recurringBlobs: Set<String>
      get() = versions.groupingBy { it.blob }.eachCount().filterValues { it > 1 }.keys

    /**
     * The timeline to actually display: unchanged when the preview is stable, and **deduplicated to
     * one entry per distinct state** when it isn't.
     *
     * An unstable preview's run list is almost entirely noise — the same handful of renders
     * alternating — so showing every run buries the real content under hundreds of identical
     * thumbnails. Trimming keeps each distinct state once (widest span, summed commits, with
     * [Version.occurrences] recording how many runs it had) so the timeline shows *what* the
     * preview flips between instead of *how often* it flipped. [observations], [flapCount] and
     * [unstable] still carry the raw counts, so nothing is hidden — only collapsed.
     */
    val displayVersions: List<Version>
      get() = if (!unstable) versions else trimRecurring(versions)
  }

  /**
   * Collapse an unstable timeline's runs to one [Version] per distinct blob, newest first.
   *
   * Ordering follows each state's most recent appearance, so the newest render still leads. `since`
   * takes the oldest run's introducing commit and `until` the newest run's last one, making the
   * entry span the whole period the state was in play.
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
   * Collapse raw newest-first [observations] per path into [Timeline]s.
   *
   * Deletions terminate a path's history rather than becoming a version: a preview that was removed
   * and later re-added should not read as having "returned" to its old bytes and so score a flap.
   */
  fun collapse(observations: Map<String, List<Observation>>): Map<String, Timeline> =
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
   * Parse the output of a [logArgs] invocation into newest-first observations per path.
   *
   * Tolerant by design: this reads a branch that CI writes and that predates the parser, so a line
   * that doesn't fit the expected shape is skipped rather than failing the whole history. A raw
   * line arriving before any header (impossible from git, possible from a truncated capture) is
   * dropped for the same reason.
   */
  fun parseGitLog(output: String): Map<String, List<Observation>> {
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
   * Read the history of [pathspec] on [ref] from the repo at [repoRoot]. Returns an empty map when
   * the ref is absent (a clone that never fetched the delivery branch) rather than throwing — the
   * history surface is additive, and a viewer without it should degrade to no timeline, not fail.
   */
  fun read(
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
  const val UNSTABLE_FLAP_THRESHOLD: Int = 2

  private const val HEADER_MARK = "\u0001"
  private const val FIELD_SEP = "\u001F"
  private const val NULL_SHA = "0000000000000000000000000000000000000000"

  /**
   * The source sha in a publish subject: `…baselines from <sha>` (compose-preview) or the `(<date>,
   * <sha>)` tail (design-artifacts). Both are short shas today; accept 7–40 hex so a future
   * full-sha stamp still parses.
   */
  private val SOURCE_SHA = Regex("(?:from|,)\\s+([0-9a-f]{7,40})\\b")
}
