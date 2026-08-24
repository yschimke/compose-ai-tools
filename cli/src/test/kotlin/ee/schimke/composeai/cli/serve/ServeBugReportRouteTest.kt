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
    // A session id may legitimately contain `/` (an on-demand revision), which is not legal in a
    // temp-directory prefix — the id is the session key, not a filename.
    val dir =
      Files.createTempDirectory("bugreport-${label.replace('/', '-')}").toFile().also {
        it.deleteOnExit()
      }
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
    registry.register(
      "compose-m3@abcdef1",
      host = bundle("compose-m3@abcdef1", listOf("button-filled")),
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
      return r.code to r.body.string()
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
    assertTrue(
      body.contains("type=\"text\" name=\"title\" required") &&
        body.contains("placeholder=\"Briefly describe the problem\""),
      body,
    )
    assertTrue(body.contains("name=\"labels\" value=\"ui-report,bug,daemon\""), body)
    assertFalse(body.contains("type=\"hidden\" name=\"title\""), body)
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
  fun `an encoded path session resolves before registry lookup`() {
    server = newServer(public = true, token = "unused")
    val (_, body) = get("/report-bug?from=%2Fcompose-m3%2540abcdef1%2Fp%2Fbutton-filled")
    assertTrue(body.contains("compose-m3@abcdef1"), body)
    assertTrue(body.contains("/compose-m3%40abcdef1/render/button-filled.png"), body)
  }

  @Test
  fun `a root-form viewer resolves through the default session, not just a system prefix`() {
    // `/p/Red` is the standard shape on a plain `compose-preview serve` — it names no system, and
    // resolving only `/{system}/p/…` lost the preview, catalog and screenshot on the most common
    // way this affordance is reached.
    server = newServer(public = true, token = "unused")
    val (_, body) = get("/report-bug?from=%2Fp%2FRed")
    assertTrue(body.contains("<th scope=\"row\">Preview</th>"), body)
    assertTrue(body.contains("Red"), body)
    assertTrue(body.contains("/render/Red.png"), body)
  }

  @Test
  fun `the reported render carries the overrides the visitor had on screen`() {
    // Without this the report embeds the DEFAULT render rather than the one that prompted it.
    server = newServer(public = true, token = "unused")
    val (_, body) = get("/report-bug?from=%2Fcompose-m3%2Fp%2Fbutton-filled%3FuiMode%3Ddark")
    assertTrue(body.contains("/compose-m3/render/button-filled.png?uiMode=dark"), body)
  }

  @Test
  fun `routing-only params never leak into the render URL`() {
    server = newServer(public = true, token = "unused")
    val (_, body) = get("/report-bug?from=%2Fp%2FRed%3Fsession%3Ddefault-mod")
    assertTrue(body.contains("/render/Red.png"), body)
    assertFalse(body.contains("render/Red.png?session"), body)
  }

  @Test
  fun `historical revision pin remains on render evidence`() {
    server = newServer(public = true, token = "unused")
    val (_, body) =
      get(
        "/report-bug?from=%2Fcompose-m3%2Fp%2Fbutton-filled%3Fat%3DABCDEF123456%26session%3Dcompose-m3"
      )
    assertTrue(body.contains("render/button-filled.png?at=abcdef123456"), body)
    assertFalse(body.contains("render/button-filled.png?session"), body)
  }

  @Test
  fun `the server's own pages are never attributed to the default catalog`() {
    // `/`, `/status`, `/docs/…` and a 404 belong to the BOX, not to a system. Falling back to the
    // default session for them would attach that catalog's provenance, trust and render lane to a
    // report about the front door.
    server = newServer(public = true, token = "unused")
    for (path in listOf("%2F", "%2Fstatus", "%2Fdocs%2Fsomething")) {
      val (_, body) = get("/report-bug?from=$path")
      assertFalse(body.contains("<th scope=\"row\">Design system</th>"), "$path: $body")
      assertFalse(body.contains("<th scope=\"row\">Catalog</th>"), "$path: $body")
    }
  }

  @Test
  fun `a path naming an unknown system is not re-attributed to the default one`() {
    // `ref.system` is set but unknown. Falling through to the default session would let a
    // same-named preview there be matched and rendered as if it were the reported one.
    server = newServer(public = true, token = "unused")
    val (_, body) = get("/report-bug?from=%2Fnot-a-system%2Fp%2FRed")
    assertFalse(body.contains("<th scope=\"row\">Design system</th>"), body)
    assertFalse(body.contains("<th scope=\"row\">Preview</th>"), body)
    assertFalse(body.contains("render/Red.png"), body)
    // The path itself is still reported — that IS where the visitor was.
    assertTrue(body.contains("/not-a-system/p/Red"), body)
  }

  @Test
  fun `an explicit session is honoured on query-mode catalog routes with no preview`() {
    // `/?session=…`, `/pages/foo?session=…`, `/parity?session=…` carry the footer and have no
    // system in their path at all — the visitor's own page named the catalog, so it is used.
    server = newServer(public = true, token = "unused")
    for (path in listOf("%2F", "%2Fpages%2Fshape", "%2Fparity")) {
      val (_, body) = get("/report-bug?from=$path%3Fsession%3Dcompose-m3")
      assertTrue(body.contains("design-artifacts/compose-m3"), "$path: $body")
      assertTrue(body.contains("<th scope=\"row\">Design system</th>"), "$path: $body")
    }
  }

  @Test
  fun `a percent-escaped session is decoded before it is looked up`() {
    // `ServeWeb.queryString` percent-encodes it on the way out, so an on-demand revision session
    // arrives as `session=a%2Fb` while the registry stores the raw key — looking up the encoded
    // spelling silently finds nothing and the report loses everything.
    registry.register("rev/one", host = bundle("rev/one", listOf("Blue")), pinned = true)
    server = newServer(public = true, token = "unused")
    val (_, body) = get("/report-bug?from=%2Fp%2FBlue%3Fsession%3Drev%252Fone")
    assertTrue(body.contains("<th scope=\"row\">Preview</th>"), body)
    assertTrue(body.contains("Blue"), body)
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

  @Test
  fun `the floating launcher rides every page, names both trackers, and is absent where it leads`() {
    server = newServer(public = true, token = "unused")
    for (path in listOf("/", "/status", "/compose-m3/", "/compose-m3/p/button-filled")) {
      val body = get(path).second
      assertTrue(body.contains("class=\"cp-fab\""), "$path: no launcher")
      // The whole point of the panel: the two destinations, told apart before the choice.
      assertTrue(body.contains("cp-fab-catalog"), "$path: no catalog half")
      assertTrue(body.contains("preview server</strong>"), "$path: no server half")
      assertTrue(body.contains("<code>yschimke/compose-ai-tools</code>"), "$path: unnamed repo")
      // The capture controls ship hidden; `report-capture.js` unhides them only where the browser
      // can actually grab a frame.
      assertTrue(body.contains("<div class=\"cp-shot\" hidden>"), "$path: capture not hidden")
      assertTrue(body.contains("data-cp-capture=\"element\""), "$path: no element mode")
    }
    assertFalse(
      get("/report-bug").second.contains("class=\"cp-fab\""),
      "the launcher is a button back to the page you are already on",
    )
  }

  @Test
  fun `the report page carries the mount its captures arrive in`() {
    // They travel in `sessionStorage` from the page being reported; the page renders whatever came.
    server = newServer(public = true, token = "unused")
    val body = get("/report-bug").second
    assertTrue(body.contains("class=\"cp-shots\""), body)
    assertTrue(body.contains("report-capture.js"), body)
  }

  @Test
  fun `a report from a browser-composed view says which view, and labels the render as the base one`() {
    // Issue #4261: the embedded PNG is the only picture of a preview this server can make, and on
    // the spec lane it is not what the reporter was looking at.
    server = newServer(public = true, token = "unused")
    val (_, body) =
      get("/report-bug?from=%2Fcompose-m3%2Fp%2Fbutton-filled%3Fmode%3Dspec%26specView%3Dtriptych")
    assertTrue(body.contains("<th scope=\"row\">View</th>"), body)
    assertTrue(body.contains("design spec (triptych)"), body)
    assertTrue(body.contains("The base render of that preview"), body)
  }
}
