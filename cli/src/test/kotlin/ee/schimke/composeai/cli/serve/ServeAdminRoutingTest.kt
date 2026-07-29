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
  private val unfetchable = setOf("ghost")

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
  ): Pair<Int, String> {
    val req =
      Request.Builder()
        .url(url(path))
        .apply {
          if (token != null) header(ServeHttpServer.ADMIN_TOKEN_HEADER, token)
          when (method) {
            "GET" -> get()
            "DELETE" -> delete()
            else -> method(method, (body ?: "").toRequestBody("application/json".toMediaType()))
          }
        }
        .build()
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
  fun `the admin routes are gated by the admin token even on a public server`() {
    // A public box is open for browsing, so the admin surface needs its own credential — and a bad
    // one 404s (like the browse gate) rather than confirming the route exists.
    assertEquals(404, send("/admin/catalogs", token = null).first)
    assertEquals(404, send("/admin/catalogs", token = "wrong").first)
    assertEquals(404, send("/admin/catalogs", method = "POST", body = "{}", token = null).first)
    assertEquals(200, send("/admin/catalogs").first)
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
        client.newCall(req).execute().use { it.body?.string() ?: "" }
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
}
