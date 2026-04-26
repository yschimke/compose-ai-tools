package ee.schimke.composeai.cli

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.io.path.copyTo
import kotlin.system.exitProcess
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * `compose-preview share-gist <markdown> [--public|--secret] [--desc TEXT] [--json] <image>...`
 *
 * Creates a GitHub gist containing a markdown file plus image attachments. `gh gist create` rejects
 * binary inputs, so this seeds the gist with the markdown text-only, then pushes the images into
 * the gist's git repo (gists are git repos).
 *
 * Default visibility is `--secret`. `--public` is opt-in.
 *
 * Image references inside the markdown should use basenames (e.g. `![](before.png)`). This command
 * does NOT rewrite paths.
 */
class ShareGistCommand(args: List<String>) {
  private val jsonOut = "--json" in args
  private val visibility: GistVisibility =
    when {
      "--public" in args -> GistVisibility.PUBLIC
      else -> GistVisibility.SECRET
    }
  private val description: String? = args.flagValue("--desc")
  private val positional: List<String> = parsePositional(args)

  fun run() {
    if (positional.size < 2) {
      System.err.println(USAGE)
      exitProcess(64) // EX_USAGE
    }

    val markdown = File(positional[0])
    val images = positional.drop(1).map(::File)

    if (!markdown.isFile) {
      System.err.println("not a file: ${markdown.path}")
      exitProcess(1)
    }
    val missingImages = images.filterNot { it.isFile }
    if (missingImages.isNotEmpty()) {
      System.err.println("not a file: ${missingImages.joinToString(", ") { it.path }}")
      exitProcess(1)
    }

    // Image basename collisions would silently overwrite each other in the gist's flat git tree.
    // Catch before doing any network work.
    val byName = images.groupBy { it.name }
    val collisions = byName.filterValues { it.size > 1 }
    if (collisions.isNotEmpty()) {
      System.err.println(
        "image basename collisions: ${collisions.keys.joinToString(", ")}. " +
          "Rename so each image has a unique filename."
      )
      exitProcess(1)
    }

    requireOnPath("gh", "Install GitHub CLI: https://cli.github.com")
    requireOnPath("git", "Install git.")

    val (committerName, committerEmail) = readGitIdentity()

    val gistUrl = createGist(markdown)
    val gistId = parseGistId(gistUrl)
    val rawBase = parseRawBase(gistUrl)

    val tmp = Files.createTempDirectory("compose-preview-gist-")
    try {
      val clone = tmp.resolve("g").toFile()
      runOrFail(
        listOf("git", "clone", "--quiet", "https://gist.github.com/$gistId.git", clone.path),
        "git clone of the new gist failed (gist exists at $gistUrl)",
      )

      for (img in images) {
        img.toPath().copyTo(clone.toPath().resolve(img.name), overwrite = true)
      }

      runOrFail(
        listOf("git", "-C", clone.path, "add", "--") + images.map { it.name },
        "git add failed (gist exists at $gistUrl)",
      )
      runOrFail(
        listOf(
          "git",
          "-C",
          clone.path,
          "-c",
          "user.name=$committerName",
          "-c",
          "user.email=$committerEmail",
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
      tmp.toFile().deleteRecursively()
    }

    emit(gistUrl, rawBase, images)
  }

  private fun emit(gistUrl: String, rawBase: String, images: List<File>) {
    if (jsonOut) {
      val response =
        ShareGistResponse(
          url = gistUrl,
          rawBaseUrl = rawBase,
          images =
            images.map { ShareGistImage(path = it.absolutePath, rawUrl = "$rawBase/${it.name}") },
        )
      println(JSON.encodeToString(ShareGistResponse.serializer(), response))
    } else {
      val label = if (visibility == GistVisibility.PUBLIC) "public" else "secret"
      println("Created $label gist: $gistUrl")
      for (img in images) println("  $rawBase/${img.name}")
    }
  }

  private fun createGist(markdown: File): String {
    val cmd = buildList {
      add("gh")
      add("gist")
      add("create")
      when (visibility) {
        GistVisibility.PUBLIC -> add("--public")
        GistVisibility.SECRET -> {} // gh's default
      }
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

  private fun readGitIdentity(): Pair<String, String> {
    val name = exec(listOf("git", "config", "--get", "user.name")).stdout.trim()
    val email = exec(listOf("git", "config", "--get", "user.email")).stdout.trim()
    if (name.isBlank() || email.isBlank()) {
      System.err.println(
        "git user.name / user.email not set. Run:\n" +
          "  git config --global user.name 'Your Name'\n" +
          "  git config --global user.email 'you@example.com'\n" +
          "(Used only as the commit identity inside the throwaway gist clone.)"
      )
      exitProcess(1)
    }
    return name to email
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

  private fun exec(cmd: List<String>): ExecResult {
    return try {
      val p = ProcessBuilder(cmd).redirectErrorStream(false).start()
      val stdout = p.inputStream.bufferedReader().use { it.readText() }
      val stderr = p.errorStream.bufferedReader().use { it.readText() }
      if (!p.waitFor(60, TimeUnit.SECONDS)) {
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

  enum class GistVisibility {
    PUBLIC,
    SECRET,
  }

  companion object {
    private const val USAGE =
      "usage: compose-preview share-gist <markdown> [--public|--secret] [--desc TEXT] [--json] <image>..."

    private val JSON = Json {
      prettyPrint = true
      encodeDefaults = true
    }

    private val FLAGS_TAKING_VALUE = setOf("--desc")
    private val FLAGS_NO_VALUE = setOf("--public", "--secret", "--json")

    /**
     * Picks positional arguments (markdown + images) out of the argv, skipping flags and their
     * values. Mirrors the dispatch logic in [Main] but locally — the CLI's shared [flagValue]
     * helper only does single-flag lookup, not full positional extraction.
     */
    internal fun parsePositional(args: List<String>): List<String> {
      val out = mutableListOf<String>()
      var i = 0
      while (i < args.size) {
        val a = args[i]
        when {
          a in FLAGS_TAKING_VALUE -> i += 2
          a in FLAGS_NO_VALUE -> i += 1
          a.startsWith("--") -> i += 1 // unknown flag — skip just the flag, not a following value
          else -> {
            out += a
            i += 1
          }
        }
      }
      return out
    }

    /**
     * `gh gist create` prints log lines on stderr ("Creating gist…") and the resulting URL on
     * stdout. The URL is the first `https://gist.github.com/...` token. Returns null when no URL is
     * present.
     */
    internal fun extractGistUrl(stdout: String): String? {
      val pattern = Regex("""https://gist\.github\.com/[A-Za-z0-9_./-]+""")
      return pattern.find(stdout)?.value?.trimEnd('/')
    }

    /**
     * Gist URLs have either `https://gist.github.com/<id>` (anonymous) or
     * `https://gist.github.com/<user>/<id>` (authenticated, the common case via `gh`). The git
     * remote is keyed by `<id>` only.
     */
    internal fun parseGistId(url: String): String {
      val tail = url.substringAfter("https://gist.github.com/").trimEnd('/')
      // Last path segment is always the gist id (32-char hex), regardless of whether the username
      // segment is present.
      return tail.substringAfterLast('/')
    }

    /**
     * Raw asset URLs for a gist live on `gist.githubusercontent.com` and require both the username
     * and the gist id. We compute it from the create-time URL rather than the git remote so the
     * username is preserved; anonymous gists fall back to the no-username form.
     */
    internal fun parseRawBase(url: String): String {
      val tail = url.substringAfter("https://gist.github.com/").trimEnd('/')
      val rawTail =
        if ('/' in tail) tail // <user>/<id>
        else tail // <id> only (anonymous)
      return "https://gist.githubusercontent.com/$rawTail/raw"
    }
  }
}

@Serializable
internal data class ShareGistResponse(
  val schema: String = "compose-preview-share-gist/v1",
  val url: String,
  val rawBaseUrl: String,
  val images: List<ShareGistImage>,
)

@Serializable internal data class ShareGistImage(val path: String, val rawUrl: String)
