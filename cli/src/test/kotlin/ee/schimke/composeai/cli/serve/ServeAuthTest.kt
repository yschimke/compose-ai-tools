package ee.schimke.composeai.cli.serve

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The token gate ([ServeHttpServer.isAuthorized]). In normal mode the provided token must match; in
 * `--public` mode every request is authorized (the deployed public server, where browsing the
 * catalogs / bundles is the point and safety is structural, not token-based).
 */
class ServeAuthTest {

  private val token = "s3cret-token"

  @Test
  fun `non-public mode requires the matching token`() {
    assertTrue(ServeHttpServer.isAuthorized(token, token, isPublic = false))
    assertFalse(ServeHttpServer.isAuthorized(token, "wrong", isPublic = false))
    assertFalse(ServeHttpServer.isAuthorized(token, null, isPublic = false))
  }

  @Test
  fun `public mode authorizes every request regardless of token`() {
    assertTrue(ServeHttpServer.isAuthorized(token, null, isPublic = true))
    assertTrue(ServeHttpServer.isAuthorized(token, "wrong", isPublic = true))
    assertTrue(ServeHttpServer.isAuthorized(token, token, isPublic = true))
  }

  @Test
  fun `github auth requires a full oauth and repo config`() {
    assertFailsWith<IllegalArgumentException> {
      ServeGithubAuthConfig(
        clientId = "client",
        clientSecret = "secret",
        cookieSecret = "short",
        repository = "yschimke/compose-ai-tools",
      )
    }
    assertFailsWith<IllegalArgumentException> {
      ServeGithubAuthConfig(
        clientId = "client",
        clientSecret = "secret",
        cookieSecret = "x".repeat(32),
        repository = "not-a-repo",
      )
    }
    ServeGithubAuthConfig(
      clientId = "client",
      clientSecret = "secret",
      cookieSecret = "x".repeat(32),
      repository = "yschimke/compose-ai-tools",
    )
  }

  @Test
  fun `github auth only redirects back to local paths`() {
    assertEquals("/compose-m3/p/Button", ServeGithubAuth.safeReturnTo("/compose-m3/p/Button"))
    assertEquals("/", ServeGithubAuth.safeReturnTo("https://evil.example/"))
    assertEquals("/", ServeGithubAuth.safeReturnTo("//evil.example/"))
  }

  @Test
  fun `github auth token match rejects missing or different values`() {
    assertTrue(ServeGithubAuth.tokensMatch("state", "state"))
    assertFalse(ServeGithubAuth.tokensMatch("state", "other"))
    assertFalse(ServeGithubAuth.tokensMatch("state", null))
  }

  @Test
  fun `github protected live viewer pages are not cached`() {
    assertEquals(
      "no-store",
      ServeHttpServer.viewerCacheControl(
        githubAuthConfigured = true,
        hasLiveStream = true,
        isPublic = true,
      ),
    )
    assertEquals(
      "public, max-age=60, stale-while-revalidate=300",
      ServeHttpServer.viewerCacheControl(
        githubAuthConfigured = true,
        hasLiveStream = false,
        isPublic = true,
      ),
    )
    assertEquals(
      "no-store",
      ServeHttpServer.viewerCacheControl(
        githubAuthConfigured = false,
        hasLiveStream = true,
        isPublic = false,
      ),
    )
  }

  @Test
  fun `wasm assets get the content types a streaming wasm load requires`() {
    // application/wasm is mandatory: WebAssembly.instantiateStreaming rejects octet-stream. The
    // ES-module loader (.mjs) and its glue (.js) must be a JS type to execute.
    assertEquals("application/wasm", ServeHttpServer.wasmContentType("composeApp.wasm").toString())
    assertEquals("text/javascript", ServeHttpServer.wasmContentType("composeApp.mjs").toString())
    assertEquals(
      "text/javascript",
      ServeHttpServer.wasmContentType("custom-formatters.js").toString(),
    )
    assertEquals("text/html", ServeHttpServer.wasmContentType("index.html").toString())
    assertEquals(
      "application/json",
      ServeHttpServer.wasmContentType("composeApp.wasm.map").toString(),
    )
  }
}
