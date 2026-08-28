package ee.schimke.composeai.cli.serve

import kotlin.test.Test
import kotlin.test.assertEquals
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/**
 * What we ask a visitor to consent to at sign-in.
 *
 * This used to be a flat `read:user repo` for every deployment. `repo` is full control of *private*
 * repositories — read and write, code and settings, across every private repo the visitor can reach
 * — requested from everyone in order to answer one question about one repository. On a public
 * gating repo it buys nothing: the repo payload the access check now reads is available there to a
 * token with no repository scope at all.
 */
class ServeGithubAuthScopeTest {

  private fun config(repository: String = "yschimke/compose-ai-tools", scope: String? = null) =
    ServeGithubAuthConfig(
      clientId = "id",
      clientSecret = "secret",
      cookieSecret = "0123456789012345678901234567890123",
      repository = repository,
      oauthScope = scope,
    )

  /** An anonymous GET of the gating repo answering [code] — 200 is the definition of public. */
  private fun probing(code: Int): OkHttpClient =
    OkHttpClient.Builder()
      .addInterceptor { chain ->
        Response.Builder()
          .request(chain.request())
          .protocol(Protocol.HTTP_1_1)
          .code(code)
          .message(if (code == 200) "OK" else "Not Found")
          .body("{}".toResponseBody("application/json".toMediaType()))
          .build()
      }
      .build()

  private fun scopeFor(code: Int, scope: String? = null): String =
    ServeGithubAuth(config(scope = scope), anonymousClient = probing(code)).requestedScope()

  @Test
  fun `a public gating repo asks for no repository scope at all`() {
    assertEquals("read:user", scopeFor(200))
    assertEquals(ServeGithubAuth.PUBLIC_REPO_SCOPE, scopeFor(200))
  }

  /**
   * A private repo genuinely needs `repo` — classic OAuth apps have no read-only repository scope,
   * so this is already the narrowest thing that works, not an oversight.
   */
  @Test
  fun `a private gating repo still needs repo`() {
    assertEquals("read:user repo", scopeFor(404))
    assertEquals(ServeGithubAuth.PRIVATE_REPO_SCOPE, scopeFor(404))
  }

  /**
   * Unknown visibility asks for the *wider* scope. Over-requesting inconveniences the visitor;
   * under-requesting would fail their sign-in outright — and this probe runs before anyone has
   * signed in, so a rate limit or a network blip is a realistic way to land here.
   *
   * Deliberately the opposite default from the visibility check inside the access decision, which
   * falls to the stricter *access* rule. Same principle — fail towards the safe side — pointing in
   * opposite directions because the two questions are different.
   */
  @Test
  fun `an unreachable gating repo asks for the wider scope`() {
    assertEquals(ServeGithubAuth.PRIVATE_REPO_SCOPE, scopeFor(500))
    assertEquals(ServeGithubAuth.PRIVATE_REPO_SCOPE, scopeFor(403))
  }

  @Test
  fun `an explicit override wins over the probe`() {
    assertEquals("read:org", scopeFor(200, scope = "read:org"))
    assertEquals("read:org", scopeFor(404, scope = "read:org"))
  }

  @Test
  fun `a blank override falls back to the derived scope`() {
    assertEquals(ServeGithubAuth.PUBLIC_REPO_SCOPE, scopeFor(200, scope = "   "))
  }

  /** The probe is one-shot: a sign-in burst must not become a burst of GitHub calls. */
  @Test
  fun `the visibility probe runs once however often the scope is read`() {
    var calls = 0
    val counting =
      OkHttpClient.Builder()
        .addInterceptor { chain ->
          calls++
          Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body("{}".toResponseBody("application/json".toMediaType()))
            .build()
        }
        .build()
    val auth = ServeGithubAuth(config(), anonymousClient = counting)

    repeat(5) { auth.requestedScope() }

    assertEquals(1, calls)
  }

  /** An override must not probe GitHub at all — there is nothing for the answer to change. */
  @Test
  fun `an override does not probe`() {
    var calls = 0
    val counting =
      OkHttpClient.Builder()
        .addInterceptor { chain ->
          calls++
          Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body("{}".toResponseBody("application/json".toMediaType()))
            .build()
        }
        .build()
    val auth = ServeGithubAuth(config(scope = "read:user"), anonymousClient = counting)

    auth.requestedScope()

    assertEquals(0, calls)
  }
}
