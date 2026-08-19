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
 * End-to-end check that the **viewer** offers the per-preview "report an issue" affordance, on a
 * real embedded [ServeHttpServer].
 *
 * Deliberately a route test rather than a fixture assertion. `ServeWebFixtureTest` builds its
 * goldens by calling [ServeWeb.viewerPage] directly with a `reportIssue` of its own, so the golden
 * kept showing the affordance for the whole period the HTTP handler was passing `reportIssue =
 * null` — a page nobody was serving. Only a request through the real route can tell "the renderer
 * can draw this" apart from "the server actually wires it".
 *
 * The body's shape is covered by [ServeIssueReportTest]; what is checked here is the wiring: that
 * the form is emitted, that it targets the repo owning the preview's Kotlin, that it carries the
 * overrides on screen, and that the two reference-scoped facts stay off a page that cannot name
 * them.
 */
class ServeViewerIssueReportRouteTest {

  private fun png(): ByteArray =
    ByteArrayOutputStream()
      .also { ImageIO.write(BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", it) }
      .toByteArray()

  private fun bundle(label: String, previewIds: List<String>): ServeBundleHost {
    val dir = Files.createTempDirectory("viewer-report-$label").toFile().also { it.deleteOnExit() }
    File(dir, "index.html").writeText("<html></html>")
    File(dir, "previews").apply { mkdirs() }
    previewIds.forEach { File(dir, "previews/$it.png").writeBytes(png()) }
    return ServeBundleHost(
      dir,
      label = label,
      title = "Compose Material 3",
      // Source and delivery deliberately live in DIFFERENT repos, so the assertions can tell
      // "filed against the repo that owns the Kotlin" apart from both the delivery repo and
      // [ServeIssueReport.FALLBACK_REPO] — all three would otherwise be the same string.
      catalogSource =
        ServeWeb.CatalogSource(
          repo = "example/design-catalog",
          ref = "main",
          module = "samples/design-catalog-compose-m3",
        ),
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

  private fun newServer(): ServeHttpServer {
    registry.register(
      "compose-m3",
      host = bundle("compose-m3", listOf("button-filled")),
      pinned = true,
    )
    return ServeHttpServer(
        host = "127.0.0.1",
        requestedPort = 0,
        token = "unused",
        sessions = registry,
        defaultSessionId = "compose-m3",
        isPublic = true,
        catalogSessions = listOf("compose-m3"),
      )
      .also { it.start() }
  }

  private var server: ServeHttpServer? = null
  private val client = OkHttpClient()

  private fun get(path: String): Pair<Int, String> {
    val url = "http://127.0.0.1:${server!!.port}$path"
    client.newCall(Request.Builder().url(url).build()).execute().use { r ->
      return r.code to r.body.string()
    }
  }

  @AfterTest
  fun tearDown() {
    server?.stop()
    registry.close()
  }

  @Test
  fun `the viewer offers a report filed against the repo that owns the preview`() {
    server = newServer()
    val (code, body) = get("/compose-m3/p/button-filled")
    assertEquals(200, code)
    // Named for the tracker it goes to, not "report an issue" — the server has a second report a
    // click away in the footer, and the two used to be told apart only by where they sat.
    assertTrue(body.contains("report a catalog issue"), body)
    assertTrue(
      body.contains("action=\"https://github.com/example/design-catalog/issues/new\""),
      body,
    )
    // Not the delivery repo, and not the fallback — a preview bug goes where the Kotlin lives.
    assertFalse(
      body.contains("action=\"https://github.com/yschimke/compose-ai-tools/issues/new\""),
      body,
    )
  }

  @Test
  fun `the report names the preview and the catalog build it came from`() {
    server = newServer()
    val (_, body) = get("/compose-m3/p/button-filled")
    // The preview's identity is a row of the body's "Which preview" table, not a server-written
    // title — the title is the reporter's to write.
    assertTrue(body.contains("| Preview | `button-filled` |"), body)
    assertTrue(body.contains("design-artifacts/compose-m3"), body)
    assertTrue(body.contains("compose-ai-tools 0.16.54"), body)
  }

  @Test
  fun `the served page asks the reporter for a title and will not take a blank one`() {
    server = newServer()
    val (_, body) = get("/compose-m3/p/button-filled")
    assertTrue(
      body.contains(
        "<input class=\"cp-report-summary-input\" type=\"text\" name=\"title\" required"
      ),
      body,
    )
    assertFalse(body.contains("type=\"hidden\" name=\"title\""), body)
  }

  @Test
  fun `the report carries the overrides in force, not the preview's defaults`() {
    // The whole point of reporting from the viewer: the bug is usually in what the knobs produced.
    server = newServer()
    val (_, body) = get("/compose-m3/p/button-filled?uiMode=dark")
    assertTrue(body.contains("/compose-m3/render/button-filled.png?uiMode=dark"), body)
  }

  @Test
  fun `reference-scoped facts stay on the comparison, which is the page that can name them`() {
    // A viewer names no design reference and has run no parity scorer, so the locator fence and
    // the raw-comparison row must be absent rather than emitted empty or with a placeholder the
    // viewer's own script never fills.
    server = newServer()
    val (_, body) = get("/compose-m3/p/button-filled")
    assertTrue(body.contains("cp-report-body"), body)
    assertFalse(body.contains(ServeIssueReport.LOCATOR_FENCE), body)
    assertFalse(body.contains(ServeIssueReport.RAW_SCORES_PLACEHOLDER), body)
    assertFalse(body.contains("Raw comparison"), body)
  }
}
