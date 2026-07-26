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
 * End-to-end check for the `/status` + `/status.json` routes on a real embedded [ServeHttpServer]
 * fronting static bundle catalogs. Covers the HTML page, the machine-readable JSON (the Home
 * Assistant / monitor surface), content negotiation (`?format=json`), and the token gate.
 */
class ServeStatusTest {

  private fun png(): ByteArray =
    ByteArrayOutputStream()
      .also { ImageIO.write(BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", it) }
      .toByteArray()

  private fun bundle(
    label: String,
    previewIds: List<String>,
    title: String? = null,
  ): ServeBundleHost {
    val dir = Files.createTempDirectory("status-$label").toFile().also { it.deleteOnExit() }
    File(dir, "index.html").writeText("<html></html>")
    File(dir, "previews").apply { mkdirs() }
    previewIds.forEach { File(dir, "previews/$it.png").writeBytes(png()) }
    return ServeBundleHost(dir, label = label, title = title)
  }

  private val registry = ServeSessionRegistry(open = { null })
  private val daemonLog = DaemonStartupLog(clock = { 1_000L })

  private fun newServer(public: Boolean, token: String): ServeHttpServer {
    registry.register(
      "default-mod",
      host = bundle("default-mod", listOf("com.example.Red")),
      pinned = true,
    )
    registry.register(
      "compose-m3",
      host =
        bundle("compose-m3", listOf("button-filled", "switch-on"), title = "Compose Material 3"),
      pinned = true,
    )
    registry.register(
      "cadence",
      host = bundle("cadence", listOf("beat"), title = "Cadence"),
      pinned = true,
    )
    // A recorded startup failure, so the status shows the degraded state + failure row.
    daemonLog.record("wear-m3", "daemon launch timed out")
    return ServeHttpServer(
        host = "127.0.0.1",
        requestedPort = 0,
        token = token,
        sessions = registry,
        defaultSessionId = "default-mod",
        isPublic = public,
        catalogSessions = listOf("compose-m3"),
        appCatalogSessions = listOf("cadence"),
        daemonLog = daemonLog,
        allowRenderTrusted = true,
        trustStoreConfigured = true,
        catalogRefreshSeconds = 600,
        acceptBundlesEnabled = false,
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
  fun `status_json is the machine-readable snapshot`() {
    server = newServer(public = true, token = "unused")
    val (code, body) = get("/status.json")
    assertEquals(200, code)
    assertTrue(body.contains("\"schema\":\"compose-preview-serve/status/v1\""), body)
    assertTrue(body.contains("\"public\":true"), body)
    // A recorded failure ⇒ degraded, and it appears in the failures array.
    assertTrue(body.contains("\"status\":\"degraded\""), body)
    assertTrue(body.contains("daemon launch timed out"), "the failure reason is carried: $body")
    assertTrue(body.contains("\"session\":\"wear-m3\""), body)
    // The configured catalogs are listed (listed compose-m3 + unlisted cadence).
    assertTrue(body.contains("\"id\":\"compose-m3\""), body)
    assertTrue(body.contains("\"id\":\"cadence\""), body)
    assertTrue(body.contains("\"path\":\"/compose-m3/\""), body)
    // Config is surfaced for a monitor.
    assertTrue(body.contains("\"allowRenderTrusted\":true"), body)
    assertTrue(body.contains("\"catalogRefreshSeconds\":600"), body)
    // Static bundle catalogs run no daemon, so no live servers.
    assertTrue(body.contains("\"runningServers\":[]"), "static catalogs run no daemon: $body")
  }

  @Test
  fun `status serves a styled html page`() {
    server = newServer(public = true, token = "unused")
    val (code, body) = get("/status")
    assertEquals(200, code)
    assertTrue(body.contains("<!doctype html>") && body.contains("<html"), "is an html document")
    assertTrue(body.contains("Server status"), body)
    // The catalog table lists the systems with their titles, and the machine form is linked.
    assertTrue(body.contains("Compose Material 3"), body)
    assertTrue(body.contains("href=\"/status.json\""), body)
    // The recent failure surfaces the degraded badge + row.
    assertTrue(body.contains("recent daemon failure(s)"), body)
    assertTrue(body.contains("daemon launch timed out"), body)
  }

  @Test
  fun `status honours format=json content negotiation`() {
    server = newServer(public = true, token = "unused")
    val (code, body) = get("/status?format=json")
    assertEquals(200, code)
    assertTrue(body.contains("\"schema\":\"compose-preview-serve/status/v1\""), body)
  }

  /**
   * A trusted catalog whose daemon has gone idle must still report its trust. `/status` reads
   * metadata with the non-resuming [ServeSessionRegistry.peekHost], so a suspended catalog used to
   * come back as an all-null row — blank trust cell, zero previews — which on the public server
   * (preview.coo.ee) read as "untrusted" for five of eight correctly-trusted catalogs. The facts
   * come from the delivery branch, not the daemon, so suspension must not erase them.
   */
  @Test
  fun `a suspended catalog still reports its last-known trust`() {
    var now = 0L
    val suspendable =
      ServeSessionRegistry(
        open = { null },
        idleTimeoutMillis = 10,
        // 0 ⇒ no reaper thread; this test drives suspendIdle() directly against the fake clock.
        reaperIntervalMillis = 0,
        clock = { now },
      )
    val trusted =
      ServeBundleHost(
        Files.createTempDirectory("status-idle")
          .toFile()
          .also { it.deleteOnExit() }
          .apply {
            File(this, "index.html").writeText("<html></html>")
            File(this, "previews").mkdirs()
            File(this, "previews/beat.png").writeBytes(png())
          },
        label = "confetti-wear",
        trust =
          BundleVerifier.Verdict.Trusted(
            listOf(
              BundleVerifier.Basis.Branch("joreilly/Confetti", "design-artifacts/confetti-wear")
            )
          ),
        title = "Confetti Wear",
      )
    // Registered NOT pinned, so it suspends like a real live catalog does when its daemon idles.
    suspendable.register("confetti-wear", host = trusted)
    val srv =
      ServeHttpServer(
          host = "127.0.0.1",
          requestedPort = 0,
          token = "unused",
          sessions = suspendable,
          defaultSessionId = "confetti-wear",
          isPublic = true,
          catalogSessions = listOf("confetti-wear"),
          trustStoreConfigured = true,
        )
        .also { it.start() }
    try {
      fun statusJson(): String {
        val url = "http://127.0.0.1:${srv.port}/status.json"
        client.newCall(Request.Builder().url(url).build()).execute().use {
          return it.body?.string() ?: ""
        }
      }
      // Resident: a live read, not a snapshot.
      val whileResident = statusJson()
      assertTrue(whileResident.contains("\"metaStale\":false"), whileResident)
      assertTrue(
        whileResident.contains(
          "\"trust\":\"branch:joreilly/Confetti@design-artifacts/confetti-wear\""
        ),
        whileResident,
      )

      now = 100
      assertEquals(1, suspendable.suspendIdle(), "the idle catalog suspends")

      val homeUrl = "http://127.0.0.1:${srv.port}/"
      val home =
        client.newCall(Request.Builder().url(homeUrl).build()).execute().use {
          it.body?.string() ?: ""
        }
      assertTrue(home.contains("href=\"/confetti-wear/\""), home)
      assertTrue(home.contains("Confetti Wear"), home)
      assertEquals(
        0,
        suspendable.runningDaemons().size,
        "building the home index must not resume an idle catalog daemon",
      )

      val whileIdle = statusJson()
      // The regression: trust, title, preview count and provenance all survive the suspension...
      assertTrue(
        whileIdle.contains("\"trust\":\"branch:joreilly/Confetti@design-artifacts/confetti-wear\""),
        "a suspended catalog keeps its trust verdict: $whileIdle",
      )
      assertTrue(whileIdle.contains("\"title\":\"Confetti Wear\""), whileIdle)
      assertTrue(whileIdle.contains("\"previews\":1"), whileIdle)
      // …flagged as last-known rather than a live read, and still counted as trusted.
      assertTrue(whileIdle.contains("\"metaStale\":true"), whileIdle)
      assertTrue(whileIdle.contains("\"trusted\":1"), "the summary counts it: $whileIdle")

      // The HTML page says "last known" instead of leaving the trust cell reading as untrusted.
      val htmlUrl = "http://127.0.0.1:${srv.port}/status"
      val html =
        client.newCall(Request.Builder().url(htmlUrl).build()).execute().use {
          it.body?.string() ?: ""
        }
      assertTrue(html.contains("✓ trusted"), html)
      assertTrue(html.contains("last known"), html)
    } finally {
      srv.stop()
      suspendable.close()
    }
  }

  @Test
  fun `status is token-gated on a non-public server`() {
    server = newServer(public = false, token = "s3cret")
    // No token → 404 (obscurity), like the other gated routes.
    assertEquals(404, get("/status").first)
    assertEquals(404, get("/status.json").first)
    // With the token → 200.
    assertEquals(200, get("/status.json", token = "s3cret").first)
    val (htmlCode, html) = get("/status", token = "s3cret")
    assertEquals(200, htmlCode)
    // The generated links keep the token so clicking them doesn't hit the intentional 404.
    assertTrue(html.contains("href=\"/status.json?token=s3cret\""), "status.json link keeps token")
    assertTrue(html.contains("href=\"/compose-m3/?token=s3cret\""), "catalog link keeps token")
  }
}
