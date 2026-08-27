package ee.schimke.composeai.cli.serve

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * End-to-end check of the **document lane** over real HTTP: `POST /docs` ingests a known document,
 * the returned `/d/<id>` permalink plays it back, `/d/<id>/raw` hands over the bytes, and the link
 * stops resolving the moment its TTL is up.
 *
 * Runs public (no token) so the assertions stay about the lane, not the auth gate; a host that
 * didn't opt into `--accept-docs` is covered by [documentRoutesAreAbsentWithoutTheOptIn].
 */
class ServeDocRoutingTest {

  private var now = 1_700_000_000_000L

  private val docStore = ServeDocStore(ttlSeconds = 60, allowedHosts = emptyList(), clock = { now })

  private val registry = ServeSessionRegistry(open = { null })

  private val server: ServeHttpServer by lazy {
    ServeHttpServer(
        host = "127.0.0.1",
        requestedPort = 0,
        token = "unused-in-public",
        sessions = registry,
        defaultSessionId = "none",
        isPublic = true,
        docStore = docStore,
      )
      .also { it.start() }
  }

  /** A second host with no document store — the lane must not exist at all there. */
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

  private val client = OkHttpClient()

  @AfterTest
  fun stop() {
    runCatching { server.stop() }
    runCatching { plainServer.stop() }
    runCatching { registry.close() }
  }

  private fun url(path: String, port: Int = server.port) = "http://127.0.0.1:$port$path"

  private fun get(path: String, port: Int = server.port) =
    client.newCall(Request.Builder().url(url(path, port)).build()).execute()

  private fun upload(name: String, bytes: ByteArray, contentType: String = "application/json") =
    client
      .newCall(
        Request.Builder()
          .url(url("/docs?name=$name"))
          .post(bytes.toRequestBody(contentType.toMediaType()))
          .build()
      )
      .execute()

  @Test
  fun `an uploaded lottie is playable at its expiring permalink until the ttl runs out`() {
    val bytes = ServeDocFixtures.lottieDoc()
    val accepted =
      upload("loading.json", bytes).use { response ->
        assertEquals(201, response.code)
        Json.parseToJsonElement(response.body.string()).jsonObject
      }
    val id = accepted["id"]!!.jsonPrimitive.content
    val path = accepted["url"]!!.jsonPrimitive.content
    assertEquals("/d/$id", path)
    assertEquals("Lottie", accepted["format"]!!.jsonPrimitive.content)
    assertEquals("lottie", accepted["formatId"]!!.jsonPrimitive.content)
    assertEquals("1m", accepted["expiresIn"]!!.jsonPrimitive.content)

    get(path).use { response ->
      assertEquals(200, response.code)
      val html = response.body.string()
      // The page mounts the format's vendored player against the document's raw bytes…
      assertTrue(html.contains("/doc-player/lottie/bundle.js"), html.take(400))
      assertTrue(html.contains("$path/raw"), "the page points the player at the raw document")
      // …and surfaces what the server read out of the document.
      assertTrue(html.contains("200 × 100") && html.contains("Spinner"))
      // An expiring capability URL must never be cached by a shared proxy.
      assertEquals("private, no-store", response.header("Cache-Control"))
    }

    get("$path/raw").use { response ->
      assertEquals(200, response.code)
      assertTrue(response.body.contentType().toString().startsWith("application/json"))
      assertEquals("nosniff", response.header("X-Content-Type-Options"))
      assertEquals(bytes.toList(), response.body.bytes().toList())
    }

    // Past the TTL both lanes stop answering — the page as a styled 404, the raw bytes as a plain
    // one — and neither says whether the id ever existed.
    now += 61_000
    get(path).use { assertEquals(404, it.code) }
    get("$path/raw").use { assertEquals(404, it.code) }
  }

  @Test
  fun `a remote compose document is served for the RC player`() {
    val bytes = ServeDocFixtures.remoteComposeDoc(width = 320, height = 320)
    val path =
      upload("watchface.rc", bytes, "application/octet-stream").use { response ->
        assertEquals(201, response.code)
        Json.parseToJsonElement(response.body.string()).jsonObject["url"]!!.jsonPrimitive.content
      }

    get(path).use { response ->
      val html = response.body.string()
      assertTrue(html.contains("/doc-player/remotecompose/bundle.js"))
      // The stage is sized from the document's declared header size before the player loads.
      assertTrue(html.contains("width=\"320\" height=\"320\""), html.take(400))
    }
    get("$path/raw").use { response ->
      assertEquals("application/octet-stream", response.body.contentType().toString())
      assertEquals(bytes.toList(), response.body.bytes().toList())
    }
  }

  @Test
  fun `an upload that is not a known document is refused`() {
    upload("evil.rc", "<html><script>alert(1)</script></html>".toByteArray(), "text/html").use {
      assertEquals(400, it.code)
      assertTrue(it.body.string().contains("unrecognised document format"))
    }
  }

  @Test
  fun `a url upload is refused while no host is allowlisted`() {
    client
      .newCall(
        Request.Builder()
          .url(url("/docs?url=https%3A%2F%2Fexample.com%2Fa.json"))
          .post(ByteArray(0).toRequestBody())
          .build()
      )
      .execute()
      .use {
        assertEquals(400, it.code)
        assertTrue(it.body.string().contains("allowlist"))
      }
  }

  @Test
  fun `an unknown or malformed permalink is a styled 404`() {
    get("/d/aaaaaaaaaaaaaaaaaaaaaa").use { response ->
      assertEquals(404, response.code)
      assertTrue(response.body.string().contains("expired"))
    }
    get("/d/..%2F..%2Fetc").use { assertEquals(404, it.code) }
  }

  @Test
  fun `the upload page and each format's player are served`() {
    get("/docs").use { response ->
      assertEquals(200, response.code)
      val html = response.body.string()
      assertTrue(html.contains("Share a document"))
      // Uploads only: with no allowlisted host the URL field is not rendered (the script still
      // carries its inert wiring, so the check is on the markup).
      assertTrue(
        !html.contains("<form class=\"cp-doc-form\""),
        "no URL form on an upload-only host",
      )
      assertTrue(
        ServeWeb.docUploadPage("t", isPublic = true, ttlSeconds = 60, urlUploadAllowed = true)
          .contains("<form class=\"cp-doc-form\""),
        "a host that allows URL fetches does render the URL form",
      )
    }
    for (format in ServeDocFormats.ALL) {
      get(format.playerPath).use { response ->
        assertEquals(200, response.code, "${format.id} player")
        assertTrue(response.body.contentType().toString().startsWith("text/javascript"))
        assertTrue(response.body.bytes().size > 1000, "${format.id} bundle is vendored")
      }
    }
    get("/doc-player/nope/bundle.js").use { assertEquals(404, it.code) }
  }

  @Test
  fun `a token-gated host puts its token on the permalink the upload page hands back`() {
    // The API answers with the bare `/d/<id>` path (a script already holds the token), but the
    // page's own link must carry the token or the recipient's first click 404s.
    val gated =
      ServeWeb.docUploadPage("s3cret", isPublic = false, ttlSeconds = 60, urlUploadAllowed = false)
    assertTrue(gated.contains("var suffix = \"?token=s3cret\""), "the page knows its own query")
    assertTrue(gated.contains("var path = doc.url + suffix;"), "the link carries it")
    assertTrue(
      gated.contains("esc(path)"),
      "and the anchor uses the suffixed path, not the bare one",
    )
    // A public host adds nothing (its links are token-free by design).
    assertTrue(
      ServeWeb.docUploadPage("s3cret", isPublic = true, ttlSeconds = 60, urlUploadAllowed = false)
        .contains("var suffix = \"\"")
    )
  }

  @Test
  fun documentRoutesAreAbsentWithoutTheOptIn() {
    // No --accept-docs ⇒ no ingestion surface at all; the paths fall through to the session
    // catch-all and 404 like any unknown session.
    get("/docs", plainServer.port).use { assertEquals(404, it.code) }
    get("/d/aaaaaaaaaaaaaaaaaaaaaa", plainServer.port).use { assertEquals(404, it.code) }
    get("/doc-player/lottie/bundle.js", plainServer.port).use { assertEquals(404, it.code) }
  }
}
