package ee.schimke.composeai.cli

import java.io.File
import java.net.URI
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody

/**
 * The client half of the serve host's image lane (`POST /images`), for `share-preview --mechanism
 * serve`.
 *
 * Everything here exists because of what is being sent: a **GitHub credential with write access to
 * the caller's repository**, to a host named on the command line. The upload itself is one POST;
 * the care is all in making sure that credential cannot end up somewhere it shouldn't.
 *
 * ## The rules, and why each one is here
 *
 * - **HTTPS, or loopback.** A `http://` URL to anything but `127.0.0.1` / `localhost` / `::1` is
 *   refused rather than warned about: the token would cross the network in the clear, and a warning
 *   an agent doesn't read is not a control. Loopback is exempt because that's a developer's own
 *   `compose-preview serve`, where there is no network to sniff.
 * - **No redirects.** [OkHttpClient.followRedirects] is off and a `3xx` is an error naming the
 *   `Location` it refused. A redirect is the classic way to walk a credential onto another origin,
 *   and there is no legitimate reason for this endpoint to move.
 * - **No credentials in the URL.** A `https://user:pass@host/` form is refused — it puts a secret
 *   into every log line that records the destination, and it is never what the caller meant.
 * - **The token is never an argument.** It is read from a file, the environment, or `gh auth
 *   token`'s stdout — never from `--github-token`, which would put it in shell history, in `ps`
 *   output, and in any CI log that echoes the command. See [AgentGithubToken].
 * - **The token is never printed.** Not in the summary, not in an error, not in `--json` output.
 *   Errors quote the host and the status, which is what a caller needs to act.
 *
 * ## What the caller is trusting
 *
 * And what the *reader* gets: the URL that comes back is unguessable but not access-controlled, so
 * an upload is a publication decision rather than a share with the repo's collaborators. That is
 * the trade that makes the lane work — GitHub's image proxy fetches a PR body's images anonymously
 * — but it means a render of something unreleased does not belong on a public host.
 *
 * The host verifies the presented token by asking GitHub who it belongs to, which means it *holds*
 * that credential for the length of a request. Its side promises not to keep it (used for two
 * reads, dropped, cache keyed by SHA-256) — but that is a promise made by whoever runs the box.
 * Point this at a host you trust with a repo-scoped credential, and prefer a short-lived one: in
 * CI, `${'$'}{{ github.token }}` expires with the job, where a personal access token does not.
 */
internal class ServeImageUploader(
  baseUrl: String,
  private val token: String,
  /** The host's own browse token (`--token`), for a serve box that isn't `--public`. */
  private val hostToken: String? = null,
  private val client: OkHttpClient =
    OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build(),
) {

  private val base = baseUrl.trimEnd('/')

  sealed interface Result {
    data class Ok(val url: String, val expiresIn: String?) : Result

    data class Failed(val reason: String) : Result
  }

  /**
   * Upload one image and return the absolute URL to embed.
   *
   * [label] is what the host uses as a display name and alt text; it is the file's basename, never
   * its path — the server treats it as a label, and there is no reason to disclose the caller's
   * directory layout to it.
   */
  fun upload(file: File, label: String = file.name): Result {
    val request =
      Request.Builder()
        .url("$base/images?name=${encodeQuery(label)}${hostToken.tokenQuery()}")
        .header("Authorization", "Bearer $token")
        .header("Accept", "application/json")
        .post(file.asRequestBody(OCTET_STREAM))
        .build()
    return try {
      client.newCall(request).execute().use { response ->
        when {
          response.isRedirect ->
            Result.Failed(
              "refusing to follow a redirect from $base (to " +
                "${response.header("Location") ?: "an unnamed location"}): a credential must not " +
                "travel to a host you did not name."
            )
          !response.isSuccessful -> {
            val detail = response.body?.string()?.trim()?.take(400).orEmpty()
            Result.Failed(
              "$base answered ${response.code}${if (detail.isEmpty()) "" else ": $detail"}"
            )
          }
          else -> parse(response.body?.string().orEmpty())
        }
      }
    } catch (e: Exception) {
      // The message may name the host but can never name the credential — it was a header, and
      // OkHttp's exceptions carry the URL, not the headers.
      Result.Failed("could not reach $base: ${e.message ?: e.javaClass.simpleName}")
    }
  }

  private fun parse(body: String): Result {
    val accepted =
      try {
        JSON.decodeFromString(ImageAccepted.serializer(), body)
      } catch (e: Exception) {
        return Result.Failed("$base did not answer with an image-lane response")
      }
    val url = accepted.url?.takeIf { it.isNotBlank() } ?: return Result.Failed("no url in response")
    return Result.Ok(url, accepted.expiresIn)
  }

  /**
   * A non-public host wants its browse token on the request too. It rides in the query because that
   * is where every other serve route reads it from, and unlike the GitHub credential it is only
   * ever pointed at the host that issued it.
   */
  private fun String?.tokenQuery(): String =
    if (isNullOrBlank()) "" else "&token=${encodeQuery(this)}"

  private fun encodeQuery(value: String): String = java.net.URLEncoder.encode(value, Charsets.UTF_8)

  /** The subset of the host's `201` payload this client reads. */
  @Serializable
  private data class ImageAccepted(val url: String? = null, val expiresIn: String? = null)

  companion object {
    private val OCTET_STREAM = "application/octet-stream".toMediaType()

    private val JSON = Json { ignoreUnknownKeys = true }

    private val LOOPBACK = setOf("127.0.0.1", "localhost", "::1", "[::1]")

    /**
     * [url] with any `user:password@` stripped, for printing.
     *
     * Every message that names a destination goes through this. A URL carrying credentials is
     * refused rather than used, but *refusing* it is exactly when its text gets printed to a
     * terminal, a CI log and `--json` output — so the refusal must not be the thing that publishes
     * the secret. Unparseable input is redacted by shape rather than trusted.
     */
    fun redactedUrl(url: String): String {
      val uri = runCatching { URI(url.trim()) }.getOrNull()
      if (uri?.userInfo == null) {
        // Not parseable as a URI (so the check above proves nothing): strip anything that looks
        // like userinfo in an authority, which is the only place a secret can hide in a URL.
        return url.replace(Regex("""(?<=://)[^/@\s]*@"""), "***@")
      }
      val port = if (uri.port >= 0) ":${uri.port}" else ""
      return "${uri.scheme}://***@${uri.host}$port${uri.rawPath.orEmpty()}"
    }

    /**
     * Whether [url] is somewhere a GitHub credential may be sent, or the reason it isn't. Null when
     * the URL is acceptable.
     */
    fun rejectUnsafeUrl(url: String): String? {
      val uri =
        try {
          URI(url)
        } catch (e: Exception) {
          return "invalid --serve-url '${redactedUrl(url)}': ${e.message ?: "not a URL"}"
        }
      val scheme = uri.scheme?.lowercase()
      val host = uri.host?.lowercase()
      if (scheme == null || host.isNullOrBlank()) {
        return "invalid --serve-url '${redactedUrl(url)}': expected something like " +
          "https://preview.example.com"
      }
      if (uri.userInfo != null) {
        return "refusing --serve-url with credentials in it: they leak into every log that " +
          "records the destination. Pass the host alone."
      }
      if (scheme != "https" && !(scheme == "http" && host in LOOPBACK)) {
        return "refusing to send a GitHub token to '${redactedUrl(url)}' over $scheme — use " +
          "https:// (http:// is allowed only for a loopback host)."
      }
      return null
    }
  }
}

/**
 * Where the GitHub credential for an upload comes from, in order, and deliberately **not** from a
 * command-line argument.
 *
 * A `--github-token <value>` flag is the obvious API and the wrong one: it lands in shell history,
 * in `ps` output for every process on the box while the upload runs, and in the log of any CI job
 * that echoes its commands. Every source below avoids that — a file the caller already protects, an
 * environment variable (what CI hands a job anyway), or `gh`'s own stdout.
 *
 * The order puts the explicit choice first and the ambient ones after, so a caller who names a file
 * is never silently overridden by an inherited `GITHUB_TOKEN`.
 */
internal object AgentGithubToken {

  sealed interface Result {
    /** [source] is safe to print — it names where the token came from, never what it is. */
    data class Ok(val token: String, val source: String) : Result

    data class Err(val message: String) : Result
  }

  fun resolve(
    tokenFile: String?,
    env: (String) -> String? = System::getenv,
    ghToken: () -> String? = null.let { { null } },
  ): Result {
    tokenFile?.let { path ->
      val file = File(path)
      if (!file.isFile) return Result.Err("--github-token-file: not a file: $path")
      val token = file.readText().trim()
      if (token.isEmpty()) return Result.Err("--github-token-file: $path is empty")
      return Result.Ok(token, "--github-token-file")
    }
    for (name in ENV_NAMES) {
      env(name)
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.let {
          return Result.Ok(it, "\$$name")
        }
    }
    ghToken()
      ?.trim()
      ?.takeIf { it.isNotEmpty() }
      ?.let {
        return Result.Ok(it, "gh auth token")
      }
    return Result.Err(
      "no GitHub credential for the upload. The host admits collaborators of its configured " +
        "repository, so supply one of:\n" +
        "  \$GITHUB_TOKEN / \$GH_TOKEN   (what a CI job already has — prefer the job's own " +
        "\${{ github.token }}, which expires with the job)\n" +
        "  --github-token-file <path>   (a file you already protect)\n" +
        "  gh auth login                (then this reads `gh auth token` for you)\n" +
        "There is deliberately no --github-token flag: an argument is visible in `ps` and in CI logs."
    )
  }

  private val ENV_NAMES = listOf("GITHUB_TOKEN", "GH_TOKEN")
}

/**
 * Rewrites a report's image references onto the URLs they were uploaded to.
 *
 * The gist and branch mechanisms both publish the markdown *beside* its images, so a relative
 * `![](before.png)` resolves on its own. The serve host has no page to publish the markdown to —
 * the whole output is text the caller pastes into a PR body, where a relative link resolves to
 * nothing. So this is the one mechanism that must rewrite, and it rewrites by **basename**, which
 * is the reference shape the command already documents.
 */
internal object SharePreviewMarkdown {

  /**
   * [uploaded] maps a basename to its absolute URL. A reference whose basename isn't in the map is
   * left exactly as it was: this rewrites what it knows about and never guesses at the rest.
   */
  fun rewrite(markdown: String, uploaded: Map<String, String>): String =
    IMAGE_REFERENCE.replace(markdown) { match ->
      val alt = match.groupValues[1]
      val target = match.groupValues[2]
      // Basename of whatever the reference points at, so `./shots/before.png` and `before.png`
      // both resolve to the file that was uploaded under that name.
      val basename = target.substringAfterLast('/').substringBefore('?').substringBefore('#')
      val url = uploaded[basename] ?: return@replace match.value
      "![$alt]($url)"
    }

  /**
   * `![alt](target)`, with the target stopping at whitespace so a markdown title (`![a](b "t")`)
   * doesn't get swallowed — and deliberately not matching a target already wrapped in backticks,
   * which is the malformed shape the PR-body rule warns about and which this must not propagate.
   */
  private val IMAGE_REFERENCE = Regex("""!\[([^\]]*)]\(([^)\s`]+)\)""")
}
