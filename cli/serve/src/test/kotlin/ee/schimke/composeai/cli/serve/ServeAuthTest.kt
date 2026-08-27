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
  fun `a private server never lets a shared cache keep its prebaked imagery`() {
    // The prebaked hero + grid-thumbnail URLs are content-addressed, so on the public server they
    // are `immutable` — that is what makes a repeat visit paint from cache with no requests. On a
    // token-gated server the same URLs carry the bearer token, and licensing a shared proxy to keep
    // those pixels for a year would outlive the token itself: revoking it would not stop the proxy
    // handing private catalog imagery to anyone with the URL.
    assertEquals(
      "public, max-age=31536000, immutable",
      ServeHttpServer.prebakedImageCacheControl(isPublic = true),
    )
    assertEquals("no-store", ServeHttpServer.prebakedImageCacheControl(isPublic = false))
  }

  @Test
  fun `a published capture is revalidated rather than promised immutable`() {
    // `immutable` is a promise that what a URL names can never change, and every other route that
    // claims it earns the claim by carrying the bytes' own hash in the path. A capture's URL is
    // derived from the STICKER it accompanies, so re-publishing a changed recording reuses it —
    // and a client told to keep the old bytes for a year would have nothing to revalidate against.
    assertFalse(
      ServeHttpServer.MOTION_CACHE_CONTROL.contains("immutable"),
      "a sticker-derived URL must not borrow the content-addressed lanes' immutable promise",
    )
    assertTrue(ServeHttpServer.MOTION_CACHE_CONTROL.contains("must-revalidate"))

    // …and the ETag is what keeps revalidating cheap: it is over the BYTES, so a re-publish under
    // the same id misses and an unchanged one answers 304 without resending many frames.
    val one = ServeHttpServer.motionEtag(byteArrayOf(1, 2, 3))
    assertEquals(one, ServeHttpServer.motionEtag(byteArrayOf(1, 2, 3)))
    assertFalse(one == ServeHttpServer.motionEtag(byteArrayOf(1, 2, 4)))
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
  fun `pages carrying sign-in state are never cached, shared or private`() {
    // Every page on a GitHub-auth server renders the visitor's sign-in state, so none of them may
    // be stored: `public` would let the CDN hand one visitor's login to the next, and even a purely
    // private `max-age` replays the pre-sign-in HTML after the OAuth round-trip — the "still shows
    // me logged out until I refresh" bug.
    assertEquals(
      "private, no-store",
      ServeHttpServer.pageCacheControl(githubAuthConfigured = true, isPublic = true),
    )
    assertEquals(
      "public, max-age=60, stale-while-revalidate=300",
      ServeHttpServer.pageCacheControl(githubAuthConfigured = false, isPublic = true),
    )
    assertEquals(
      "no-store",
      ServeHttpServer.pageCacheControl(githubAuthConfigured = false, isPublic = false),
    )
  }

  /**
   * The narrowing that makes shared links storable. "This server has auth configured" is not the
   * same claim as "this response is personal" — an anonymous request gets the signed-out rendering
   * every anonymous visitor gets, and telling every intermediary and link-preview service not to
   * retain it is a stronger statement than the bytes deserve.
   */
  @Test
  fun `an anonymous page on an auth server is public bytes, not a personal response`() {
    assertEquals(
      "public, max-age=0, s-maxage=300, must-revalidate",
      ServeHttpServer.pageCacheControl(
        githubAuthConfigured = true,
        isPublic = true,
        signedIn = false,
      ),
    )
    // `max-age=0` is load-bearing: the browser still revalidates every visit, so the sign-in chip
    // can never be replayed stale. No `stale-while-revalidate` — that is the directive that would
    // paint the pre-sign-in HTML after the OAuth round-trip.
    assertFalse(
      ServeHttpServer.pageCacheControl(true, isPublic = true, signedIn = false)
        .contains("stale-while-revalidate")
    )
    // A token-gated host stays on `no-store` whoever is asking: its URLs carry a credential, and
    // "nobody is signed in" says nothing about whether a shared cache may store the response.
    assertEquals(
      "private, no-store",
      ServeHttpServer.pageCacheControl(
        githubAuthConfigured = true,
        isPublic = false,
        signedIn = false,
      ),
    )
    // Same rule on the viewer, which is the page most often shared.
    assertEquals(
      "public, max-age=0, s-maxage=300, must-revalidate",
      ServeHttpServer.viewerCacheControl(
        githubAuthConfigured = true,
        isPublic = true,
        signedIn = false,
      ),
    )
  }

  @Test
  fun `the viewer page follows the same personalisation rule as every other page`() {
    // Not just the live-streaming viewer: the sign-in chip and the issue reporter's "filed as @you"
    // are on the static viewer too.
    assertEquals(
      "private, no-store",
      ServeHttpServer.viewerCacheControl(githubAuthConfigured = true, isPublic = true),
    )
    assertEquals(
      "public, max-age=60, stale-while-revalidate=300",
      ServeHttpServer.viewerCacheControl(githubAuthConfigured = false, isPublic = true),
    )
    assertEquals(
      "no-store",
      ServeHttpServer.viewerCacheControl(githubAuthConfigured = false, isPublic = false),
    )
    assertEquals(
      "no-store",
      ServeHttpServer.viewerCacheControl(
        githubAuthConfigured = false,
        isPublic = true,
        stagedCapabilitiesPending = true,
      ),
      "a viewer assembled before staged RC discovery completes must not be cached",
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
