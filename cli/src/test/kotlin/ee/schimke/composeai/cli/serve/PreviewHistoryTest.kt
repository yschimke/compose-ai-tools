package ee.schimke.composeai.cli.serve

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PreviewHistoryTest {

  private val a = "a".repeat(40)
  private val b = "b".repeat(40)
  private val c = "c".repeat(40)
  private val nullSha = "0".repeat(40)

  /**
   * Builds the exact bytes `git log --format=%x01%H%x1f%aI%x1f%s --raw --no-abbrev` emits, so the
   * parser is tested against the real wire shape rather than a convenient approximation. [entries]
   * is newest-first: each is a commit plus the paths it touched, as `path to newBlob`.
   */
  private fun gitLog(vararg entries: Pair<String, List<Pair<String, String>>>): String =
    buildString {
      entries.forEach { (subject, files) ->
        val sha = subject.hashCode().toUInt().toString(16).padStart(40, '0')
        append("\u0001").append(sha).append("\u001F").append("2026-08-06T10:00:00+00:00")
        append("\u001F").append(subject).append("\n\n")
        files.forEach { (path, blob) ->
          // Real git prints the pre-image too; the parser only reads column 4 (post-image).
          append(":100644 100644 ").append(c).append(' ').append(blob).append(" M\t").append(path)
          append('\n')
        }
      }
    }

  private fun timeline(log: String, path: String): PreviewHistory.Timeline =
    PreviewHistory.collapse(PreviewHistory.parseGitLog(log)).getValue(path)

  @Test
  fun `adjacent identical renders collapse into one version`() {
    val log =
      gitLog(
        "publish 4" to listOf("renders/m/Foo.png" to a),
        "publish 3" to listOf("renders/m/Foo.png" to a),
        "publish 2" to listOf("renders/m/Foo.png" to a),
        "publish 1" to listOf("renders/m/Foo.png" to b),
      )
    val t = timeline(log, "renders/m/Foo.png")

    assertEquals(4, t.observations, "raw commit count is preserved")
    assertEquals(2, t.versions.size, "three identical publishes are one version")
    assertEquals(3, t.versions.first().commits)
    assertEquals(0, t.flapCount)
    assertFalse(t.unstable)
  }

  @Test
  fun `a version spans from the commit that introduced it to the newest carrying it`() {
    val log =
      gitLog(
        "newest" to listOf("renders/m/Foo.png" to a),
        "middle" to listOf("renders/m/Foo.png" to a),
        "oldest" to listOf("renders/m/Foo.png" to a),
      )
    val v = timeline(log, "renders/m/Foo.png").versions.single()

    assertEquals("oldest", v.since.subject, "since is the run's oldest commit")
    assertEquals("newest", v.until.subject, "until is the run's newest commit")
  }

  @Test
  fun `a clean sequence of distinct renders never reads as unstable`() {
    val log =
      gitLog(
        "third" to listOf("renders/m/Foo.png" to c),
        "second" to listOf("renders/m/Foo.png" to b),
        "first" to listOf("renders/m/Foo.png" to a),
      )
    val t = timeline(log, "renders/m/Foo.png")

    assertEquals(3, t.versions.size)
    assertEquals(0, t.flapCount, "changing a lot is not flapping")
    assertFalse(t.unstable)
    assertEquals(t.versions, t.displayVersions, "a stable timeline is shown untrimmed")
  }

  @Test
  fun `a single revert is not enough to call a preview unstable`() {
    val log =
      gitLog(
        "reverted back to a" to listOf("renders/m/Foo.png" to a),
        "changed to b" to listOf("renders/m/Foo.png" to b),
        "first" to listOf("renders/m/Foo.png" to a),
      )
    val t = timeline(log, "renders/m/Foo.png")

    assertEquals(1, t.flapCount, "one return to earlier bytes")
    assertFalse(t.unstable, "a legitimate revert must not be flagged")
  }

  /** The real shape found on `compose-preview/main`: two renders alternating on every publish. */
  @Test
  fun `alternating renders flag as unstable and trim to their distinct states`() {
    val log =
      gitLog(
        *Array(8) { i -> "publish $i" to listOf("renders/m/Flaky.png" to if (i % 2 == 0) a else b) }
      )
    val t = timeline(log, "renders/m/Flaky.png")

    assertEquals(8, t.observations)
    assertEquals(8, t.versions.size, "every publish changed the bytes, so every run is length 1")
    assertEquals(2, t.distinctBlobs)
    assertEquals(6, t.flapCount)
    assertTrue(t.unstable)
    assertEquals(setOf(a, b), t.recurringBlobs)

    val trimmed = t.displayVersions
    assertEquals(2, trimmed.size, "225-run reality collapses to the states it flips between")
    assertEquals(listOf(a, b), trimmed.map { it.blob }, "newest state leads")
    assertTrue(trimmed.all { it.recurring })
    assertEquals(4, trimmed.first().occurrences, "four separate runs had these bytes")
    assertEquals(8, trimmed.sumOf { it.commits }, "trimming loses no commits")
  }

  @Test
  fun `a trimmed state spans its whole period in play`() {
    val log =
      gitLog(
        *Array(4) { i -> "publish $i" to listOf("renders/m/Flaky.png" to if (i % 2 == 0) a else b) }
      )
    val newest = timeline(log, "renders/m/Flaky.png").displayVersions.first()

    assertEquals("publish 0", newest.until.subject, "until is the most recent appearance")
    assertEquals("publish 2", newest.since.subject, "since reaches back to the oldest run")
  }

  @Test
  fun `history stops at the most recent deletion`() {
    val log =
      gitLog(
        "re-added" to listOf("renders/m/Foo.png" to c),
        "deleted" to listOf("renders/m/Foo.png" to nullSha),
        "older a" to listOf("renders/m/Foo.png" to a),
        "older b" to listOf("renders/m/Foo.png" to b),
      )
    val t = timeline(log, "renders/m/Foo.png")

    assertEquals(1, t.observations, "history covers only the current incarnation")
    assertEquals(listOf(c), t.versions.map { it.blob })
  }

  @Test
  fun `delete and re-add churn does not read as flapping`() {
    // Without deletion truncation the null sha alternates with content and scores flaps, which
    // would mark every renamed or removed-and-restored preview as non-deterministic.
    val log =
      gitLog(
        "re-added" to listOf("renders/m/Foo.png" to a),
        "deleted" to listOf("renders/m/Foo.png" to nullSha),
        "added" to listOf("renders/m/Foo.png" to a),
        "deleted again" to listOf("renders/m/Foo.png" to nullSha),
        "first" to listOf("renders/m/Foo.png" to a),
      )
    val t = timeline(log, "renders/m/Foo.png")

    assertEquals(0, t.flapCount)
    assertFalse(t.unstable)
  }

  @Test
  fun `one commit touching many renders splits per path`() {
    val log =
      gitLog(
        "publish 2" to listOf("renders/m/A.png" to a, "renders/m/B.png" to b),
        "publish 1" to listOf("renders/m/A.png" to a, "renders/m/B.png" to c),
      )
    val all = PreviewHistory.collapse(PreviewHistory.parseGitLog(log))

    assertEquals(setOf("renders/m/A.png", "renders/m/B.png"), all.keys)
    assertEquals(1, all.getValue("renders/m/A.png").versions.size, "unchanged across both commits")
    assertEquals(2, all.getValue("renders/m/B.png").versions.size)
  }

  @Test
  fun `source sha is recovered from either publisher's subject`() {
    val log =
      gitLog(
        "Update preview baselines from 27ea28c1" to listOf("renders/m/Foo.png" to a),
        "chore(design-artifacts): regenerate compose-m3 catalog (2026-08-06, 1a2b3c4d)" to
          listOf("renders/m/Foo.png" to b),
        "a subject with no sha at all" to listOf("renders/m/Foo.png" to c),
      )
    val versions = timeline(log, "renders/m/Foo.png").versions

    assertEquals("27ea28c1", versions[0].until.sourceSha, "compose-preview wording")
    assertEquals("1a2b3c4d", versions[1].until.sourceSha, "design-artifacts wording")
    assertEquals(null, versions[2].until.sourceSha, "absent rather than guessed")
  }

  @Test
  fun `paths containing spaces and colons survive the parse`() {
    // Module segments are Gradle paths (`samples:wear`) and preview names can carry spaces.
    val path = "renders/samples:wear/PreviewsKt.Foo_Devices_-_Large Round.png"
    val log = gitLog("publish" to listOf(path to a))

    assertEquals(setOf(path), PreviewHistory.parseGitLog(log).keys)
  }

  @Test
  fun `git log is asked not to quote non-ASCII paths`() {
    val args = PreviewHistory.logArgs("compose-preview/main", "renders")

    assertEquals(listOf("-c", "core.quotePath=false"), args.take(2), "must precede the subcommand")
  }

  /**
   * The escaped form is exactly what `git log --raw` emitted for `renders/café/Foo—dash.png` with
   * git's default `core.quotePath`, captured from a real repo — not hand-written.
   */
  @Test
  fun `octal-escaped non-ASCII paths decode back to UTF-8`() {
    val quoted = """"renders/caf\303\251/Foo\342\200\224dash.png""""

    assertEquals("renders/café/Foo—dash.png", PreviewHistory.unquotePath(quoted))
  }

  @Test
  fun `a quoted path still keys history under its real name`() {
    // core.quotePath=false does not cover a path containing a quote or backslash, so the parser
    // must decode rather than rely on the config alone.
    val log = gitLog("publish" to listOf(""""renders/m/Say \"hi\".png"""" to a))

    assertEquals(setOf("""renders/m/Say "hi".png"""), PreviewHistory.parseGitLog(log).keys)
  }

  @Test
  fun `control-character and backslash escapes decode`() {
    assertEquals("renders/m/a\tb.png", PreviewHistory.unquotePath(""""renders/m/a\tb.png""""))
    assertEquals("""renders/m/a\b.png""", PreviewHistory.unquotePath(""""renders/m/a\\b.png""""))
  }

  @Test
  fun `an unquoted path is passed through untouched`() {
    val plain = "renders/samples:wear/Foo_Devices_-_Large Round.png"

    assertEquals(
      plain,
      PreviewHistory.unquotePath(plain),
      "no round-trip damage in the common case",
    )
  }

  @Test
  fun `malformed lines are skipped rather than failing the whole history`() {
    val good = gitLog("publish" to listOf("renders/m/Foo.png" to a))
    val log = good + ":not-a-real-raw-line\n" + "\u0001truncated-header\n" + "stray text\n"

    assertEquals(setOf("renders/m/Foo.png"), PreviewHistory.parseGitLog(log).keys)
  }

  @Test
  fun `an unreadable ref yields no history instead of throwing`() {
    val failing = GitRunner { _, _ -> GitResult(exitCode = 128, stdout = "fatal: bad revision") }

    val history = PreviewHistory.read(File("."), "compose-preview/main", git = failing)

    assertTrue(history.isEmpty(), "a clone without the delivery branch degrades to no timeline")
  }

  @Test
  fun `read passes the ref and pathspec through to git`() {
    var seen: List<String> = emptyList()
    val recording = GitRunner { _, args ->
      seen = args
      GitResult(0, gitLog("publish" to listOf("renders/m/Foo.png" to a)))
    }

    val history = PreviewHistory.read(File("."), "compose-preview/main", "renders", recording)

    assertEquals(PreviewHistory.logArgs("compose-preview/main", "renders"), seen)
    assertTrue(seen.contains("--no-abbrev"), "blob shas must not be abbreviated")
    assertEquals(1, history.size)
  }
}
