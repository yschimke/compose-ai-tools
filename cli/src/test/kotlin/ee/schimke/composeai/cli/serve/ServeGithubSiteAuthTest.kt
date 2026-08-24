package ee.schimke.composeai.cli.serve

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/**
 * Signing in **from a top-level site host** on a box whose OAuth callback is pinned to one origin.
 *
 * This never worked. The auth cookies were host-only, so a sign-in begun on `m3.preview.coo.ee`
 * wrote its `cp_gh_state` cookie there while GitHub returned to `preview.coo.ee` — the callback saw
 * no state, answered 401, and even a session minted there would have been scoped to the wrong host.
 * The server's answer was to withhold the sign-in affordance on every site, which left live preview
 * and playground snapshot-only on all of them.
 *
 * The fix is a cookie **domain**: written for the parent, both cookies reach the pinned callback
 * host and every site host under it. So the CSRF check happens exactly where it always did, one
 * session covers the whole family, and the only thing the cross-host case adds is a redirect
 * target. These tests pin that, and pin the guards on the redirect target — the one new place a
 * client-supplied value steers the response.
 */
class ServeGithubSiteAuthTest {

  private var now: Instant = Instant.parse("2026-01-01T00:00:00Z")
  private val clock =
    object : Clock() {
      override fun getZone() = ZoneOffset.UTC

      override fun withZone(zone: java.time.ZoneId?) = this

      override fun instant(): Instant = now
    }

  private val fakeGitHub =
    OkHttpClient.Builder()
      .addInterceptor { chain ->
        val request = chain.request()
        val body =
          when (request.url.encodedPath) {
            "/login/oauth/access_token" -> """{"access_token":"token"}"""
            "/user" -> """{"login":"octo"}"""
            else -> """{"private":false,"permissions":{"push":true}}"""
          }
        Response.Builder()
          .request(request)
          .protocol(Protocol.HTTP_1_1)
          .code(200)
          .message("OK")
          .body(body.toResponseBody("application/json".toMediaType()))
          .build()
      }
      .build()

  private fun auth(cookieDomain: String? = "preview.coo.ee") =
    ServeGithubAuth(
      ServeGithubAuthConfig(
        clientId = "client",
        clientSecret = "secret",
        cookieSecret = "x".repeat(32),
        repository = "yschimke/compose-ai-tools",
        callbackBaseUrl = "https://preview.coo.ee",
        cookieDomain = cookieDomain,
      ),
      verifier = GitHubOAuthVerifier(fakeGitHub),
      clock = clock,
      anonymousClient = fakeGitHub,
    )

  private val sites =
    ServeSiteRegistry.of(
      listOf("m3.preview.coo.ee" to "m3-catalog", "wear.preview.coo.ee" to "wear-m3")
    )

  private val registry = ServeSessionRegistry(open = { null })

  private var server: ServeHttpServer? = null

  private fun serverWith(cookieDomain: String? = "preview.coo.ee"): ServeHttpServer =
    server
      ?: ServeHttpServer(
          host = "127.0.0.1",
          requestedPort = 0,
          token = "unused-in-public",
          sessions = registry,
          defaultSessionId = "none",
          isPublic = true,
          githubAuth = auth(cookieDomain),
          sites = sites,
        )
        .also {
          it.start()
          server = it
        }

  @AfterTest
  fun stop() {
    runCatching { server?.stop() }
    runCatching { registry.close() }
  }

  private val noRedirect = OkHttpClient.Builder().followRedirects(false).build()

  private fun get(path: String, host: String?, cookie: String? = null): Response {
    val builder = Request.Builder().url("http://127.0.0.1:${serverWith().port}$path")
    if (host != null) builder.header("X-Forwarded-Host", host)
    if (cookie != null) builder.header("Cookie", cookie)
    return noRedirect.newCall(builder.build()).execute()
  }

  /** Start a sign-in on [host]; returns the OAuth `state` and the `cp_gh_state` cookie pair. */
  private fun start(host: String?): Pair<String, String> =
    get("/auth/github/start?return=%2Fp%2Fbadge", host).use { resp ->
      assertEquals(302, resp.code)
      val state =
        resp.header("Location").orEmpty().substringAfter("state=").substringBefore("&").let {
          java.net.URLDecoder.decode(it, "UTF-8")
        }
      state to resp.header("Set-Cookie").orEmpty().substringBefore(";")
    }

  @Test
  fun `a sign-in started on a site host comes back to that site host`() {
    val (state, stateCookie) = start("m3.preview.coo.ee")
    get("/auth/github/callback?code=ok&state=${enc(state)}", "preview.coo.ee", stateCookie).use {
      assertEquals(302, it.code)
      // The whole point: the visitor lands back on the site they started from, on the page they
      // were reading — not on a relative path resolved against the pinned callback origin, which
      // would silently move them to preview.coo.ee.
      assertEquals("https://m3.preview.coo.ee/p/badge", it.header("Location"))
      val session = it.headers("Set-Cookie").first { c -> c.startsWith("cp_gh_auth=") }
      // …carrying a cookie the site host will actually send back.
      assertTrue(
        session.contains("domain=preview.coo.ee", ignoreCase = true),
        "the session cookie must be scoped to the parent domain, was: $session",
      )
    }
  }

  @Test
  fun `the state cookie is domain-scoped, which is what lets the CSRF check still run`() {
    val (_, stateCookie) = start("m3.preview.coo.ee")
    // The cookie is set on the site host but must be readable at the pinned callback origin.
    // Without
    // the Domain attribute this is exactly where the old flow died.
    assertTrue(stateCookie.startsWith("cp_gh_state="))
    get("/auth/github/start", "m3.preview.coo.ee").use {
      assertTrue(
        it.header("Set-Cookie").orEmpty().contains("domain=preview.coo.ee", ignoreCase = true),
        "the state cookie must span the parent domain",
      )
    }
  }

  @Test
  fun `a callback with no matching state cookie is still refused`() {
    // The login-CSRF guard is unchanged by any of this: an attacker-forged callback carrying their
    // own code has no state cookie in the victim's browser and dies here, as it always did.
    val (state, _) = start("m3.preview.coo.ee")
    get("/auth/github/callback?code=ok&state=${enc(state)}", "preview.coo.ee").use {
      assertEquals(401, it.code)
    }
  }

  @Test
  fun `a state naming a host this server does not serve cannot steer the redirect`() {
    // The origin host is signed, but signing proves only that we minted it — a site removed from
    // config between the two legs, or a state replayed against a differently-configured box, must
    // not become an open redirect off the back of a real sign-in.
    val evil =
      ServeHttpServer(
          host = "127.0.0.1",
          requestedPort = 0,
          token = "unused",
          sessions = ServeSessionRegistry(open = { null }),
          defaultSessionId = "none",
          isPublic = true,
          githubAuth = auth(),
          // Same auth config and secret, but m3 is NOT a site here.
          sites = ServeSiteRegistry.of(listOf("wear.preview.coo.ee" to "wear-m3")),
        )
        .also { it.start() }
    try {
      val (state, stateCookie) = start("m3.preview.coo.ee")
      noRedirect
        .newCall(
          Request.Builder()
            .url("http://127.0.0.1:${evil.port}/auth/github/callback?code=ok&state=${enc(state)}")
            .header("X-Forwarded-Host", "preview.coo.ee")
            .header("Cookie", stateCookie)
            .build()
        )
        .execute()
        .use {
          assertEquals(302, it.code)
          // Falls back to the relative return rather than redirecting off-host.
          assertEquals("/p/badge", it.header("Location"))
        }
    } finally {
      runCatching { evil.stop() }
    }
  }

  @Test
  fun `a sign-in on the main host is unchanged`() {
    val (state, stateCookie) = start("preview.coo.ee")
    get("/auth/github/callback?code=ok&state=${enc(state)}", "preview.coo.ee", stateCookie).use {
      assertEquals(302, it.code)
      // Relative, exactly as before the origin host was ever carried.
      assertEquals("/p/badge", it.header("Location"))
    }
  }

  @Test
  fun `a site's styled 404 follows the authenticated page cache policy`() {
    get("/missing", "m3.preview.coo.ee").use {
      assertEquals(404, it.code)
      assertEquals(ServeHttpServer.ANON_PAGE_CACHE_CONTROL, it.header("Cache-Control"))
      assertTrue(it.headers("Vary").any { value -> value.contains("Cookie", ignoreCase = true) })
    }

    val (state, stateCookie) = start("m3.preview.coo.ee")
    val sessionCookie =
      get(
          "/auth/github/callback?code=ok&state=${enc(state)}",
          "preview.coo.ee",
          stateCookie,
        )
        .use { response ->
          response.headers("Set-Cookie").first { it.startsWith("cp_gh_auth=") }.substringBefore(';')
        }
    get("/missing", "m3.preview.coo.ee", sessionCookie).use {
      assertEquals(404, it.code)
      assertEquals(ServeHttpServer.SIGNED_IN_PAGE_CACHE_CONTROL, it.header("Cache-Control"))
      assertTrue(it.headers("Vary").any { value -> value.contains("Cookie", ignoreCase = true) })
    }
  }

  @Test
  fun `a catalog with nothing behind a login does not invite one`() {
    // A plain static bundle: no live stream to unlock, and no playground compiling against it. The
    // sign-in would change nothing on this page, which is the dead affordance the viewer's own chip
    // refuses to be — so the landing withholds the control even though the sign-in round-trips
    // here perfectly well, as its own /status proves two lines down.
    val dir = java.nio.file.Files.createTempDirectory("static-catalog").toFile()
    dir.deleteOnExit()
    java.io.File(dir, "index.html").writeText("<html></html>")
    java.io.File(dir, "previews").mkdirs()
    registry.register(
      "m3-catalog",
      host = ServeBundleHost(dir, label = "m3-catalog", title = "M3"),
      pinned = true,
    )

    get("/", "m3.preview.coo.ee").use {
      assertEquals(200, it.code)
      assertFalse(
        it.body.string().contains("cp-gh-auth"),
        "a static catalog has no gated lane, so its landing must not offer a sign-in",
      )
    }
    get("/status", "m3.preview.coo.ee").use {
      assertTrue(
        it.body.string().contains("cp-gh-auth"),
        "…and the withholding is about the catalog's lanes, not about this host's round-trip",
      )
    }
  }

  @Test
  fun `a site host only shows the sign-in control when the sign-in can come back`() {
    // The header control follows the same predicate the card and viewer affordances always did:
    // a login that cannot return leaves the visitor signed out, so offering it is a dead end.
    get("/status", "m3.preview.coo.ee").use {
      assertEquals(200, it.code)
      assertTrue(
        it.body.string().contains("cp-gh-auth"),
        "a site under the cookie domain can round-trip a sign-in, so it is offered",
      )
    }

    // Same server, a host it does not serve: nothing may redirect there, so nothing invites it.
    get("/status", "other.preview.coo.ee").use {
      assertEquals(200, it.code)
      assertFalse(
        it.body.string().contains("cp-gh-auth"),
        "a host outside the site list cannot round-trip, so the control is withheld",
      )
    }

    // And with host-only cookies against a pinned callback, no site host can round-trip at all.
    val hostOnly =
      ServeHttpServer(
          host = "127.0.0.1",
          requestedPort = 0,
          token = "unused-in-public",
          sessions = ServeSessionRegistry(open = { null }),
          defaultSessionId = "none",
          isPublic = true,
          githubAuth = auth(cookieDomain = null),
          sites = sites,
        )
        .also { it.start() }
    try {
      val req = { host: String ->
        noRedirect
          .newCall(
            Request.Builder()
              .url("http://127.0.0.1:${hostOnly.port}/status")
              .header("X-Forwarded-Host", host)
              .build()
          )
          .execute()
      }
      req("m3.preview.coo.ee").use {
        assertFalse(
          it.body.string().contains("cp-gh-auth"),
          "the site cannot come back to itself",
        )
      }
      // The pinned callback host itself is unaffected — this is where sign-in always worked.
      req("preview.coo.ee").use {
        assertTrue(it.body.string().contains("cp-gh-auth"), "the main host still offers sign-in")
      }
    } finally {
      runCatching { hostOnly.stop() }
    }
  }

  @Test
  fun `without a cookie domain a site host is told sign-in cannot round-trip`() {
    val hostOnly = auth(cookieDomain = null)
    assertFalse(
      hostOnly.canRoundTrip("m3.preview.coo.ee", sites.hosts),
      "host-only cookies cannot reach a pinned callback, so the affordance must stay withheld",
    )
    assertTrue(hostOnly.canRoundTrip("preview.coo.ee", sites.hosts))
  }

  @Test
  fun `with a cookie domain a configured site round-trips, an outsider does not`() {
    val withDomain = auth()
    assertTrue(withDomain.canRoundTrip("m3.preview.coo.ee", sites.hosts))
    assertTrue(withDomain.canRoundTrip("preview.coo.ee", sites.hosts))
    // Not a configured site, so nothing may be redirected to it…
    assertFalse(withDomain.canRoundTrip("other.preview.coo.ee", sites.hosts))
    // …and a lookalike that merely ends with the domain string is not under it. `endsWith` without
    // the dot would have said yes here and offered a sign-in that cannot work.
    assertFalse(withDomain.canRoundTrip("notpreview.coo.ee", sites.hosts))
    assertFalse(withDomain.canRoundTrip(null, sites.hosts))
  }

  @Test
  fun `a cookie domain that cannot work is refused at startup, not at the Set-Cookie`() {
    fun config(domain: String) =
      ServeGithubAuthConfig(
        clientId = "client",
        clientSecret = "secret",
        cookieSecret = "x".repeat(32),
        repository = "yschimke/compose-ai-tools",
        callbackBaseUrl = "https://preview.coo.ee",
        cookieDomain = domain,
      )
    // A public suffix: the browser would drop the cookie and the visitor would just never be signed
    // in, with nothing logged. Far better to refuse to boot.
    assertFailsWith<IllegalArgumentException> { config("co.uk") }
    // A single label is the same failure in a different shape.
    assertFailsWith<IllegalArgumentException> { config("localhost") }
    // A domain that doesn't cover the callback host writes cookies nobody ever sends back.
    assertFailsWith<IllegalArgumentException> { config("example.com") }
    assertFailsWith<IllegalArgumentException> { config("not a host") }
    // The working shapes: the callback host itself, and a parent of it.
    config("preview.coo.ee")
    config("coo.ee")
    config(".preview.coo.ee")
  }

  private fun enc(v: String): String = java.net.URLEncoder.encode(v, "UTF-8")
}
