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
    call.response.cookies.append(stateCookie(state, maxAge = STATE_TTL_SECONDS))
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
    val session = signedSession(user)
    call.response.cookies.append(authCookie(session, maxAge = SESSION_TTL_SECONDS))
    call.response.cookies.append(stateCookie("", maxAge = 0))
    call.respondRedirect(statePayload.returnTo)
  }

  fun isAuthenticated(call: ApplicationCall): Boolean {
    val cookie = call.request.cookieValue(AUTH_COOKIE) ?: return false
    return verifySession(cookie) != null
  }

  fun loginPath(call: ApplicationCall): String {
    val current = call.uriWithQuery()
    return "$START_PATH?return=${urlEncode(current)}"
  }

  fun accessRepository(): String = config.repository

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

  private fun signedSession(login: String): String {
    val expiresAt = clock.millis() + SESSION_TTL_SECONDS * 1000
    return sign("${login.lowercase()}|$expiresAt")
  }

  private fun verifySession(value: String): String? {
    val payload = verifySigned(value) ?: return null
    val parts = payload.split("|", limit = 2)
    if (parts.size != 2) return null
    val expiresAt = parts[1].toLongOrNull() ?: return null
    return parts[0].takeIf { expiresAt > clock.millis() && it.isNotBlank() }
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

  private fun stateCookie(value: String, maxAge: Long): Cookie =
    Cookie(
      name = STATE_COOKIE,
      value = value,
      path = "/",
      maxAge = maxAge.toInt(),
      httpOnly = true,
      encoding = CookieEncoding.URI_ENCODING,
      extensions = mapOf("SameSite" to "Lax"),
    )

  private fun authCookie(value: String, maxAge: Long): Cookie =
    Cookie(
      name = AUTH_COOKIE,
      value = value,
      path = "/",
      maxAge = maxAge.toInt(),
      httpOnly = true,
      encoding = CookieEncoding.URI_ENCODING,
      extensions = mapOf("SameSite" to "Lax"),
    )

  private data class StatePayload(val returnTo: String)

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

class GitHubOAuthVerifier(private val client: OkHttpClient = OkHttpClient()) {
  fun verify(code: String, redirectUri: String, config: ServeGithubAuthConfig): Result<String> =
    runCatching {
      val token = exchangeCode(code, redirectUri, config)
      val login = fetchLogin(token)
      if (config.allowedUsers.isNotEmpty() && login.lowercase() !in config.allowedUsers) {
        error("GitHub user $login is not allowed")
      }
      val permission = fetchPermission(token, config.repository, login)
      if (permission !in ALLOWED_REPO_PERMISSIONS) error("GitHub user $login is not a collaborator")
      login
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

  private fun fetchPermission(token: String, repo: String, login: String): String {
    val request =
      Request.Builder()
        .url("https://api.github.com/repos/$repo/collaborators/$login/permission")
        .header(HttpHeaders.Authorization, "Bearer $token")
        .header(HttpHeaders.Accept, "application/vnd.github+json")
        .build()
    return client.newCall(request).execute().use { response ->
      if (!response.isSuccessful) error("permission lookup failed: ${response.code}")
      JSON.decodeFromString(GitHubPermissionResponse.serializer(), response.body.string())
        .permission
    }
  }

  companion object {
    private val ALLOWED_REPO_PERMISSIONS = setOf("admin", "maintain", "write", "triage", "read")
    private val JSON = Json { ignoreUnknownKeys = true }
  }
}

@Serializable
private data class GitHubTokenResponse(@SerialName("access_token") val accessToken: String? = null)

@Serializable private data class GitHubUserResponse(val login: String)

@Serializable private data class GitHubPermissionResponse(val permission: String)

private fun ApplicationCall.uriWithQuery(): String = request.uri

private fun io.ktor.server.request.ApplicationRequest.cookieValue(name: String): String? =
  headers[HttpHeaders.Cookie]
    ?.split(";")
    ?.map { it.trim() }
    ?.firstNotNullOfOrNull { part ->
      val idx = part.indexOf('=')
      if (idx > 0 && part.substring(0, idx) == name) part.substring(idx + 1) else null
    }

private fun externalOrigin(call: ApplicationCall): String {
  val forwardedProto = call.request.headers["X-Forwarded-Proto"]?.substringBefore(",")?.trim()
  val forwardedHost = call.request.headers["X-Forwarded-Host"]?.substringBefore(",")?.trim()
  val proto = forwardedProto?.takeIf { it.isNotBlank() } ?: call.request.origin.scheme
  val host = forwardedHost?.takeIf { it.isNotBlank() } ?: call.request.host()
  return "$proto://$host"
}

private fun urlEncode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8)
