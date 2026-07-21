package ee.schimke.composeai.cli

import ee.schimke.composeai.io.SystemFileSystem
import ee.schimke.composeai.io.TemporaryDirectory
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path.Companion.toPath

/**
 * `compose-preview share-preview <markdown> [image]... | <dir>` `[--mechanism auto|gist|branch]
 * [--public|--secret] [--desc TEXT]` `[--branch BRANCH] [--remote REMOTE] [--raw-base URL]
 * [--pr-number N] [--message MSG]` `[--allow-non-preview-branch] [--json]`
 *
 * One command for getting rendered previews somewhere an agent or reviewer can open them. It folds
 * the former `share-gist` (markdown + image attachments → a GitHub gist) and `publish-images` (a
 * directory of PNGs → a shared capture branch) into a single surface that picks the right mechanism
 * for the environment it runs in:
 *
 * - **Permissions pick the mechanism.** When the GitHub CLI is installed *and* authenticated, the
 *   default is a **gist** — isolated, doesn't touch the project repo. When it isn't (e.g. Claude
 *   Code's hosted web sessions, which have no `gh` and no token but do have an authenticated git
 *   remote), it falls back to pushing a **branch** through that remote. `--mechanism` forces one.
 * - **The current branch picks the target.** For the branch mechanism the destination capture
 *   branch is derived from the branch you're on (`compose-preview/share/<branch>`), so each
 *   feature/PR branch's snapshots stay separate; mainline/release branches are refused. `--branch`
 *   overrides.
 *
 * Two input shapes:
 * - **report**: `<markdown> <image>...` — a markdown file plus image attachments. Works with either
 *   mechanism. Image references inside the markdown should use basenames (`![](before.png)`); this
 *   command does not rewrite paths.
 * - **bulk**: a single `<dir>` of PNGs — branch mechanism only (a directory of binaries isn't a
 *   gist). Mirrors what the `preview-comment` GitHub Action pushes in CI.
 *
 * Branch pushes are SHA-pinned: raw URLs reference the new commit's SHA, so they keep resolving
 * even after the capture branch moves or the PR merges.
 */
class SharePreviewCommand(
  args: List<String>,
  private val fileSystem: FileSystem = SystemFileSystem,
) {
  private val jsonOut = "--json" in args
  private val mechanismRaw: String? = args.flagValue("--mechanism")
  private val forcedMechanism: Mechanism? = Mechanism.parse(mechanismRaw)
  private val visibility: GistVisibility =
    if ("--public" in args) GistVisibility.PUBLIC else GistVisibility.SECRET
  private val description: String? = args.flagValue("--desc")
  private val branchOverride: String? = args.flagValue("--branch")
  private val remote: String = args.flagValue("--remote") ?: "origin"
  private val rawBaseOverride: String? = args.flagValue("--raw-base")
  private val prNumber: String? = args.flagValue("--pr-number")
  private val customMessage: String? = args.flagValue("--message")
  private val allowNonPreviewBranch = "--allow-non-preview-branch" in args
  private val positional: List<String> = parsePositional(args)

  fun run() {
    if (mechanismRaw != null && mechanismRaw.lowercase() !in setOf("auto", "gist", "branch")) {
      System.err.println("invalid --mechanism '$mechanismRaw' (must be auto|gist|branch)")
      exitProcess(64)
    }
    if (positional.isEmpty()) {
      System.err.println(USAGE)
      exitProcess(64) // EX_USAGE
    }

    val first = File(positional[0])
    val mode = if (positional.size == 1 && first.isDirectory) Mode.BULK else Mode.REPORT

    requireOnPath("git", "Install git.")

    val mechanism =
      when (val r = resolveMechanism(mode, forcedMechanism, ::gistAvailable, ::branchAvailable)) {
        is MechanismResult.Ok -> r.mechanism
        is MechanismResult.Err -> {
          System.err.println(r.message)
          exitProcess(1)
        }
      }

    when (mechanism) {
      Mechanism.GIST -> runGist(parseReport())
      Mechanism.BRANCH -> runBranch(mode)
    }
  }

  // --- gist mechanism -----------------------------------------------------

  private fun runGist(report: Report) {
    requireOnPath("gh", "Install GitHub CLI: https://cli.github.com")
    val (name, email) = readGitIdentity()

    val gistUrl = createGist(report.markdown)
    val gistId = parseGistId(gistUrl)
    val rawBase = parseRawBase(gistUrl)

    if (report.images.isNotEmpty()) {
      val tmp = TemporaryDirectory / "compose-preview-share-${UUID.randomUUID()}"
      fileSystem.createDirectories(tmp)
      try {
        val clonePath = tmp / "g"
        val clone = clonePath.toFile()
        runOrFail(
          listOf("git", "clone", "--quiet", "https://gist.github.com/$gistId.git", clone.path),
          "git clone of the new gist failed (gist exists at $gistUrl)",
        )
        for (img in report.images) {
          fileSystem.copy(img.path.toPath(), clonePath / img.name)
        }
        runOrFail(
          listOf("git", "-C", clone.path, "add", "--") + report.images.map { it.name },
          "git add failed (gist exists at $gistUrl)",
        )
        runOrFail(
          listOf(
            "git",
            "-C",
            clone.path,
            "-c",
            "user.name=$name",
            "-c",
            "user.email=$email",
            "commit",
            "--quiet",
            "-m",
            "add images",
          ),
          "git commit failed (gist exists at $gistUrl)",
        )
        runOrFail(
          listOf("git", "-C", clone.path, "push", "--quiet", "origin", "HEAD"),
          "git push to gist failed (gist exists at $gistUrl)",
        )
      } finally {
        fileSystem.deleteRecursively(tmp)
      }
    }

    val files = buildList {
      add(SharePreviewFile(report.markdown.absolutePath, report.markdown.name, gistUrl))
      report.images.forEach {
        add(SharePreviewFile(it.absolutePath, it.name, "$rawBase/${it.name}"))
      }
    }
    emit(
      SharePreviewResponse(mechanism = "gist", url = gistUrl, rawBaseUrl = rawBase, files = files)
    )
  }

  private fun createGist(markdown: File): String {
    val cmd = buildList {
      add("gh")
      add("gist")
      add("create")
      if (visibility == GistVisibility.PUBLIC) add("--public")
      description?.let {
        add("--desc")
        add(it)
      }
      add("--")
      add(markdown.path)
    }
    val result = exec(cmd)
    if (result.exitCode != 0) {
      System.err.println("gh gist create failed (exit ${result.exitCode}):")
      if (result.stderr.isNotBlank()) System.err.println(result.stderr.trim())
      exitProcess(result.exitCode.takeIf { it != 0 } ?: 1)
    }
    val url = extractGistUrl(result.stdout)
    if (url == null) {
      System.err.println("gh gist create did not print a gist URL. stdout was: ${result.stdout}")
      exitProcess(1)
    }
    return url
  }

  // --- branch mechanism ---------------------------------------------------

  private fun runBranch(mode: Mode) {
    if (remote.startsWith("-")) {
      // `git push -<flag>` / `git remote get-url -<flag>` would parse as a flag, not a remote.
      System.err.println("invalid --remote: $remote (must not start with '-')")
      exitProcess(64)
    }
    val remoteUrl = readRemoteUrl(remote)
    val branch =
      when (val t = resolveTargetBranch(branchOverride, currentBranch(), allowNonPreviewBranch)) {
        is TargetResult.Ok -> t.branch
        is TargetResult.Err -> {
          System.err.println(t.message)
          exitProcess(64)
        }
      }

    val rawUrlBase = rawBaseOverride?.trimEnd('/') ?: githubRawUrlBase(remoteUrl)

    val tmp = TemporaryDirectory / "compose-preview-share-${UUID.randomUUID()}"
    fileSystem.createDirectories(tmp)
    try {
      val staging = (tmp / "staging").toFile().apply { mkdirs() }
      val relativePaths: List<String> =
        when (mode) {
          Mode.BULK -> {
            val source = File(positional[0])
            if (File(source, ".git").exists()) {
              System.err.println(
                "${source.path} contains a `.git` directory — refusing to publish a nested repo."
              )
              exitProcess(1)
            }
            source.copyRecursively(staging, overwrite = true)
            source
              .walkTopDown()
              .filter { it.isFile }
              .map { it.relativeTo(source).path.replace(File.separatorChar, '/') }
              .sorted()
              .toList()
          }
          Mode.REPORT -> {
            val report = parseReport()
            val all = listOf(report.markdown) + report.images
            for (f in all) fileSystem.copy(f.path.toPath(), (tmp / "staging" / f.name))
            all.map { it.name }.sorted()
          }
        }
      if (relativePaths.isEmpty()) {
        // Empty bulk batch is a successful no-op (matches the CI action). Crucially, don't require
        // git identity here — a scratch checkout with no user.name/email shouldn't error on a batch
        // that has nothing to commit.
        emit(
          SharePreviewResponse(
            mechanism = "branch",
            url = null,
            rawBaseUrl = null,
            files = emptyList(),
          )
        )
        return
      }

      val (name, email) = readGitIdentity()
      val message = customMessage ?: defaultMessage(prNumber, readHeadSha())

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
      val commitSha = pushWithRetry(staging, branch, name, email, tree, message)

      val pattern = rawUrlBase?.let { "$it/$commitSha" }
      val files = relativePaths.map { rel ->
        SharePreviewFile(path = rel, name = rel, rawUrl = pattern?.let { "$it/$rel" })
      }
      emit(
        SharePreviewResponse(
          mechanism = "branch",
          url = pattern?.let { base -> reportMarkdownName(mode)?.let { "$base/$it" } },
          rawBaseUrl = pattern,
          commit = commitSha,
          branch = branch,
          files = files,
        )
      )
    } finally {
      fileSystem.deleteRecursively(tmp)
    }
  }

  private fun reportMarkdownName(mode: Mode): String? =
    if (mode == Mode.REPORT) File(positional[0]).name else null

  private fun pushWithRetry(
    staging: File,
    branch: String,
    name: String,
    email: String,
    tree: String,
    message: String,
  ): String {
    var attempt = 1
    while (true) {
      val parent = fetchParent(staging, branch)
      val commitArgs = mutableListOf("git", "-C", staging.path)
      commitArgs += listOf("-c", "user.name=$name", "-c", "user.email=$email")
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

  private fun fetchParent(staging: File, branch: String): String? {
    val fetch =
      exec(listOf("git", "-C", staging.path, "fetch", "--depth=1", "--quiet", remote, branch))
    if (fetch.exitCode != 0) return null
    val rev = exec(listOf("git", "-C", staging.path, "rev-parse", "FETCH_HEAD"))
    return if (rev.exitCode == 0) rev.stdout.trim().takeIf { it.isNotEmpty() } else null
  }

  // --- shared input handling ---------------------------------------------

  /** Validates and returns the report (markdown + images), exiting on bad input. */
  private fun parseReport(): Report {
    val markdown = File(positional[0])
    val images = positional.drop(1).map(::File)
    if (!markdown.isFile) {
      System.err.println("not a file: ${markdown.path}")
      exitProcess(1)
    }
    val missing = images.filterNot { it.isFile }
    if (missing.isNotEmpty()) {
      System.err.println("not a file: ${missing.joinToString(", ") { it.path }}")
      exitProcess(1)
    }
    // Flat tree (gist or staged branch) silently overwrites colliding basenames — catch first.
    val collisions = (listOf(markdown) + images).groupBy { it.name }.filterValues { it.size > 1 }
    if (collisions.isNotEmpty()) {
      System.err.println(
        "filename collisions: ${collisions.keys.joinToString(", ")}. " +
          "Rename so each file has a unique basename."
      )
      exitProcess(1)
    }
    return Report(markdown, images)
  }

  // --- availability probes (permissions) ----------------------------------

  private fun gistAvailable(): Boolean {
    if (!onPath("gh")) return false
    return exec(listOf("gh", "auth", "status")).exitCode == 0
  }

  private fun branchAvailable(): Boolean {
    val result = exec(listOf("git", "remote", "get-url", remote))
    return result.exitCode == 0 && result.stdout.isNotBlank()
  }

  // --- git/process plumbing ----------------------------------------------

  private fun currentBranch(): String? {
    val result = exec(listOf("git", "rev-parse", "--abbrev-ref", "HEAD"))
    return if (result.exitCode == 0) result.stdout.trim().takeIf { it.isNotEmpty() && it != "HEAD" }
    else null
  }

  private fun readGitIdentity(): Pair<String, String> {
    val name = exec(listOf("git", "config", "--get", "user.name")).stdout.trim()
    val email = exec(listOf("git", "config", "--get", "user.email")).stdout.trim()
    if (name.isBlank() || email.isBlank()) {
      System.err.println(
        "git user.name / user.email not set. Run:\n" +
          "  git config --global user.name 'Your Name'\n" +
          "  git config --global user.email 'you@example.com'"
      )
      exitProcess(1)
    }
    return name to email
  }

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

  private fun onPath(binary: String): Boolean {
    val probe = exec(listOf("sh", "-c", "command -v $binary"))
    return probe.exitCode == 0 && probe.stdout.isNotBlank()
  }

  private fun requireOnPath(binary: String, hint: String) {
    if (!onPath(binary)) {
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
      // Drain stderr on a separate thread. With separate stdout/stderr pipes, reading stdout to EOF
      // first (as this did) deadlocks when the child fills the ~64 KB stderr buffer before closing
      // stdout — e.g. a `git push` whose server hook is chatty on stderr. Consuming both pipes
      // concurrently is the only safe ordering, and it lets the `waitFor` timeout actually fire.
      val stderrHolder = arrayOfNulls<String>(1)
      val stderrThread =
        Thread { stderrHolder[0] = p.errorStream.bufferedReader().use { it.readText() } }
          .apply {
            isDaemon = true
            start()
          }
      val stdout = p.inputStream.bufferedReader().use { it.readText() }
      val finished = p.waitFor(120, TimeUnit.SECONDS)
      if (!finished) p.destroyForcibly()
      stderrThread.join(TimeUnit.SECONDS.toMillis(5))
      val stderr = stderrHolder[0] ?: ""
      if (!finished) {
        ExecResult(124, stdout, stderr + "\n[command timed out]")
      } else {
        ExecResult(p.exitValue(), stdout, stderr)
      }
    } catch (e: Exception) {
      ExecResult(1, "", e.message ?: e.javaClass.simpleName)
    }
  }

  // --- output -------------------------------------------------------------

  private fun emit(response: SharePreviewResponse) {
    if (jsonOut) {
      println(JSON.encodeToString(SharePreviewResponse.serializer(), response))
      return
    }
    when (response.mechanism) {
      "gist" -> {
        val label = if (visibility == GistVisibility.PUBLIC) "public" else "secret"
        println("Created $label gist: ${response.url}")
        response.files.drop(1).forEach { it.rawUrl?.let { url -> println("  $url") } }
      }
      "branch" -> {
        if (response.commit == null) {
          println("Nothing to publish.")
          return
        }
        println("Pushed ${response.files.size} file(s) to $remote/${response.branch}")
        println("  commit: ${response.commit}")
        if (response.rawBaseUrl != null) {
          response.url?.let { println("  report: $it") }
          response.files.forEach { f -> f.rawUrl?.let { println("  ${f.name}: $it") } }
        } else {
          println("  (no raw URL pattern — non-GitHub remote; pass --raw-base to supply one)")
        }
      }
    }
  }

  private data class ExecResult(val exitCode: Int, val stdout: String, val stderr: String)

  private data class Report(val markdown: File, val images: List<File>)

  internal enum class Mode {
    REPORT,
    BULK,
  }

  enum class GistVisibility {
    PUBLIC,
    SECRET,
  }

  enum class Mechanism {
    GIST,
    BRANCH;

    companion object {
      /** Maps `--mechanism` to a forced choice; `auto`, null, or unknown values yield null. */
      fun parse(raw: String?): Mechanism? =
        when (raw?.lowercase()) {
          "gist" -> GIST
          "branch" -> BRANCH
          else -> null
        }
    }
  }

  internal sealed interface MechanismResult {
    data class Ok(val mechanism: Mechanism) : MechanismResult

    data class Err(val message: String) : MechanismResult
  }

  internal sealed interface TargetResult {
    data class Ok(val branch: String) : TargetResult

    data class Err(val message: String) : TargetResult
  }

  companion object {
    private const val USAGE =
      "usage: compose-preview share-preview <markdown> [image]... | <dir> " +
        "[--mechanism auto|gist|branch] [--public|--secret] [--desc TEXT] [--branch BRANCH] " +
        "[--remote REMOTE] [--raw-base URL] [--pr-number N] [--message MSG] " +
        "[--allow-non-preview-branch] [--json]"

    private const val MAX_PUSH_ATTEMPTS = 5

    private val JSON = Json {
      prettyPrint = true
      encodeDefaults = true
    }

    private val FLAGS_TAKING_VALUE =
      setOf(
        "--mechanism",
        "--desc",
        "--branch",
        "--remote",
        "--raw-base",
        "--pr-number",
        "--message",
      )
    private val FLAGS_NO_VALUE =
      setOf("--json", "--public", "--secret", "--allow-non-preview-branch")

    private val HARD_BLOCKED_BRANCHES = setOf("main", "master", "develop", "trunk", "HEAD")
    private val HARD_BLOCKED_PREFIXES = listOf("release/", "releases/")
    private val PREVIEW_BRANCH_PREFIXES = listOf("compose-preview/", "preview_")
    private val SAFE_REFNAME = Regex("""^[A-Za-z0-9][A-Za-z0-9._/-]*$""")

    /**
     * Loopback git proxy used by Claude Code's hosted (web) sessions: the container's `origin` is
     * rewritten to `http://<user>@127.0.0.1:<port>/git/<owner>/<repo>`, with auth carried by the
     * proxy. The proxy fronts github.com, so the bytes we push still serve from
     * `raw.githubusercontent.com`. We only treat a remote this way when the host is loopback AND
     * the path is `/git/<owner>/<repo>` — a real GitHub Enterprise remote (arbitrary host) must NOT
     * be mapped onto github.com's raw host, so it falls through to the `--raw-base` override.
     */
    private val LOOPBACK_GIT_PROXY =
      Regex("""^https?://(?:[^@/]*@)?(?:127\.0\.0\.1|localhost|\[::1\])(?::\d+)?/git/(.+)$""")

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
     * Resolves which mechanism to use. Permissions pick it: an explicit `--mechanism` wins
     * (erroring if that path isn't available), otherwise gist is preferred when the GitHub CLI is
     * installed and authenticated, falling back to a branch push when a remote is reachable. BULK
     * input (a directory) can only go to a branch.
     *
     * [gistAvailable] / [branchAvailable] are probed lazily so we don't, say, shell out to `gh auth
     * status` when the caller already forced `--mechanism branch`.
     */
    internal fun resolveMechanism(
      mode: Mode,
      forced: Mechanism?,
      gistAvailable: () -> Boolean,
      branchAvailable: () -> Boolean,
    ): MechanismResult {
      if (mode == Mode.BULK) {
        if (forced == Mechanism.GIST) {
          return MechanismResult.Err(
            "a directory can't be shared as a gist — drop --mechanism gist or pass a markdown report."
          )
        }
        return if (branchAvailable()) MechanismResult.Ok(Mechanism.BRANCH)
        else MechanismResult.Err("no usable git remote for the branch push.")
      }
      return when (forced) {
        Mechanism.GIST ->
          if (gistAvailable()) MechanismResult.Ok(Mechanism.GIST)
          else
            MechanismResult.Err(
              "--mechanism gist requested but the GitHub CLI isn't installed/authenticated " +
                "(need `gh` on PATH and `gh auth status` to pass)."
            )
        Mechanism.BRANCH ->
          if (branchAvailable()) MechanismResult.Ok(Mechanism.BRANCH)
          else
            MechanismResult.Err("--mechanism branch requested but no usable git remote was found.")
        null ->
          when {
            gistAvailable() -> MechanismResult.Ok(Mechanism.GIST)
            branchAvailable() -> MechanismResult.Ok(Mechanism.BRANCH)
            else ->
              MechanismResult.Err(
                "no way to share: install + authenticate the GitHub CLI (`gh`) for gists, or " +
                  "run from a checkout with a pushable remote for the branch mechanism."
              )
          }
      }
    }

    /**
     * Resolves the destination capture branch for the branch mechanism. An explicit `--branch`
     * override is validated and used as-is; otherwise the branch is derived from the current branch
     * as `compose-preview/share/<current>`, which keeps each feature/PR branch's snapshots
     * separate. Mainline / release branches are refused as a source so renders never land next to
     * production code, and the final name is validated the same way `--branch` would be.
     */
    internal fun resolveTargetBranch(
      override: String?,
      currentBranch: String?,
      allowNonPreview: Boolean,
    ): TargetResult {
      if (override != null) {
        validateBranch(override, allowNonPreview)?.let {
          return TargetResult.Err(it)
        }
        return TargetResult.Ok(override)
      }
      if (currentBranch == null) {
        return TargetResult.Err(
          "couldn't determine the current branch (detached HEAD?). Pass --branch to choose a target."
        )
      }
      if (
        currentBranch in HARD_BLOCKED_BRANCHES ||
          HARD_BLOCKED_PREFIXES.any { currentBranch.startsWith(it) }
      ) {
        return TargetResult.Err(
          "refusing to snapshot from '$currentBranch': check out a feature/PR branch, or pass " +
            "--branch to choose an explicit capture branch."
        )
      }
      val target = "compose-preview/share/$currentBranch"
      validateBranch(target, allowNonPreview)?.let {
        return TargetResult.Err(
          "derived target branch '$target' is not a valid ref ($it). Pass --branch explicitly."
        )
      }
      return TargetResult.Ok(target)
    }

    /** Branch-name safety check, layered. Returns null when acceptable; else the error message. */
    internal fun validateBranch(branch: String, allowNonPreview: Boolean): String? {
      if (!SAFE_REFNAME.matches(branch) || ".." in branch || "@{" in branch) {
        return "invalid branch '$branch': must start with a letter or digit and use only " +
          "[A-Za-z0-9._/-]; refspec/path-injection patterns rejected."
      }
      if (branch in HARD_BLOCKED_BRANCHES || HARD_BLOCKED_PREFIXES.any { branch.startsWith(it) }) {
        return "refusing to push to '$branch': mainline / release branches are never a valid " +
          "destination, even with --allow-non-preview-branch."
      }
      if (PREVIEW_BRANCH_PREFIXES.none { branch.startsWith(it) } && !allowNonPreview) {
        return "branch '$branch' is outside the preview allowlist (compose-preview/* or " +
          "legacy preview_*). Pass --allow-non-preview-branch to push to a custom branch " +
          "(mainline branches stay blocked regardless)."
      }
      return null
    }

    /**
     * Default commit message format mirrors the CI action's: `Preview renders for PR #N (sha::8)`.
     */
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
     * Maps a GitHub remote URL to its raw.githubusercontent.com prefix, minus the commit and path
     * components. Returns null for remotes it can't confidently map (use `--raw-base`).
     *
     * Examples:
     * - `https://github.com/owner/repo.git` → `https://raw.githubusercontent.com/owner/repo`
     * - `git@github.com:owner/repo.git` → `https://raw.githubusercontent.com/owner/repo`
     * - `http://x@127.0.0.1:38695/git/owner/repo` → `https://raw.githubusercontent.com/owner/repo`
     * - `git@gitlab.com:owner/repo.git` → null
     */
    internal fun githubRawUrlBase(remoteUrl: String): String? {
      LOOPBACK_GIT_PROXY.find(remoteUrl)?.let { match ->
        val ownerRepo = match.groupValues[1].removeSuffix(".git").trim('/')
        return if (ownerRepo.count { it == '/' } == 1 && ownerRepo.isNotBlank()) {
          "https://raw.githubusercontent.com/$ownerRepo"
        } else {
          null
        }
      }
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
      if (ownerRepo.count { it == '/' } != 1 || ownerRepo.isBlank()) return null
      return "https://raw.githubusercontent.com/$ownerRepo"
    }

    /** The URL `gh gist create` prints on stdout; the first `https://gist.github.com/...` token. */
    internal fun extractGistUrl(stdout: String): String? {
      val pattern = Regex("""https://gist\.github\.com/[A-Za-z0-9_./-]+""")
      return pattern.find(stdout)?.value?.trimEnd('/')
    }

    /** Gist id is the last path segment, whether or not a `<user>/` segment precedes it. */
    internal fun parseGistId(url: String): String {
      val tail = url.substringAfter("https://gist.github.com/").trimEnd('/')
      return tail.substringAfterLast('/')
    }

    /** Raw asset base for a gist, preserving the username when present. */
    internal fun parseRawBase(url: String): String {
      val tail = url.substringAfter("https://gist.github.com/").trimEnd('/')
      return "https://gist.githubusercontent.com/$tail/raw"
    }
  }
}

@Serializable
internal data class SharePreviewResponse(
  val schema: String = "compose-preview-share-preview/v1",
  val mechanism: String,
  /** Primary shareable link: the gist URL, or the markdown report's raw URL on a branch push. */
  val url: String?,
  /** Gist raw base, or `<rawBase>/<commit>` for a branch push; null for non-GitHub remotes. */
  val rawBaseUrl: String?,
  val commit: String? = null,
  val branch: String? = null,
  val files: List<SharePreviewFile> = emptyList(),
)

@Serializable
internal data class SharePreviewFile(val path: String, val name: String, val rawUrl: String?)
