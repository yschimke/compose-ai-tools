package ee.schimke.composeai.cli.serve

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * End-to-end routing check for [ServeHttpServer]: a real embedded server fronting two static
 * [ServeBundleHost] sessions, exercised over HTTP. Guards the two access forms — the legacy
 * `?session=` query lane and the canonical path lane (`/<system>/…`) — and, crucially, that the
 * constant top-level routes (`/healthz`, `/version`) still win over the `/{system}` catch-all in
 * Ktor's route scoring (a regression here would 404 liveness checks or shadow `/version`).
 *
 * Runs public (no token) so the assertions stay about routing, not the auth gate ([ServeAuthTest]).
 */
class ServeHttpRoutingTest {

  private val previewId = "com.example.Red"

  private fun png(): ByteArray =
    ByteArrayOutputStream()
      .also { ImageIO.write(BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", it) }
      .toByteArray()

  private fun bundle(label: String): ServeBundleHost {
    val dir = Files.createTempDirectory("routing-$label").toFile().also { it.deleteOnExit() }
    File(dir, "index.html").writeText("<html></html>")
    File(dir, "previews").apply { mkdirs() }
    File(dir, "previews/$previewId.png").writeBytes(png())
    return ServeBundleHost(dir, label = label)
  }

  private val registry = ServeSessionRegistry(open = { null })
  private val server: ServeHttpServer by lazy {
    registry.register("default-mod", host = bundle("default-mod"), pinned = true)
    registry.register("compose-m3", host = bundle("compose-m3"), pinned = true)
    ServeHttpServer(
        host = "127.0.0.1",
        requestedPort = 0,
        token = "unused-in-public",
        sessions = registry,
        defaultSessionId = "default-mod",
        isPublic = true,
        catalogSessions = listOf("compose-m3"),
      )
      .also { it.start() }
  }

  private val client = OkHttpClient()

  private fun get(path: String): Pair<Int, String> {
    val req = Request.Builder().url("http://127.0.0.1:${server.port}$path").build()
    client.newCall(req).execute().use { r ->
      return r.code to (r.body?.string() ?: "")
    }
  }

  @AfterTest
  fun tearDown() {
    server.stop()
    registry.close()
  }

  @Test
  fun `constant top-level routes still win over the system catch-all`() {
    assertEquals(200 to "ok", get("/healthz"))
    val (code, body) = get("/version")
    assertEquals(200, code)
    assertTrue(body.contains("compose-preview-serve/version/v1"), "version json: $body")
  }

  @Test
  fun `a catalog is reachable under its canonical path`() {
    val (landingCode, landing) = get("/compose-m3/")
    assertEquals(200, landingCode)
    // The landing's own links stay on the path (no &session=). Public mode is open, so the link
    // also carries no ?token — the route needs none.
    assertTrue(landing.contains("href=\"/compose-m3/p/$previewId\""), "path card link: $landing")
    assertTrue(!landing.contains("token="), "public path landing links are token-free: $landing")

    val (viewerCode, viewer) = get("/compose-m3/p/$previewId")
    assertEquals(200, viewerCode)
    assertTrue(viewer.contains("data-preview-id=\"$previewId\""), "viewer for the preview")

    // The path render lane returns the baked PNG bytes.
    val renderReq =
      Request.Builder()
        .url("http://127.0.0.1:${server.port}/compose-m3/render/$previewId.png")
        .build()
    client.newCall(renderReq).execute().use { r ->
      assertEquals(200, r.code)
      assertEquals("image/png", r.body?.contentType()?.let { "${it.type}/${it.subtype}" })
    }

    val (apiCode, api) = get("/compose-m3/api/previews")
    assertEquals(200, apiCode)
    assertTrue(api.contains("\"module\":\"compose-m3\""), "api for the path session: $api")
  }

  @Test
  fun `the bare root serves the design-systems home index, not the default module`() {
    val (code, body) = get("/")
    assertEquals(200, code)
    assertTrue(body.contains("Design systems"), "root is the systems index: $body")
    // A card links to the catalog's canonical path and shows a hero preview from its /render lane.
    assertTrue(body.contains("href=\"/compose-m3/\""), "index card links to the system: $body")
    assertTrue(
      body.contains("/compose-m3/render/$previewId.png"),
      "index card renders a hero preview: $body",
    )
    // It is NOT the default module's own preview grid.
    assertTrue(!body.contains("default-mod"), "root is the index, not the default module: $body")
  }

  @Test
  fun `the legacy query session lane still works`() {
    val (code, body) = get("/?session=compose-m3")
    assertEquals(200, code)
    assertTrue(body.contains("com.example"), "query-lane landing lists the preview")

    val (viewerCode, _) = get("/p/$previewId?session=compose-m3")
    assertEquals(200, viewerCode)
  }

  @Test
  fun `an unknown system path 404s like a bad session`() {
    assertEquals(404, get("/no-such-system/").first)
    assertEquals(404, get("/no-such-system/p/$previewId").first)
  }
}
