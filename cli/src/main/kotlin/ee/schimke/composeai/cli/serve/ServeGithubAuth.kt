package ee.schimke.composeai.cli.serve

import io.ktor.http.Cookie
import io.ktor.http.CookieEncoding
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import io.ktor.server.request.host
import io.ktor.server.request.uri
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.RoutingContext
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

data class ServeGithubAuthConfig(
  val clientId: String,
  val clientSecret: String,
  val cookieSecret: String,
  val repository: String,
  val allowedUsers: Set<String> = emptySet(),
  val callbackBaseUrl: String? = null,
) {
  init {
    require(clientId.isNotBlank()) { "GitHub OAuth client id is required" }
    require(clientSecret.isNotBlank()) { "GitHub OAuth client secret is required" }
    require(cookieSecret.length >= MIN_COOKIE_SECRET_CHARS) {
      "GitHub auth cookie secret must be at least $MIN_COOKIE_SECRET_CHARS characters"
    }
    require(repository.matches(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"))) {
      "GitHub auth repository must be owner/repo"
    }
  }

  companion object {
    const val MIN_COOKIE_SECRET_CHARS = 32
  }
}

class ServeGithubAuth(
  private val config: ServeGithubAuthConfig,
  private val verifier: GitHubOAuthVerifier = GitHubOAuthVerifier(),
  private val clock: Clock = Clock.systemUTC(),
) {
  suspend fun RoutingContext.handleStart() {
    val returnTo = safeReturnTo(call.request.queryParameters["return"] ?: "/")
    val state = signedState(nonce(), returnTo)
    call.response.cookies.append(
      stateCookie(
        state,
        maxAge = STATE_TTL_SECONDS,
        secure = isSecure(call, config.callbackBaseUrl),
      )
    )
    call.respondRedirect(authorizeUrl(call, state))
  }

  suspend fun RoutingContext.handleCallback() {
    val state = call.request.queryParameters["state"].orEmpty()
    val expected = call.request.cookieValue(STATE_COOKIE).orEmpty()
    val code = call.request.queryParameters["code"].orEmpty()
    val statePayload = verifyState(state)
    if (
      state.isBlank() || code.isBlank() || statePayload == null || !tokensMatch(state, expected)
    ) {
      call.respondText("GitHub sign-in failed.", status = HttpStatusCode.Unauthorized)
      return
    }
    val user =
      withContext(Dispatchers.IO) { verifier.verify(code, callbackUrl(call), config) }
        .getOrElse {
          call.respondText("GitHub sign-in failed.", status = HttpStatusCode.Forbidden)
          return
        }
    val session = signedSession(user.login, user.repositoryAccess)
    val secure = isSecure(call, config.callbackBaseUrl)
    call.response.cookies.append(authCookie(session, maxAge = SESSION_TTL_SECONDS, secure = secure))
    call.response.cookies.append(stateCookie("", maxAge = 0, secure = secure))
    call.respondRedirect(statePayload.returnTo)
  }

  fun isAuthenticated(call: ApplicationCall): Boolean {
    return currentLogin(call) != null
  }

  fun currentLogin(call: ApplicationCall): String? {
    val cookie = call.request.cookieValue(AUTH_COOKIE) ?: return null
    return verifySession(cookie)?.login
  }

  fun hasRepositoryAccess(call: ApplicationCall): Boolean {
    val cookie = call.request.cookieValue(AUTH_COOKIE) ?: return false
    return verifySession(cookie)?.repositoryAccess == true
  }

  fun loginPath(call: ApplicationCall): String {
    val current = call.uriWithQuery()
    return "$START_PATH?return=${urlEncode(current)}"
  }

  fun accessRepository(): String = config.repository

  fun isRestrictedToAllowedUsers(): Boolean = config.allowedUsers.isNotEmpty()

  private fun authorizeUrl(call: ApplicationCall, state: String): String {
    val params =
      listOf(
          "client_id" to config.clientId,
          "redirect_uri" to callbackUrl(call),
          "scope" to "read:user repo",
          "state" to state,
        )
        .joinToString("&") { (k, v) -> "$k=${urlEncode(v)}" }
    return "https://github.com/login/oauth/authorize?$params"
  }

  private fun callbackUrl(call: ApplicationCall?): String =
    config.callbackBaseUrl?.trimEnd('/')?.plus(CALLBACK_PATH)
      ?: call?.let { externalOrigin(it) + CALLBACK_PATH }
      ?: CALLBACK_PATH

  private fun signedState(nonce: String, returnTo: String): String = sign("$nonce|$returnTo")

  private fun verifyState(value: String): StatePayload? {
    val payload = verifySigned(value) ?: return null
    val idx = payload.indexOf('|')
    if (idx <= 0) return null
    val returnTo = safeReturnTo(payload.substring(idx + 1))
    return StatePayload(returnTo)
  }

  private fun signedSession(login: String, repositoryAccess: Boolean): String {
    val expiresAt = clock.millis() + SESSION_TTL_SECONDS * 1000
    val repoFlag = if (repositoryAccess) "repo" else "no-repo"
    return sign("${login.lowercase()}|$repoFlag|$expiresAt")
  }

  private fun verifySession(value: String): SessionPayload? {
    val payload = verifySigned(value) ?: return null
    val parts = payload.split("|")
    val (login, repositoryAccess, expiresAt) =
      when (parts.size) {
        // Backwards compatible with cookies minted before playground repo-rights gating. They stay
        // authenticated for live preview, but do not satisfy the stricter playground gate.
        2 -> Triple(parts[0], false, parts[1].toLongOrNull())
        3 -> Triple(parts[0], parts[1] == "repo", parts[2].toLongOrNull())
        else -> return null
      }
    if (expiresAt == null || expiresAt <= clock.millis() || login.isBlank()) return null
    return SessionPayload(login, repositoryAccess)
  }

  private fun sign(payload: String): String {
    val bytes = payload.toByteArray(Charsets.UTF_8)
    val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    val sig = hmac(encoded)
    return "$encoded.$sig"
  }

  private fun verifySigned(value: String): String? {
    val parts = value.split(".", limit = 2)
    if (parts.size != 2 || !tokensMatch(hmac(parts[0]), parts[1])) return null
    return runCatching { Base64.getUrlDecoder().decode(parts[0]).toString(Charsets.UTF_8) }
      .getOrNull()
  }

  private fun hmac(value: String): String {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(config.cookieSecret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
    return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(value.toByteArray()))
  }

  private fun nonce(): String {
    val bytes = ByteArray(18)
    SECURE_RANDOM.nextBytes(bytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
  }

  private fun stateCookie(value: String, maxAge: Long, secure: Boolean): Cookie =
    sessionCookie(STATE_COOKIE, value, maxAge, secure)

  private fun authCookie(value: String, maxAge: Long, secure: Boolean): Cookie =
    sessionCookie(AUTH_COOKIE, value, maxAge, secure)

  /**
   * [secure] is derived per request rather than hardcoded: a deployment terminates TLS at Caddy and
   * must not hand the session cookie back over a plaintext downgrade, while `serve` on
   * `http://localhost` would never see the cookie again if it were always set. See [isSecure].
   */
  private fun sessionCookie(name: String, value: String, maxAge: Long, secure: Boolean): Cookie =
    Cookie(
      name = name,
      value = value,
      path = "/",
      maxAge = maxAge.toInt(),
      secure = secure,
      httpOnly = true,
      encoding = CookieEncoding.URI_ENCODING,
      extensions = mapOf("SameSite" to "Lax"),
    )

  private data class StatePayload(val returnTo: String)

  private data class SessionPayload(val login: String, val repositoryAccess: Boolean)

  companion object {
    const val START_PATH = "/auth/github/start"
    const val CALLBACK_PATH = "/auth/github/callback"
    private const val AUTH_COOKIE = "cp_gh_auth"
    private const val STATE_COOKIE = "cp_gh_state"
    private const val STATE_TTL_SECONDS = 10L * 60
    private const val SESSION_TTL_SECONDS = 12L * 60 * 60
    private val SECURE_RANDOM = SecureRandom()

    fun safeReturnTo(value: String): String =
      if (value.startsWith("/") && !value.startsWith("//")) value else "/"

    fun tokensMatch(expected: String, provided: String?): Boolean {
      if (provided == null) return false
      return MessageDigest.isEqual(expected.toByteArray(), provided.toByteArray())
    }
  }
}

data class GitHubOAuthUser(val login: String, val repositoryAccess: Boolean)

class GitHubOAuthVerifier(private val client: OkHttpClient = OkHttpClient()) {
  fun verify(
    code: String,
    redirectUri: String,
    config: ServeGithubAuthConfig,
  ): Result<GitHubOAuthUser> = runCatching {
    val token = exchangeCode(code, redirectUri, config)
    val login = fetchLogin(token)
    if (config.allowedUsers.isNotEmpty() && login.lowercase() !in config.allowedUsers) {
      error("GitHub user $login is not allowed")
    }
    GitHubOAuthUser(
      login,
      repositoryAccess = fetchRepositoryAccess(token, config.repository, login),
    )
  }

  private fun exchangeCode(
    code: String,
    redirectUri: String,
    config: ServeGithubAuthConfig,
  ): String {
    val body =
      FormBody.Builder()
        .add("client_id", config.clientId)
        .add("client_secret", config.clientSecret)
        .add("code", code)
        .add("redirect_uri", redirectUri)
        .build()
    val request =
      Request.Builder()
        .url("https://github.com/login/oauth/access_token")
        .header(HttpHeaders.Accept, "application/json")
        .post(body)
        .build()
    return client.newCall(request).execute().use { response ->
      if (!response.isSuccessful) error("token exchange failed: ${response.code}")
      val payload = JSON.decodeFromString(GitHubTokenResponse.serializer(), response.body.string())
      payload.accessToken ?: error("token exchange did not return access_token")
    }
  }

  private fun fetchLogin(token: String): String {
    val request =
      Request.Builder()
        .url("https://api.github.com/user")
        .header(HttpHeaders.Authorization, "Bearer $token")
        .header(HttpHeaders.Accept, "application/vnd.github+json")
        .build()
    return client.newCall(request).execute().use { response ->
      if (!response.isSuccessful) error("user lookup failed: ${response.code}")
      JSON.decodeFromString(GitHubUserResponse.serializer(), response.body.string()).login
    }
  }

  /**
   * Whether [login] has access to [repository] that means something — the gate on the playground,
   * which compiles and runs a stranger's Kotlin.
   *
   * What "means something" is depends on the repository's visibility, and #3313 got this half
   * right. On a **public** repo, `read` is what GitHub reports for *every* authenticated user,
   * because reading is what public means — so a read-level gate there admits the whole of GitHub,
   * which is the hole #3313 closed. On a **private** repo, `read` is the opposite: somebody
   * deliberately granted this person access to a repository nobody else can see. Requiring write
   * everywhere, as #3313 did, locked out read-only collaborators in the one case that was never
   * broken.
   *
   * So: public repo → require `admin` / `maintain` / `write`. Private repo → any permission other
   * than `none`, exactly as before #3313. One extra API call per sign-in, on a path that already
   * makes two.
   *
   * When visibility can't be determined the answer is **write**, the safe side: a token that can't
   * read the repo metadata tells us nothing that should widen a gate on code execution.
   */
  private fun fetchRepositoryAccess(token: String, repository: String, login: String): Boolean {
    val request =
      Request.Builder()
        .url("https://api.github.com/repos/$repository/collaborators/$login/permission")
        .header(HttpHeaders.Authorization, "Bearer $token")
        .header(HttpHeaders.Accept, "application/vnd.github+json")
        .build()
    return client.newCall(request).execute().use { response ->
      if (!response.isSuccessful) return@use false
      val payload =
        JSON.decodeFromString(GitHubPermissionResponse.serializer(), response.body.string())
      val permission = payload.permission.lowercase()
      val role = payload.roleName?.trim()?.lowercase()
      val write = permission in WRITE_PERMISSIONS || (role != null && role in WRITE_PERMISSIONS)
      if (write) return@use true
      // Not write. Only a private repo can still qualify, and only on a real (non-`none`) grant.
      val readish = permission != "none" || (role != null && role != "none")
      readish && !isPublicRepository(token, repository)
    }
  }

  /**
   * Whether [repository] is public. Defaults to **true** when the lookup fails or the field is
   * absent — see [fetchRepositoryAccess]: public is the stricter branch, so an unknown visibility
   * falls back to requiring write rather than accepting a bare `read`.
   */
  private fun isPublicRepository(token: String, repository: String): Boolean {
    val request =
      Request.Builder()
        .url("https://api.github.com/repos/$repository")
        .header(HttpHeaders.Authorization, "Bearer $token")
        .header(HttpHeaders.Accept, "application/vnd.github+json")
        .build()
    return runCatching {
        client.newCall(request).execute().use { response ->
          if (!response.isSuccessful) return@use true
          JSON.decodeFromString(GitHubRepositoryResponse.serializer(), response.body.string())
            .private != true
        }
      }
      .getOrDefault(true)
  }

  companion object {
    private val JSON = Json { ignoreUnknownKeys = true }

    /**
     * Values meaning "can push", across both the legacy `permission` field and the fine-grained
     * `role_name` one. `maintain` only ever appears in the latter today, but naming it in both
     * costs nothing and survives GitHub widening the legacy field.
     */
    private val WRITE_PERMISSIONS = setOf("admin", "maintain", "write")
  }
}

@Serializable
private data class GitHubTokenResponse(@SerialName("access_token") val accessToken: String? = null)

@Serializable private data class GitHubUserResponse(val login: String)

@Serializable
private data class GitHubPermissionResponse(
  val permission: String = "none",
  @SerialName("role_name") val roleName: String? = null,
)

/** Only [private] is read; absent means "couldn't tell", which resolves to public. */
@Serializable private data class GitHubRepositoryResponse(val private: Boolean? = null)

private fun ApplicationCall.uriWithQuery(): String = request.uri

private fun io.ktor.server.request.ApplicationRequest.cookieValue(name: String): String? =
  headers[HttpHeaders.Cookie]
    ?.split(";")
    ?.map { it.trim() }
    ?.firstNotNullOfOrNull { part ->
      val idx = part.indexOf('=')
      if (idx > 0 && part.substring(0, idx) == name) part.substring(idx + 1) else null
    }

/**
 * Whether this request reached us over TLS, so the cookies can be marked `secure`.
 *
 * The configured `callbackBaseUrl` is the authoritative answer where it exists — it is the operator
 * stating the public origin — and it is what a reverse-proxied deployment is told to set. Otherwise
 * fall back to the request's own view, which behind a proxy means `X-Forwarded-Proto`.
 */
internal fun isSecure(call: ApplicationCall, callbackBaseUrl: String? = null): Boolean =
  callbackBaseUrl?.trim()?.takeIf { it.isNotEmpty() }?.startsWith("https://", ignoreCase = true)
    ?: externalOrigin(call).startsWith("https://", ignoreCase = true)

private fun externalOrigin(call: ApplicationCall): String {
  val forwardedProto = call.request.headers["X-Forwarded-Proto"]?.substringBefore(",")?.trim()
  val forwardedHost = call.request.headers["X-Forwarded-Host"]?.substringBefore(",")?.trim()
  val proto = forwardedProto?.takeIf { it.isNotBlank() } ?: call.request.origin.scheme
  val host = forwardedHost?.takeIf { it.isNotBlank() } ?: call.request.host()
  return "$proto://$host"
}

private fun urlEncode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8)
