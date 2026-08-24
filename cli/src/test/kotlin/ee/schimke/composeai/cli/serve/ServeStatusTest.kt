package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.StreamCodec
import ee.schimke.composeai.daemon.protocol.StreamFrameParams
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
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
    provenance: ServeWeb.CatalogProvenance? = null,
    failedPreviewIds: List<String> = emptyList(),
    deferredPreviewIds: List<String> = emptyList(),
  ): ServeBundleHost {
    val dir = Files.createTempDirectory("status-$label").toFile().also { it.deleteOnExit() }
    File(dir, "index.html").writeText("<html></html>")
    File(dir, "previews").apply { mkdirs() }
    previewIds.forEach { File(dir, "previews/$it.png").writeBytes(png()) }
    if (failedPreviewIds.isNotEmpty()) {
      val entries =
        failedPreviewIds.joinToString(",") { id ->
          "\"$id\":{\"componentId\":\"Broken\",\"renderFailure\":{" +
            "\"id\":\"$id\",\"componentId\":\"Broken\",\"errorClass\":" +
            "\"java.lang.NoSuchMethodError\",\"message\":\"boom\"}}"
        }
      File(dir, "previews/${ServeCatalogStore.VARIANTS_FILE}").writeText("{$entries}")
    }
    return ServeBundleHost(
      dir,
      label = label,
      title = title,
      provenance = provenance,
      declaredBaked = previewIds + failedPreviewIds,
      liveOnly = deferredPreviewIds,
    )
  }

  private val registry = ServeSessionRegistry(open = { null })
  private val daemonLog = DaemonStartupLog(clock = { 1_000L })

  private fun newServer(
    public: Boolean,
    token: String,
    catalogLoads: CatalogLoadTracker? = null,
    failedCatalogPreviews: List<String> = emptyList(),
    deferredCatalogPreviews: List<String> = emptyList(),
    recordDaemonFailure: Boolean = true,
    playgroundHealth: (() -> PlaygroundHealth)? = null,
  ): ServeHttpServer {
    registry.register(
      "default-mod",
      host = bundle("default-mod", listOf("com.example.Red")),
      pinned = true,
    )
    registry.register(
      "compose-m3",
      host =
        bundle(
          "compose-m3",
          listOf("button-filled", "switch-on"),
          title = "Compose Material 3",
          provenance =
            ServeWeb.CatalogProvenance(
              repo = "yschimke/compose-ai-tools",
              branch = "design-artifacts/compose-m3",
              generatedAt = Instant.now().minusSeconds(2 * 60 * 60).toString(),
              toolVersion = "0.16.54",
              designParityVersion = "0.1.25",
            ),
          failedPreviewIds = failedCatalogPreviews,
          deferredPreviewIds = deferredCatalogPreviews,
        ),
      pinned = true,
    )
    registry.register(
      "cadence",
      host = bundle("cadence", listOf("beat"), title = "Cadence"),
      pinned = true,
    )
    // A recorded startup failure, so the status shows the degraded state + failure row.
    if (recordDaemonFailure) daemonLog.record("wear-m3", "daemon launch timed out")
    return ServeHttpServer(
        host = "127.0.0.1",
        requestedPort = 0,
        token = token,
        sessions = registry,
        defaultSessionId = "default-mod",
        isPublic = public,
        catalogSessions = listOf("compose-m3"),
        appCatalogSessions = listOf("cadence"),
        catalogLoads = catalogLoads,
        daemonLog = daemonLog,
        allowRenderTrusted = true,
        trustStoreConfigured = true,
        catalogRefreshSeconds = 600,
        acceptBundlesEnabled = false,
        playgroundHealth = playgroundHealth,
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
    assertTrue(body.contains("\"composeAiToolsVersion\":\"0.16.54\""), body)
    assertTrue(body.contains("\"designParityVersion\":\"0.1.25\""), body)
    // Config is surfaced for a monitor.
    assertTrue(body.contains("\"allowRenderTrusted\":true"), body)
    assertTrue(body.contains("\"catalogRefreshSeconds\":600"), body)
    // A resident static host consumes no live seat, but it still belongs in the diagnostic list.
    assertTrue(
      body.contains("\"runningServers\":[") &&
        body.contains("\"id\":\"compose-m3\",\"label\":\"compose-m3\""),
      "resident static hosts remain diagnosable: $body",
    )
    assertTrue(
      body.contains(
        "\"id\":\"compose-m3\",\"label\":\"compose-m3\",\"backend\":\"static\",\"seatWeight\":0"
      ),
      "static hosts must not claim a daemon backend or live seat: $body",
    )
  }

  @Test
  fun `live status responses are not cacheable`() {
    server = newServer(public = true, token = "unused")
    for (path in listOf("/status", "/status.json", "/status?format=json")) {
      val request = Request.Builder().url("http://127.0.0.1:${server!!.port}$path").build()
      client.newCall(request).execute().use { response ->
        assertEquals(200, response.code)
        assertEquals("no-store", response.header("Cache-Control"))
      }
    }
  }

  @Test
  fun `status_json exposes owner-free editing soak counters`() {
    server =
      newServer(public = true, token = "unused") {
        PlaygroundHealth(
          admittedBy = "GitHub repository access",
          sandboxProfile = "bwrap",
          sandboxActive = true,
          jailDropped = false,
          sandboxMemoryMb = 1536,
          sandboxCpus = 1.0,
          sandboxTtlSeconds = 180,
          probe = null,
          compilerJailed = true,
          compileSlots = 1,
          modes = { emptyList() },
          editing = {
            PlaygroundHealth.Editing(
              enabled = true,
              active = true,
              expiresAtEpochMs = 2_000,
              lastRevision = 7,
              acquisitions = 3,
              compileAttempts = 12,
              incrementalCompiles = 10,
              fullFallbacks = 2,
              lastCompileMillis = 418,
            )
          },
        )
      }

    val (code, body) = get("/status.json")

    assertEquals(200, code)
    assertTrue(body.contains("\"editing\":{\"enabled\":true,\"active\":true"), body)
    assertTrue(body.contains("\"lastRevision\":7"), body)
    assertTrue(body.contains("\"incrementalCompiles\":10"), body)
    assertTrue(body.contains("\"fullFallbacks\":2"), body)
    assertFalse(body.contains("editLease"), "status must not expose the lease capability: $body")
  }

  @Test
  fun `status_json tracks catalog theme optimization completion`() {
    server = newServer(public = true, token = "unused")
    val catalogId = "button-filled"
    val daemonId = "FilledButton"
    val theme = ServeTheme("Brand Dark", "com.example.BrandDark")
    val overrides = PreviewOverrides(themeProvider = theme.providerFqn)
    val cache = CatalogThemeCache(maxBytes = 1024)
    val key = ServeOverrides.cacheKey(catalogId, overrides)
    cache.configureTargets(listOf(key))
    cache.put(key, png())
    val live =
      object : ServeHost {
        override val previews = listOf(ServePreview(daemonId, daemonId))
        override val label = "live"
        override val declaredThemes = listOf(theme)

        override fun render(previewId: String, overrides: PreviewOverrides): RenderOutcome =
          RenderOutcome.Ok(png())

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
    registry.register(
      "compose-m3",
      host =
        ServeCatalogLiveHost(
          alias = mapOf(catalogId to daemonId),
          live = live,
          baked = bundle("compose-m3", listOf(catalogId), title = "Compose Material 3"),
          catalogThemeCache = cache,
        ),
      pinned = true,
    )

    val (code, body) = get("/status.json")

    assertEquals(200, code)
    assertTrue(body.contains("\"themeOptimization\":{\"state\":\"complete\""), body)
    assertTrue(body.contains("\"fullyOptimized\":true"), body)
    assertTrue(body.contains("\"cached\":1"), body)
    assertTrue(body.contains("\"renderCache\":{"), body)
    assertTrue(body.contains("\"entries\":1"), body)
    assertTrue(body.contains("\"maxBytes\":1024"), body)
    assertTrue(body.contains("\"evictions\":0"), body)

    val (htmlCode, html) = get("/status")
    assertEquals(200, htmlCode)
    assertTrue(html.contains("preview cache 1 entries"), html)
    assertTrue(html.contains("/ 1 KiB"), html)
  }

  @Test
  fun `status_json includes per-preview daemon pool occupancy`() {
    val live =
      object : ServeHost {
        override val previews: List<ServePreview> = listOf(ServePreview("a", "A"))
        override val label: String = "live"
        override val hasLiveStream: Boolean = true

        override fun render(previewId: String, overrides: PreviewOverrides): RenderOutcome =
          RenderOutcome.Ok(png())

        override fun activeStreamCount(): Int = 1

        override fun subscribeStream(
          previewId: String,
          overrides: PreviewOverrides,
          codec: StreamCodec?,
          maxFps: Int?,
          onUnavailable: ((String) -> Unit)?,
          onFrame: (StreamFrameParams) -> Unit,
        ): StreamHandle? = null

        override fun daemonPoolStats(): List<DaemonPoolSnapshot> =
          listOf(DaemonPoolSnapshot("per-preview", open = 2, maxOpen = 4, activeStreams = 1))

        override fun close() {}
      }
    registry.register("live", host = live)
    server =
      ServeHttpServer(
          host = "127.0.0.1",
          requestedPort = 0,
          token = "unused",
          sessions = registry,
          defaultSessionId = "live",
          isPublic = true,
        )
        .also { it.start() }

    val (code, body) = get("/status.json")

    assertEquals(200, code)
    assertTrue(body.contains("\"daemonPools\""), body)
    assertTrue(body.contains("\"name\":\"per-preview\""), body)
    assertTrue(body.contains("\"open\":2"), body)
    assertTrue(body.contains("\"maxOpen\":4"), body)
  }

  @Test
  fun `status health recovers after a successful live render while retaining failure history`() {
    val stats = RenderPerfStats()
    val live =
      object : ServeHost {
        override val previews: List<ServePreview> = listOf(ServePreview("a", "A"))
        override val label: String = "live"
        override val hasLiveStream: Boolean = true

        override fun render(previewId: String, overrides: PreviewOverrides): RenderOutcome =
          RenderOutcome.Ok(png())

        override fun activeStreamCount(): Int = 1

        override fun subscribeStream(
          previewId: String,
          overrides: PreviewOverrides,
          codec: StreamCodec?,
          maxFps: Int?,
          onUnavailable: ((String) -> Unit)?,
          onFrame: (StreamFrameParams) -> Unit,
        ): StreamHandle? = null

        override fun renderPerfStats(): RenderPerfSnapshot = stats.snapshot()

        override fun close() {}
      }
    registry.register("live", host = live)
    server =
      ServeHttpServer(
          host = "127.0.0.1",
          requestedPort = 0,
          token = "unused",
          sessions = registry,
          defaultSessionId = "live",
          isPublic = true,
        )
        .also { it.start() }

    stats.recordFailed(500, timeout = false, reason = "transient render failure")
    val (_, failedJson) = get("/status.json")
    assertTrue(failedJson.contains("\"status\":\"degraded\""), failedJson)
    assertTrue(failedJson.contains("\"lastRenderFailed\":true"), failedJson)

    stats.recordOk(100, cold = false)
    val (_, recoveredJson) = get("/status.json")
    assertTrue(recoveredJson.contains("\"status\":\"ok\""), recoveredJson)
    assertTrue(recoveredJson.contains("\"lastRenderFailed\":false"), recoveredJson)
    assertTrue(recoveredJson.contains("transient render failure"), recoveredJson)

    val (_, recoveredHtml) = get("/status")
    assertTrue(recoveredHtml.contains("✓ healthy"), recoveredHtml)
    assertTrue(recoveredHtml.contains("transient render failure"), recoveredHtml)
  }

  @Test
  fun `status health includes a resident daemon whose render breaker disabled its live stream`() {
    val stats =
      RenderPerfStats()
        .snapshot()
        .copy(
          breaker =
            RenderBreakerSnapshot(
              open = true,
              fatal = true,
              reason = "render linkage failure",
            )
        )
    val broken =
      object : ServeHost {
        override val previews: List<ServePreview> = listOf(ServePreview("a", "A"))
        override val label: String = "broken"
        override val hasLiveStream: Boolean = false

        override fun render(previewId: String, overrides: PreviewOverrides): RenderOutcome =
          RenderOutcome.Failed("render linkage failure")

        override fun activeStreamCount(): Int = 0

        override fun subscribeStream(
          previewId: String,
          overrides: PreviewOverrides,
          codec: StreamCodec?,
          maxFps: Int?,
          onUnavailable: ((String) -> Unit)?,
          onFrame: (StreamFrameParams) -> Unit,
        ): StreamHandle? = null

        override fun renderPerfStats(): RenderPerfSnapshot = stats

        override fun close() {}
      }
    registry.register("broken", host = broken)
    server =
      ServeHttpServer(
          host = "127.0.0.1",
          requestedPort = 0,
          token = "unused",
          sessions = registry,
          defaultSessionId = "broken",
          isPublic = true,
        )
        .also { it.start() }

    val (jsonCode, json) = get("/status.json")
    assertEquals(200, jsonCode)
    assertTrue(json.contains("\"status\":\"degraded\""), json)
    assertTrue(json.contains("\"runningServers\":[{\"id\":\"broken\""), json)
    assertTrue(json.contains("\"breaker\":{\"open\":true"), json)

    val (htmlCode, html) = get("/status")
    assertEquals(200, htmlCode)
    assertTrue(html.contains("1 open live render breaker"), html)
    assertTrue(html.contains("href=\"#recent-render-failures\""), html)
    assertTrue(html.contains(">broken<"), html)
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
    assertTrue(
      body.contains(
        "href=\"https://github.com/yschimke/compose-ai-tools/tree/design-artifacts/compose-m3\""
      ),
      body,
    )
    assertTrue(body.contains("2 hours ago"), body)
    assertTrue(body.contains("compose-ai-tools <code>0.16.54</code>"), body)
    assertTrue(body.contains("design-parity <code>0.1.25</code>"), body)
    // The recent failure surfaces the degraded badge + row.
    assertTrue(body.contains("degraded"), body)
    assertTrue(body.contains("href=\"#recent-daemon-failures\""), body)
    assertTrue(body.contains("1 daemon startup failure"), body)
    assertTrue(body.contains("daemon launch timed out"), body)
  }

  @Test
  fun `status uses relative catalog times and omits a repeated server id`() {
    val now = Instant.parse("2026-08-18T12:00:00Z")
    val html =
      ServeWeb.statusPage(
        token = "unused",
        view =
          ServeWeb.StatusView(
            version = "test",
            public = true,
            nowMillis = now.toEpochMilli(),
            overallOk = true,
            summary = emptyList(),
            config = emptyList(),
            catalogs =
              listOf(
                ServeWeb.StatusCatalog(
                  id = "old-catalog",
                  title = "Old catalog",
                  listed = true,
                  trust = "unverified",
                  previews = 1,
                  live = false,
                  running = false,
                  degradation = null,
                  provenance =
                    ServeWeb.CatalogProvenance(
                      repo = "example/catalog",
                      branch = "published",
                      generatedAt = now.minusSeconds(2 * 86_400).toString(),
                    ),
                ),
                ServeWeb.StatusCatalog(
                  id = "future-catalog",
                  title = "Future catalog",
                  listed = true,
                  trust = "unverified",
                  previews = 1,
                  live = false,
                  running = false,
                  degradation = null,
                  provenance =
                    ServeWeb.CatalogProvenance(
                      repo = "example/future-catalog",
                      branch = "published",
                      generatedAt = now.plusSeconds(2 * 86_400).toString(),
                    ),
                ),
              ),
            servers =
              listOf(
                ServeWeb.StatusServer(
                  id = "same-session-x",
                  label = "same-session-x",
                  backend = "desktop",
                  activeStreams = 0,
                  upForText = "1m",
                )
              ),
            failures = emptyList(),
          ),
      )

    assertTrue(html.contains(">2 days ago</span>"), html)
    assertTrue(html.contains("title=\"2026-08-16T12:00:00Z\""), html)
    assertTrue(html.contains(">in 2 days</span>"), html)
    assertTrue(html.contains("title=\"2026-08-20T12:00:00Z\""), html)
    assertTrue(html.contains("<td>same-session-x</td>"), html)
    assertFalse(html.contains("<div class=\"cp-muted\">same-session-x</div>"), html)
  }

  @Test
  fun `status distinguishes published render failures from an empty catalog`() {
    server =
      newServer(
        public = true,
        token = "unused",
        failedCatalogPreviews = listOf("render-failed--button-filled"),
        deferredCatalogPreviews = listOf("live-only-one", "live-only-two"),
        recordDaemonFailure = false,
      )
    val (_, json) = get("/status.json")
    assertTrue(json.contains("\"failedRenders\":1"), json)
    assertTrue(json.contains("\"deferredPreviews\":2"), json)
    assertTrue(json.contains("\"status\":\"ok\""), json)

    val (_, html) = get("/status")
    assertTrue(html.contains("2 rendered · 1 failed · 2 deferred"), html)
    assertTrue(html.contains("Published catalog renders"), html)
    assertTrue(html.contains("✓ healthy"), html)
    assertTrue(html.contains("No recent render failures."), html)
    assertTrue(html.contains("cp-meter-segment--warning"), html)
  }

  @Test
  fun `configured catalog failures remain visible in status`() {
    val loads =
      CatalogLoadTracker(
        listOf(
          CatalogLoadTracker.Config(
            "compose-m3",
            listed = true,
            repo = "yschimke/compose-ai-tools",
            branch = "design-artifacts/compose-m3",
          ),
          CatalogLoadTracker.Config(
            "reply",
            listed = true,
            repo = "yschimke/compose-samples",
            branch = "design-artifacts/reply",
          ),
        ),
        clock = { 4_242L },
      )
    loads.recordSuccess("compose-m3")
    loads.recordFailure("reply", "could not parse catalog.json: expected a string")
    server = newServer(public = true, token = "unused", catalogLoads = loads)

    val (jsonCode, json) = get("/status.json")
    assertEquals(200, jsonCode)
    assertTrue(json.contains("\"status\":\"degraded\""), json)
    assertTrue(json.contains("\"total\":2"), json)
    assertTrue(json.contains("\"loaded\":1"), json)
    assertTrue(json.contains("\"failed\":1"), json)
    assertTrue(json.contains("\"id\":\"reply\""), json)
    assertTrue(json.contains("\"loadState\":\"failed\""), json)
    assertTrue(json.contains("could not parse catalog.json: expected a string"), json)
    assertTrue(json.contains("\"lastLoadAttemptEpochMillis\":4242"), json)

    val (htmlCode, html) = get("/status")
    assertEquals(200, htmlCode)
    assertTrue(html.contains("1/2 loaded"), html)
    assertTrue(html.contains("failed to load"), html)
    assertTrue(html.contains("could not parse catalog.json: expected a string"), html)
    assertTrue(!html.contains("href=\"/reply/\""), "a failed catalog must not link to a 404: $html")
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
          return it.body.string()
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
        client.newCall(Request.Builder().url(homeUrl).build()).execute().use { it.body.string() }
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

      // …and the HTML page marks the row "last known" rather than dropping the qualifier.
      //
      // Positive trust is deliberately SILENT since #3893 — a green tick beside every catalog is
      // chrome nobody reads, and only the warning verdict carries information — so this used to
      // assert `✓ trusted` and can't. What still has to hold, and is what the regression was
      // actually about, is that suspending a catalog must not downgrade its verdict: the one
      // catalog on this page is trusted, so the untrusted warning must be absent from the whole
      // document even though its facts are now a snapshot rather than a live read.
      val htmlUrl = "http://127.0.0.1:${srv.port}/status"
      val html =
        client.newCall(Request.Builder().url(htmlUrl).build()).execute().use { it.body.string() }
      assertFalse(
        html.contains("untrusted"),
        "a suspended trusted catalog is not downgraded: $html",
      )
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

  @Test
  fun `status reports delivery-branch read counters`() {
    // The gap this closes: renders have had failure telemetry for a long time, and branch reads —
    // the lane that actually talks to GitHub — had none. So "is GitHub rate-limiting us, or was
    // that asset never published?" could only be answered by reproducing it by hand with curl.
    val stats = BranchFetchStats(clock = { 1_700_000_000_000L })
    stats.record(BranchFetch.Ok(byteArrayOf(1)))
    stats.record(BranchFetch.NotFound)
    stats.record(BranchFetch.Throttled(5))

    server =
      ServeHttpServer(
          host = "127.0.0.1",
          requestedPort = 0,
          token = "unused",
          sessions = registry,
          defaultSessionId = "default-mod",
          isPublic = true,
          branchFetchStats = { stats.snapshot() },
        )
        .also { it.start() }

    val (code, body) = get("/status.json")
    assertEquals(200, code)
    val branch =
      Json.parseToJsonElement(body).jsonObject["branchFetch"]?.jsonObject
        ?: error("status.json carries no branchFetch: $body")
    assertEquals(3, branch["attempted"]?.jsonPrimitive?.int)
    assertEquals(1, branch["ok"]?.jsonPrimitive?.int)
    // The two that must never merge: an absent asset is routine, a throttle is the alert.
    assertEquals(1, branch["notFound"]?.jsonPrimitive?.int)
    assertEquals(1, branch["throttled"]?.jsonPrimitive?.int)
    assertEquals(
      1_700_000_000_000L,
      branch["lastThrottleAtEpochMillis"]?.jsonPrimitive?.long,
      "a monitor needs to know WHEN, not just whether",
    )
  }

  @Test
  fun `a server that has read no branch advertises no counters`() {
    // Null rather than a block of zeros, like the render roll-up: "nothing has been read" and
    // "everything read succeeded" are different answers, and a monitor that saw `throttled: 0` from
    // a server that has never read a branch would be reading reassurance into silence.
    server =
      ServeHttpServer(
          host = "127.0.0.1",
          requestedPort = 0,
          token = "unused",
          sessions = registry,
          defaultSessionId = "default-mod",
          isPublic = true,
        )
        .also { it.start() }

    val (code, body) = get("/status.json")
    assertEquals(200, code)
    val field = Json.parseToJsonElement(body).jsonObject["branchFetch"]
    assertTrue(field == null || field is kotlinx.serialization.json.JsonNull, "got $field in $body")
  }

  @Test
  fun `status reports optimizer admission so a slow box explains itself`() {
    // The per-catalog themeOptimization rows cannot answer this: a box where 15 catalogs all report
    // "running" and nothing progresses looks, catalog by catalog, exactly like a healthy one. What
    // distinguishes them is how many passes are INSIDE the door versus parked at it.
    val bg =
      ServeBackgroundWork(
        maxConcurrentRenders = 8,
        clock = { 5_000L },
        maxConcurrentOptimizers = 2,
      )
    bg.pauseOptimizers(millis = 60_000, reason = "traffic spike")

    server =
      ServeHttpServer(
          host = "127.0.0.1",
          requestedPort = 0,
          token = "unused",
          sessions = registry,
          defaultSessionId = "default-mod",
          isPublic = true,
          themeOptimizerStats = { bg.optimizerAdmissionSnapshot() },
        )
        .also { it.start() }

    val (code, body) = get("/status.json")
    assertEquals(200, code)
    val opt =
      Json.parseToJsonElement(body).jsonObject["themeOptimizer"]?.jsonObject
        ?: error("status.json carries no themeOptimizer: $body")
    assertEquals(2, opt["lanes"]?.jsonPrimitive?.int)
    assertEquals(0, opt["running"]?.jsonPrimitive?.int)
    assertEquals(true, opt["paused"]?.jsonPrimitive?.content?.toBoolean())
    assertEquals(65_000L, opt["pausedUntilEpochMillis"]?.jsonPrimitive?.long)
    assertTrue(body.contains("traffic spike"), "the pause reason should reach the page: $body")
  }

  /**
   * The gate's input reaches the page, in both projections.
   *
   * `/status.json` had every counter describing what a pass did once it was granted a turn and none
   * describing whether a turn was available at all — so a server whose quiet gate never opened
   * published the same row as one with nothing left to do. The HTML page carried even less: 23
   * catalogs each saying "theme optimization paused" and nothing saying why.
   */
  @Test
  fun `status explains a theme optimizer gate that is being held shut`() {
    val bg = ServeBackgroundWork(clock = { 5_000L })
    bg.initialCatalogLoadFinished()
    // Null is the registry's "a session holds an open lease" — permanently busy to the gate.
    bg.idleClock { null }

    server =
      ServeHttpServer(
          host = "127.0.0.1",
          requestedPort = 0,
          token = "unused",
          sessions = registry,
          defaultSessionId = "default-mod",
          isPublic = true,
          themeOptimizerStats = { bg.optimizerAdmissionSnapshot() },
        )
        .also { it.start() }

    val (code, body) = get("/status.json")
    assertEquals(200, code)
    val opt =
      Json.parseToJsonElement(body).jsonObject["themeOptimizer"]?.jsonObject
        ?: error("status.json carries no themeOptimizer: $body")
    assertTrue(
      opt["serverIdleMillis"] is kotlinx.serialization.json.JsonNull,
      "a busy clock is published as null, not omitted: $body",
    )
    assertEquals(
      ServeBackgroundWork.IDLE_BLOCKED_BY_SESSION_LEASE,
      opt["idleBlockedBy"]?.jsonPrimitive?.content,
    )
    assertTrue(
      (opt["idleThresholdMillis"]?.jsonPrimitive?.long ?: 0) > 0,
      "the threshold travels with the reading: $body",
    )
    assertNotNull(
      Json.parseToJsonElement(body).jsonObject["daemons"]?.jsonObject?.get("leasedSessions"),
      "the lease holders are named beside the daemon counters: $body",
    )

    val (htmlCode, html) = get("/status")
    assertEquals(200, htmlCode)
    assertTrue(html.contains("Theme optimiser gate"), "the page names the gate: $html")
    assertTrue(html.contains("closed"), "and says it is shut: $html")
  }
}
