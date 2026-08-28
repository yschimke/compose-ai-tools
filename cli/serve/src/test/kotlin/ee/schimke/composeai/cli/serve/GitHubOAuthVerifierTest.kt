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

  /**
   * Serves the endpoints `verify` walks, with a caller-chosen permission payload and repository
   * visibility. [repoJson] null makes the repo lookup fail, which must resolve to the strict
   * (public) rule.
   */
  private fun verifierReporting(
    permissionJson: String,
    repoJson: String? = PUBLIC_REPO,
  ): GitHubOAuthVerifier {
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
                url.endsWith("/repos/${config.repository}") -> repoJson
                else -> error("unexpected request to $url")
              }
            Response.Builder()
              .request(chain.request())
              .protocol(Protocol.HTTP_1_1)
              .code(if (body == null) 404 else 200)
              .message(if (body == null) "Not Found" else "OK")
              .body((body ?: "{}").toResponseBody("application/json".toMediaType()))
              .build()
          }
        )
        .build()
    return GitHubOAuthVerifier(client)
  }

  private fun accessFor(permissionJson: String, repoJson: String? = PUBLIC_REPO): Boolean {
    val user =
      verifierReporting(permissionJson, repoJson)
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

  // ── Visibility split ───────────────────────────────────────────────────────────────────────────
  // `read` means opposite things either side of it: on a public repo GitHub hands it to everyone,
  // on a private one somebody deliberately granted it. #3313 required write on both, which locked
  // out read-only collaborators in the case that was never broken.

  @Test
  fun `read on a private repo is repository access`() {
    assertTrue(accessFor("""{"permission":"read","role_name":"read"}""", PRIVATE_REPO))
  }

  @Test
  fun `triage on a private repo is repository access`() {
    assertTrue(accessFor("""{"permission":"read","role_name":"triage"}""", PRIVATE_REPO))
  }

  @Test
  fun `none on a private repo is still not repository access`() {
    assertFalse(accessFor("""{"permission":"none","role_name":"none"}""", PRIVATE_REPO))
  }

  @Test
  fun `write on a private repo is repository access`() {
    assertTrue(accessFor("""{"permission":"write","role_name":"write"}""", PRIVATE_REPO))
  }

  /** Unknown visibility takes the strict branch: an unreadable repo must not widen the gate. */
  @Test
  fun `an unreadable repo falls back to requiring write`() {
    assertFalse(accessFor("""{"permission":"read","role_name":"read"}""", repoJson = null))
    assertTrue(accessFor("""{"permission":"write","role_name":"write"}""", repoJson = null))
  }

  // ── The repo payload's own `permissions` block ────────────────────────────────────────────────
  // Preferred over /collaborators/{login}/permission because it answers visibility AND access in
  // one call, and — the point of the change — is readable on a public repo by a token carrying no
  // `repo` scope. The cases above, whose payloads carry no `permissions`, still exercise the
  // collaborator-endpoint fallback unchanged.

  @Test
  fun `pull-only on a public repo is not repository access`() {
    assertFalse(accessFor(NEVER_CALLED, publicRepo(pull = true)))
  }

  @Test
  fun `push on a public repo is repository access`() {
    assertTrue(accessFor(NEVER_CALLED, publicRepo(pull = true, push = true)))
  }

  @Test
  fun `admin on a public repo is repository access`() {
    assertTrue(accessFor(NEVER_CALLED, publicRepo(pull = true, admin = true)))
  }

  @Test
  fun `maintain on a public repo is repository access`() {
    assertTrue(accessFor(NEVER_CALLED, publicRepo(pull = true, maintain = true)))
  }

  @Test
  fun `pull-only on a private repo is repository access`() {
    assertTrue(accessFor(NEVER_CALLED, privateRepo(pull = true)))
  }

  @Test
  fun `no permissions at all on a private repo is not repository access`() {
    assertFalse(accessFor(NEVER_CALLED, privateRepo()))
  }

  private fun publicRepo(
    pull: Boolean = false,
    push: Boolean = false,
    admin: Boolean = false,
    maintain: Boolean = false,
    triage: Boolean = false,
  ) = repo(private = false, pull, push, admin, maintain, triage)

  private fun privateRepo(
    pull: Boolean = false,
    push: Boolean = false,
    admin: Boolean = false,
    maintain: Boolean = false,
    triage: Boolean = false,
  ) = repo(private = true, pull, push, admin, maintain, triage)

  private fun repo(
    private: Boolean,
    pull: Boolean,
    push: Boolean,
    admin: Boolean,
    maintain: Boolean,
    triage: Boolean,
  ) =
    """{"private":$private,"permissions":{"admin":$admin,"maintain":$maintain,""" +
      """"push":$push,"triage":$triage,"pull":$pull}}"""

  private companion object {
    const val PUBLIC_REPO = """{"private":false}"""
    const val PRIVATE_REPO = """{"private":true}"""

    /**
     * Handed to the collaborator endpoint in the `permissions`-block cases. If the code ever falls
     * back to it there the verdict flips to `true`, so a test asserting `false` would fail loudly
     * rather than passing for the wrong reason.
     */
    const val NEVER_CALLED = """{"permission":"admin","role_name":"admin"}"""
  }
}
