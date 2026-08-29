package ee.schimke.composeai.cli.serve

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * End-to-end check of the **image lane** over real HTTP: who may `POST /images`, what the answer
 * carries, and that `GET /i/<id>.png` hands back the bytes to a caller with no credential at all —
 * which is the property the whole feature rests on, since GitHub's image proxy fetches a PR body's
 * images anonymously.
 *
 * The host runs `--public`, so nothing here is riding on the browse token: every refusal below is
 * the GitHub gate refusing, on the same host where reading a preview is wide open.
 */
class ServeImageRoutingTest {

  private var now = 1_700_000_000_000L

  /**
   * A stand-in for the GitHub round-trip. The real gate's decision *rules* are
   * [GitHubOAuthVerifier.verifyAccessToken]'s and tested there; what these tests are about is what
   * each verdict does to a request.
   */
  private class FakeAuth(
    override val repository: String = "yschimke/compose-ai-tools",
    private val collaborators: Map<String, String> = mapOf(TOKEN to "octocat"),
    private val known: Map<String, String> = mapOf(OUTSIDER_TOKEN to "stranger"),
  ) : ServeImageUploadAuth {
    override fun identify(bearerToken: String?): ServeImageUploadAuth.Identity {
      val token =
        bearerToken?.takeIf { it.isNotBlank() } ?: return ServeImageUploadAuth.Identity.Missing
      // Stands in for the GitHub round-trip the real gate makes: what the pre-auth budget exists
      // to bound is how often this runs at all.
      verifications.incrementAndGet()
      collaborators[token]?.let {
        return ServeImageUploadAuth.Identity.Ok(it)
      }
      known[token]?.let {
        return ServeImageUploadAuth.Identity.Refused(
          403,
          "GitHub user $it does not have access to $repository.",
        )
      }
      return ServeImageUploadAuth.Identity.Refused(401, "GitHub could not verify that token.")
    }

    companion object {
      const val TOKEN = "gho_collaborator"
      const val OUTSIDER_TOKEN = "gho_outsider"

      /** How many credentials have been verified, across every instance. */
      val verifications = java.util.concurrent.atomic.AtomicInteger()
    }
  }

  private val imageStore = ServeImageStore(ttlSeconds = 60, clock = { now })

  private val registry = ServeSessionRegistry(open = { null })

  private val server: ServeHttpServer by lazy {
    ServeHttpServer(
        host = "127.0.0.1",
        requestedPort = 0,
        token = "unused-in-public",
        sessions = registry,
        defaultSessionId = "none",
        isPublic = true,
        imageStore = imageStore,
        imageUploadAuth = FakeAuth(),
      )
      .also { it.start() }
  }

  /** The browser path: no bearer, but a repository-matched OAuth session resolved by the host. */
  private val browserServer: ServeHttpServer by lazy {
    ServeHttpServer(
        host = "127.0.0.1",
        requestedPort = 0,
        token = "unused-in-public",
        sessions = ServeSessionRegistry(open = { null }),
        defaultSessionId = "none",
        isPublic = true,
        imageStore = ServeImageStore(ttlSeconds = 60, clock = { now }),
        imageUploadAuth = FakeAuth(),
        imageBrowserLogin = { _, repository ->
          "browser-user".takeIf { repository == "yschimke/compose-ai-tools" }
        },
      )
      .also { it.start() }
  }

  /** A second host with no image lane — the routes must not exist there at all. */
  private val plainServer: ServeHttpServer by lazy {
    ServeHttpServer(
        host = "127.0.0.1",
        requestedPort = 0,
        token = "unused-in-public",
        sessions = ServeSessionRegistry(open = { null }),
        defaultSessionId = "none",
        isPublic = true,
      )
      .also { it.start() }
  }

  /** A third host whose per-caller budget is one upload a minute, to exercise the throttle. */
  private val meteredServer: ServeHttpServer by lazy {
    ServeHttpServer(
        host = "127.0.0.1",
        requestedPort = 0,
        token = "unused-in-public",
        sessions = ServeSessionRegistry(open = { null }),
        defaultSessionId = "none",
        isPublic = true,
        imageStore = ServeImageStore(ttlSeconds = 60, clock = { now }),
        imageUploadAuth = FakeAuth(),
        imageUploadLimiter = ServeRateLimiter(permitsPerWindow = 1, windowSeconds = 60),
      )
      .also { it.start() }
  }

  /**
   * A fourth host for the spray test, with a budget of its own. The pre-auth budget is keyed by
   * address, so two tests sharing one limiter would be sharing one bucket — and would pass or fail
   * on the order they happened to run in.
   */
  private val sprayServer: ServeHttpServer by lazy {
    ServeHttpServer(
        host = "127.0.0.1",
        requestedPort = 0,
        token = "unused-in-public",
        sessions = ServeSessionRegistry(open = { null }),
        defaultSessionId = "none",
        isPublic = true,
        imageStore = ServeImageStore(ttlSeconds = 60, clock = { now }),
        imageUploadAuth = FakeAuth(),
        imageUploadLimiter = ServeRateLimiter(permitsPerWindow = 1, windowSeconds = 60),
      )
      .also { it.start() }
  }

  private val client = OkHttpClient()

  @AfterTest
  fun stop() {
    runCatching { server.stop() }
    runCatching { browserServer.stop() }
    runCatching { plainServer.stop() }
    runCatching { meteredServer.stop() }
    runCatching { sprayServer.stop() }
    runCatching { registry.close() }
  }

  private fun url(path: String, port: Int = server.port) = "http://127.0.0.1:$port$path"

  private fun get(path: String, port: Int = server.port) =
    client.newCall(Request.Builder().url(url(path, port)).build()).execute()

  private fun upload(
    name: String = "before.png",
    bytes: ByteArray = ServeImageFixtures.png(),
    bearer: String? = FakeAuth.TOKEN,
    port: Int = server.port,
  ) =
    client
      .newCall(
        Request.Builder()
          .url(url("/images?name=$name", port))
          .apply { if (bearer != null) header("Authorization", "Bearer $bearer") }
          .post(bytes.toRequestBody("application/octet-stream".toMediaType()))
          .build()
      )
      .execute()

  @Test
  fun `a collaborator's png comes back as an embeddable url that anyone can fetch`() {
    val accepted =
      upload().use { response ->
        assertEquals(201, response.code)
        Json.parseToJsonElement(response.body.string()).jsonObject
      }
    val id = accepted["id"]!!.jsonPrimitive.content
    val path = accepted["path"]!!.jsonPrimitive.content
    val absolute = accepted["url"]!!.jsonPrimitive.content

    assertEquals("/i/$id.png", path)
    assertEquals("png", accepted["formatId"]!!.jsonPrimitive.content)
    assertEquals("octocat", accepted["uploadedBy"]!!.jsonPrimitive.content)
    assertEquals(4, accepted["width"]!!.jsonPrimitive.content.toInt())
    assertEquals(3, accepted["height"]!!.jsonPrimitive.content.toInt())
    assertEquals("http://127.0.0.1:${server.port}$path", absolute)
    // The finished embed line, so the caller never has to assemble the markdown itself.
    assertEquals("![before.png]($absolute)", accepted["markdown"]!!.jsonPrimitive.content)

    // The fetch that matters: no Authorization header, no browse token — what GitHub's image proxy
    // does on behalf of a PR body.
    get(path).use { response ->
      assertEquals(200, response.code)
      assertTrue(response.header("Content-Type")!!.startsWith("image/png"))
      assertEquals("nosniff", response.header("X-Content-Type-Options"))
      assertTrue(response.body.bytes().contentEquals(ServeImageFixtures.png()))
    }
  }

  @Test
  fun `uploading without a github credential is refused and stores nothing`() {
    val before = imageStore.occupancy().count
    upload(bearer = null).use { response ->
      assertEquals(401, response.code)
      assertNotNull(response.header("WWW-Authenticate"))
      val body = response.body.string()
      assertTrue(body.contains("yschimke/compose-ai-tools"), body)
    }
    assertEquals(before, imageStore.occupancy().count)
  }

  @Test
  fun `a repository-matched browser session can upload without exposing its oauth token`() {
    upload(bearer = null, port = browserServer.port).use { response ->
      assertEquals(201, response.code)
      val accepted = Json.parseToJsonElement(response.body.string()).jsonObject
      assertEquals("browser-user", accepted["uploadedBy"]!!.jsonPrimitive.content)
    }
  }

  @Test
  fun `a real github account without access to the gating repo is refused`() {
    upload(bearer = FakeAuth.OUTSIDER_TOKEN).use { response ->
      assertEquals(403, response.code)
      assertTrue(response.body.string().contains("does not have access"))
    }
    upload(bearer = "gho_madeup").use { response -> assertEquals(401, response.code) }
  }

  @Test
  fun `an upload that is not an image is refused`() {
    upload(name = "evil.png", bytes = "<html><script>alert(1)</script></html>".toByteArray()).use {
      assertEquals(400, it.code)
      assertTrue(it.body.string().contains("unrecognised"))
    }
  }

  @Test
  fun `a link 404s once expired, and under a suffix that is not its own`() {
    val path =
      upload().use {
        Json.parseToJsonElement(it.body.string()).jsonObject["path"]!!.jsonPrimitive.content
      }
    val id = path.removePrefix("/i/").removeSuffix(".png")

    get("/i/$id.jpg").use { assertEquals(404, it.code) }
    get("/i/not-a-real-id").use { assertEquals(404, it.code) }
    get(path).use { assertEquals(200, it.code) }

    now += 61_000
    get(path).use { response ->
      assertEquals(404, response.code)
      // An expired id and one that never existed answer the same way — the 404 says nothing about
      // whether there was ever anything here.
      assertEquals("not found", response.body.string())
    }
  }

  @Test
  fun `a caller past their budget is throttled rather than served`() {
    upload(port = meteredServer.port).use { assertEquals(201, it.code) }
    upload(port = meteredServer.port).use { response ->
      assertEquals(429, response.code)
      assertNotNull(response.header("Retry-After"))
    }
  }

  @Test
  fun `an unauthenticated token spray is throttled before it reaches github`() {
    // The budget an anonymous caller is charged against is taken BEFORE the identity check, keyed
    // by address — otherwise each unique bad token buys a synchronous GitHub round-trip on this
    // host's outbound connection, which neither the fingerprint cache nor the per-login budget can
    // bound.
    val attempted = FakeAuth.verifications.get()
    upload(bearer = "gho_spray_1", port = sprayServer.port).use { assertEquals(401, it.code) }
    upload(bearer = "gho_spray_2", port = sprayServer.port).use { response ->
      assertEquals(429, response.code)
      assertNotNull(response.header("Retry-After"))
    }
    assertEquals(
      1,
      FakeAuth.verifications.get() - attempted,
      "the second spray attempt must not have reached the verifier",
    )
  }

  @Test
  fun `the presented token is never echoed back`() {
    upload().use { response ->
      val body = response.body.string()
      assertFalse(body.contains(FakeAuth.TOKEN), body)
    }
  }

  @Test
  fun `the image routes are absent without the opt-in`() {
    // No --accept-images ⇒ no ingestion surface at all.
    upload(port = plainServer.port).use {
      assertTrue(it.code == 404 || it.code == 405, "${it.code}")
    }
    get("/i/anything", port = plainServer.port).use { assertEquals(404, it.code) }
  }
}
