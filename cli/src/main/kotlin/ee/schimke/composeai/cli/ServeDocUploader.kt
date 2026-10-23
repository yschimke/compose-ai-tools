package ee.schimke.composeai.cli

import java.net.URI
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * The client half of the serve host's document lane (`POST /docs`, `serve --accept-docs`), for `rc
 * upload`. The host sniffs the body, stores it in memory for `--doc-ttl`, and answers with a
 * `/d/<id>` permalink that plays it in the viewer's browser.
 *
 * Unlike the image lane no GitHub credential is involved: the lane admits the host's own operator
 * token (or nobody, on a `--public` box). That token still only goes where it was issued, so:
 * - with a token, HTTPS or loopback only ([rejectUnsafeUrl]);
 * - no redirects (a `3xx` is an error naming its `Location`);
 * - no credentials in the URL.
 */
internal class ServeDocUploader(
  baseUrl: String,
  /**
   * The host's operator token (`serve --token`), sent as `?token=` like every other serve route.
   */
  private val hostToken: String? = null,
  private val client: OkHttpClient =
    SharedOkHttpClient.client.newBuilder().followRedirects(false).followSslRedirects(false).build(),
) {

  private val base = baseUrl.trimEnd('/')

  sealed interface Result {
    /** [url] is absolute; [response] is the host's payload as it sent it. */
    data class Ok(val url: String, val response: DocAccepted) : Result

    data class Failed(val reason: String) : Result
  }

  /** Upload [bytes] under the display label [name] (a basename, never a path). */
  fun upload(bytes: ByteArray, name: String): Result {
    NetworkPolicy.requireForUrl(
      base,
      "Uploading $name to ${redactedUrl(base)}",
      "Keep the document local, or upload to a preview server on this machine.",
    )
    val request =
      Request.Builder()
        .url("$base/docs?name=${encodeQuery(name)}${tokenQuery()}")
        .header("Accept", "application/json")
        .post(bytes.toRequestBody(OCTET_STREAM))
        .build()
    return try {
      client.newCall(request).execute().use { response ->
        when {
          response.isRedirect ->
            Result.Failed(
              "refusing to follow a redirect from ${redactedUrl(base)} (to " +
                "${response.header("Location") ?: "an unnamed location"}): the upload goes only " +
                "to the host you named."
            )
          !response.isSuccessful -> {
            val detail = response.body.string().trim().take(400)
            Result.Failed(
              "${redactedUrl(base)} answered ${response.code}" +
                (if (detail.isEmpty()) "" else ": $detail") +
                if (response.code in LANE_ABSENT_CODES && detail.isEmpty()) DOC_LANE_OFF else ""
            )
          }
          else -> parse(response.body.string())
        }
      }
    } catch (e: Exception) {
      // OkHttp exceptions carry the URL, which may carry `?token=`; name the host only.
      Result.Failed("could not reach ${redactedUrl(base)}: ${e.javaClass.simpleName}")
    }
  }

  private fun parse(body: String): Result {
    val accepted =
      try {
        JSON.decodeFromString(DocAccepted.serializer(), body)
      } catch (_: Exception) {
        return Result.Failed("${redactedUrl(base)} did not answer with a document-lane response")
      }
    val url = accepted.url?.takeIf { it.isNotBlank() } ?: return Result.Failed("no url in response")
    // The host answers `/d/<id>`; resolve it against the base so the printed link opens as is.
    val absolute =
      runCatching { URI("$base/").resolve(url).toString() }.getOrNull()
        ?: return Result.Failed("unusable url in response: $url")
    return Result.Ok(absolute, accepted)
  }

  private fun tokenQuery(): String =
    if (hostToken.isNullOrBlank()) "" else "&token=${encodeQuery(hostToken)}"

  private fun encodeQuery(value: String): String = java.net.URLEncoder.encode(value, Charsets.UTF_8)

  /** The host's `201` payload (`compose-preview-serve/doc/v1`), the fields this client reads. */
  @Serializable
  data class DocAccepted(
    val id: String? = null,
    val name: String? = null,
    val format: String? = null,
    val bytes: Long? = null,
    val url: String? = null,
    val expiresIn: String? = null,
    val expiresAtEpochSeconds: Long? = null,
  )

  companion object {
    private val LANE_ABSENT_CODES = setOf(404, 405)

    private const val DOC_LANE_OFF =
      " — most likely that host does not accept documents (it was started without " +
        "--accept-docs); a --serve-url with a stray path would answer the same way, so check " +
        "the URL too."

    private val OCTET_STREAM = "application/octet-stream".toMediaType()

    private val JSON = Json { ignoreUnknownKeys = true }

    private val LOOPBACK = setOf("127.0.0.1", "localhost", "::1", "[::1]")

    fun redactedUrl(url: String): String = ServeImageUploader.redactedUrl(url)

    /**
     * Null when [url] may be uploaded to, else the reason not. Plain `http://` to a remote host is
     * refused only when [withToken], since the token would cross the network in the clear.
     */
    fun rejectUnsafeUrl(url: String, withToken: Boolean): String? {
      val uri =
        try {
          URI(url)
        } catch (e: Exception) {
          return "invalid --serve-url '${redactedUrl(url)}': ${e.message ?: "not a URL"}"
        }
      val scheme = uri.scheme?.lowercase()
      val host = uri.host?.lowercase()
      if (scheme !in setOf("http", "https") || host.isNullOrBlank()) {
        return "invalid --serve-url '${redactedUrl(url)}': expected something like " +
          "https://preview.example.com"
      }
      if (uri.userInfo != null) {
        return "refusing --serve-url with credentials in it: they leak into every log that " +
          "records the destination. Pass the host alone (and the token with --serve-token)."
      }
      if (withToken && scheme != "https" && host !in LOOPBACK) {
        return "refusing to send a serve token to '${redactedUrl(url)}' over $scheme — use " +
          "https:// (http:// is allowed only for a loopback host)."
      }
      return null
    }
  }
}
