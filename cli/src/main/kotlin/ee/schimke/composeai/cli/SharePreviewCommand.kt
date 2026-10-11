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
 * `compose-preview share-preview <markdown> [image]... | <dir>` `[--mechanism
 * auto|gist|branch|serve] [--public|--secret] [--desc TEXT]` `[--branch BRANCH] [--remote REMOTE]
 * [--raw-base URL] [--pr-number N] [--message MSG]` `[--allow-non-preview-branch] [--serve-url URL]
 * [--serve-token TOKEN]` `[--github-token-file PATH] [--json]`
 *
 * Gets rendered previews somewhere an agent or reviewer can open them, picking the mechanism from
 * what the environment permits (`--mechanism` forces one):
 * - **serve** when a host is configured (`--serve-url`, `$COMPOSE_PREVIEW_SERVE_URL`, or the
 *   project's `composePreview.serveUrl`, [resolveProjectServeUrl]). Uploads each image
 *   ([ServeImageUploader]) and rewrites the report with absolute links, which expire (7 days by
 *   default). Sends a GitHub credential ([AgentGithubToken]), so where it may go is checked.
 * - **gist** when `gh` is installed and authenticated.
 * - **branch** otherwise: push to `compose-preview/share/<current branch>` (mainline/release
 *   refused; `--branch` overrides). Raw URLs are SHA-pinned so they survive branch moves.
 *
 * Inputs: a **report** (`<markdown> <image>...`, images referenced by basename) or a **bulk**
 * `<dir>` of PNGs (not gist).
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
  /**
   * The serve host to upload to: `--serve-url`, `$COMPOSE_PREVIEW_SERVE_URL`, or the project's
   * `composePreview.serveUrl` ([resolveProjectServeUrl]). Its presence is the opt-in, and reading
   * it from the project makes serve the default for everyone in that repository.
   */
  private val resolvedServeUrl: ResolvedServeUrl? =
    resolveProjectServeUrl(findGradleProjectRoot(), args)

  /**
   * Whether the resolved host may be sent a credential ([confirmProjectServeHost]); null when no
   * host was named. An unconfirmed project-named host is unusable: `gradle.properties` is editable
   * by any pull request, so trusting it would leak a token to whoever wrote the branch.
   */
  private val serveTrust: ServeUrlTrust? = resolvedServeUrl?.let {
    val root = findGradleProjectRoot()
    // `.git/config` can't be edited by a pull request, so it anchors repo-scoped confirmations.
    confirmProjectServeHost(it, projectRoot = root, originRepo = gitOriginRepo(root))
  }

  private val serveUrl: String?
    get() = (serveTrust as? ServeUrlTrust.Trusted)?.resolved?.url

  /**
   * The host's browse token for a non-`--public` serve box, falling back to an agent access grant
   * for that host ([AgentAccessStore]). An explicit `--serve-token` is never overridden.
   */
  private val serveHostToken: String? by lazy {
    (args.flagValue("--serve-token") ?: System.getenv("COMPOSE_PREVIEW_SERVE_TOKEN"))
      ?.trim()
      ?.takeIf { it.isNotEmpty() } ?: serveUrl?.let { AgentAccessStore().tokenFor(it) }
  }
  private val githubTokenFile: String? = args.flagValue("--github-token-file")
  /**
   * Whether the caller passed the unsupported `--github-token`, captured because [args] isn't
   * retained; it gets its own refusal naming the safe alternatives.
   */
  private val usedInlineTokenFlag: Boolean = args.any {
    it == "--github-token" || it.startsWith("--github-token=")
  }
  private val positional: List<String> = parsePositional(args)

  fun run() {
    if (
      mechanismRaw != null && mechanismRaw.lowercase() !in setOf("auto", "gist", "branch", "serve")
    ) {
      System.err.println("invalid --mechanism '$mechanismRaw' (must be auto|gist|branch|serve)")
      exitProcess(64)
    }
    // A token in argv leaks via `ps` and CI logs; refuse and name the alternatives.
    if (usedInlineTokenFlag) {
      System.err.println(
        "--github-token is not a flag, on purpose: an argument is visible in `ps` and in CI logs. " +
          "Use \$GITHUB_TOKEN / \$GH_TOKEN, --github-token-file <path>, or `gh auth login`."
      )
      exitProcess(64)
    }
    if (positional.isEmpty()) {
      System.err.println(USAGE)
      exitProcess(64) // EX_USAGE
    }

    val first = File(positional[0])
    val mode = if (positional.size == 1 && first.isDirectory) Mode.BULK else Mode.REPORT

    // An unconfirmed project host never selects serve; explain the fallback once, with the
    // confirming command.
    (serveTrust as? ServeUrlTrust.NeedsConfirmation)?.let {
      if (forcedMechanism == Mechanism.SERVE) {
        System.err.println(it.how)
        exitProcess(64)
      }
      System.err.println("compose-preview: not using this project's preview server. ${it.how}")
    }

    val mechanism =
      when (
        val r =
          resolveMechanism(
            mode,
            forcedMechanism,
            ::gistAvailable,
            ::branchAvailable,
            serveAvailable = { serveUrl != null },
          )
      ) {
        is MechanismResult.Ok -> r.mechanism
        is MechanismResult.Err -> {
          System.err.println(r.message)
          exitProcess(1)
        }
      }

    // Serve needs no git; the probes above tolerate a missing git on their own.
    if (mechanism != Mechanism.SERVE) requireOnPath("git", "Install git.")

    when (mechanism) {
      Mechanism.GIST -> runGist(parseReport())
      Mechanism.BRANCH -> runBranch(mode)
      Mechanism.SERVE -> runServe(mode)
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

  // --- serve mechanism ----------------------------------------------------

  /**
   * Upload to a `compose-preview serve --accept-images` host and print embeddable URLs, rewriting
   * the report since the host doesn't publish markdown. Credential destination is checked by
   * [ServeImageUploader]; its source is never argv ([AgentGithubToken]).
   */
  private fun runServe(mode: Mode) {
    val url = serveUrl
    if (url == null) {
      System.err.println(
        "--mechanism serve needs a host: pass --serve-url https://… (or set " +
          "\$COMPOSE_PREVIEW_SERVE_URL)."
      )
      exitProcess(64)
    }
    ServeImageUploader.rejectUnsafeUrl(url)?.let {
      System.err.println(it)
      exitProcess(64)
    }
    // A missing GitHub credential is fatal only without a host credential: an agent grant with the
    // `images` capability lets the host decide (docs/design/AGENT_ACCESS_GRANTS.md).
    val hostToken = serveHostToken
    val credential =
      when (val r = AgentGithubToken.resolve(githubTokenFile, ghToken = ::ghAuthToken)) {
        is AgentGithubToken.Result.Ok -> r
        is AgentGithubToken.Result.Err -> {
          if (hostToken == null) {
            System.err.println(r.message)
            exitProcess(1)
          }
          null
        }
      }

    val report = if (mode == Mode.REPORT) parseReport() else null
    val images =
      report?.images
        ?: File(positional[0]).walkTopDown().filter { it.isFile }.sortedBy { it.name }.toList()
    if (images.isEmpty()) {
      // Same successful no-op the branch mechanism answers an empty batch with.
      emit(SharePreviewResponse(mechanism = "serve", url = null, rawBaseUrl = url))
      return
    }

    val uploader = ServeImageUploader(url, credential?.token, hostToken)
    val uploaded = LinkedHashMap<String, String>()
    var expiresIn: String? = null
    for (image in images) {
      when (val result = uploader.upload(image)) {
        is ServeImageUploader.Result.Ok -> {
          uploaded[image.name] = result.url
          if (expiresIn == null) expiresIn = result.expiresIn
        }
        is ServeImageUploader.Result.Failed -> {
          System.err.println("upload of ${image.name} failed: ${result.reason}")
          // Stop at the first failure: a half-rewritten report mixes live and broken links
          // invisibly.
          exitProcess(1)
        }
      }
    }

    val rewritten = report?.let { SharePreviewMarkdown.rewrite(it.markdown.readText(), uploaded) }
    emit(
      SharePreviewResponse(
        mechanism = "serve",
        url = null,
        rawBaseUrl = url.trimEnd('/'),
        files = images.map { SharePreviewFile(it.absolutePath, it.name, uploaded[it.name]) },
        markdown = rewritten,
        expiresIn = expiresIn,
        // Null when a grant admitted the upload — there was no GitHub credential to have a source.
        credentialSource = credential?.source,
        serveUrlSource = resolvedServeUrl?.source?.display,
      )
    )
  }

  /** `gh auth token`, when the GitHub CLI is installed and signed in; null otherwise. */
  private fun ghAuthToken(): String? {
    if (!onPath("gh")) return null
    val result = exec(listOf("gh", "auth", "token"))
    return if (result.exitCode == 0) result.stdout.trim().takeIf { it.isNotEmpty() } else null
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
        // An empty bulk batch is a successful no-op and needs no git identity.
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
      // Drain stderr concurrently, or a chatty child fills the stderr pipe and deadlocks the stdout
      // read.
      val stderrHolder = arrayOfNulls<String>(1)
      val stderrThread = Thread {
        stderrHolder[0] = p.errorStream.bufferedReader().use { it.readText() }
      }
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
      "serve" -> {
        if (response.files.isEmpty()) {
          println("Nothing to upload.")
          return
        }
        val expiry = response.expiresIn?.let { " (links expire in $it)" } ?: ""
        // With an agent grant there is no GitHub credential source to name.
        val admitted =
          response.credentialSource?.let { "authenticated from $it" }
            ?: "authenticated by the agent access grant for this host"
        println(
          "Uploaded ${response.files.size} image(s) to ${response.rawBaseUrl}$expiry, $admitted"
        )
        response.files.forEach { f -> f.rawUrl?.let { println("  ${f.name}: $it") } }
        response.markdown?.let {
          println()
          println("Report markdown, with absolute links — paste this into the PR body:")
          println()
          print(it)
          if (!it.endsWith("\n")) println()
        }
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
    BRANCH,
    SERVE;

    companion object {
      /** Maps `--mechanism` to a forced choice; `auto`, null, or unknown values yield null. */
      fun parse(raw: String?): Mechanism? =
        when (raw?.lowercase()) {
          "gist" -> GIST
          "branch" -> BRANCH
          "serve" -> SERVE
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
        "[--mechanism auto|gist|branch|serve] [--public|--secret] [--desc TEXT] " +
        "[--branch BRANCH] [--remote REMOTE] [--raw-base URL] [--pr-number N] [--message MSG] " +
        "[--allow-non-preview-branch] [--serve-url URL] [--serve-token TOKEN] " +
        "[--github-token-file PATH] [--json]"

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
        "--serve-url",
        "--serve-token",
        "--github-token-file",
      )
    private val FLAGS_NO_VALUE =
      setOf("--json", "--public", "--secret", "--allow-non-preview-branch")

    private val HARD_BLOCKED_BRANCHES = setOf("main", "master", "develop", "trunk", "HEAD")
    private val HARD_BLOCKED_PREFIXES = listOf("release/", "releases/")
    private val PREVIEW_BRANCH_PREFIXES = listOf("compose-preview/", "preview_")
    private val SAFE_REFNAME = Regex("""^[A-Za-z0-9][A-Za-z0-9._/-]*$""")

    /**
     * Claude Code web sessions rewrite `origin` to a loopback proxy
     * (`http://<user>@127.0.0.1:<port>/git/<owner>/<repo>`) fronting github.com. Only loopback
     * hosts with that path shape map to `raw.githubusercontent.com`; GitHub Enterprise remotes need
     * `--raw-base`.
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
     * Resolve the mechanism: an explicit `--mechanism` wins (erroring if unavailable); otherwise
     * serve, then gist, then branch. Bulk input can't use gist. Availability is probed lazily.
     */
    internal fun resolveMechanism(
      mode: Mode,
      forced: Mechanism?,
      gistAvailable: () -> Boolean,
      branchAvailable: () -> Boolean,
      serveAvailable: () -> Boolean = { false },
    ): MechanismResult {
      // Forced serve accepts either input shape.
      if (forced == Mechanism.SERVE) {
        return if (serveAvailable()) MechanismResult.Ok(Mechanism.SERVE)
        else
          MechanismResult.Err(
            "--mechanism serve requested but no host was given: pass --serve-url https://… " +
              "(or set \$COMPOSE_PREVIEW_SERVE_URL)."
          )
      }
      if (mode == Mode.BULK) {
        if (forced == Mechanism.GIST) {
          return MechanismResult.Err(
            "a directory can't be shared as a gist — drop --mechanism gist or pass a markdown report."
          )
        }
        // A configured host outranks the branch push for a directory too.
        if (serveAvailable()) return MechanismResult.Ok(Mechanism.SERVE)
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
        // Already handled above; named so the `when` stays exhaustive without an else.
        Mechanism.SERVE -> MechanismResult.Ok(Mechanism.SERVE)
        null ->
          when {
            // A configured serve host is a deliberate choice, unlike ambient `gh` or a remote, so
            // it wins.
            serveAvailable() -> MechanismResult.Ok(Mechanism.SERVE)
            gistAvailable() -> MechanismResult.Ok(Mechanism.GIST)
            branchAvailable() -> MechanismResult.Ok(Mechanism.BRANCH)
            else ->
              MechanismResult.Err(
                "no way to share: point --serve-url at a compose-preview serve host with the " +
                  "image lane on, install + authenticate the GitHub CLI (`gh`) for gists, or " +
                  "run from a checkout with a pushable remote for the branch mechanism."
              )
          }
      }
    }

    /**
     * Resolve the capture branch: a validated `--branch`, or `compose-preview/share/<current>`.
     * Mainline/release branches are refused as a source.
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

    /** Matches the CI action's format: `Preview renders for PR #N (sha::8)`. */
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
     * Map a GitHub remote URL to its `raw.githubusercontent.com/<owner>/<repo>` prefix, or null
     * when it can't be mapped confidently (use `--raw-base`).
     *
     * Examples:
     * - `https://github.com/owner/repo.git` → `https://raw.githubusercontent.com/owner/repo`
     * - `git@github.com:owner/repo.git` → `https://raw.githubusercontent.com/owner/repo`
     * - `http://x@127.0.0.1:38695/git/owner/repo` → `https://raw.githubusercontent.com/owner/repo`
     * - `git@gitlab.com:owner/repo.git` → null
     */
    internal fun githubRawUrlBase(remoteUrl: String): String? =
      githubOwnerRepo(remoteUrl)?.let { "https://raw.githubusercontent.com/$it" }

    /**
     * `owner/repo` for a GitHub remote in any form this CLI sees (https, ssh, `git@`, the loopback
     * proxy [LOOPBACK_GIT_PROXY]), or null. Read from `.git/config`, which a pull request can't
     * change, so it is usable as an identity for [resolveProjectServeUrl]'s trust check.
     */
    internal fun githubOwnerRepo(remoteUrl: String): String? {
      LOOPBACK_GIT_PROXY.find(remoteUrl)?.let { match ->
        val ownerRepo = match.groupValues[1].removeSuffix(".git").trim('/')
        return ownerRepo.takeIf { it.count { c -> c == '/' } == 1 && it.isNotBlank() }
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
      return ownerRepo
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
  /**
   * The report markdown with image links rewritten to absolute URLs (serve only); null otherwise.
   */
  val markdown: String? = null,
  /** How long the uploaded links live, as the host reported it (serve mechanism only). */
  val expiresIn: String? = null,
  /**
   * Where the GitHub credential came from (`$GITHUB_TOKEN`, `--github-token-file`, `gh auth
   * token`); never the secret.
   */
  val credentialSource: String? = null,
  /** Which source named the host (`--serve-url`, environment, or `gradle.properties`). */
  val serveUrlSource: String? = null,
)

@Serializable
internal data class SharePreviewFile(val path: String, val name: String, val rawUrl: String?)
