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
 * serve`. What it sends is a GitHub credential with write access to the caller's repository, so the
 * rules are about where that credential may go:
 * - HTTPS, or loopback only: plain `http://` elsewhere is refused, not warned about.
 * - No redirects ([OkHttpClient.followRedirects] off; a `3xx` is an error naming its `Location`).
 * - No credentials in the URL (`https://user:pass@host/` is refused).
 * - The token is never an argument ([AgentGithubToken]) and never printed.
 *
 * The returned URL is unguessable but public (GitHub's proxy fetches PR images anonymously), so an
 * upload is a publication decision. The host verifies the token with GitHub, so it briefly holds
 * it: use a host you trust, and prefer a short-lived token (`${'$'}{{ github.token }}` in CI).
 */
internal class ServeImageUploader(
  baseUrl: String,
  /**
   * The GitHub credential, or null. A host admitting an agent grant with `images` needs none, and
   * an empty bearer would get the caller refused, so the header is omitted and [hostToken]
   * identifies the caller.
   */
  private val token: String?,
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
   * Upload one image and return the absolute URL to embed. [label] (display name / alt text) is a
   * basename, never a path.
   */
  fun upload(file: File, label: String = file.name): Result {
    val request =
      Request.Builder()
        .url("$base/images?name=${encodeQuery(label)}${hostToken.tokenQuery()}")
        .apply { token?.let { header("Authorization", "Bearer $it") } }
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
            val detail = response.body.string().trim().take(400)
            Result.Failed(
              "$base answered ${response.code}${if (detail.isEmpty()) "" else ": $detail"}" +
                // A bodyless 404 or 405 is what a host without `--accept-images` returns (405 when
                // a catch-all matches the path); say the lane may be off rather than suggest a
                // typo.
                if (response.code in LANE_ABSENT_CODES && detail.isEmpty()) IMAGE_LANE_OFF else ""
            )
          }
          else -> parse(response.body.string())
        }
      }
    } catch (e: Exception) {
      // OkHttp exceptions carry the URL, never headers, so the credential can't leak here.
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
   * A non-public host's browse token, in the query like every other serve route; it only ever goes
   * to the host that issued it.
   */
  private fun String?.tokenQuery(): String =
    if (isNullOrBlank()) "" else "&token=${encodeQuery(this)}"

  private fun encodeQuery(value: String): String = java.net.URLEncoder.encode(value, Charsets.UTF_8)

  /** The subset of the host's `201` payload this client reads. */
  @Serializable
  private data class ImageAccepted(val url: String? = null, val expiresIn: String? = null)

  companion object {
    /**
     * Bodyless statuses from an absent image lane: 404 (no match) or 405 (catch-all without
     * `POST`).
     */
    private val LANE_ABSENT_CODES = setOf(404, 405)

    /**
     * Hint for a bodyless [LANE_ABSENT_CODES] answer. [rejectUnsafeUrl] doesn't check the path, so
     * a stray `--serve-url` prefix or a proxy gives the same 404; offer the likely cause and what
     * to check.
     */
    private const val IMAGE_LANE_OFF =
      " — most likely that host does not accept image uploads (it was started without " +
        "--accept-images); a --serve-url with a stray path would answer the same way, so check " +
        "the URL too. Otherwise ask its operator to enable the lane, or share these images " +
        "another way (--mechanism gist / branch)."

    private val OCTET_STREAM = "application/octet-stream".toMediaType()

    private val JSON = Json { ignoreUnknownKeys = true }

    private val LOOPBACK = setOf("127.0.0.1", "localhost", "::1", "[::1]")

    /**
     * [url] with any `user:password@` stripped, for every message naming a destination — refusing
     * such a URL is exactly when it would otherwise be printed. Unparseable input is redacted by
     * shape.
     */
    fun redactedUrl(url: String): String {
      val uri = runCatching { URI(url.trim()) }.getOrNull()
      if (uri?.userInfo == null) {
        // Not a parseable URI: strip anything shaped like userinfo in an authority.
        return url.replace(Regex("""(?<=://)[^/@\s]*@"""), "***@")
      }
      val port = if (uri.port >= 0) ":${uri.port}" else ""
      return "${uri.scheme}://***@${uri.host}$port${uri.rawPath.orEmpty()}"
    }

    /** Null when [url] may be sent a GitHub credential, else the reason it may not. */
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
 * Where the upload's GitHub credential comes from, in order — never a command-line argument, which
 * would leak via shell history, `ps` and CI logs. Explicit sources (a file) come before ambient
 * ones (environment, `gh`), so a named file is never overridden by an inherited `GITHUB_TOKEN`.
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
 * Rewrites a report's image references to their uploaded URLs. Only the serve mechanism needs this:
 * gist and branch publish the markdown beside its images. Matches by basename, the documented
 * reference shape.
 */
internal object SharePreviewMarkdown {

  /** [uploaded] maps basename → absolute URL; unknown references are left untouched. */
  fun rewrite(markdown: String, uploaded: Map<String, String>): String =
    IMAGE_REFERENCE.replace(markdown) { match ->
      val alt = match.groupValues[1]
      val target = match.groupValues[2]
      // So `./shots/before.png` and `before.png` both match the uploaded name.
      val basename = target.substringAfterLast('/').substringBefore('?').substringBefore('#')
      val url = uploaded[basename] ?: return@replace match.value
      "![$alt]($url)"
    }

  /**
   * `![alt](target)`, stopping at whitespace so a title isn't swallowed, and not matching backtick-
   * wrapped targets (a malformed shape that must not be propagated).
   */
  private val IMAGE_REFERENCE = Regex("""!\[([^\]]*)]\(([^)\s`]+)\)""")
}
