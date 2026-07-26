package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.daemon.protocol.RemoteNamedValue
import ee.schimke.composeai.data.overrides.PreviewOverrideDeclaration
import ee.schimke.composeai.data.overrides.PreviewOverrideType
import ee.schimke.composeai.data.overrides.PreviewOverrideValue
import ee.schimke.composeai.data.overrides.PreviewOverridesPayload
import ee.schimke.composeai.data.remotecompose.RemoteComposeDeclarationsPayload
import ee.schimke.composeai.data.remotecompose.RemoteComposeKnobDeclaration
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * End-to-end routing check for [ServeHttpServer]: a real embedded server fronting two static
 * [ServeBundleHost] sessions, exercised over HTTP. Guards the two access forms — the legacy
 * `?session=` query lane and the canonical path lane (`/<system>/…`) — and, crucially, that the
 * constant top-level routes (`/healthz`, `/readyz`, `/version`) still win over the `/{system}`
 * catch-all in Ktor's route scoring (a regression here would 404 liveness/readiness checks or
 * shadow `/version`).
 *
 * Runs public (no token) so the assertions stay about routing, not the auth gate ([ServeAuthTest]).
 */
class ServeHttpRoutingTest {

  private val previewId = "com.example.Red"

  private fun png(): ByteArray =
    ByteArrayOutputStream()
      .also { ImageIO.write(BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", it) }
      .toByteArray()

  /** An author-declared string knob, carried into the bundle as an `overrides.json` sidecar. */
  private val labelKnob =
    PreviewOverrideDeclaration(
      key = "label",
      type = PreviewOverrideType.STRING,
      label = "Label",
      default = PreviewOverrideValue.StringValue("Tap me"),
    )

  /** A declared Remote Compose knob, carried into the bundle as a `remotecompose.json` sidecar. */
  private val rcColorKnob =
    RemoteComposeKnobDeclaration("shaderColor", RemoteNamedValue.ColorValue("#FF7DE2FF"))

  /** Arbitrary Remote Compose document bytes, carried into the bundle as an `ir/<id>.rc`. */
  private val rcDocBytes = byteArrayOf(0x52, 0x43, 0x01, 0x02, 0x03)

  private fun bundle(
    label: String,
    overrides: List<PreviewOverrideDeclaration> = emptyList(),
    remoteComposeKnobs: List<RemoteComposeKnobDeclaration> = emptyList(),
    degradations: List<ServeDegradation> = emptyList(),
    rcDoc: ByteArray? = null,
  ): ServeBundleHost {
    val dir = Files.createTempDirectory("routing-$label").toFile().also { it.deleteOnExit() }
    File(dir, "index.html").writeText("<html></html>")
    File(dir, "previews").apply { mkdirs() }
    File(dir, "previews/$previewId.png").writeBytes(png())
    if (rcDoc != null) {
      File(dir, "ir").apply { mkdirs() }
      File(dir, "ir/$previewId.rc").writeBytes(rcDoc)
    }
    if (overrides.isNotEmpty()) {
      val sidecar =
        Json.encodeToString(
          PreviewOverridesPayload.serializer(),
          PreviewOverridesPayload(overrides),
        )
      File(dir, "previews/$previewId.overrides.json").writeText(sidecar)
    }
    if (remoteComposeKnobs.isNotEmpty()) {
      val sidecar =
        Json.encodeToString(
          RemoteComposeDeclarationsPayload.serializer(),
          RemoteComposeDeclarationsPayload(remoteComposeKnobs),
        )
      File(dir, "previews/$previewId.remotecompose.json").writeText(sidecar)
    }
    return ServeBundleHost(dir, label = label, degradations = degradations)
  }

  private val registry = ServeSessionRegistry(open = { null })
  private val server: ServeHttpServer by lazy {
    registry.register("default-mod", host = bundle("default-mod"), pinned = true)
    registry.register(
      "compose-m3",
      host = bundle("compose-m3", listOf(labelKnob), listOf(rcColorKnob), rcDoc = rcDocBytes),
      pinned = true,
    )
    // A baked-only session carrying a degradation reason — reachable at /baked-only/… but kept off
    // catalogSessions so the home-index test is unaffected. Exercises the /api/previews surfacing.
    registry.register(
      "baked-only",
      host = bundle("baked-only", degradations = listOf(ServeDegradation.catalogBakedOnly())),
      pinned = true,
    )
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

  /** Poll `/readyz` until it latches ready (the prober renders off the request path), up to ~5s. */
  private fun awaitReady(): Boolean {
    repeat(50) {
      if (get("/readyz") == 200 to "ready") return true
      Thread.sleep(100)
    }
    return false
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
  fun `readyz goes green once the default session renders a preview`() {
    // The first poll kicks off the server-owned readiness prober; the default session (default-mod)
    // carries a baked preview, so that render succeeds OFF the request path and latches ready — the
    // signal docker-rollout gates the swap on. Unlike /healthz (a static "ok"), this only flips
    // because a real render succeeded. Poll until green, exactly like a real healthcheck does.
    assertTrue(awaitReady(), "readyz should latch ready after the default session renders")
    // Latched: it stays green.
    assertEquals(200 to "ready", get("/readyz"))
  }

  @Test
  fun `readyz withholds ready when the default session cannot render`() {
    // A server whose default session resolves to nothing (an empty registry) can't render a
    // representative preview, so /readyz must report 503 "warming" — NOT a false green. This is the
    // failure docker-rollout must catch: a replica up on the port but unable to serve. Its own
    // server + registry so the class-level `server` fields are untouched.
    val emptyRegistry = ServeSessionRegistry(open = { null })
    val brokenServer =
      ServeHttpServer(
          host = "127.0.0.1",
          requestedPort = 0,
          token = "unused-in-public",
          sessions = emptyRegistry,
          defaultSessionId = "no-such-session",
          isPublic = true,
        )
        .also { it.start() }
    try {
      val req = Request.Builder().url("http://127.0.0.1:${brokenServer.port}/readyz").build()
      client.newCall(req).execute().use { r ->
        assertEquals(503, r.code)
        assertEquals("warming", r.body?.string())
      }
    } finally {
      brokenServer.stop()
      emptyRegistry.close()
    }
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
  fun `public browse pages and baked previews advertise static generation`() {
    val homeReq = Request.Builder().url("http://127.0.0.1:${server.port}/").build()
    client.newCall(homeReq).execute().use { response ->
      assertEquals("static-page", response.header(ServeHttpServer.GENERATION_HEADER))
      assertTrue(response.header("Cache-Control")?.startsWith("public, max-age=60") == true)
    }

    val landingReq = Request.Builder().url("http://127.0.0.1:${server.port}/compose-m3/").build()
    client.newCall(landingReq).execute().use { response ->
      assertEquals("static-page", response.header(ServeHttpServer.GENERATION_HEADER))
      assertTrue(response.header("Cache-Control")?.startsWith("public, max-age=60") == true)
    }

    val viewerReq =
      Request.Builder().url("http://127.0.0.1:${server.port}/compose-m3/p/$previewId").build()
    client.newCall(viewerReq).execute().use { response ->
      assertEquals("static-page", response.header(ServeHttpServer.GENERATION_HEADER))
      assertTrue(response.header("Cache-Control")?.startsWith("public, max-age=60") == true)
    }

    val renderReq =
      Request.Builder()
        .url("http://127.0.0.1:${server.port}/compose-m3/render/$previewId.png")
        .build()
    client.newCall(renderReq).execute().use { response ->
      assertEquals("baked", response.header(ServeHttpServer.GENERATION_HEADER))
      assertTrue(response.header("Cache-Control")?.startsWith("public, max-age=300") == true)
    }
  }

  @Test
  fun `variant render remains non cacheable`() {
    val req =
      Request.Builder()
        .url("http://127.0.0.1:${server.port}/compose-m3/render/$previewId.png?fontScale=1.5")
        .build()
    client.newCall(req).execute().use { response ->
      // This fixture is a static bundle, so it can only return baked bytes; the variant-bearing
      // request is nevertheless dynamic and must never poison the cache for another query.
      assertEquals("baked", response.header(ServeHttpServer.GENERATION_HEADER))
      assertEquals("no-store", response.header("Cache-Control"))
    }
  }

  @Test
  fun `a static bundle 404s the svg render lane`() {
    // The .svg lane is routed and dispatched, but a bundle host has no daemon to run the figma-svg
    // export, so it resolves to NotFound (only a daemon-backed ServeRenderHost produces SVG).
    val (code, _) = get("/compose-m3/render/$previewId.svg")
    assertEquals(404, code)
  }

  @Test
  fun `a static bundle 404s the full-page svg render lane`() {
    // `?scroll=long` routes to the full-page (compose/figma-svg-long) lane; a bundle host has no
    // daemon to run the expanded re-render, so it resolves to NotFound like the viewport SVG lane.
    val (code, _) = get("/compose-m3/render/$previewId.svg?scroll=long")
    assertEquals(404, code)
  }

  @Test
  fun `a static bundle 404s the slots render lane`() {
    // The .slots lane is routed and dispatched, but a bundle host has no daemon to capture a
    // semantics tree, so it resolves to NotFound (only a daemon-backed ServeRenderHost extracts
    // slots).
    val (code, _) = get("/compose-m3/render/$previewId.slots")
    assertEquals(404, code)
  }

  @Test
  fun `the rc render lane serves the captured remote compose document bytes`() {
    // compose-m3 carries an `ir/<id>.rc` sidecar, so `GET /render/<id>.rc` returns those
    // bytes
    // verbatim (octet-stream) for the in-browser player to replay client-side.
    val req =
      Request.Builder()
        .url("http://127.0.0.1:${server.port}/compose-m3/render/$previewId.rc")
        .build()
    client.newCall(req).execute().use { r ->
      assertEquals(200, r.code)
      assertEquals(
        "application/octet-stream",
        r.body?.contentType()?.let { "${it.type}/${it.subtype}" },
      )
      assertTrue(rcDocBytes.contentEquals(r.body?.bytes()), "rc bytes served verbatim")
    }
  }

  @Test
  fun `a bundle without an rc sidecar 404s the rc render lane`() {
    // default-mod carries no `ir/` tree, so the client-side player lane resolves to NotFound rather
    // than serving an empty document.
    val (code, _) = get("/render/$previewId.rc?session=default-mod")
    assertEquals(404, code)
  }

  @Test
  fun `the viewer offers the in-browser canvas lane when a preview carries an rc document`() {
    // compose-m3's preview has an `ir/<id>.rc` sidecar, so its viewer page advertises the RC canvas
    // lane: the flag the transport JS keys on, the canvas, the mode radio, and the toggle button.
    val (code, html) = get("/compose-m3/p/$previewId")
    assertEquals(200, code)
    assertTrue(html.contains("data-has-rc-doc=\"1\""), "viewer flags the rc document: $html")
    assertTrue(html.contains("id=\"cp-rc-canvas\""), "rc canvas element present")
    assertTrue(html.contains("id=\"cp-rc-btn\""), "rc toggle button present")
    assertTrue(html.contains("value=\"rc\""), "rc mode radio present")
    // The client-side lane JS loads the player and applies knob edits without a daemon round-trip.
    assertTrue(html.contains("/rc-player/bundle.js"), "the lane loads the player bundle")
    assertTrue(html.contains("RcdPlayer"), "the lane creates the Rc player")
    assertTrue(html.contains("setNamedFloatOverride"), "rc knob edits apply client-side")
  }

  @Test
  fun `the viewer omits the canvas lane when the preview has no rc document`() {
    // default-mod carries no `.rc` document, so the RC canvas lane's HTML (flag, canvas, toggle) is
    // absent and its Remote Compose knobs stay on the daemon path.
    val (code, html) = get("/p/$previewId?session=default-mod")
    assertEquals(200, code)
    // The transport JS always *reads* `data-has-rc-doc`, so assert the attribute form (`="1"`) is
    // absent rather than the bare name, plus the lane's HTML elements.
    assertTrue(
      !html.contains("data-has-rc-doc=\"1\""),
      "no rc-doc flag on a docless preview: $html",
    )
    assertTrue(!html.contains("id=\"cp-rc-canvas\""), "no rc canvas on a docless preview")
    assertTrue(!html.contains("id=\"cp-rc-btn\""), "no rc toggle on a docless preview")
  }

  @Test
  fun `the rc player bundle is served as javascript with a conditional-request etag`() {
    // The vendored Remote Compose player rides in the CLI jar and is served over
    // `/rc-player/bundle.js` (a constant segment, session-independent) so the viewer's client-side
    // canvas lane can load the `RC` global. Served as JS (so the browser executes it) with a
    // content-hash ETag for cheap conditional requests.
    val etag: String
    val req = Request.Builder().url("http://127.0.0.1:${server.port}/rc-player/bundle.js").build()
    client.newCall(req).execute().use { r ->
      assertEquals(200, r.code)
      assertEquals("text/javascript", r.body?.contentType()?.let { "${it.type}/${it.subtype}" })
      val body = r.body?.string() ?: ""
      assertTrue(body.contains("RcdPlayer"), "the bundle exposes the RcdPlayer entry point")
      etag = r.header("ETag") ?: ""
      assertTrue(etag.isNotEmpty(), "carries a content-hash ETag")
    }
    // A conditional GET carrying the matching ETag gets a cheap 304 instead of re-downloading the
    // ~640 KB bundle.
    val conditional =
      Request.Builder()
        .url("http://127.0.0.1:${server.port}/rc-player/bundle.js")
        .header("If-None-Match", etag)
        .build()
    client.newCall(conditional).execute().use { r -> assertEquals(304, r.code) }
  }

  @Test
  fun `api previews advertises v2 and carries author override declarations`() {
    val (code, api) = get("/compose-m3/api/previews")
    assertEquals(200, code)
    // v2 = the payload now carries per-preview override declarations.
    assertTrue(api.contains("\"schema\":\"compose-preview-serve/v2\""), "schema v2: $api")
    // The declared `label` knob (from the sidecar) surfaces to a programmatic client.
    assertTrue(api.contains("\"overrides\":["), "overrides array present: $api")
    assertTrue(api.contains("\"key\":\"label\""), "declared knob key: $api")
    assertTrue(api.contains("\"value\":\"Tap me\""), "declared knob default value: $api")
    // The declared Remote Compose knob (from the `.remotecompose.json` sidecar) surfaces too, with
    // its typed default (the `kind` discriminator + argb the connector round-trips).
    assertTrue(api.contains("\"remoteComposeKnobs\":["), "rc knobs array present: $api")
    assertTrue(api.contains("\"name\":\"shaderColor\""), "declared rc knob name: $api")
    assertTrue(api.contains("\"kind\":\"color\""), "rc knob typed default kind: $api")
    assertTrue(api.contains("\"argb\":\"#FF7DE2FF\""), "rc knob default argb: $api")
    // A fully-served (non-degraded) session carries an empty degradations array.
    assertTrue(
      api.contains("\"degradations\":[]"),
      "empty degradations for a live-capable session: $api",
    )
  }

  @Test
  fun `api previews surfaces the degradation reason for a snapshot-only session`() {
    val (code, api) = get("/baked-only/api/previews")
    assertEquals(200, code)
    // The session-level reason rides alongside `trust`, so a programmatic client (the Figma plugin)
    // sees WHY the session is snapshot-only without scraping the viewer HTML.
    assertTrue(api.contains("\"code\":\"catalog-baked-only\""), "degradation code: $api")
    assertTrue(api.contains("publishes no live bundle"), "human detail: $api")
  }

  @Test
  fun `the bare root serves the design-systems home index, not the default module`() {
    val (code, body) = get("/")
    assertEquals(200, code)
    assertTrue(body.contains("Design systems"), "root is the systems index: $body")
    // A card links to the catalog's canonical path and shows a hero preview — from the PREBAKED
    // `/hero/` lane, not the live `/render` one, so the front door costs the server nothing to
    // paint.
    assertTrue(body.contains("href=\"/compose-m3/\""), "index card links to the system: $body")
    assertTrue(
      heroSrc(body)?.startsWith("/hero/compose-m3/") == true,
      "index card shows a prebaked hero: $body",
    )
    assertTrue(
      !body.contains("/compose-m3/render/$previewId.png"),
      "the front door does not put a render request on the server: $body",
    )
    // It is NOT the default module's own preview grid.
    assertTrue(!body.contains("default-mod"), "root is the index, not the default module: $body")
  }

  /** The `src` of the first hero `<img>` on the home index, or null when there is none. */
  private fun heroSrc(html: String): String? =
    Regex("""<img[^>]*src="(/hero/[^"]+)"""").find(html)?.groupValues?.get(1)

  @Test
  fun `a prebaked hero is served immutable, and revalidates to 304`() {
    val src = heroSrc(get("/").second) ?: error("no prebaked hero on the front door")
    val req = Request.Builder().url("http://127.0.0.1:${server.port}$src").build()
    val etag =
      client.newCall(req).execute().use { r ->
        assertEquals(200, r.code)
        assertEquals("image/png", r.header("Content-Type")?.substringBefore(';'))
        assertEquals(
          "public, max-age=31536000, immutable",
          r.header("Cache-Control"),
          "the content-hashed hero URL can be cached forever",
        )
        assertTrue((r.body?.bytes()?.size ?: 0) > 0, "hero bytes are served")
        r.header("ETag") ?: error("hero carries no ETag")
      }
    // A conditional request (a cache that chose to revalidate anyway) costs bytes, not a render.
    val conditional =
      Request.Builder()
        .url("http://127.0.0.1:${server.port}$src")
        .header("If-None-Match", etag)
        .build()
    client.newCall(conditional).execute().use { assertEquals(304, it.code) }
  }

  @Test
  fun `an unknown hero name 404s`() {
    assertEquals(404, get("/hero/compose-m3/0000000000000000.png").first)
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

  @Test
  fun `index_json serves a storybook stories index`() {
    val (code, body) = get("/compose-m3/index.json")
    assertEquals(200, code)
    assertTrue(body.contains("\"v\":${StorybookCompat.INDEX_VERSION}"), "index version: $body")
    assertTrue(body.contains("\"entries\":"), "entries map: $body")
    assertTrue(body.contains("\"type\":\"story\""), "story-typed entry: $body")
    // The synthetic importPath encodes the native preview id, so a reader can recover it.
    assertTrue(
      body.contains("virtual:compose-preview/$previewId"),
      "entry importPath carries the native id: $body",
    )
    // The legacy query-session lane serves the same index.
    val (queryCode, queryBody) = get("/index.json?session=compose-m3")
    assertEquals(200, queryCode)
    assertTrue(queryBody.contains("\"entries\":"), "query-lane index: $queryBody")
  }

  @Test
  fun `iframe_html renders one story in isolation as an html page embedding the png`() {
    // The native preview id is accepted verbatim by iframe.html (the deep-link escape hatch), so
    // this doesn't depend on how the story id is minted from the label.
    val req =
      Request.Builder()
        .url("http://127.0.0.1:${server.port}/compose-m3/iframe.html?id=$previewId")
        .build()
    client.newCall(req).execute().use { r ->
      assertEquals(200, r.code)
      assertEquals("text/html", r.body?.contentType()?.let { "${it.type}/${it.subtype}" })
      val body = r.body?.string() ?: ""
      assertTrue(body.startsWith("<!doctype html>"), "is an html document: $body")
      assertTrue(body.contains("src=\"data:image/png;base64,"), "embeds the render png: $body")
    }
  }

  @Test
  fun `iframe_html 400s a missing id and 404s an unknown one`() {
    assertEquals(400, get("/compose-m3/iframe.html").first)
    assertEquals(404, get("/compose-m3/iframe.html?id=no.such.Story").first)
  }

  @Test
  fun `iframe_html format svg 404s on a static bundle`() {
    // The SVG lane inlines the figma-svg export, which only a daemon-backed host produces; a static
    // ServeBundleHost 404s it just like the /render/<id>.svg lane does.
    assertEquals(404, get("/compose-m3/iframe.html?id=$previewId&format=svg").first)
  }
}
