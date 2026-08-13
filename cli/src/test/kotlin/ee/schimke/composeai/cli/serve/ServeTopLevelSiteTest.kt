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
    assertEquals(308, code)
    assertEquals("/p/button-filled?theme=dark", location)

    // The bare catalog path collapses to the site root.
    assertEquals("/" to 308, get("/compose-m3", host = siteHost).let { it.third to it.first })

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
  fun `an explicit session query cannot reach past the site host`() {
    server = newServer()
    // The isolation the path 404 gives has to hold for the older `?session=` spelling of the same
    // request, or `/api/previews?session=wear-m3` serves the neighbour `/wear-m3/` refuses.
    val (code, body, _) = get("/api/previews?session=wear-m3", host = siteHost)
    assertEquals(200, code)
    assertTrue(body.contains("button-filled"), "the site's own catalog answers: $body")
    assertFalse(body.contains("\"chip\""), "a query param must not re-point the session: $body")
    // The landing is the site's catalog too, not the one named in the query.
    val (_, landing, _) = get("/?session=cadence", host = siteHost)
    assertTrue(landing.contains("Compose Material 3"), landing)
    assertFalse(landing.contains("Cadence"), landing)
    // On the main host `?session=` still selects, exactly as it always did.
    val (_, mainBody, _) = get("/api/previews?session=wear-m3")
    assertTrue(mainBody.contains("chip"), mainBody)
  }

  @Test
  fun `the canonical redirect is same-origin and method-preserving`() {
    server = newServer()
    // An extra slash after the system would otherwise build `//evil.example` — read by browsers as
    // a protocol-relative URL to another origin, i.e. an open redirect on every site host.
    val (code, _, location) = get("/compose-m3//evil.example", host = siteHost)
    assertEquals(308, code)
    assertEquals("/evil.example", location)
    assertTrue(
      location!!.startsWith("/") && !location.startsWith("//"),
      "the redirect target must be same-origin: $location",
    )
    // 308 rather than 301, because the canonical prefix also carries POST routes and a 301 is
    // re-issued as GET by most clients.
    assertEquals(308, get("/compose-m3/p/button-filled", host = siteHost).first)
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
  fun `the header bar names the catalog on every page`() {
    server = newServer()
    // The bar used to say only "compose-preview" everywhere, so which design system you were
    // looking at lived solely in the page's own <h1> and scrolled away with it.
    for (path in listOf("/", "/p/button-filled")) {
      val (code, body, _) = get(path, host = siteHost)
      assertEquals(200, code, path)
      assertTrue(
        body.contains("<span class=\"cp-site-catalog\">Compose Material 3</span>"),
        "the header names the catalog on $path: $body",
      )
    }
    // …and on the canonical path too — this is not a site-only affordance.
    val (_, mainBody, _) = get("/wear-m3/")
    assertTrue(mainBody.contains("<span class=\"cp-site-catalog\">Wear M3</span>"), mainBody)
    // The front door belongs to no catalog, so it keeps the bare brand.
    val (_, home, _) = get("/")
    assertFalse(home.contains("cp-site-catalog"), home)
  }

  @Test
  fun `a site's chrome wears its catalog's skin on every page`() {
    server = newServer()
    // A hostname that publishes one design system should not render its /status and its 404 in the
    // built-in chrome beside a themed landing — one hostname, one skin.
    for (path in listOf("/status", "/no-such-page-here")) {
      val (_, body, _) = get(path, host = siteHost)
      assertTrue(
        body.contains("data-cp-theme-key=\"cp-theme:compose-m3\""),
        "the theme choice is shared across the hostname on $path: $body",
      )
      assertTrue(
        body.contains("<span class=\"cp-site-catalog\">Compose Material 3</span>"),
        "the bar names the catalog on $path: $body",
      )
    }
    // The main host's /status belongs to no catalog and is unchanged.
    val (_, mainStatus, _) = get("/status")
    assertFalse(mainStatus.contains("cp-theme:compose-m3"), mainStatus)
    assertFalse(mainStatus.contains("cp-site-catalog"), mainStatus)
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
  fun `an uploaded bundle session is not reachable through a site host`() {
    // A catalog row is not the only thing `/{system}/…` can acquire: an uploaded bundle registers
    // a session under its own name, and a catalog-only check let it serve through a site hostname.
    registry.register(
      "some-upload",
      host = bundle("some-upload", listOf("uploaded"), "Some Upload"),
      pinned = true,
    )
    server = newServer()
    assertEquals(404, get("/some-upload/", host = siteHost).first)
    assertEquals(404, get("/some-upload/p/uploaded", host = siteHost).first)
    // …and a revision of a neighbouring catalog, which is addressed as `<system>@<rev>`.
    assertEquals(404, get("/wear-m3@abc123/", host = siteHost).first)
    // The main host still serves the upload.
    assertEquals(200, get("/some-upload/").first)
  }

  @Test
  fun `a suspended session still counts as known to its site`() {
    // `peekHost` answers "resident right now" and goes null for a session that has been suspended
    // while its registry entry stays, registered and resumable. Both the site's status count and
    // the foreign-session gate read membership now, not residency — reading residency reported
    // `known: 0` beside an available catalog, and let an idle neighbour fall through the gate to
    // the `/{system}/…` handler that would resume and serve it.
    server = newServer()
    // Re-register the site's catalog as known-but-not-resident: the shape a suspended session has.
    registry.register(
      "compose-m3",
      state =
        ServeSessionState(
          descriptor = File("daemon-launch.json"),
          workspaceRoot = File("."),
          workspaceName = "w",
          previews = emptyList(),
          label = "compose-m3",
        ),
    )
    val (code, body, _) = get("/status.json", host = siteHost)
    assertEquals(200, code)
    assertTrue(body.contains("\"known\":1"), "a suspended catalog has not disappeared: $body")
  }

  @Test
  fun `every constant route still works on a site host`() {
    // The site gate is an ALLOWLIST of the server's own routes, so a route missing from
    // ServeSites.RESERVED_SYSTEMS would 404 on every site hostname. Walk the whole list against a
    // live server: no entry may be swallowed by the canonical-path redirect (308) or the
    // neighbour refusal (404 from the interceptor rather than the handler).
    server = newServer()
    for (route in ServeSites.RESERVED_SYSTEMS) {
      val (code, _, location) = get("/$route", host = siteHost)
      assertFalse(
        code == 308,
        "'/$route' was mistaken for the canonical catalog prefix and redirected to $location",
      )
    }
    // Spot-check the ones with a real body, which must answer identically on both hosts.
    assertEquals(200, get("/healthz", host = siteHost).first)
    assertEquals(200, get("/version", host = siteHost).first)
    assertEquals(200, get("/robots.txt", host = siteHost).first)
  }

  @Test
  fun `an unknown first segment is refused rather than resolved`() {
    // With --revisions a raw ref like `main` is not a registered session until the generic route
    // leases it and the factory BUILDS it, so no enumeration of existing sessions can catch it in
    // time. The gate is an allowlist for exactly that reason: anything that is neither this site's
    // system nor one of the server's routes is refused before it can be created.
    server = newServer()
    for (unknown in listOf("main", "some-ref", "not-a-catalog", "wear-m3@abc123")) {
      assertEquals(404, get("/$unknown/", host = siteHost).first, "'/$unknown/' must be refused")
    }
  }

  @Test
  fun `a neighbour's social card is not served through a site host`() {
    server = newServer()
    // Bake the neighbour's card the way the main host does — by rendering its landing — then take
    // the hash out of its og:image, which is public there. `/social/` is ungated by design (an
    // unfurler never replays a token), so ownership is the only thing standing between that hash
    // and this hostname answering with another catalog's title.
    val (_, wearLanding, _) = get("/wear-m3/")
    val hash =
      Regex("/social/([a-z0-9]+\\.png)").find(wearLanding)?.groupValues?.get(1)
        ?: error("no social card on the neighbour's landing: $wearLanding")
    assertEquals(200, get("/social/$hash").first, "it serves on the main host")
    assertEquals(404, get("/social/$hash", host = siteHost).first, "but never through the site")
  }

  @Test
  fun `status aggregates are scoped, not just the catalog list`() {
    server = newServer()
    val (_, body, _) = get("/status.json", host = siteHost)
    // `daemons.known` was a box-wide session count, so a per-app monitor was reading the box.
    // Three sessions are registered; the site knows its own.
    assertTrue(body.contains("\"known\":1"), "session count is site-scoped: $body")
    val (_, mainBody, _) = get("/status.json")
    assertTrue(mainBody.contains("\"known\":3"), "the main host still counts the box: $mainBody")
  }

  @Test
  fun `a site cannot claim any constant route as its system`() {
    // `pg` was missing from the reserved set, so a catalog named `pg` could be a site and swallow
    // `/pg/<token>` — every playground redemption on that hostname redirecting to `/<token>`.
    for (reserved in listOf("pg", "render", "p", "api", "wasm", "playground", "status")) {
      val problems = mutableListOf<String>()
      val sites =
        ServeSites.of(
          listOf("x.example.test" to reserved),
          knownSystems = setOf(reserved),
          onProblem = problems::add,
        )
      assertTrue(sites.isEmpty, "'$reserved' must be refused as a site system")
      assertTrue(problems.single().contains("built-in route"), problems.toString())
    }
  }

  @Test
  fun `retiring a catalog a site is published as is refused`() {
    // Retiring it would strand the hostname: its root 404s at once, and after a restart the now
    // unserved mapping is dropped so the host falls through to the global front door — a domain
    // published as one app quietly becoming an index of every other.
    val tracker =
      CatalogLoadTracker(
        listOf(
          CatalogLoadTracker.Config(
            system = "compose-m3",
            listed = true,
            repo = "yschimke/compose-ai-tools",
            branch = "design-artifacts/compose-m3",
          )
        )
      )
    tracker.recordSuccess("compose-m3")
    val admin =
      ServeCatalogAdmin(
        tracker = tracker,
        defaultRepo = "yschimke/compose-ai-tools",
        branchPrefix = "design-artifacts/",
        configFile = null,
        load = { _, _ -> null },
        unload = {},
        sites = ServeSites.of(listOf(siteHost to "compose-m3")),
      )
    val result = admin.unregister("compose-m3")
    assertTrue(result is ServeCatalogAdmin.Result.Conflict, "$result")
    assertTrue(
      (result as ServeCatalogAdmin.Result.Conflict).reason.contains(siteHost),
      result.reason,
    )
    // A catalog no site names retires as before.
    assertTrue(admin.unregister("not-a-site") is ServeCatalogAdmin.Result.Conflict)
  }

  @Test
  fun `a site cannot claim a built-in route as its system`() {
    // `/render/<id>.png` on a site mapped to `render` would be read as a canonical prefixed URL and
    // redirected to `/<id>.png`, breaking every image. Such an id is already unreachable at its
    // canonical path anyway (the constant route outscores `/{system}`), so it is refused.
    val problems = mutableListOf<String>()
    val sites =
      ServeSites.of(
        listOf("render.example.test" to "render", "ok.example.test" to "compose-m3"),
        knownSystems = setOf("render", "compose-m3"),
        onProblem = problems::add,
      )
    assertNull(sites.systemFor("render.example.test"))
    assertEquals("compose-m3", sites.systemFor("ok.example.test"))
    assertTrue(problems.single().contains("collides with a built-in route"), problems.toString())
  }

  @Test
  fun `an empty known-system set means nothing is known, not skip the check`() {
    // A module-backed server serves no catalogs at all. A site naming one is a typo to report, not
    // a mapping to keep — keeping it 404s every route on that hostname instead.
    val problems = mutableListOf<String>()
    val sites =
      ServeSites.of(
        listOf("app.example.test" to "typo"),
        knownSystems = emptySet(),
        onProblem = problems::add,
      )
    assertTrue(sites.isEmpty, "an unserved system is dropped")
    assertTrue(problems.single().contains("does not serve"), problems.toString())
    // Null still means "don't check" — the tests and callers that validate elsewhere.
    assertEquals(
      "typo",
      ServeSites.of(listOf("app.example.test" to "typo")).systemFor("app.example.test"),
    )
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
    // The `--sites` flag path parses BEFORE the served set is known (ServeCommand re-validates the
    // combined list against it afterwards), so an unchecked parse must keep its entries rather
    // than read "no systems supplied" as "no systems exist".
    assertEquals(
      "m3-catalog",
      ServeSites.parse("m3.preview.coo.ee=m3-catalog").systemFor("m3.preview.coo.ee"),
    )
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
