package ee.schimke.composeai.cli

import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * The agent's half of the device-grant flow — see
 * [docs/design/AGENT_ACCESS_GRANTS.md](../../../../../../../docs/design/AGENT_ACCESS_GRANTS.md).
 *
 * Sibling of [ServeImageUploader], and it carries the same transport rules for the same reason:
 * what moves over this connection is a credential, so a plaintext hop or a redirect onto another
 * origin is refused rather than warned about.
 *
 * - **HTTPS, or loopback.** `http://` to anything but `127.0.0.1` / `localhost` / `::1` is an
 *   error. The device secret goes up on the poll and the token comes back down on it; neither
 *   belongs on the wire in the clear. Loopback is exempt because that is the caller's own `serve`.
 * - **No redirects.** A `3xx` is an error naming the `Location` it refused. Following one would
 *   replay the device secret at whatever host the response nominated, which is the classic way to
 *   walk a credential onto an origin the caller never chose.
 * - **No credentials in the URL.** A `https://user:pass@host/` form is refused — see
 *   [AgentAccessStore.normalizeOrigin], which is also what keys the saved grant.
 *
 * The poll timeout is short and the caller loops, rather than one long-poll: an agent that is
 * waiting on a human should be able to print a countdown and be interrupted, and a socket held open
 * for ten minutes through whatever proxies are in the way is the less reliable of the two designs.
 */
internal class AgentAccessClient(
  baseUrl: String,
  private val client: OkHttpClient =
    OkHttpClient.Builder()
      .followRedirects(false)
      .followSslRedirects(false)
      .callTimeout(30, TimeUnit.SECONDS)
      .build(),
) {

  /** The normalised origin every request is sent to, and the key the grant is stored under. */
  val origin: String =
    AgentAccessStore.normalizeOrigin(baseUrl)
      ?: throw IllegalArgumentException(
        "not an http(s) server URL (and credentials in the URL are refused): $baseUrl"
      )

  init {
    require(isSecureEnough(origin)) {
      "refusing to send an access request over plaintext to $origin — use https:// (or a " +
        "loopback address, which never leaves this machine)"
    }
  }

  sealed interface Result<out T> {
    data class Ok<T>(val value: T) : Result<T>

    /** [reason] is safe to print: it names the host and the status, never a secret. */
    data class Err(val reason: String) : Result<Nothing>
  }

  /** `POST /agent-access/request`. */
  fun open(label: String, scope: String, ttlSeconds: Long): Result<OpenResponse> {
    val body =
      JSON.encodeToString(
        OpenRequestBody.serializer(),
        OpenRequestBody(label = label, scope = scope, ttlSeconds = ttlSeconds),
      )
    return post("/agent-access/request", body, OpenResponse.serializer())
  }

  /** `POST /agent-access/poll`. */
  fun poll(requestId: String, deviceSecret: String): Result<PollResponse> {
    val body =
      JSON.encodeToString(
        PollRequestBody.serializer(),
        PollRequestBody(requestId = requestId, deviceSecret = deviceSecret),
      )
    return post("/agent-access/poll", body, PollResponse.serializer())
  }

  /** `GET /agent-access/whoami` with the stored bearer. */
  fun whoami(token: String): Result<WhoamiResponse> =
    get("/agent-access/whoami", token, WhoamiResponse.serializer())

  /** `POST /agent-access/revoke` with the stored bearer. */
  fun revoke(token: String): Result<RevokeResponse> =
    post("/agent-access/revoke", "{}", RevokeResponse.serializer(), token)

  private fun <T> post(
    path: String,
    body: String,
    serializer: kotlinx.serialization.KSerializer<T>,
    token: String? = null,
  ): Result<T> {
    val builder =
      Request.Builder()
        .url(origin + path)
        .header("Accept", "application/json")
        .post(body.toRequestBody(JSON_MEDIA))
    token?.let { builder.header(TOKEN_HEADER, it) }
    return execute(builder.build(), serializer)
  }

  private fun <T> get(
    path: String,
    token: String?,
    serializer: kotlinx.serialization.KSerializer<T>,
  ): Result<T> {
    val builder = Request.Builder().url(origin + path).header("Accept", "application/json")
    token?.let { builder.header(TOKEN_HEADER, it) }
    return execute(builder.build(), serializer)
  }

  private fun <T> execute(
    request: Request,
    serializer: kotlinx.serialization.KSerializer<T>,
  ): Result<T> {
    return try {
      client.newCall(request).execute().use { response ->
        if (response.isRedirect) {
          return Result.Err(
            "$origin redirected to ${response.header("Location")} — refusing to follow it with a " +
              "credential in the request"
          )
        }
        val text = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
          val retry = response.header("Retry-After")?.let { " (retry after ${it}s)" }.orEmpty()
          return Result.Err(
            "$origin answered ${response.code}$retry" +
              text.trim().takeIf { it.isNotEmpty() }?.let { ": ${it.take(300)}" }.orEmpty()
          )
        }
        Result.Ok(JSON.decodeFromString(serializer, text))
      }
    } catch (e: Exception) {
      Result.Err("could not reach $origin: ${e.message}")
    }
  }

  // The wire shapes, mirrored from `ServeAgentGrants`. Deliberately re-declared rather than shared:
  // the CLI and the server are versioned separately and a caller may be talking to an older host,
  // so every field is defaulted and unknown ones are ignored.

  @kotlinx.serialization.Serializable
  private data class OpenRequestBody(val label: String, val scope: String, val ttlSeconds: Long)

  @kotlinx.serialization.Serializable
  private data class PollRequestBody(val requestId: String, val deviceSecret: String)

  @kotlinx.serialization.Serializable
  data class OpenResponse(
    val requestId: String = "",
    val deviceSecret: String = "",
    val userCode: String = "",
    val approveUrl: String = "",
    val pollUrl: String = "",
    val expiresInSeconds: Long = 0,
    val pollIntervalSeconds: Long = 3,
    val requestedScope: String = "",
    val requestedTtlSeconds: Long = 0,
    val maxScope: String = "",
    val maxTtlSeconds: Long = 0,
  )

  @kotlinx.serialization.Serializable
  data class PollResponse(
    val status: String = "",
    val token: String? = null,
    val tokenHeader: String? = null,
    val scopes: List<String> = emptyList(),
    val expiresInSeconds: Long? = null,
    val approvedBy: String? = null,
    val retryAfterSeconds: Long? = null,
    val message: String? = null,
  )

  @kotlinx.serialization.Serializable
  data class WhoamiResponse(
    val active: Boolean = false,
    val scopes: List<String> = emptyList(),
    val expiresInSeconds: Long? = null,
    val approvedBy: String? = null,
    val label: String? = null,
    val fingerprint: String? = null,
  )

  @kotlinx.serialization.Serializable
  data class RevokeResponse(val revoked: Boolean = false, val message: String? = null)

  companion object {
    /** Matches `ServeHttpServer.TOKEN_HEADER`; the server also accepts `Authorization: Bearer`. */
    const val TOKEN_HEADER = "X-Compose-Preview-Token"

    private val JSON = Json { ignoreUnknownKeys = true }
    private val JSON_MEDIA = "application/json".toMediaType()

    private val LOOPBACK = setOf("localhost", "127.0.0.1", "::1")

    /**
     * HTTPS anywhere, or plain HTTP only to this machine.
     *
     * The host is taken from [java.net.URI], not by splitting on `:`. An IPv6 literal is written
     * `http://[::1]:8723`, so the naive split yields `"["` — which matches no loopback entry, and
     * refused a local server as if it were on the open internet.
     */
    fun isSecureEnough(origin: String): Boolean {
      if (origin.startsWith("https://", ignoreCase = true)) return true
      val host = runCatching { java.net.URI(origin).host }.getOrNull()?.lowercase() ?: return false
      return host.removePrefix("[").removeSuffix("]") in LOOPBACK
    }
  }
}
