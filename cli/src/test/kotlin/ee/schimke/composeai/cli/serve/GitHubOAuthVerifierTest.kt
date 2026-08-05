package ee.schimke.composeai.cli.serve

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/**
 * The repo-access half of sign-in, which is what gates the playground (`/playground`, `POST
 * /api/<v>/compiler/run`, `/pg/<token>`) — the surfaces that compile and run a stranger's Kotlin.
 * Live preview only needs a signed-in user and is unaffected by any of this.
 */
class GitHubOAuthVerifierTest {

  private val config =
    ServeGithubAuthConfig(
      clientId = "id",
      clientSecret = "secret",
      cookieSecret = "0123456789012345678901234567890123",
      repository = "yschimke/compose-ai-tools",
    )

  /** Serves the three endpoints `verify` walks, with a caller-chosen permission payload. */
  private fun verifierReporting(permissionJson: String): GitHubOAuthVerifier {
    val client =
      OkHttpClient.Builder()
        .addInterceptor(
          Interceptor { chain ->
            val url = chain.request().url.toString()
            val body =
              when {
                url.contains("login/oauth/access_token") -> """{"access_token":"t"}"""
                url.endsWith("/user") -> """{"login":"octocat"}"""
                url.contains("/collaborators/") -> permissionJson
                else -> error("unexpected request to $url")
              }
            Response.Builder()
              .request(chain.request())
              .protocol(Protocol.HTTP_1_1)
              .code(200)
              .message("OK")
              .body(body.toResponseBody("application/json".toMediaType()))
              .build()
          }
        )
        .build()
    return GitHubOAuthVerifier(client)
  }

  private fun accessFor(permissionJson: String): Boolean {
    val user =
      verifierReporting(permissionJson)
        .verify("code", "https://example.test/cb", config)
        .getOrThrow()
    assertEquals("octocat", user.login)
    return user.repositoryAccess
  }

  /**
   * The regression this exists for: GitHub answers `read` from the collaborator-permission endpoint
   * for *any* authenticated user on a **public** repository, because reading is what public means.
   * The old `permission != "none"` test therefore admitted every GitHub account to the playground
   * whenever the configured `--github-auth-repo` was public — which is the documented setup.
   */
  @Test
  fun `read on a public repo is not repository access`() {
    assertFalse(accessFor("""{"permission":"read","role_name":"read"}"""))
  }

  @Test
  fun `none is not repository access`() {
    assertFalse(accessFor("""{"permission":"none","role_name":"none"}"""))
  }

  @Test
  fun `triage is not repository access`() {
    assertFalse(accessFor("""{"permission":"read","role_name":"triage"}"""))
  }

  @Test
  fun `write is repository access`() {
    assertTrue(accessFor("""{"permission":"write","role_name":"write"}"""))
  }

  @Test
  fun `admin is repository access`() {
    assertTrue(accessFor("""{"permission":"admin","role_name":"admin"}"""))
  }

  /** `maintain` collapses onto `write` in the legacy field; honour either spelling. */
  @Test
  fun `maintain is repository access`() {
    assertTrue(accessFor("""{"permission":"write","role_name":"maintain"}"""))
  }

  /** An older payload with no `role_name` still decides off the legacy field alone. */
  @Test
  fun `a payload without a role name still reads the legacy permission`() {
    assertTrue(accessFor("""{"permission":"write"}"""))
    assertFalse(accessFor("""{"permission":"read"}"""))
  }
}
