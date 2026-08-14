package ee.schimke.composeai.cli

import ee.schimke.composeai.cli.serve.PreviewHistoryManifest
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class HistoryManifestCommandTest {

  /** Stands in for `exitProcess` so a refuse-to-publish path fails the test, not the JVM. */
  private class CommandExit(val code: Int) : RuntimeException("exit $code")

  private val baselines =
    """
    {
      "samples:wear/com.example.PreviewsKt.Foo_Large Round": {
        "module": "samples:wear",
        "renderBasename": "Foo_Large_Round.png"
      }
    }
    """
      .trimIndent()

  /** A throwaway git repo shaped like a delivery branch, so the command's git calls are real. */
  private fun deliveryRepo(vararg commits: Pair<String, String>): File {
    val dir = createTempDirectory("history-manifest-test").toFile()
    fun git(vararg args: String) {
      val p = ProcessBuilder(listOf("git") + args).directory(dir).redirectErrorStream(true).start()
      check(p.waitFor() == 0) { "git ${args.joinToString(" ")} failed" }
    }
    git("init", "--quiet", "--initial-branch", "main")
    git("config", "user.email", "test@example.com")
    git("config", "user.name", "Test")
    File(dir, "baselines.json").writeText(baselines)
    File(dir, "renders/samples:wear").mkdirs()
    commits.forEach { (subject, bytes) ->
      File(dir, "renders/samples:wear/Foo_Large_Round.png").writeText(bytes)
      git("add", "-A")
      git("commit", "--quiet", "-m", subject)
    }
    return dir
  }

  private fun run(repo: File, vararg extra: String): Pair<List<String>, List<String>> {
    val out = mutableListOf<String>()
    val err = mutableListOf<String>()
    HistoryManifestCommand(
        args = listOf("--repo", repo.path, "--branch", "main") + extra,
        workingDir = repo,
        stdout = { out += it },
        stderr = { err += it },
        exit = { throw CommandExit(it) },
      )
      .run()
    return out to err
  }

  @Test
  fun `writes a manifest joined against the branch's own baselines`() {
    val repo = deliveryRepo("Update preview baselines from 27ea28c1" to "v1")
    val output = File(repo, "history.json")

    val (out, err) = run(repo, "--output", output.path)

    assertTrue(err.isEmpty(), "unexpected stderr: $err")
    val manifest = assertNotNull(PreviewHistoryManifest.decode(output.readText()))
    val entry = manifest.previews.getValue("samples:wear/com.example.PreviewsKt.Foo_Large Round")
    assertEquals("renders/samples:wear/Foo_Large_Round.png", entry.path)
    assertEquals("27ea28c1", entry.versions.single().sourceSha)
    assertContains(out.joinToString("\n"), "1 previews")
  }

  @Test
  fun `generatedFrom is the resolved sha, not the ref name`() {
    // A viewer compares this against the newest render commit to tell whether the manifest is
    // stale; a ref name would always look current.
    val repo = deliveryRepo("publish" to "v1")
    val output = File(repo, "history.json")

    run(repo, "--output", output.path)

    val manifest = assertNotNull(PreviewHistoryManifest.decode(output.readText()))
    assertTrue(manifest.generatedFrom.matches(Regex("[0-9a-f]{40}")), manifest.generatedFrom)
  }

  @Test
  fun `a history-only commit does not change the manifest`() {
    // The manifest ships in its own commit, which moves the branch tip. Anchoring generatedFrom to
    // the tip would make every regeneration differ from the published file, so each baseline run
    // would append another history commit forever. Regenerating after a history-only commit must
    // be byte-identical so the push can skip.
    val repo = deliveryRepo("Update preview baselines from aaaaaaaa" to "v1")
    val first = File(repo, "history.json")
    run(repo, "--output", first.path)
    val firstText = first.readText()

    // Commit the manifest, exactly as the publish step does — tip moves, renders do not.
    ProcessBuilder("git", "add", "-A").directory(repo).start().waitFor()
    ProcessBuilder("git", "commit", "--quiet", "-m", "Update preview history from aaaaaaaa")
      .directory(repo)
      .start()
      .waitFor()

    val second = File(repo, "regenerated.json")
    run(repo, "--output", second.path)

    assertEquals(firstText, second.readText(), "regeneration must be byte-identical, or CI churns")
  }

  @Test
  fun `generatedFrom tracks the newest render commit, not the branch tip`() {
    val repo = deliveryRepo("Update preview baselines from aaaaaaaa" to "v1")
    val renderTip =
      ProcessBuilder("git", "rev-parse", "HEAD")
        .directory(repo)
        .start()
        .inputStream
        .bufferedReader()
        .readText()
        .trim()
    File(repo, "unrelated.txt").writeText("not a render")
    ProcessBuilder("git", "add", "-A").directory(repo).start().waitFor()
    ProcessBuilder("git", "commit", "--quiet", "-m", "history-only commit")
      .directory(repo)
      .start()
      .waitFor()
    val output = File(repo, "history.json")

    run(repo, "--output", output.path)

    val manifest = assertNotNull(PreviewHistoryManifest.decode(output.readText()))
    assertEquals(renderTip, manifest.generatedFrom)
  }

  @Test
  fun `successive publishes become successive versions, newest first`() {
    // End-to-end over real `git log` output rather than a synthetic log: collapse semantics are
    // pinned by PreviewHistoryTest / PreviewHistoryManifestTest, so what this covers is that the
    // command drives real git correctly and orders the result the way a viewer expects.
    val repo = deliveryRepo("Update preview baselines from aaaaaaaa" to "v1")
    File(repo, "renders/samples:wear/Foo_Large_Round.png").writeText("v2")
    ProcessBuilder("git", "add", "-A").directory(repo).start().waitFor()
    ProcessBuilder("git", "commit", "--quiet", "-m", "Update preview baselines from bbbbbbbb")
      .directory(repo)
      .start()
      .waitFor()
    val output = File(repo, "history.json")

    run(repo, "--output", output.path)

    val entry =
      assertNotNull(PreviewHistoryManifest.decode(output.readText())).previews.values.single()
    assertEquals(2, entry.versions.size)
    assertEquals(2, entry.observations)
    assertEquals(
      listOf("bbbbbbbb", "aaaaaaaa"),
      entry.versions.map { it.sourceSha },
      "newest publish first",
    )
  }

  @Test
  fun `an explicit baselines file overrides the branch copy`() {
    val repo = deliveryRepo("publish" to "v1")
    val override = File(repo, "other-baselines.json")
    override.writeText(
      """{"renamed/id": {"module": "samples:wear", "renderBasename": "Foo_Large_Round.png"}}"""
    )
    val output = File(repo, "history.json")

    run(repo, "--output", output.path, "--baselines", override.path)

    val manifest = assertNotNull(PreviewHistoryManifest.decode(output.readText()))
    assertEquals(setOf("renamed/id"), manifest.previews.keys)
  }

  @Test
  fun `a missing explicit baselines file is an error, not an empty manifest`() {
    val repo = deliveryRepo("publish" to "v1")
    val output = File(repo, "history.json")

    val error = runCatching {
      run(repo, "--output", output.path, "--baselines", "/nope/missing.json")
    }.exceptionOrNull()

    assertEquals(1, assertIs<CommandExit>(error).code)
    assertFalse(output.exists(), "must not write a manifest it could not join")
  }

  @Test
  fun `baselines with no usable entries refuses to write`() {
    // Writing an empty manifest here would publish a file that reads as "this branch has no
    // history at all", which is worse than failing the publish step.
    val repo = deliveryRepo("publish" to "v1")
    val empty = File(repo, "empty.json")
    empty.writeText("{}")
    val output = File(repo, "history.json")

    val error = runCatching {
      run(repo, "--output", output.path, "--baselines", empty.path)
    }.exceptionOrNull()

    assertEquals(1, assertIs<CommandExit>(error).code)
    assertFalse(output.exists())
  }

  @Test
  fun `an unresolvable ref is an error, not an empty manifest`() {
    // PreviewHistory.read returns an empty map when git exits non-zero, so without an up-front ref
    // check a typo'd or unfetched ref looks exactly like "this branch has no history" — and the
    // publish step would then overwrite a good history.json with an empty one.
    val repo = deliveryRepo("publish" to "v1")
    val output = File(repo, "history.json")

    val error = runCatching {
      HistoryManifestCommand(
          args = listOf("--repo", repo.path, "--branch", "no-such-ref", "--output", output.path),
          workingDir = repo,
          stdout = {},
          stderr = {},
          exit = { throw CommandExit(it) },
        )
        .run()
    }
      .exceptionOrNull()

    assertEquals(1, assertIs<CommandExit>(error).code)
    assertFalse(output.exists(), "must not overwrite a published manifest with an empty one")
  }

  @Test
  fun `a resolvable ref with no render history refuses rather than emptying the manifest`() {
    // Baselines list previews but the log shows no renders: the read failed or the pathspec found
    // nothing. Either way it is never a legitimately empty branch.
    val repo = createTempDirectory("history-manifest-empty").toFile()
    fun git(vararg a: String) {
      ProcessBuilder(listOf("git") + a).directory(repo).redirectErrorStream(true).start().waitFor()
    }
    git("init", "--quiet", "--initial-branch", "main")
    git("config", "user.email", "test@example.com")
    git("config", "user.name", "Test")
    File(repo, "baselines.json").writeText(baselines)
    git("add", "-A")
    git("commit", "--quiet", "-m", "baselines only, no renders")
    val output = File(repo, "history.json")

    val err = mutableListOf<String>()
    val error = runCatching {
      HistoryManifestCommand(
          args = listOf("--repo", repo.path, "--branch", "main", "--output", output.path),
          workingDir = repo,
          stdout = {},
          stderr = { err += it },
          exit = { throw CommandExit(it) },
        )
        .run()
    }
      .exceptionOrNull()

    assertEquals(1, assertIs<CommandExit>(error).code)
    assertFalse(output.exists())
    assertContains(err.joinToString("\n"), "refusing to write an empty manifest")
  }

  @Test
  fun `--help prints usage and names the sibling command it is not`() {
    val out = mutableListOf<String>()
    HistoryManifestCommand(
        args = listOf("--help"),
        stdout = { out += it },
        stderr = {},
        exit = { throw CommandExit(it) },
      )
      .run()

    val text = out.joinToString("\n")
    assertContains(text, "history-manifest")
    assertContains(text, "compose-preview history", ignoreCase = false)
  }

  @Test
  fun `the summary reports dropped render paths rather than staying silent`() {
    // Renders for deleted or renamed previews are dropped by design; silence would read as full
    // coverage.
    val repo = deliveryRepo("publish" to "v1")
    File(repo, "renders/samples:wear/Orphaned.png").writeText("x")
    ProcessBuilder("git", "add", "-A").directory(repo).start().waitFor()
    ProcessBuilder("git", "commit", "--quiet", "-m", "add orphan").directory(repo).start().waitFor()
    val output = File(repo, "history.json")

    val (out, _) = run(repo, "--output", output.path)

    assertContains(out.joinToString("\n"), "1 unmatched render paths dropped")
  }

  @Test
  fun `--quiet writes the file without a summary`() {
    val repo = deliveryRepo("publish" to "v1")
    val output = File(repo, "history.json")

    val (out, _) = run(repo, "--output", output.path, "--quiet")

    assertTrue(out.isEmpty(), "expected no stdout, got: $out")
    assertTrue(output.isFile)
  }
}
