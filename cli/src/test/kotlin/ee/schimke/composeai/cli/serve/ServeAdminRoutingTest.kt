package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.bundle.BundleSigning
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem

/**
 * The runtime catalog-admin surface over real HTTP: publishing a catalog with `POST
 * /admin/catalogs`, listing with `GET`, retiring with `DELETE` — and, most importantly, that the
 * routes are gated by the **admin** token even though this server runs `--public`, and that a newly
 * published catalog shows up on the front-page index without a restart.
 *
 * The catalog fetch is stubbed (a static bundle host registered on demand); what's exercised here
 * is the route wiring, the gate, and the effect on the server's live view of its catalog set.
 */
class ServeAdminRoutingTest {

  private val adminToken = "admin-secret"

  private val refreshes = mutableListOf<String>()

  /** A real pool, so the cache-clearing route is exercised against actual blobs on disk. */
  private val blobPool =
    CatalogBlobPool(Files.createTempDirectory("admin-blobs").toFile().also { it.deleteOnExit() })
  private val fs = FakeFileSystem()
  private val configPath = "/config/catalogs.json".toPath()
  private val configFile = ServeCatalogsConfigFile(configPath, fs)

  private fun png(): ByteArray =
    ByteArrayOutputStream()
      .also { ImageIO.write(BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", it) }
      .toByteArray()

  private fun bundle(label: String): ServeBundleHost {
    val dir = Files.createTempDirectory("admin-$label").toFile().also { it.deleteOnExit() }
    File(dir, "index.html").writeText("<html></html>")
    File(dir, "previews").apply { mkdirs() }
    File(dir, "previews/com.example.Red.png").writeBytes(png())
    return ServeBundleHost(dir, label = label)
  }

  private val registry = ServeSessionRegistry(open = { null })

  private val tracker =
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

  /** Systems the stubbed "fetch" refuses, so the failure path is reachable from a request. */
  private val unfetchable = mutableSetOf("ghost")

  private val admin =
    ServeCatalogAdmin(
      tracker = tracker,
      defaultRepo = "yschimke/compose-ai-tools",
      branchPrefix = "design-artifacts/",
      configFile = configFile,
      groups = listOf(ServeCatalogsConfig.Group("ds", "Design Systems", "design system(s)")),
      load = { system, _ ->
        if (system in unfetchable) {
          "branch not found"
        } else {
          registry.register(system, host = bundle(system), pinned = true)
          tracker.recordSuccess(system)
          null
        }
      },
      unload = { registry.unregister(it) },
      onLog = {},
    )

  /**
   * The in-browser Wasm apps, as the server sees them: a LIVE map, empty at boot. A catalog
   * published at runtime can carry one, so the `/wasm/` route has to exist and read through to the
   * current contents rather than a boot-time snapshot.
   */
  private val wasmCatalogs = java.util.concurrent.ConcurrentHashMap<String, File>()

  /**
   * The live trust store + its admin, wired the way `serve` wires them. Starts empty so a test can
   * observe the whole point of the feature: a producer trusted over HTTP is in force on the running
   * server, without the image rebuild the baked trust store used to require.
   */
  private val trustStoreFile = ServeTrustStoreFile("/config/producers.json".toPath(), fs)
  private val trust = MutableTrustStore()
  private val trustAdmin = ServeTrustAdmin(trust, trustStoreFile, onLog = {})

  /**
   * The live site map + its admin, started EMPTY on purpose. A box with no sites at boot is the
   * case that used to be unfixable without a restart, so it is the one worth driving over HTTP: the
   * request-path interceptor has to exist on a server that was configured with no hostnames.
   */
  private val siteRegistry = ServeSiteRegistry.empty()
  private val siteAdmin =
    ServeSiteAdmin(
      registry = siteRegistry,
      servedSystems = { tracker.snapshot().map { it.config.system }.toSet() },
      configFile = configFile,
      onLog = {},
    )
  private val optimizerWork = ServeBackgroundWork()

  private val server: ServeHttpServer by lazy {
    registry.register("compose-m3", host = bundle("compose-m3"), pinned = true)
    tracker.recordSuccess("compose-m3")
    ServeHttpServer(
        host = "127.0.0.1",
        requestedPort = 0,
        token = "unused-in-public",
        sessions = registry,
        defaultSessionId = "compose-m3",
        // Public browsing — the admin routes must STILL require their own token.
        isPublic = true,
        catalogSessions = listOf("compose-m3"),
        catalogLoads = tracker,
        catalogAdmin = admin,
        trustAdmin = trustAdmin,
        sites = siteRegistry,
        siteAdmin = siteAdmin,
        themeOptimizerAdmin = optimizerWork,
        catalogCacheStats = { blobPool.snapshot() },
        catalogCacheClear = { blobPool.clear() },
        catalogRefresh = { system, force ->
          refreshes += if (force) "$system!force" else system
          CatalogRefreshResult.CURRENT
        },
        adminToken = adminToken,
        wasmCatalogs = wasmCatalogs,
      )
      .also { it.start() }
  }

  private val client = OkHttpClient()

  private fun url(path: String) = "http://127.0.0.1:${server.port}$path"

  private fun send(
    path: String,
    method: String = "GET",
    body: String? = null,
    token: String? = adminToken,
    /** A `Host` header to send as, for driving a top-level site through the loopback port. */
    host: String? = null,
    followRedirects: Boolean = true,
  ): Pair<Int, String> {
    val req =
      Request.Builder()
        .url(url(path))
        .apply {
          if (host != null) header("Host", host)
          if (token != null) header(ServeHttpServer.ADMIN_TOKEN_HEADER, token)
          when (method) {
            "GET" -> get()
            "DELETE" -> delete()
            else -> method(method, (body ?: "").toRequestBody("application/json".toMediaType()))
          }
        }
        .build()
    val http = if (followRedirects) client else client.newBuilder().followRedirects(false).build()
    http.newCall(req).execute().use { r ->
      return r.code to (r.headers["Location"] ?: r.body.string())
    }
  }

  @AfterTest
  fun tearDown() {
    server.stop()
    registry.close()
  }

  @Test
  fun `the admin routes are gated by the admin token even on a public server`() {
    // A public box is open for browsing, so the admin surface needs its own credential — and a bad
    // one 404s (like the browse gate) rather than confirming the route exists.
    assertEquals(404, send("/admin/catalogs", token = null).first)
    assertEquals(404, send("/admin/catalogs", token = "wrong").first)
    assertEquals(404, send("/admin/catalogs", method = "POST", body = "{}", token = null).first)
    assertEquals(200, send("/admin/catalogs").first)
  }

  @Test
  fun `forcing a refresh needs the admin token, and without it does no work`() {
    // The counterpart to the public-server refusal: with the credential the operator configured,
    // `?force=1` reaches the refresher; without it the request is refused the way every other
    // admin action is, and nothing is reloaded.
    assertEquals(404, send("/compose-m3/refresh?force=1", method = "POST", token = null).first)
    assertEquals(404, send("/compose-m3/refresh?force=1", method = "POST", token = "wrong").first)
    assertTrue(refreshes.isEmpty(), "a refused force must do no remote work: $refreshes")

    assertEquals(200, send("/compose-m3/refresh?force=1", method = "POST").first)
    assertEquals(listOf("compose-m3!force"), refreshes)

    // An ordinary refresh stays open to any browser on this public box.
    assertEquals(200, send("/compose-m3/refresh", method = "POST", token = null).first)
    assertEquals(listOf("compose-m3!force", "compose-m3"), refreshes)
  }

  @Test
  fun `clearing the catalog cache drops its blobs and reports what is left`() {
    val url = "https://raw.githubusercontent.com/o/r/${"a".repeat(40)}/images/button.png"
    blobPool.write(url, "some cached bytes".toByteArray())
    assertEquals(1, blobPool.snapshot().blobs)

    val (code, body) = send("/admin/catalog-cache", method = "DELETE")

    assertEquals(200, code)
    assertTrue(body.contains("\"blobs\":0"), body)
    assertEquals(0, blobPool.snapshot().blobs)
    assertNull(blobPool.read(url), "the bytes are gone, not merely unreferenced")
  }

  @Test
  fun `the per-catalog theme-cache actions are gated like every other admin route`() {
    // The pair an operator reaches for when a catalog's pixels look wrong. Both mutate a durable
    // store, so both sit behind the same credential as the rest of the admin surface — and a
    // missing or wrong token 404s rather than confirming the route is there.
    for (action in listOf("regenerate", "drop")) {
      val path = "/admin/catalogs/compose-m3/theme-cache/$action"
      assertEquals(404, send(path, method = "POST", token = null).first, action)
      assertEquals(404, send(path, method = "POST", token = "wrong").first, action)
    }
  }

  @Test
  fun `a theme-cache action on an unknown catalog is a not-found, not a silent success`() {
    // With the credential, so this is the route answering rather than the gate: an operator who
    // mistypes a system must be told, not left believing a cache was regenerated.
    val (code, body) =
      send("/admin/catalogs/no-such-catalog/theme-cache/regenerate", method = "POST")
    assertEquals(404, code)
    assertTrue(body.contains("no-such-catalog"), body)
  }

  @Test
  fun `clearing the catalog cache needs the admin token like every other admin route`() {
    blobPool.write(
      "https://raw.githubusercontent.com/o/r/${"b".repeat(40)}/images/button.png",
      "kept".toByteArray(),
    )

    assertEquals(404, send("/admin/catalog-cache", method = "DELETE", token = null).first)
    assertEquals(404, send("/admin/catalog-cache", method = "DELETE", token = "wrong").first)
    assertEquals(1, blobPool.snapshot().blobs, "a refused request must not have cleared anything")
  }

  @Test
  fun `listing reports the configured catalogs and their load state`() {
    val (code, body) = send("/admin/catalogs")

    assertEquals(200, code)
    assertTrue(body.contains("compose-preview-serve/admin-catalogs/v1"), body)
    assertTrue(body.contains("\"system\":\"compose-m3\""), body)
    assertTrue(body.contains("\"state\":\"loaded\""), body)
  }

  @Test
  fun `publishing a catalog serves it immediately and persists it`() {
    val body = """{"system":"cadence","repo":"yschimke/cadence","listed":false}"""

    val (code, response) = send("/admin/catalogs", method = "POST", body = body)

    assertEquals(200, code, response)
    assertTrue(response.contains("\"status\":\"ok\""), response)
    // Served right away — no restart, no re-deploy.
    assertEquals(
      200,
      Request.Builder().url(url("/cadence/")).build().let { req ->
        client.newCall(req).execute().use { it.code }
      },
    )
    assertTrue(send("/admin/catalogs").second.contains("cadence"))
    assertEquals(listOf("cadence"), configFile.load().catalogs.map { it.system })
  }

  @Test
  fun `a published catalog appears on the front page without a restart`() {
    send(
      "/admin/catalogs",
      method = "POST",
      body = """{"system":"newcat","repo":"someorg/newcat"}""",
    )

    val home =
      Request.Builder().url(url("/")).build().let { req ->
        client.newCall(req).execute().use { it.body.string() }
      }

    assertTrue(home.contains("href=\"/newcat/\""), "the new catalog is indexed: $home")
  }

  @Test
  fun `a malformed entry is a bad request and an unfetchable one a bad gateway`() {
    assertEquals(400, send("/admin/catalogs", method = "POST", body = "not json").first)
    assertEquals(
      400,
      send("/admin/catalogs", method = "POST", body = """{"system":"../escape"}""").first,
    )
    assertEquals(
      502,
      send("/admin/catalogs", method = "POST", body = """{"system":"ghost"}""").first,
    )
    // A duplicate of an already-served catalog is a conflict, not a silent overwrite.
    assertEquals(
      409,
      send("/admin/catalogs", method = "POST", body = """{"system":"compose-m3"}""").first,
    )
  }

  @Test
  fun `a malformed optimizer pause duration is rejected instead of using the default`() {
    val (code, body) = send("/admin/theme-optimization/pause?minutes=144O", method = "POST")

    assertEquals(400, code)
    assertTrue(body.contains("minutes must be an integer"), body)
    assertFalse(optimizerWork.optimizersPaused(), "a typo must not silently pause for 30 minutes")
  }

  @Test
  fun `a Wasm app registered after boot is served, and unregistering stops it`() {
    // An admin-enabled server starts with no Wasm apps at all, so the route must be registered
    // anyway and resolve against the live map — otherwise a catalog published at runtime gets no
    // /wasm/<system>/ lane until the container is recreated.
    assertEquals(404, send("/wasm/latecomer/index.html", token = null).first)

    val dir = Files.createTempDirectory("admin-wasm").toFile().also { it.deleteOnExit() }
    File(dir, "index.html").writeText("<html>wasm</html>")
    wasmCatalogs["latecomer"] = dir

    val (code, body) = send("/wasm/latecomer/index.html", token = null)
    assertEquals(200, code)
    assertTrue(body.contains("wasm"), body)

    // …and a retired catalog's assets stop being served rather than lingering.
    wasmCatalogs.remove("latecomer")
    assertEquals(404, send("/wasm/latecomer/index.html", token = null).first)
  }

  @Test
  fun `retiring a catalog stops serving it`() {
    send("/admin/catalogs", method = "POST", body = """{"system":"temp","repo":"someorg/temp"}""")

    val (code, response) = send("/admin/catalogs/temp", method = "DELETE")

    assertEquals(200, code, response)
    assertFalse(send("/admin/catalogs").second.contains("\"system\":\"temp\""))
    // Retiring it twice is a conflict — the second call has nothing to retire.
    assertEquals(409, send("/admin/catalogs/temp", method = "DELETE").first)
  }

  // --- front-page groups -------------------------------------------------------------------------

  @Test
  fun `the group routes are gated by the admin token even on a public server`() {
    assertEquals(404, send("/admin/groups", token = null).first)
    assertEquals(404, send("/admin/groups", token = "wrong").first)
    assertEquals(200, send("/admin/groups").first)
  }

  @Test
  fun `a group defined after its catalogs still collects them`() {
    // The whole reason /admin/groups exists. Publish first, ungrouped...
    send(
      "/admin/catalogs",
      method = "POST",
      body = """{"system":"pocketcasts","repo":"someorg/pocket-casts-android"}""",
    )
    assertEquals(null, tracker.configFor("pocketcasts")?.group)

    // ...then define the section and re-post the entry claiming it.
    val group =
      """{"id":"pocket-casts","heading":"Automattic/pocket-casts-android","noun":"app(s)"}"""
    assertEquals(200, send("/admin/groups", method = "POST", body = group).first)
    val (code, _) =
      send(
        "/admin/catalogs",
        method = "POST",
        body =
          """{"system":"pocketcasts","repo":"someorg/pocket-casts-android","group":"pocket-casts"}""",
      )

    // Converged in place: no re-fetch, no retire, and the card moves.
    assertEquals(200, code)
    assertEquals(
      "Automattic/pocket-casts-android",
      tracker.configFor("pocketcasts")?.group?.heading,
    )
  }

  @Test
  fun `defining a group regroups an already-claiming catalog with no second post`() {
    // A catalog whose config entry ALREADY claims a group the server doesn't know yet: rejected on
    // publish, so publish it plain, persist the claim, then define the group.
    send(
      "/admin/catalogs",
      method = "POST",
      body = """{"system":"newcat","repo":"someorg/newcat"}""",
    )
    configFile.save(
      configFile
        .load()
        .withEntry(
          ServeCatalogsConfig.Entry(system = "newcat", repo = "someorg/newcat", group = "later")
        )
    )

    val (code, _) =
      send(
        "/admin/groups",
        method = "POST",
        body = """{"id":"later","heading":"Defined Later","noun":"thing(s)"}""",
      )

    assertEquals(200, code)
    assertEquals("Defined Later", tracker.configFor("newcat")?.group?.heading)
  }

  @Test
  fun `removing a group drops its catalogs back to the owner fallback`() {
    send(
      "/admin/groups",
      method = "POST",
      body = """{"id":"ds2","heading":"Temporary","noun":"thing(s)"}""",
    )
    send(
      "/admin/catalogs",
      method = "POST",
      body = """{"system":"grouped","repo":"someorg/grouped","group":"ds2"}""",
    )
    assertEquals("Temporary", tracker.configFor("grouped")?.group?.heading)

    assertEquals(200, send("/admin/groups/ds2", method = "DELETE").first)

    assertEquals(null, tracker.configFor("grouped")?.group)
    assertFalse(send("/admin/groups").second.contains("Temporary"))
  }

  @Test
  fun `an unchanged group is a conflict and an unknown one cannot be removed`() {
    val body = """{"id":"dupe","heading":"Dupe","noun":"x"}"""
    assertEquals(200, send("/admin/groups", method = "POST", body = body).first)
    assertEquals(409, send("/admin/groups", method = "POST", body = body).first)
    assertEquals(409, send("/admin/groups/nosuch", method = "DELETE").first)
  }

  @Test
  fun `a group heading can be restyled without touching its catalogs`() {
    send("/admin/groups", method = "POST", body = """{"id":"ds3","heading":"Before","noun":"x"}""")
    send(
      "/admin/catalogs",
      method = "POST",
      body = """{"system":"restyled","repo":"someorg/restyled","group":"ds3"}""",
    )

    assertEquals(
      200,
      send("/admin/groups", method = "POST", body = """{"id":"ds3","heading":"After","noun":"x"}""")
        .first,
    )

    assertEquals("After", tracker.configFor("restyled")?.group?.heading)
    assertEquals("loaded", tracker.snapshot().first { it.config.system == "restyled" }.loadState)
  }

  @Test
  fun `a malformed group is refused`() {
    assertEquals(
      400,
      send("/admin/groups", method = "POST", body = """{"id":"bad id","heading":"H"}""").first,
    )
    assertEquals(
      400,
      send("/admin/groups", method = "POST", body = """{"id":"ok","heading":""}""").first,
    )
  }

  @Test
  fun `re-publishing an unchanged catalog is still a conflict`() {
    val body = """{"system":"same","repo":"someorg/same","listed":true}"""
    assertEquals(200, send("/admin/catalogs", method = "POST", body = body).first)
    // Convergence must not turn a genuine duplicate into a silent success.
    assertEquals(409, send("/admin/catalogs", method = "POST", body = body).first)
  }

  @Test
  fun `re-publishing from a different repo re-points it in place`() {
    send("/admin/catalogs", method = "POST", body = """{"system":"moved","repo":"someorg/one"}""")

    val (code, msg) =
      send("/admin/catalogs", method = "POST", body = """{"system":"moved","repo":"someorg/two"}""")

    // This used to be a 409 telling the caller to retire it first — a two-step dance whose failure
    // mode was a catalog published nowhere, and whose 409 read as success to the deployment
    // reconcile that drives this route. A repo change is now one atomic swap: fetch the new source,
    // then record where the bytes come from.
    assertEquals(200, code, msg)
    assertEquals("someorg/two", tracker.configFor("moved")?.repo)
    assertTrue(send("/admin/catalogs").second.contains("someorg/two"))
    // Still served, and still there — the swap replaced content rather than dropping a session.
    assertEquals(
      200,
      Request.Builder().url(url("/moved/")).build().let { req ->
        client.newCall(req).execute().use { it.code }
      },
    )
    // And it survives a restart under the new provenance.
    assertEquals("someorg/two", configFile.load().catalogs.single { it.system == "moved" }.repo)
  }

  @Test
  fun `a re-point that cannot be fetched leaves the catalog on its old repo`() {
    send("/admin/catalogs", method = "POST", body = """{"system":"stays","repo":"someorg/one"}""")
    unfetchable += "stays"

    val (code, msg) =
      send("/admin/catalogs", method = "POST", body = """{"system":"stays","repo":"someorg/two"}""")

    // The load runs BEFORE anything is dropped, so a source that cannot be fetched costs nothing
    // but the attempt. Under retire-then-publish this is where the catalog disappeared.
    assertEquals(502, code, msg)
    assertTrue(msg.contains("still serving someorg/one"), msg)
    assertEquals("someorg/one", tracker.configFor("stays")?.repo)
    assertEquals(
      200,
      Request.Builder().url(url("/stays/")).build().let { req ->
        client.newCall(req).execute().use { it.code }
      },
    )
    assertEquals("someorg/one", configFile.load().catalogs.single { it.system == "stays" }.repo)
  }

  // --- producer trust ----------------------------------------------------------------------------

  @Test
  fun `the trust routes are gated by the admin token even on a public server`() {
    assertEquals(404, send("/admin/trust", token = null).first)
    assertEquals(404, send("/admin/trust", token = "wrong").first)
    assertEquals(404, send("/admin/trust", method = "POST", body = "{}", token = null).first)
    assertEquals(200, send("/admin/trust").first)
  }

  @Test
  fun `trusting a branch takes effect on the running server and persists`() {
    val body = """{"kind":"branch","repo":"yschimke/horologist","branch":"design-artifacts/*"}"""

    val (code, response) = send("/admin/trust", method = "POST", body = body)

    assertEquals(200, code, response)
    assertTrue(response.contains("\"status\":\"ok\""), response)
    // The whole point: no image rebuild, no restart — the next catalog fetch sees this.
    assertTrue(trust.get().trustsBranch("yschimke/horologist", "design-artifacts/horologist"))
    assertTrue(trustStoreFile.load().trustsBranch("yschimke/horologist", "design-artifacts/x"))
    assertTrue(send("/admin/trust").second.contains("yschimke/horologist"))
  }

  @Test
  fun `an untrusted branch stays untrusted until it is added`() {
    assertFalse(trust.get().trustsBranch("yschimke/horologist", "design-artifacts/horologist"))
    assertFalse(send("/admin/trust").second.contains("yschimke/horologist"))
  }

  @Test
  fun `a match-everything repo pattern is rejected over HTTP`() {
    val (code, _) =
      send("/admin/trust", method = "POST", body = """{"kind":"branch","repo":"*/*"}""")

    assertEquals(400, code)
    assertTrue(trust.get().branches.isEmpty())
  }

  @Test
  fun `an unknown trust kind is a bad request`() {
    assertEquals(400, send("/admin/trust", method = "POST", body = """{"kind":"banana"}""").first)
  }

  @Test
  fun `a producer can be retired by query parameter`() {
    send(
      "/admin/trust",
      method = "POST",
      body = """{"kind":"branch","repo":"yschimke/horologist","branch":"design-artifacts/*"}""",
    )
    assertTrue(trust.get().trustsBranch("yschimke/horologist", "design-artifacts/horologist"))

    // The repo slug carries a slash, which is why the selector rides the query string.
    val (code, _) =
      send(
        "/admin/trust?kind=branch&repo=yschimke%2Fhorologist&branch=design-artifacts%2F*",
        method = "DELETE",
      )

    assertEquals(200, code)
    assertFalse(trust.get().trustsBranch("yschimke/horologist", "design-artifacts/horologist"))
    // Retiring it twice is a conflict.
    assertEquals(
      409,
      send(
          "/admin/trust?kind=branch&repo=yschimke%2Fhorologist&branch=design-artifacts%2F*",
          method = "DELETE",
        )
        .first,
    )
  }

  @Test
  fun `pinned key material is never echoed back by the list route`() {
    val keys = BundleSigning.generateKeyPair()
    val body = """{"kind":"key","keyId":"ci","name":"CI","publicKey":"${keys.publicKeyB64}"}"""
    assertEquals(200, send("/admin/trust", method = "POST", body = body).first)

    val listed = send("/admin/trust").second

    assertTrue(listed.contains("\"keyId\":\"ci\""), listed)
    assertFalse(listed.contains(keys.publicKeyB64), "public key material must not be echoed back")
  }

  @Test
  fun `the site routes are gated by the admin token like every other admin surface`() {
    assertEquals(404, send("/admin/sites", token = null).first)
    assertEquals(404, send("/admin/sites", token = "wrong").first)
    assertEquals(200, send("/admin/sites").first)
  }

  @Test
  fun `a site published over HTTP serves that hostname immediately`() {
    // The whole point: this server booted with NO sites, so before the POST the hostname is just
    // another vhost and `/compose-m3/` is the ordinary canonical path. Adding it used to need
    // SERVE_SITES in the box's .env and a container recreate.
    assertEquals(
      200,
      send("/compose-m3/", host = "m3.example.com", followRedirects = false).first,
    )

    val (code, body) =
      send(
        "/admin/sites",
        method = "POST",
        body = """{"host":"m3.example.com","system":"compose-m3"}""",
      )
    assertEquals(200, code)
    assertTrue(body.contains("compose-preview-serve/admin-site-result/v1"), body)

    // Now the canonical path redirects to the rooted spelling on that host — the interceptor is
    // live on a server that started with no sites at all.
    val (redirect, location) =
      send("/compose-m3/", host = "m3.example.com", followRedirects = false)
    assertEquals(308, redirect)
    assertEquals("/", location)
    // …and the hostname was written back, so a restart keeps serving it.
    assertEquals(
      listOf(ServeCatalogsConfig.Site("m3.example.com", "compose-m3")),
      configFile.load().sites,
    )
    assertTrue(send("/admin/sites").second.contains("m3.example.com"))
  }

  @Test
  fun `a site naming an unserved catalog is rejected with a reason`() {
    val (code, body) =
      send(
        "/admin/sites",
        method = "POST",
        body = """{"host":"ghost.example.com","system":"never-published"}""",
      )
    assertEquals(400, code)
    assertTrue(body.contains("never-published"), body)
  }

  @Test
  fun `retiring a site over HTTP gives the hostname back to the front door`() {
    send(
      "/admin/sites",
      method = "POST",
      body = """{"host":"m3.example.com","system":"compose-m3"}""",
    )
    assertEquals(200, send("/admin/sites/m3.example.com", method = "DELETE").first)
    assertEquals(
      200,
      send("/compose-m3/", host = "m3.example.com", followRedirects = false).first,
    )
    assertEquals(emptyList(), configFile.load().sites)
  }
}
