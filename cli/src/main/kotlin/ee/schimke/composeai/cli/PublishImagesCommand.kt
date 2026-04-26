package ee.schimke.composeai.cli

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * `compose-preview publish-images DIR [--branch preview_pr] [--remote origin] [--pr-number N]
 * [--message MSG] [--json]`
 *
 * Pushes the contents of a staging directory as a single commit on a shared rendered-PNG branch
 * (default `preview_pr`), mirroring what the `preview-comment` GitHub Action does in CI. Lets an
 * agent that already has a populated `_pr_renders/`-shape directory get stable raw URLs without
 * scripting the `git init` / `commit-tree` / race-loop dance by hand.
 *
 * Deliberately small: this is the push primitive, not the whole `preview-comment` workflow. The
 * agent decides what to put in the staging directory (typically the changed/new PNGs from a
 * `compose-preview show --json` diff) and writes the PR comment markdown itself; this command just
 * gets the bytes onto a branch and prints the resulting commit SHA + raw URL pattern.
 */
class PublishImagesCommand(args: List<String>) {
  private val jsonOut = "--json" in args
  private val branch: String = args.flagValue("--branch") ?: "preview_pr"
  private val remote: String = args.flagValue("--remote") ?: "origin"
  private val prNumber: String? = args.flagValue("--pr-number")
  private val customMessage: String? = args.flagValue("--message")
  /**
   * Opt-in escape hatch for branch names outside the `preview_*` allowlist. Hard-blocked names
   * ([HARD_BLOCKED_BRANCHES]) stay rejected even with this flag — pushing rendered PNGs onto
   * `main`/`master`/etc. is never the intended use of this command.
   */
  private val allowNonPreviewBranch = "--allow-non-preview-branch" in args
  private val positional: List<String> = parsePositional(args)

  fun run() {
    if (positional.size != 1) {
      System.err.println(USAGE)
      exitProcess(64) // EX_USAGE
    }

    validateBranch(branch, allowNonPreviewBranch)?.let { message ->
      System.err.println(message)
      exitProcess(64)
    }

    if (remote.startsWith("-")) {
      // `git push -<flag>` would be interpreted as a flag, not a remote. Defensive even though
      // git remote names with leading dashes are unusual in practice.
      System.err.println("invalid --remote: $remote (must not start with '-')")
      exitProcess(64)
    }

    val source = File(positional[0])
    if (!source.isDirectory) {
      System.err.println("not a directory: ${source.path}")
      exitProcess(1)
    }
    if (File(source, ".git").exists()) {
      System.err.println(
        "${source.path} contains a `.git` directory — refusing to publish a nested repo. " +
          "Pass a plain directory of PNGs."
      )
      exitProcess(1)
    }

    val files = source.walkTopDown().filter { it.isFile }.toList()
    if (files.isEmpty()) {
      // Mirrors the GHA's noop-on-empty behaviour: it's a successful no-op, not an error.
      if (jsonOut) {
        println(
          JSON.encodeToString(
            PublishImagesResponse.serializer(),
            PublishImagesResponse(
              commit = null,
              branch = branch,
              remote = remote,
              remoteUrl = "",
              rawUrlPattern = null,
              pushedFiles = emptyList(),
            ),
          )
        )
      } else {
        println("Nothing to publish — ${source.path} contains no files.")
      }
      return
    }

    requireOnPath("git", "Install git.")
    val (committerName, committerEmail) = readGitIdentity()
    val remoteUrl = readRemoteUrl(remote)
    val rawUrlBase = githubRawUrlBase(remoteUrl)
    val headSha = readHeadSha()
    val message = customMessage ?: defaultMessage(prNumber, headSha)

    val tmp = Files.createTempDirectory("compose-preview-publish-")
    try {
      val staging = tmp.resolve("staging").toFile().apply { mkdirs() }
      // Copy SOURCE's contents (not SOURCE itself) into staging so the commit tree mirrors
      // SOURCE's layout exactly — `renders/<module>/<id>.png` stays at the same relative path.
      source.copyRecursively(staging, overwrite = true)

      runOrFail(listOf("git", "-C", staging.path, "init", "--quiet"), "git init failed")
      runOrFail(
        listOf("git", "-C", staging.path, "remote", "add", remote, remoteUrl),
        "git remote add failed",
      )
      runOrFail(listOf("git", "-C", staging.path, "add", "-A"), "git add failed")

      val tree =
        execOrFail(listOf("git", "-C", staging.path, "write-tree"), "git write-tree failed")
          .stdout
          .trim()

      val commitSha = pushWithRetry(staging, committerName, committerEmail, tree, message)

      val pushedRelative =
        files.map { it.relativeTo(source).path.replace(File.separatorChar, '/') }.sorted()
      emit(commitSha, remoteUrl, rawUrlBase, pushedRelative)
    } finally {
      tmp.toFile().deleteRecursively()
    }
  }

  /**
   * Up to [MAX_PUSH_ATTEMPTS] attempts at the fetch-parent → commit-tree → push cycle. Mirrors the
   * GHA's retry shape: the shared branch can have parallel pushes from sibling PRs in flight,
   * second-to-push hits non-fast-forward, re-parent on the new tip and retry. Returns the commit
   * SHA on success.
   */
  private fun pushWithRetry(
    staging: File,
    committerName: String,
    committerEmail: String,
    tree: String,
    message: String,
  ): String {
    var attempt = 1
    while (true) {
      val parent = fetchParent(staging)
      val commitArgs = mutableListOf("git", "-C", staging.path)
      // Identity goes on the commit-tree call rather than `git config --local` so we don't
      // mutate any persistent state — the temp staging dir gets deleted, but defending against
      // a stray copy of staged config is cheap and explicit.
      commitArgs += listOf("-c", "user.name=$committerName", "-c", "user.email=$committerEmail")
      commitArgs += listOf("commit-tree", tree)
      if (parent != null) commitArgs += listOf("-p", parent)
      commitArgs += listOf("-m", message)
      val commit = execOrFail(commitArgs, "git commit-tree failed").stdout.trim()

      val push =
        exec(listOf("git", "-C", staging.path, "push", remote, "$commit:refs/heads/$branch"))
      if (push.exitCode == 0) return commit

      if (attempt >= MAX_PUSH_ATTEMPTS) {
        System.err.println("push to $remote/$branch failed after $attempt attempt(s). Last error:")
        if (push.stderr.isNotBlank()) System.err.println(push.stderr.trim())
        exitProcess(1)
      }
      val isRace =
        push.stderr.contains("non-fast-forward", ignoreCase = true) ||
          push.stderr.contains("fetch first", ignoreCase = true) ||
          push.stderr.contains("rejected", ignoreCase = true)
      if (!isRace) {
        // Some other failure (auth, network, missing remote). Retry won't help.
        System.err.println("push to $remote/$branch failed:")
        if (push.stderr.isNotBlank()) System.err.println(push.stderr.trim())
        exitProcess(1)
      }
      val delaySeconds = attempt * 2 + (0..2).random()
      System.err.println(
        "push to $remote/$branch lost the race; retry $attempt/$MAX_PUSH_ATTEMPTS in ${delaySeconds}s…"
      )
      Thread.sleep(delaySeconds * 1000L)
      attempt++
    }
  }

  /**
   * Tip of the remote branch as a SHA, or null on first-ever push (the branch doesn't exist yet —
   * the GHA falls back to an orphan commit in that case, and so do we via the null-parent path in
   * [pushWithRetry]).
   */
  private fun fetchParent(staging: File): String? {
    val fetch =
      exec(listOf("git", "-C", staging.path, "fetch", "--depth=1", "--quiet", remote, branch))
    if (fetch.exitCode != 0) return null
    val rev = exec(listOf("git", "-C", staging.path, "rev-parse", "FETCH_HEAD"))
    return if (rev.exitCode == 0) rev.stdout.trim().takeIf { it.isNotEmpty() } else null
  }

  private fun emit(commitSha: String, remoteUrl: String, rawUrlBase: String?, files: List<String>) {
    if (jsonOut) {
      println(
        JSON.encodeToString(
          PublishImagesResponse.serializer(),
          PublishImagesResponse(
            commit = commitSha,
            branch = branch,
            remote = remote,
            remoteUrl = remoteUrl,
            rawUrlPattern = rawUrlBase?.let { "$it/$commitSha/{path}" },
            pushedFiles = files,
          ),
        )
      )
    } else {
      println("Pushed ${files.size} file(s) to $remote/$branch")
      println("  commit: $commitSha")
      if (rawUrlBase != null) {
        println("  raw URL pattern: $rawUrlBase/$commitSha/{path}")
      } else {
        println("  remote: $remoteUrl (no GitHub raw URL pattern available — non-GitHub remote)")
      }
    }
  }

  // --- helpers ------------------------------------------------------------

  private fun readGitIdentity(): Pair<String, String> {
    val name = exec(listOf("git", "config", "--get", "user.name")).stdout.trim()
    val email = exec(listOf("git", "config", "--get", "user.email")).stdout.trim()
    if (name.isBlank() || email.isBlank()) {
      System.err.println(
        "git user.name / user.email not set. Run:\n" +
          "  git config --global user.name 'Your Name'\n" +
          "  git config --global user.email 'you@example.com'\n" +
          "(Used only as the commit identity in the temporary staging repo.)"
      )
      exitProcess(1)
    }
    return name to email
  }

  /**
   * `git remote get-url <remote>` from the cwd. `<remote>` must exist on the project repo — the
   * staging clone re-adds it pointing at the same URL so credentials resolve the same way.
   */
  private fun readRemoteUrl(remote: String): String {
    val result = exec(listOf("git", "remote", "get-url", remote))
    if (result.exitCode != 0 || result.stdout.isBlank()) {
      System.err.println(
        "git remote '$remote' not found in this repo. " +
          "Pass --remote NAME, or run from a checkout that has the remote configured."
      )
      exitProcess(1)
    }
    return result.stdout.trim()
  }

  private fun readHeadSha(): String? {
    val result = exec(listOf("git", "rev-parse", "HEAD"))
    return if (result.exitCode == 0) result.stdout.trim().takeIf { it.isNotEmpty() } else null
  }

  private fun requireOnPath(binary: String, hint: String) {
    val probe = exec(listOf("sh", "-c", "command -v $binary"))
    if (probe.exitCode != 0 || probe.stdout.isBlank()) {
      System.err.println("$binary not found on PATH. $hint")
      exitProcess(1)
    }
  }

  private fun runOrFail(cmd: List<String>, contextMessage: String) {
    val result = exec(cmd)
    if (result.exitCode != 0) {
      System.err.println(contextMessage)
      if (result.stderr.isNotBlank()) System.err.println(result.stderr.trim())
      exitProcess(result.exitCode.takeIf { it != 0 } ?: 1)
    }
  }

  private fun execOrFail(cmd: List<String>, contextMessage: String): ExecResult {
    val result = exec(cmd)
    if (result.exitCode != 0) {
      System.err.println(contextMessage)
      if (result.stderr.isNotBlank()) System.err.println(result.stderr.trim())
      exitProcess(result.exitCode.takeIf { it != 0 } ?: 1)
    }
    return result
  }

  private fun exec(cmd: List<String>): ExecResult {
    return try {
      val p = ProcessBuilder(cmd).redirectErrorStream(false).start()
      val stdout = p.inputStream.bufferedReader().use { it.readText() }
      val stderr = p.errorStream.bufferedReader().use { it.readText() }
      if (!p.waitFor(120, TimeUnit.SECONDS)) {
        p.destroyForcibly()
        ExecResult(124, stdout, stderr + "\n[command timed out]")
      } else {
        ExecResult(p.exitValue(), stdout, stderr)
      }
    } catch (e: Exception) {
      ExecResult(1, "", e.message ?: e.javaClass.simpleName)
    }
  }

  private data class ExecResult(val exitCode: Int, val stdout: String, val stderr: String)

  companion object {
    private const val USAGE =
      "usage: compose-preview publish-images DIR [--branch BRANCH] [--remote REMOTE] [--pr-number N] [--message MSG] [--allow-non-preview-branch] [--json]"

    private const val MAX_PUSH_ATTEMPTS = 5

    private val JSON = Json {
      prettyPrint = true
      encodeDefaults = true
    }

    private val FLAGS_TAKING_VALUE = setOf("--branch", "--remote", "--pr-number", "--message")
    private val FLAGS_NO_VALUE = setOf("--json", "--allow-non-preview-branch")

    /**
     * Branches `publish-images` will never push to, regardless of `--allow-non-preview-branch`.
     * This is policy on top of the allowlist: pushing rendered PNGs onto a project's mainline or
     * release branches is never the intended use of this command, so we hard-block even with the
     * escape hatch. Anything else outside the `preview_*` allowlist requires the flag.
     */
    private val HARD_BLOCKED_BRANCHES = setOf("main", "master", "develop", "trunk", "HEAD")
    private val HARD_BLOCKED_PREFIXES = listOf("release/", "releases/")

    /**
     * Conservative subset of `git check-ref-format`. Real refnames allow more, but for this
     * command's scope (push to a named branch the user controls) we want a tight surface that
     * rejects path-injection (`../`), refspec syntax (`:`), and flag-injection (`-`).
     */
    private val SAFE_REFNAME = Regex("""^[A-Za-z0-9][A-Za-z0-9._/-]*$""")

    internal fun parsePositional(args: List<String>): List<String> {
      val out = mutableListOf<String>()
      var i = 0
      while (i < args.size) {
        val a = args[i]
        when {
          a in FLAGS_TAKING_VALUE -> i += 2
          a in FLAGS_NO_VALUE -> i += 1
          a.startsWith("--") -> i += 1
          else -> {
            out += a
            i += 1
          }
        }
      }
      return out
    }

    /**
     * Branch-name safety check, layered. Returns null when the branch is acceptable; otherwise the
     * error message a caller should print.
     *
     * 1. Refname must match [SAFE_REFNAME] — catches `--branch ../foo`, `:bar`, `-flag`, etc.
     *    before they reach `git push`.
     * 2. Refname must not be in [HARD_BLOCKED_BRANCHES] or under [HARD_BLOCKED_PREFIXES] — pushing
     *    rendered PNGs onto mainline / release branches is never the intended use, even with the
     *    escape hatch.
     * 3. Refname must match the `preview_` prefix, OR `--allow-non-preview-branch` must be set.
     *
     * The first failing rule wins so error messages stay focused.
     */
    internal fun validateBranch(branch: String, allowNonPreview: Boolean): String? {
      if (!SAFE_REFNAME.matches(branch) || ".." in branch || "@{" in branch) {
        return "invalid --branch '$branch': must start with a letter or digit and use only " +
          "[A-Za-z0-9._/-]; refspec/path-injection patterns rejected."
      }
      if (branch in HARD_BLOCKED_BRANCHES || HARD_BLOCKED_PREFIXES.any { branch.startsWith(it) }) {
        return "refusing to push to '$branch': mainline / release branches are never a valid " +
          "destination for publish-images, even with --allow-non-preview-branch."
      }
      if (!branch.startsWith("preview_") && !allowNonPreview) {
        return "branch '$branch' is outside the preview_* allowlist. Pass " +
          "--allow-non-preview-branch to push to a custom branch (mainline branches stay " +
          "blocked regardless)."
      }
      return null
    }

    /** Default commit message format mirrors the GHA's: `Preview renders for PR #N (sha::8)`. */
    internal fun defaultMessage(prNumber: String?, headSha: String?): String {
      val shortSha = headSha?.take(8)
      return when {
        prNumber != null && shortSha != null -> "Preview renders for PR #$prNumber ($shortSha)"
        prNumber != null -> "Preview renders for PR #$prNumber"
        shortSha != null -> "Preview renders ($shortSha)"
        else -> "Preview renders"
      }
    }

    /**
     * Maps a GitHub remote URL (HTTPS or SSH) to its raw.githubusercontent.com prefix, minus the
     * commit and path components. Returns null for non-GitHub remotes — those callers get the
     * commit SHA but not a guess at where the bytes live.
     *
     * Examples:
     * - `https://github.com/owner/repo.git` → `https://raw.githubusercontent.com/owner/repo`
     * - `git@github.com:owner/repo.git` → `https://raw.githubusercontent.com/owner/repo`
     * - `git@gitlab.com:owner/repo.git` → null
     */
    internal fun githubRawUrlBase(remoteUrl: String): String? {
      val ownerRepo =
        when {
            remoteUrl.startsWith("https://github.com/") ->
              remoteUrl.removePrefix("https://github.com/")
            remoteUrl.startsWith("http://github.com/") ->
              remoteUrl.removePrefix("http://github.com/")
            remoteUrl.startsWith("git@github.com:") -> remoteUrl.removePrefix("git@github.com:")
            remoteUrl.startsWith("ssh://git@github.com/") ->
              remoteUrl.removePrefix("ssh://git@github.com/")
            else -> return null
          }
          .removeSuffix(".git")
          .trimEnd('/')
      // Sanity: we expect exactly `owner/repo`. Anything weirder, bail.
      if (ownerRepo.count { it == '/' } != 1 || ownerRepo.isBlank()) return null
      return "https://raw.githubusercontent.com/$ownerRepo"
    }
  }
}

@Serializable
internal data class PublishImagesResponse(
  val schema: String = "compose-preview-publish-images/v1",
  /** Null when [pushedFiles] was empty (noop). */
  val commit: String?,
  val branch: String,
  val remote: String,
  val remoteUrl: String,
  /**
   * Template URL with `{path}` placeholder; substitute the per-image relative path. Null for
   * non-GitHub remotes or when nothing was pushed.
   */
  val rawUrlPattern: String?,
  val pushedFiles: List<String>,
)
