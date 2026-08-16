package ee.schimke.composeai.cli.serve

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * End-to-end check for `GET /report-bug` on a real embedded [ServeHttpServer]: what the page shows,
 * what it refuses to echo back, and that it is gated like `/status`.
 *
 * The unit-level shape of the report body lives in [ServeBugReportTest]; this covers the wiring —
 * the route, the token gate, and the resolution of the browser-supplied `from` path into a real
 * session and preview.
 */
class ServeBugReportRouteTest {

  private fun png(): ByteArray =
    ByteArrayOutputStream()
      .also { ImageIO.write(BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", it) }
      .toByteArray()

  private fun bundle(label: String, previewIds: List<String>): ServeBundleHost {
    val dir = Files.createTempDirectory("bugreport-$label").toFile().also { it.deleteOnExit() }
    File(dir, "index.html").writeText("<html></html>")
    File(dir, "previews").apply { mkdirs() }
    previewIds.forEach { File(dir, "previews/$it.png").writeBytes(png()) }
    return ServeBundleHost(
      dir,
      label = label,
      title = "Compose Material 3",
      provenance =
        ServeWeb.CatalogProvenance(
          repo = "yschimke/compose-ai-tools",
          branch = "design-artifacts/compose-m3",
          toolVersion = "0.16.54",
        ),
      declaredBaked = previewIds,
    )
  }

  private val registry = ServeSessionRegistry(open = { null })

  private fun newServer(public: Boolean, token: String): ServeHttpServer {
    registry.register("default-mod", host = bundle("default-mod", listOf("Red")), pinned = true)
    registry.register(
      "compose-m3",
      host = bundle("compose-m3", listOf("button-filled")),
      pinned = true,
    )
    return ServeHttpServer(
        host = "127.0.0.1",
        requestedPort = 0,
        token = token,
        sessions = registry,
        defaultSessionId = "default-mod",
        isPublic = public,
        catalogSessions = listOf("compose-m3"),
      )
      .also { it.start() }
  }

  private var server: ServeHttpServer? = null
  private val client = OkHttpClient()

  private fun get(path: String, token: String? = null): Pair<Int, String> {
    val url = "http://127.0.0.1:${server!!.port}$path"
    val req = Request.Builder().url(url)
    if (token != null) req.header(ServeHttpServer.TOKEN_HEADER, token)
    client.newCall(req.build()).execute().use { r ->
      return r.code to (r.body?.string() ?: "")
    }
  }

  @AfterTest
  fun tearDown() {
    server?.stop()
    registry.close()
  }

  @Test
  fun `the page files against the repo that ships the server, not against a catalog`() {
    server = newServer(public = true, token = "unused")
    val (code, body) = get("/report-bug")
    assertEquals(200, code)
    assertTrue(
      body.contains("action=\"https://github.com/yschimke/compose-ai-tools/issues/new\""),
      body,
    )
    assertTrue(body.contains("Report a bug in the preview server"), body)
  }

  @Test
  fun `server diagnostics are shown on the page before anything is filed`() {
    server = newServer(public = true, token = "unused")
    val (_, body) = get("/report-bug")
    assertTrue(body.contains("What gets sent"), body)
    assertTrue(body.contains("compose-preview"), body)
    assertTrue(body.contains("public (open)"), body)
    // The JVM and OS the renders actually happen on.
    assertTrue(body.contains(System.getProperty("java.version")), body)
    assertTrue(body.contains(System.getProperty("os.arch")), body)
  }

  @Test
  fun `a viewer path resolves to its catalog and preview, and offers that render as evidence`() {
    server = newServer(public = true, token = "unused")
    val (_, body) = get("/report-bug?from=%2Fcompose-m3%2Fp%2Fbutton-filled")
    assertTrue(body.contains("compose-m3"), body)
    assertTrue(body.contains("button-filled"), body)
    assertTrue(body.contains("design-artifacts/compose-m3"), body)
    assertTrue(body.contains("compose-ai-tools 0.16.54"), body)
    assertTrue(body.contains("/compose-m3/render/button-filled.png"), body)
  }

  @Test
  fun `an off-origin from is refused rather than echoed into the page or the report`() {
    server = newServer(public = true, token = "unused")
    val (code, body) = get("/report-bug?from=https%3A%2F%2Fevil.example%2Fphish")
    assertEquals(200, code)
    assertFalse(body.contains("evil.example"), body)
  }

  @Test
  fun `a from naming a preview this server does not have contributes no preview row`() {
    server = newServer(public = true, token = "unused")
    val (_, body) = get("/report-bug?from=%2Fcompose-m3%2Fp%2Fnot-a-preview")
    // The path itself is a legitimate fact — that IS where the visitor was — so it is reported.
    // What must not appear is a Preview row or a render link claiming the id resolved.
    assertFalse(body.contains("<th scope=\"row\">Preview</th>"), body)
    assertFalse(body.contains("render/not-a-preview.png"), body)
    // The system is real, so it survives.
    assertTrue(body.contains("<th scope=\"row\">Design system</th>"), body)
  }

  @Test
  fun `the route is gated like status on a private server`() {
    server = newServer(public = false, token = "s3cret")
    assertEquals(404, get("/report-bug").first)
    assertEquals(200, get("/report-bug", token = "s3cret").first)
  }

  @Test
  fun `a gated report keeps the token out of the issue body but not out of the thumbnail`() {
    server = newServer(public = false, token = "s3cret")
    val (_, body) = get("/report-bug?from=%2Fcompose-m3%2Fp%2Fbutton-filled", token = "s3cret")
    // The hidden `body` input is what gets posted publicly; the token must not be in it.
    val issueBody = body.substringAfter("id=\"cp-bug-body\"").substringBefore(">")
    assertFalse(issueBody.contains("s3cret"), issueBody)
    // The thumbnail is fetched by the visitor's own browser against this gated server, so it does
    // carry the token — otherwise the page proving "this is what I saw" shows a broken image.
    assertTrue(body.contains("/compose-m3/render/button-filled.png?token=s3cret"), body)
  }

  @Test
  fun `every page offers the affordance, and the report page itself does not`() {
    server = newServer(public = true, token = "unused")
    assertTrue(get("/").second.contains("class=\"cp-report-bug\""), "front door")
    assertTrue(get("/status").second.contains("class=\"cp-report-bug\""), "status")
    assertTrue(get("/compose-m3/").second.contains("class=\"cp-report-bug\""), "catalog landing")
    assertFalse(
      get("/report-bug").second.contains("class=\"cp-report-bug\""),
      "the report page is where the footer entry leads",
    )
  }
}
