package ee.schimke.composeai.cli.serve

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.time.Instant
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * End-to-end check for **top-level sites** ([ServeSites]) on a real embedded [ServeHttpServer]: one
 * box serving three catalogs, with `m3.example.test` published as a site for `compose-m3`.
 *
 * The property under test is that the site host *looks like its own server* while being the same
 * one — so every assertion pairs a site-host request with the identical request on the main host,
 * and the main host's behaviour must be untouched.
 */
class ServeTopLevelSiteTest {

  private val siteHost = "m3.example.test"

  private fun png(): ByteArray =
    ByteArrayOutputStream()
      .also { ImageIO.write(BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", it) }
      .toByteArray()

  private fun bundle(label: String, previewIds: List<String>, title: String): ServeBundleHost {
    val dir = Files.createTempDirectory("site-$label").toFile().also { it.deleteOnExit() }
    File(dir, "index.html").writeText("<html></html>")
    File(dir, "previews").apply { mkdirs() }
    previewIds.forEach { File(dir, "previews/$it.png").writeBytes(png()) }
    return ServeBundleHost(
      dir,
      label = label,
      title = title,
      provenance =
        ServeWeb.CatalogProvenance(
          repo = "yschimke/compose-ai-tools",
          branch = "design-artifacts/$label",
          generatedAt = Instant.parse("2026-05-01T00:00:00Z").toString(),
        ),
      declaredBaked = previewIds,
    )
  }

  private val registry = ServeSessionRegistry(open = { null })
  private var server: ServeHttpServer? = null
  private val client =
    OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build()

  private fun newServer(): ServeHttpServer {
    registry.register(
      "compose-m3",
      host = bundle("compose-m3", listOf("button-filled", "switch-on"), "Compose Material 3"),
      pinned = true,
    )
    registry.register("wear-m3", host = bundle("wear-m3", listOf("chip"), "Wear M3"), pinned = true)
    registry.register("cadence", host = bundle("cadence", listOf("beat"), "Cadence"), pinned = true)
    return ServeHttpServer(
        host = "127.0.0.1",
        requestedPort = 0,
        token = "unused",
        sessions = registry,
        defaultSessionId = "",
        isPublic = true,
        catalogSessions = listOf("compose-m3", "wear-m3"),
        appCatalogSessions = listOf("cadence"),
        sites = ServeSites.of(listOf(siteHost to "compose-m3")),
      )
      .also { it.start() }
  }

  /** A request whose `Host` header is [host] — how a vhost actually reaches this listener. */
  private fun get(path: String, host: String? = null): Triple<Int, String, String?> {
    val url = "http://127.0.0.1:${server!!.port}$path"
    val req = Request.Builder().url(url)
    if (host != null) req.header("Host", host)
    client.newCall(req.build()).execute().use { r ->
      return Triple(r.code, r.body?.string() ?: "", r.header("Location"))
    }
  }

  @AfterTest
  fun tearDown() {
    server?.stop()
    registry.close()
  }

  @Test
  fun `the site root is its catalog's landing, not the front door`() {
    server = newServer()
    val (code, body, _) = get("/", host = siteHost)
    assertEquals(200, code)
    assertTrue(body.contains("Compose Material 3"), "the site opens on its own catalog: $body")
    // The neighbours this box also serves are nowhere on the page.
    assertFalse(body.contains("Wear M3"), "a site must not index its neighbours: $body")
    assertFalse(body.contains("Cadence"), "a site must not index its neighbours: $body")
    // …and there is no way back to a front door that doesn't exist on this hostname.
    assertFalse(body.contains("All design systems"), "no back button on a site: $body")

    // The main host is untouched: `/` is still the index of everything.
    val (mainCode, mainBody, _) = get("/")
    assertEquals(200, mainCode)
    assertTrue(mainBody.contains("Wear M3"), "the main front door still lists every system")
  }

  @Test
  fun `site links stay on the custom domain`() {
    server = newServer()
    val (_, body, _) = get("/", host = siteHost)
    assertTrue(body.contains("\"/p/button-filled\""), "viewer links are rooted: $body")
    assertFalse(
      body.contains("/compose-m3/p/"),
      "a site link must never walk back to the canonical path: $body",
    )

    // Same page on the main host keeps the canonical prefixed form.
    val (_, mainBody, _) = get("/compose-m3/")
    assertTrue(mainBody.contains("\"/compose-m3/p/button-filled\""), mainBody)
  }

  @Test
  fun `the canonical path redirects to the rooted URL on a site host`() {
    server = newServer()
    val (code, _, location) = get("/compose-m3/p/button-filled?theme=dark", host = siteHost)
    assertEquals(301, code)
    assertEquals("/p/button-filled?theme=dark", location)

    // The bare catalog path collapses to the site root.
    assertEquals("/" to 301, get("/compose-m3", host = siteHost).let { it.third to it.first })

    // …and on the main host the same URL is served, not redirected.
    assertEquals(200, get("/compose-m3/p/button-filled").first)
  }

  @Test
  fun `a neighbouring catalog is not reachable through a site host`() {
    server = newServer()
    assertEquals(404, get("/wear-m3/", host = siteHost).first)
    assertEquals(404, get("/cadence/", host = siteHost).first)
    // Both still serve on the main host.
    assertEquals(200, get("/wear-m3/").first)
    assertEquals(200, get("/cadence/").first)
  }

  @Test
  fun `constant routes are untouched by the site rewrite`() {
    server = newServer()
    assertEquals(200, get("/healthz", host = siteHost).first)
    // A root-mounted session route resolves to the site's catalog rather than 404ing on no session.
    val (code, body, _) = get("/api/previews", host = siteHost)
    assertEquals(200, code)
    assertTrue(body.contains("button-filled"), body)
    assertFalse(body.contains("\"beat\""), "the site's API answers for the site's catalog: $body")
  }

  @Test
  fun `status reports on the site's app only`() {
    server = newServer()
    val (code, body, _) = get("/status.json", host = siteHost)
    assertEquals(200, code)
    assertTrue(body.contains("\"id\":\"compose-m3\""), body)
    assertFalse(body.contains("\"id\":\"wear-m3\""), "a site's status is its own: $body")
    assertFalse(body.contains("\"id\":\"cadence\""), "a site's status is its own: $body")

    // The main host still reports the whole box.
    val (_, mainBody, _) = get("/status.json")
    assertTrue(mainBody.contains("\"id\":\"wear-m3\""), mainBody)
  }

  @Test
  fun `the sitemap is scoped and rooted`() {
    server = newServer()
    val (code, body, _) = get("/sitemap.xml", host = siteHost)
    assertEquals(200, code)
    assertTrue(body.contains("<loc>http://$siteHost/</loc>"), body)
    assertTrue(body.contains("<loc>http://$siteHost/p/button-filled</loc>"), body)
    assertFalse(body.contains("/compose-m3/"), "a site's URLs carry no system segment: $body")
    assertFalse(body.contains("wear-m3"), "a site's sitemap is its own: $body")

    // The main host's sitemap keeps the front door and every listed catalog.
    val (_, mainBody, _) = get("/sitemap.xml")
    assertTrue(mainBody.contains("/compose-m3/p/button-filled"), mainBody)
    assertTrue(mainBody.contains("/wear-m3/"), mainBody)
  }

  @Test
  fun `a host header with a port or different case still selects the site`() {
    server = newServer()
    for (header in listOf("$siteHost:8080", siteHost.uppercase(), "$siteHost.")) {
      val (code, body, _) = get("/", host = header)
      assertEquals(200, code, "Host: $header")
      assertTrue(body.contains("Compose Material 3"), "Host: $header did not select the site")
    }
  }

  @Test
  fun `an unknown host is the main server`() {
    server = newServer()
    val (code, body, _) = get("/", host = "preview.example.test")
    assertEquals(200, code)
    assertTrue(body.contains("Wear M3"), "an unconfigured host gets the front door: $body")
  }

  @Test
  fun `parsing drops malformed and unknown-system entries`() {
    val problems = mutableListOf<String>()
    val sites =
      ServeSites.parse(
        "m3.preview.coo.ee=m3-catalog, not a host=x, nosystem, other.coo.ee=nope",
        knownSystems = setOf("m3-catalog"),
        onProblem = problems::add,
      )
    assertEquals(
      mapOf("m3.preview.coo.ee" to "m3-catalog"),
      sites.hosts.associateWith { sites.systemFor(it)!! },
    )
    assertEquals(3, problems.size, problems.toString())
    assertNull(sites.systemFor("unknown.coo.ee"))
    assertEquals("m3.preview.coo.ee", sites.hostFor("m3-catalog"))
    assertTrue(ServeSites.parse(null).isEmpty)
  }

  @Test
  fun `a config file's sites compose with the catalog set`() {
    val config =
      ServeCatalogsConfig.parse(
        """
        {
          "catalogs": [{ "system": "m3-catalog", "repo": "yschimke/m3-catalog" }],
          "sites": [{ "host": "m3.preview.coo.ee", "system": "m3-catalog" }]
        }
        """
          .trimIndent()
      )
    assertEquals(emptyList(), config.problems())
    assertEquals("m3-catalog", config.siteMap().systemFor("m3.preview.coo.ee"))

    val orphan =
      ServeCatalogsConfig(sites = listOf(ServeCatalogsConfig.Site("m3.preview.coo.ee", "nope")))
    assertTrue(
      orphan.problems().any { it.contains("which no catalog entry serves") },
      orphan.problems().toString(),
    )
  }
}
