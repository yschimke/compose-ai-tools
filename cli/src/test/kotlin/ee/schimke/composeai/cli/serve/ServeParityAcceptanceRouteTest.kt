package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.StreamCodec
import ee.schimke.composeai.daemon.protocol.StreamFrameParams
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * `/{system}/parity` for a catalog whose only parity artifact is its **known differences**.
 *
 * The route's availability guard predates acceptances: no design references, no activity feed and
 * no issue index meant a page of zeroes, so it 404s. A catalog that publishes a known-difference
 * document is exactly the case where that reasoning inverts — with nothing else left, every
 * acceptance in it may name a preview or reference this session no longer serves, which is
 * `orphaned-target`, and the walk is the only thing in the browser that can see it. 404ing here
 * withholds the panel from the catalog that most needs it.
 *
 * And the page carrying that walk is not cacheable. The inventory and the issue rows are baked into
 * the HTML while the document is fetched live at `no-store`, so a page served from cache after an
 * in-place refresh would resolve a fresh document against a stale inventory — a renamed preview
 * then reads as an orphan. The comparison band is generation-bound by `referenceSha256`; a
 * catalog-wide walk has no equivalent anchor, so the page itself must not be stored.
 */
class ServeParityAcceptanceRouteTest {

  private val document = """{"schema":"compose-preview-known-differences/v1","acceptances":[]}"""

  private val host =
    object : ServeHost {
      override val previews = listOf(ServePreview("com.example.Red", "com.example.Red"))
      override val label = "accepts-only"

      override fun knownDifferences(): ServeKnownDifferences.Document =
        ServeKnownDifferences.Document.Text(document)

      // The page under test renders no pixels; the render lane exists only to satisfy the
      // interface.
      override fun render(previewId: String, overrides: PreviewOverrides): RenderOutcome =
        RenderOutcome.Failed("no renderer in this fixture")

      override fun subscribeStream(
        previewId: String,
        overrides: PreviewOverrides,
        codec: StreamCodec?,
        maxFps: Int?,
        onUnavailable: ((String) -> Unit)?,
        onFrame: (StreamFrameParams) -> Unit,
      ): StreamHandle? = null

      override fun activeStreamCount(): Int = 0

      override fun close() = Unit
    }

  private val client = OkHttpClient()

  private fun serve(block: (ServeHttpServer) -> Unit) {
    val registry = ServeSessionRegistry(open = { null })
    registry.register("accepts-only", host = host, pinned = true)
    val server =
      ServeHttpServer(
          host = "127.0.0.1",
          requestedPort = 0,
          token = "unused-in-public",
          sessions = registry,
          defaultSessionId = "accepts-only",
          isPublic = true,
          catalogSessions = listOf("accepts-only"),
        )
        .also { it.start() }
    try {
      block(server)
    } finally {
      server.stop()
      registry.close()
    }
  }

  @Test
  fun `a catalog whose only parity artifact is its acceptances still serves the dashboard`() {
    serve { server ->
      val request =
        Request.Builder().url("http://127.0.0.1:${server.port}/accepts-only/parity").build()
      client.newCall(request).execute().use { response ->
        assertEquals(200, response.code, "the acceptance lane keeps the page reachable")
        val page = response.body.string()
        assertTrue("cp-known-difference-audit" in page, "the walk's payload is on the page: $page")
        // Not `public, max-age=…`: a cached page walks a fresh document against a stale inventory.
        assertEquals("no-store", response.header("Cache-Control"))
      }
    }
  }

  @Test
  fun `the landing offers the view it now serves`() {
    // The landing's chip is gated on the same condition the route serves on, so a lane added to one
    // and not the other leaves a page reachable only by typing its URL.
    serve { server ->
      val request = Request.Builder().url("http://127.0.0.1:${server.port}/accepts-only").build()
      client.newCall(request).execute().use { response ->
        assertEquals(200, response.code)
        val landing = response.body.string()
        assertTrue("/accepts-only/parity" in landing, "the landing links the dashboard: $landing")
      }
    }
  }

  @Test
  fun `the inventory's identity is the session id, not the escaped path segment`() {
    // `@` is legal in a session name and escapes to `%40` in a URL segment, so an identity taken
    // from the base path would spell the same catalog two ways depending on the route form — and an
    // acceptance matches on every recorded field, so one of the two spellings orphans the document.
    val registry = ServeSessionRegistry(open = { null })
    registry.register("acc@ept", host = host, pinned = true)
    val server =
      ServeHttpServer(
          host = "127.0.0.1",
          requestedPort = 0,
          token = "unused-in-public",
          sessions = registry,
          defaultSessionId = "acc@ept",
          isPublic = true,
          catalogSessions = listOf("acc@ept"),
        )
        .also { it.start() }
    try {
      val request =
        Request.Builder().url("http://127.0.0.1:${server.port}/acc%40ept/parity").build()
      client.newCall(request).execute().use { response ->
        assertEquals(200, response.code)
        val page = response.body.string()
        assertTrue("\"system\":\"acc@ept\"" in page, "raw identity in the payload: $page")
        // The URLs stay escaped — that is what a path segment is for. Only the identity is raw.
        assertTrue("/acc%40ept/parity/known-differences.json" in page, "escaped URL: $page")
      }
    } finally {
      server.stop()
      registry.close()
    }
  }

  @Test
  fun `the json view still 404s, because it cannot represent what keeps the page alive`() {
    // `compose-preview-serve/parity/v1` carries coverage, drift, activity and gaps — every one of
    // them empty here — and nothing about acceptances, since the host does not parse that document.
    // A 200 would answer a CI check with a dashboard of zeroes whose one real fact is missing.
    serve { server ->
      for (path in listOf("/accepts-only/parity.json", "/accepts-only/parity?format=json")) {
        val request = Request.Builder().url("http://127.0.0.1:${server.port}$path").build()
        client.newCall(request).execute().use { response -> assertEquals(404, response.code, path) }
      }
    }
  }
}
