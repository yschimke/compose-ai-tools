package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.cli.BUNDLE_VERSION
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.StreamCodec
import ee.schimke.composeai.data.layoutinspector.ComposeFigmaSvgProduct
import ee.schimke.composeai.data.overrides.PreviewOverrideDeclaration
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveStream
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The embedded Ktor (CIO) HTTP server fronting a [ServeSessionRegistry]. Thin IO shell: a token
 * gate, the routes, and the query-param → [ServeOverrides] → render → PNG glue. All shared,
 * concurrency-safe state lives in the per-tenant [ServeRenderHost]s; this class adds none of its
 * own per-request state, so it serves any number of clients.
 *
 * **Multi-tenant:** one server fronts many sessions instead of one per module. Every route resolves
 * a [ServeRenderHost] from the registry by the request's `?session=` (falling back to
 * [defaultSessionId]); the registry forks the tenant behind its factory on first use. Unknown
 * sessions 404 like a bad token.
 *
 * Endpoints (all token-gated except `/healthz`, `/readyz`, `/version`, and the `/wasm/` static
 * assets):
 * - `GET /` landing page, `GET /p/{id}` viewer page,
 * - `GET /render/{id}.png` PNG bytes, `GET /api/previews` JSON, `GET /healthz` liveness,
 * - `GET /readyz` readiness (green only after a representative preview actually renders — the
 *   rolling-update gate),
 * - `GET /index.json` Storybook stories index, `GET /iframe.html?id=` isolated story render
 *   (`&format=svg` serves the vector export as an inert SVG image for DOM-capture tools),
 *   ([StorybookCompat]) — the drop-in surface downstream Storybook visual tools consume,
 * - `GET /version` host identity (CLI version, serve schema, public flag),
 * - `GET /bundle.zip` portable bundle, `WS /ws/{id}` streamed-frame lane.
 *
 * A bad/missing token returns **404** (not 401) so the server's existence isn't confirmed to a
 * scanner; the token is compared in constant time ([ServeUrls.tokensMatch]).
 */
class ServeHttpServer(
  private val host: String,
  requestedPort: Int,
  private val token: String,
  private val sessions: ServeSessionRegistry,
  private val defaultSessionId: String,
  /** When non-null, enables `POST /bundles/{name}` for clients to contribute bundles at runtime. */
  private val bundleStore: ServeBundleStore? = null,
  /**
   * Public mode: serve **without** requiring the token — every route is open. For a deployed public
   * preview server (preview.coo.ee) where browsing the published catalogs / uploaded bundles is the
   * point. Safe by construction: rendering a bundle/catalog executes no code, re-rendering
   * untrusted Compose is refused, uploads are size-capped + the `?url=` fetch is SSRF-gated. Off by
   * default, so a normal `serve` stays token-gated (a bad/absent token still 404s).
   */
  private val isPublic: Boolean = false,
  /**
   * In-browser CMP tier: system id → the assembled Wasm app directory (the
   * `:samples:cmp-wasm-catalog:wasmCatalogDist` output). When a catalog session's id is a key here,
   * its viewer offers a "Run in browser (Wasm)" toggle that mounts `/wasm/<system>/?id=<component>`
   * in a sandboxed iframe. The assets are static, generic client code (the same app for everyone,
   * no session data), so the `/wasm/` route is **ungated** — letting the iframe's relative
   * `fetch('./composeApp.wasm')` work without threading the token through every sub-resource.
   */
  private val wasmCatalogs: Map<String, File> = emptyMap(),
  /**
   * Design-system catalog sessions that registered (`--catalogs`), e.g. `["compose-m3","wear-m3"]`.
   * Surfaced as `?session=<system>` nav links on the landing page so the public front door lists
   * the served systems instead of hiding them behind the query param. Empty ⇒ no nav row (the
   * default).
   */
  private val catalogSessions: List<String> = emptyList(),
  /**
   * App catalogs registered UNLISTED (`--catalogs-unlisted`), e.g. `["meshcore-mobile","cadence"]`.
   * Served at `/<system>/` exactly like [catalogSessions], but kept OFF the front door: NOT listed
   * on the `/` systems index and NOT on the in-catalog "Design systems" nav row — reachable only by
   * their path / `?session=` (shareable by direct link). This lets an app catalog be published
   * without advertising it on the public landing. They still count toward whether a home index
   * exists, so an app's own landing keeps a "← back" link whenever the server also lists systems.
   */
  private val appCatalogSessions: List<String> = emptyList(),
  portRange: Int = DEFAULT_PORT_RANGE,
  /**
   * Max renders in flight across the HTTP `/render` lane. Defaults to the host's CPU count so a
   * small box (1–2 vCPU) sheds a render storm instead of thrashing; excess requests wait briefly
   * for a slot, then get `503 + Retry-After`. Renders also serialise inside [ServeRenderHost], so
   * this is a load-shedding bound on concurrent HTTP work, not a parallel-render knob.
   */
  maxConcurrentRenders: Int = Runtime.getRuntime().availableProcessors().coerceAtLeast(1),
  /**
   * Live-seat **permit budget** for concurrent **live** (daemon-backed) stream sessions. Each live
   * session charges permits equal to its backend weight ([ServeSessionState.liveSeatWeight]): a
   * desktop CMP daemon costs 1, a heavier Android/Robolectric one costs more, so one heavy catalog
   * can't hog a flat seat count and starve several cheap ones. A session that can't get its permits
   * is refused with WebSocket close 1013 (Try Again Later) rather than spawning a daemon that would
   * risk the OOM killer. `0` (the default) is unbounded — the historical behaviour for a local
   * `serve` on a developer box. Static (snapshot/Wasm) sessions never consume a permit, so the
   * public server's default tiers are unaffected; this only bites when `--allow-render-trusted`
   * puts a live daemon behind a catalog. See [LiveSeatLimiter].
   */
  maxLiveSeats: Int = 0,
  /**
   * Recent daemon **startup failures** — the render/live daemon a session tried to (re)open but
   * couldn't. Populated by [ServeCommand.openHost] (the single choke point every registry-driven
   * relaunch passes through) and surfaced on `/status` + `/status.json`. Null ⇒ no log wired
   * (tests, or a build that doesn't record them); the status page then shows an empty failure list.
   */
  private val daemonLog: DaemonStartupLog? = null,
  /**
   * Whether `--allow-render-trusted` is set (trusted catalogs get a live server-side render lane).
   */
  private val allowRenderTrusted: Boolean = false,
  /**
   * Whether a producer-trust store was configured (`--trust-store`); shown in the status config.
   */
  private val trustStoreConfigured: Boolean = false,
  /** Catalog auto-refresh interval in seconds (`--catalog-refresh-interval`); `0` ⇒ disabled. */
  private val catalogRefreshSeconds: Long = 0,
  /** Whether `POST /bundles` runtime uploads are accepted (`--accept-bundles`). */
  private val acceptBundlesEnabled: Boolean = false,
) {
  /** The actual bound port — may differ from the requested one if it was taken (auto-picked). */
  val port: Int = pickPort(host, requestedPort, portRange)

  /** Concurrent-render slot count (the `/render` load-shed bound), surfaced on `/status`. */
  private val renderSlots: Int = maxConcurrentRenders.coerceAtLeast(1)

  /** Wall-clock the server was constructed — the basis for the `/status` uptime figure. */
  private val startedAtMillis: Long = System.currentTimeMillis()

  private val renderSemaphore = Semaphore(renderSlots)

  /**
   * Readiness latch for `/readyz` (the rolling-update gate). Unlike `/healthz` — a static "ok" that
   * only proves the HTTP listener is up — readiness is `true` only once a representative preview
   * has *actually rendered* on this host, so docker-rollout won't drain traffic onto (and retire
   * the old replica for) a new container whose render pipeline is broken or whose catalogs failed
   * to load. Latches on the first success and stays set: the probe render is a baked, override-free
   * snapshot for a catalog session (cheap, never wakes the daemon — see
   * [ServeCatalogLiveHost.render]), but a plain daemon module would pay its cold render, so it runs
   * at most once (see [readinessProber]) and the poll only ever reads this flag.
   *
   * Set by the **server-owned** [readinessProber] thread, never inside a request coroutine: the
   * `/readyz` handler must stay instant so a health checker's short command timeout (the Docker
   * healthcheck allows 5s) can't cancel a slow first render mid-flight and discard the result — the
   * render happens off the request path, latches here when it lands, and the next poll sees it.
   */
  private val ready = AtomicBoolean(false)

  /** Starts [readinessProber] exactly once, on the first `/readyz` poll (idempotent). */
  private val readinessProbeStarted = AtomicBoolean(false)

  /**
   * The server-owned background thread that renders the representative preview until it succeeds,
   * then latches [ready]. Kicked off lazily by the first `/readyz` poll (so a plain `serve` that's
   * never health-checked pays no eager render) and interrupted on [stop]. Retries on failure so a
   * daemon still cold-starting eventually flips ready without the request path ever blocking.
   */
  @Volatile private var readinessProber: Thread? = null

  /**
   * Live-seat limiter: a permit **budget** ([maxLiveSeats]) charged per session by its backend
   * weight, so a heavy Android daemon costs more of the box than a cheap desktop CMP one. `<= 0` ⇒
   * unbounded. See [maxLiveSeats] and [LiveSeatLimiter].
   */
  private val liveSeats: LiveSeatLimiter = LiveSeatLimiter(maxLiveSeats)

  private val server: EmbeddedServer<*, *> =
    embeddedServer(CIO, host = host, port = port) {
      install(WebSockets)
      routing {
        // `/healthz` — ungated liveness: "ok" the moment the listener is up. Leaks nothing, and
        // proves nothing beyond "the process is answering HTTP". The rolling-update gate is
        // `/readyz` below, not this.
        get("/healthz") { call.respondText("ok") }

        // `/readyz` — ungated READINESS: "ready" only once a representative preview has actually
        // rendered on this host (see [ready]). This is the gate docker-rollout should wait on
        // before
        // it drains traffic onto a new replica and retires the old one — `/healthz` going green
        // only
        // means the port bound, so a replica whose render pipeline is broken (dead daemon, missing
        // baked fallback, empty/failed catalog load) would pass it and get promoted into a 500-ing
        // live server. 503 ("warming") until the first render succeeds; then it latches green.
        get("/readyz") { handleReadyz() }

        // `/version` — ungated machine-readable identity for the host: the CLI version, the serve
        // API schema, and whether this box runs open (public) or token-gated. Lets a deployer,
        // Watchtower check, or the design-artifacts gallery confirm which build is live without a
        // token, and keeps the released version OUT of the HTML goldens (it lives here, not in the
        // landing footer, so a release never churns the fixture diff).
        get("/version") {
          call.respondText(
            JSON.encodeToString(
              VersionResponse.serializer(),
              VersionResponse(version = BUNDLE_VERSION, public = isPublic),
            ),
            ContentType.Application.Json,
          )
        }

        // `/status` — the operator/observer view of this running host: published catalogs + their
        // trust/liveness, the render daemons up right now, the effective config, and recent daemon
        // startup failures. HTML by default (`?format=json` for the machine form); `/status.json`
        // is
        // the canonical JSON a monitor / Home Assistant sensor polls. Both are gated like the rest
        // (open in `--public`, else token-required) — the running-daemon + config detail is more
        // sensitive than `/version`/`/healthz`, so a private box keeps it behind the token.
        get("/status") { handleStatus(json = false) }
        get("/status.json") { handleStatus(json = true) }

        // In-browser CMP tier: serve the static Wasm app for a registered system at
        // `/wasm/<system>/<file>`. Ungated (generic client code, no session data) so the viewer's
        // sandboxed iframe and its relative asset fetches work without a token. Registered only
        // when
        // the operator mapped a system to its built dist (`--wasm-dir`).
        if (wasmCatalogs.isNotEmpty()) {
          get("/wasm/{system}/{path...}") {
            val dir = call.parameters["system"]?.let { wasmCatalogs[it] }
            if (dir == null) {
              call.respondText("not found", status = HttpStatusCode.NotFound)
              return@get
            }
            val segments = call.parameters.getAll("path").orEmpty().filter { it.isNotEmpty() }
            val rel = if (segments.isEmpty()) "index.html" else segments.joinToString("/")
            val base = dir.toPath().toAbsolutePath().normalize()
            val resolved = base.resolve(rel).normalize()
            // Zip-slip guard: a crafted `../` path must not escape the app directory.
            if (!resolved.startsWith(base)) {
              call.respondText("not found", status = HttpStatusCode.NotFound)
              return@get
            }
            val file = resolved.toFile()
            if (!file.isFile) {
              call.respondText("not found", status = HttpStatusCode.NotFound)
              return@get
            }
            // The viewer mounts this app in a `sandbox="allow-scripts"` iframe, which has an opaque
            // (null) origin — so the app's own ES-module + wasm fetches count as cross-origin and
            // need CORS. `*` is safe: these are public static client assets with no session data,
            // and keeping the strong sandbox (no `allow-same-origin`) isolates even untrusted wasm.
            call.response.headers.append(HttpHeaders.AccessControlAllowOrigin, "*")
            // Cache the heavy payload (skiko + app wasm ≈ 8 MB gzipped). The filenames aren't
            // content-hashed, so pair a moderate max-age with a size+mtime ETag: within the window
            // the browser serves from cache (no request); after it, a conditional request gets a
            // cheap 304 instead of re-downloading megabytes.
            val etag = "\"${file.length().toString(16)}-${file.lastModified().toString(16)}\""
            call.response.headers.append(HttpHeaders.CacheControl, "public, max-age=3600")
            call.response.headers.append(HttpHeaders.ETag, etag)
            if (call.request.headers[HttpHeaders.IfNoneMatch] == etag) {
              call.respond(HttpStatusCode.NotModified)
              return@get
            }
            val bytes = withContext(Dispatchers.IO) { file.readBytes() }
            call.respondBytes(bytes, wasmContentType(file.name))
          }
        }

        // Shared/public mode ingestion: a client contributes a pre-rendered bundle (upload the zip
        // as the body, or pass `?url=` to a build-results artifact) and gets back a ?session= link.
        // Only registered when the operator opts in (a bundle store is supplied).
        bundleStore?.let { store ->
          post("/bundles/{name}") {
            if (rejectBadToken()) return@post
            val name = call.parameters["name"]
            if (name.isNullOrBlank()) {
              call.respondText("missing bundle name", status = HttpStatusCode.BadRequest)
              return@post
            }
            val url = call.request.queryParameters["url"]
            // Cap the uploaded body as it streams in — receiving it whole into memory first would
            // let a client OOM the server regardless of the store's later extraction cap.
            val body =
              if (url == null) {
                withContext(Dispatchers.IO) {
                  call.receiveStream().use { readCapped(it, MAX_UPLOAD_BYTES) }
                }
                  ?: run {
                    call.respondText(
                      "bundle exceeds ${MAX_UPLOAD_BYTES / (1024 * 1024)}MB",
                      status = HttpStatusCode.PayloadTooLarge,
                    )
                    return@post
                  }
              } else {
                null
              }
            val result =
              withContext(Dispatchers.IO) {
                // isSecurityChecked = true: this route is token-gated (rejectBadToken above) and
                // the
                // store still defends in depth (name sanitisation, zip-slip, size cap; SSRF host
                // allowlist for the url case). The marker records the entry point was authorised.
                if (url != null) store.addFromUrl(name, url, isSecurityChecked = true)
                else store.add(name, body!!, isSecurityChecked = true)
              }
            when (result) {
              is ServeBundleStore.Result.Ok ->
                call.respondText(
                  JSON.encodeToString(
                    BundleAcceptedResponse.serializer(),
                    BundleAcceptedResponse(
                      session = result.name,
                      previews = result.previewCount,
                      path = "/?session=${result.name}",
                      trust = result.trust,
                    ),
                  ),
                  ContentType.Application.Json,
                  HttpStatusCode.Created,
                )
              is ServeBundleStore.Result.Failed ->
                call.respondText(result.reason, status = HttpStatusCode.BadRequest)
            }
          }
        }

        // A persistent frame lane. The browser opens this, receives frames as JSON
        // ([ServeStreamProtocol]), and sends override / switch / input messages back. Token is
        // checked post-handshake (can't 404 after upgrade) — a bad token closes immediately. Two
        // routes share one handler: the query-param `?session=` form and the path-prefixed
        // `/{system}/ws/{name}` form (the session is then the `{system}` segment).
        webSocket("/ws/{name}") { serveStreamLane() }
        webSocket("/{system}/ws/{name}") { serveStreamLane() }

        // Session-selecting routes come in two forms that share one handler each: the query-param
        // `?session=` form (back-compat) and the path-prefixed `/{system}/…` form (the canonical
        // public URL — the `{system}` segment IS the session). `sessionInPath = true` picks the
        // latter. Constant first segments (`/healthz`, `/version`, `/bundle.zip`, `/wasm/…`, …)
        // score
        // higher than `/{system}` in Ktor routing, so they still win — only genuinely unknown
        // single
        // segments fall through to a session lookup (and 404 like a bad session).
        get("/") { handleLanding(sessionInPath = false) }
        get("/{system}") { handleLanding(sessionInPath = true) }
        get("/{system}/") { handleLanding(sessionInPath = true) }

        get("/api/previews") { handleApiPreviews(sessionInPath = false) }
        get("/{system}/api/previews") { handleApiPreviews(sessionInPath = true) }

        // Storybook-compatibility surface (see [StorybookCompat]). `/index.json` is the stories
        // index every downstream visual tool (Chromatic, Percy, storycap/reg-suit, BackstopJS, the
        // test-runner) crawls to enumerate stories; `iframe.html?id=<storyId>` renders one story in
        // isolation for a screenshot tool. Both come in the query-`?session=` and
        // path-`/{system}/…`
        // forms like the rest; the constant first segment outscores `/{system}`.
        get("/index.json") { handleStorybookIndex(sessionInPath = false) }
        get("/{system}/index.json") { handleStorybookIndex(sessionInPath = true) }

        get("/iframe.html") { handleStorybookIframe(sessionInPath = false) }
        get("/{system}/iframe.html") { handleStorybookIframe(sessionInPath = true) }

        get("/bundle.zip") { handleBundleZip(sessionInPath = false) }
        get("/{system}/bundle.zip") { handleBundleZip(sessionInPath = true) }

        get("/p/{name}") { handleViewer(sessionInPath = false) }
        get("/{system}/p/{name}") { handleViewer(sessionInPath = true) }

        get("/render/{name}") { handleRender(sessionInPath = false) }
        get("/{system}/render/{name}") { handleRender(sessionInPath = true) }
      }
    }

  /** Start listening. Non-blocking; the caller keeps the process alive separately. */
  fun start() {
    server.start(wait = false)
  }

  /** Stop with a short grace period. Idempotent enough for a shutdown hook. */
  fun stop() {
    readinessProber?.interrupt()
    server.stop(gracePeriodMillis = 500, timeoutMillis = 2000)
  }

  /**
   * The session id this request selects. In **path mode** ([sessionInPath]) it's the `{system}`
   * path segment (the canonical `/<system>/…` form); otherwise the `?session=` query param, falling
   * back to [defaultSessionId]. Returned even when unknown — the lease then 404s like a bad
   * session.
   */
  private fun RoutingContext.selectedSessionId(sessionInPath: Boolean): String =
    if (sessionInPath) call.parameters["system"] ?: defaultSessionId
    else call.request.queryParameters["session"] ?: defaultSessionId

  /**
   * The session id to hand [ServeWeb] for nav-marking + link building, and the URL [basePath] its
   * same-session links get. Path mode → the `{system}` segment + `/<system>` base (links stay on
   * the path, no `?session=`); query mode → the raw `?session=` (null for the default session) +
   * empty base (links keep the legacy `&session=` behaviour). Kept separate from
   * [selectedSessionId] so the default module session renders with token-only links exactly as
   * before (byte-identical goldens).
   */
  private fun RoutingContext.webSessionAndBase(sessionInPath: Boolean): Pair<String?, String> {
    val system = if (sessionInPath) call.parameters["system"] else null
    return if (system != null) system to "/" + WebEscaping.urlEncodeSegment(system)
    else call.request.queryParameters["session"] to ""
  }

  /**
   * Resolve the tenant for [sessionId] and run [block] with its host while holding a
   * [ServeSessionRegistry.Lease] for the request's whole duration — so the reaper can't suspend the
   * daemon mid-request (e.g. a long `/bundle.zip` that renders every preview). Responds 404 when
   * the session can't be created/opened. The lease is always released.
   */
  private suspend fun RoutingContext.withLeasedSession(
    sessionId: String,
    block: suspend (ServeHost) -> Unit,
  ) {
    val lease = withContext(Dispatchers.IO) { sessions.lease(sessionId) }
    if (lease == null) {
      call.respondText("not found", status = HttpStatusCode.NotFound)
      return
    }
    try {
      block(lease.host)
    } finally {
      withContext(Dispatchers.IO) { lease.close() }
    }
  }

  /** `GET /` (query) and `GET /{system}[/]` (path): the session's preview-list landing page. */
  private suspend fun RoutingContext.handleLanding(sessionInPath: Boolean) {
    if (rejectBadToken()) return
    // Front door: when this server publishes design-system catalogs, the bare `/` (no `?session=`,
    // no `/<system>` path) is an INDEX of those systems — each with a meaningful preview — rather
    // than an arbitrary default module's grid. A plain `serve` (no `--catalogs`) keeps the module
    // landing. A query `?session=` or a `/<system>` path still selects that session's landing
    // below.
    if (
      !sessionInPath &&
        (catalogSessions.isNotEmpty() || appCatalogSessions.isNotEmpty()) &&
        call.request.queryParameters["session"] == null
    ) {
      handleHomeIndex()
      return
    }
    val (webSessionId, basePath) = webSessionAndBase(sessionInPath)
    withLeasedSession(selectedSessionId(sessionInPath)) { renderHost ->
      call.respondText(
        ServeWeb.landingPage(
          renderHost.label,
          renderHost.previews,
          token,
          webSessionId,
          trust = catalogBundleHost(renderHost)?.let { BundleVerifier.summary(it.trust) },
          isPublic = isPublic,
          // A back-to-home button whenever this server publishes a front-door index — listed
          // catalogs OR unlisted app catalogs (mirrors handleLanding's home-index condition), so an
          // app-only server's landings still link home.
          hasHomeIndex = catalogSessions.isNotEmpty() || appCatalogSessions.isNotEmpty(),
          basePath = basePath,
          version = BUNDLE_VERSION,
          // Catalog provenance (delivery branch, generation date, tool versions) for the strip
          // under the header; null for a plain (non-catalog) module session.
          provenance = catalogBundleHost(renderHost)?.provenance,
          // Crop each card's thumbnail to the component's figma-svg content box (cheap baked
          // reads),
          // so a Wear sticker shows the component, not the empty watch canvas around it.
          thumbCrop = { id -> catalogBundleHost(renderHost)?.contentCrop(id) },
          // The catalog's declared stage surface (`display.surface`), so a dark-first system's
          // unthemed cards sit on the dark stage instead of the default white.
          declaredSurface = catalogBundleHost(renderHost)?.stageSurface,
          // Why the catalog is snapshot-only, when it is (no live bundle, unverified, …) — shown as
          // a banner under the header so a browser sees it before opening a preview.
          degradations = renderHost.degradations,
        ),
        ContentType.Text.Html,
      )
    }
  }

  /**
   * The [ServeBundleHost] carrying a catalog's browse metadata (title / subtitle / trust verdict) —
   * the host itself for a static catalog, or the baked host a [ServeCatalogLiveHost] fronts when
   * the catalog is served live. Null for a plain daemon module session (no bundle metadata). Lets
   * the trust badge + card title survive a catalog being fronted by the live composite.
   */
  private fun catalogBundleHost(host: ServeHost): ServeBundleHost? =
    when (host) {
      is ServeBundleHost -> host
      is ServeCatalogLiveHost -> host.bakedHost as? ServeBundleHost
      is ServePerPreviewLiveHost -> host.bakedHost as? ServeBundleHost
      else -> null
    }

  /**
   * The public server's front-page index: the published design systems ([catalogSessions]) under a
   * "Design systems" section, each a card linking to its `/<system>/` catalog. The unlisted app
   * catalogs ([appCatalogSessions]) are intentionally NOT indexed here — they're served at
   * `/<system>/` but stay off the front door. See [homeSystemsFor].
   */
  private suspend fun RoutingContext.handleHomeIndex() {
    val systems = withContext(Dispatchers.IO) { homeSystemsFor(catalogSessions) }
    call.respondText(
      ServeWeb.homeIndexPage(systems, token, isPublic = isPublic, version = BUNDLE_VERSION),
      ContentType.Text.Html,
    )
  }

  /**
   * `GET /status` (HTML, or JSON with `?format=json`) and `GET /status.json` (JSON): a live
   * snapshot of this host — published catalogs + their trust/liveness, the render daemons up right
   * now, the effective config, and recent daemon startup failures. Gated like the API routes (open
   * in `--public`, else token-required). The JSON form is the canonical machine surface for a
   * monitor / Home Assistant sensor; the HTML form is its human face.
   */
  private suspend fun RoutingContext.handleStatus(json: Boolean) {
    if (rejectBadToken()) return
    val wantJson = json || call.request.queryParameters["format"].equals("json", ignoreCase = true)
    val data = withContext(Dispatchers.IO) { buildStatusData() }
    if (wantJson) {
      call.respondText(
        JSON.encodeToString(StatusResponse.serializer(), data.toResponse()),
        ContentType.Application.Json,
      )
    } else {
      call.respondText(ServeWeb.statusPage(data.toView(), token), ContentType.Text.Html)
    }
  }

  /**
   * `GET /readyz`: the rolling-update readiness gate. Instant and non-blocking — it only reads the
   * [ready] latch, returning `200 "ready"` once it's set and `503 "warming"` before. The render
   * that flips the latch runs on the server-owned [readinessProber], NOT in this request coroutine,
   * so a health checker's short command timeout (the Docker healthcheck allows 5s) can never cancel
   * a slow first render and discard its result — the first poll just kicks the prober off and
   * reports "warming"; a later poll sees the latched value. So the ~10s poll stays cheap even
   * against a daemon-backed module whose cold render runs for much longer than the poll timeout.
   */
  private suspend fun RoutingContext.handleReadyz() {
    if (ready.get()) {
      call.respondText("ready")
      return
    }
    // Upload-only server (`--accept-bundles`, no landing session): there's no representative
    // preview
    // to render, so "ready" means the listener is up and waiting for uploads. Latch immediately.
    if (defaultSessionId.isBlank()) {
      ready.set(true)
      call.respondText("ready")
      return
    }
    ensureReadinessProbe()
    call.respondText("warming", status = HttpStatusCode.ServiceUnavailable)
  }

  /**
   * Start the server-owned readiness prober on the first `/readyz` poll (idempotent via
   * [readinessProbeStarted]). It renders the representative preview off the request path, retrying
   * on failure, and latches [ready] on the first success — so a client that times out mid-probe
   * never discards the work. A daemon thread (interrupted on [stop]); it exits as soon as [ready]
   * is set. Gated behind an actual `/readyz` hit so a plain `serve` that's never health-checked
   * pays no eager render.
   */
  private fun ensureReadinessProbe() {
    if (!readinessProbeStarted.compareAndSet(false, true)) return
    val prober =
      Thread(
          {
            while (!ready.get() && !Thread.currentThread().isInterrupted) {
              if (probeReadiness()) {
                ready.set(true)
                return@Thread
              }
              try {
                Thread.sleep(READINESS_PROBE_RETRY_MILLIS)
              } catch (e: InterruptedException) {
                return@Thread
              }
            }
          },
          "serve-readiness-probe",
        )
        .apply { isDaemon = true }
    readinessProber = prober
    prober.start()
  }

  /**
   * One readiness attempt: lease the default session and render its first preview override-free. A
   * successful [RenderOutcome.Ok] means the render path works end-to-end — catalogs loaded, a
   * preview exists, and the host can produce bytes (baked for a catalog session, a real daemon
   * render for a plain module). Any failure — no session, no previews, a render error, or an
   * exception — returns false so the prober retries. Runs on the [readinessProber] thread (the
   * lease
   * + render are blocking). Never throws.
   */
  private fun probeReadiness(): Boolean {
    val lease = sessions.lease(defaultSessionId) ?: return false
    return try {
      val preview = lease.host.previews.firstOrNull() ?: return false
      lease.host.render(preview.id, PreviewOverrides()) is RenderOutcome.Ok
    } catch (e: Exception) {
      System.err.println("[serve] readiness probe failed: ${e.message}")
      false
    } finally {
      lease.close()
    }
  }

  /**
   * Raw catalog metadata for the status snapshot — projected to HTML rows and JSON by [StatusData].
   */
  private data class CatalogStat(
    val id: String,
    val listed: Boolean,
    val title: String?,
    val trust: String?,
    val previews: Int?,
    /** Has a live (daemon-backed) render lane — a running daemon, or a suspended live catalog. */
    val live: Boolean,
    /** A live daemon for this catalog is up right now. */
    val running: Boolean,
    val degradation: String?,
    val provenance: ServeWeb.CatalogProvenance?,
  )

  /**
   * Raw status snapshot; the single source both the HTML page and the JSON response project from.
   */
  private inner class StatusData(
    val nowMillis: Long,
    val catalogs: List<CatalogStat>,
    val running: List<ServeSessionRegistry.RunningDaemon>,
    val failures: List<DaemonStartupLog.Failure>,
  ) {
    val uptimeSeconds: Long = ((nowMillis - startedAtMillis) / 1000).coerceAtLeast(0)
    /** Live daemons (a render daemon is up), excluding pinned static baked hosts. */
    val liveDaemons: List<ServeSessionRegistry.RunningDaemon> = running.filter { it.hasLiveStream }
    val activeStreams: Int = liveDaemons.sumOf { it.activeStreams }

    private fun backendOf(weight: Int): String = if (weight >= 2) "android" else "desktop"

    fun toResponse(): StatusResponse =
      StatusResponse(
        version = BUNDLE_VERSION,
        public = isPublic,
        status = if (failures.isEmpty()) "ok" else "degraded",
        uptimeSeconds = uptimeSeconds,
        catalogs =
          CatalogSummaryDto(
            total = catalogs.size,
            listed = catalogs.count { it.listed },
            unlisted = catalogs.count { !it.listed },
            trusted = catalogs.count { it.trust != null && it.trust != "unverified" },
            degraded = catalogs.count { it.degradation != null },
          ),
        daemons =
          DaemonSummaryDto(
            known = sessions.activeCount(),
            running = liveDaemons.size,
            activeStreams = activeStreams,
            liveSeatsTotal = if (liveSeats.unbounded) 0 else liveSeats.totalPermits,
            liveSeatsAvailable = if (liveSeats.unbounded) -1 else liveSeats.availablePermits(),
            liveSeatsUnbounded = liveSeats.unbounded,
          ),
        config =
          ConfigDto(
            host = host,
            port = port,
            allowRenderTrusted = allowRenderTrusted,
            trustStore = trustStoreConfigured,
            acceptBundles = acceptBundlesEnabled,
            catalogRefreshSeconds = catalogRefreshSeconds,
            maxConcurrentRenders = renderSlots,
            liveSeats = liveSeats.totalPermits,
          ),
        catalogList =
          catalogs.map { c ->
            CatalogDto(
              id = c.id,
              listed = c.listed,
              title = c.title,
              trust = c.trust,
              previews = c.previews,
              live = c.live,
              running = c.running,
              degradation = c.degradation,
              repo = c.provenance?.repo,
              branch = c.provenance?.branch,
              generatedAt = c.provenance?.generatedAt,
              path = "/${c.id}/",
            )
          },
        runningServers =
          liveDaemons.map { d ->
            RunningServerDto(
              id = d.id,
              label = d.label,
              backend = backendOf(d.liveSeatWeight),
              seatWeight = d.liveSeatWeight,
              activeStreams = d.activeStreams,
              uptimeSeconds = d.startedAt?.let { ((nowMillis - it) / 1000).coerceAtLeast(0) },
            )
          },
        recentDaemonFailures = failures.map { FailureDto(it.atEpochMillis, it.session, it.reason) },
      )

    fun toView(): ServeWeb.StatusView {
      val seatsText =
        if (liveSeats.unbounded) "unbounded"
        else "${liveSeats.availablePermits()} free / ${liveSeats.totalPermits}"
      val summary =
        listOf(
          ServeWeb.Stat("Catalogs", catalogs.size.toString()),
          ServeWeb.Stat("Live daemons running", liveDaemons.size.toString()),
          ServeWeb.Stat("Active streams", activeStreams.toString()),
          ServeWeb.Stat("Live seats", seatsText),
          ServeWeb.Stat("Known sessions", sessions.activeCount().toString()),
          ServeWeb.Stat("Uptime", formatDuration(uptimeSeconds)),
        )
      val config =
        listOf(
          ServeWeb.Stat("Access", if (isPublic) "public (open)" else "token-gated"),
          ServeWeb.Stat("Bind", "$host:$port"),
          ServeWeb.Stat("Trusted re-render", if (allowRenderTrusted) "on" else "off"),
          ServeWeb.Stat("Trust store", if (trustStoreConfigured) "configured" else "none"),
          ServeWeb.Stat(
            "Catalog refresh",
            if (catalogRefreshSeconds > 0) "${catalogRefreshSeconds}s" else "disabled",
          ),
          ServeWeb.Stat(
            "Live seats",
            if (liveSeats.unbounded) "unbounded" else liveSeats.totalPermits.toString(),
          ),
          ServeWeb.Stat("Render slots", renderSlots.toString()),
          ServeWeb.Stat("Accept uploads", if (acceptBundlesEnabled) "on" else "off"),
        )
      return ServeWeb.StatusView(
        version = BUNDLE_VERSION,
        public = isPublic,
        overallOk = failures.isEmpty(),
        summary = summary,
        config = config,
        catalogs =
          catalogs.map { c ->
            ServeWeb.StatusCatalog(
              id = c.id,
              title = c.title ?: c.id,
              listed = c.listed,
              trust = c.trust,
              previews = c.previews ?: 0,
              live = c.live,
              running = c.running,
              degradation = c.degradation,
              provenance =
                c.provenance?.let { p ->
                  buildString {
                    append(p.repo).append('@').append(p.branch)
                    p.generatedAt?.let { append(" · ").append(it) }
                  }
                },
            )
          },
        servers =
          liveDaemons.map { d ->
            ServeWeb.StatusServer(
              id = d.id,
              label = d.label,
              backend = backendOf(d.liveSeatWeight),
              activeStreams = d.activeStreams,
              upForText =
                d.startedAt?.let { formatDuration(((nowMillis - it) / 1000).coerceAtLeast(0)) }
                  ?: "—",
            )
          },
        failures =
          failures.map { f ->
            ServeWeb.StatusFailure(
              whenText = formatInstant(f.atEpochMillis),
              session = f.session,
              reason = f.reason,
            )
          },
      )
    }
  }

  /**
   * Assemble the status snapshot. Catalog liveness is read purely from
   * [ServeSessionRegistry.runningDaemons] (a non-resuming snapshot) so a poll never wakes an idle
   * daemon: a pinned static baked host is always resident (present, no live stream); a live catalog
   * is present-with-live-stream when its daemon is up and **absent** when suspended. Catalog
   * metadata (title/trust/provenance) is read via [ServeSessionRegistry.peekHost] — also
   * non-resuming — so a suspended live catalog shows minimal detail rather than being
   * force-resumed.
   */
  private fun buildStatusData(): StatusData {
    val running = sessions.runningDaemons()
    val byId = running.associateBy { it.id }
    val entries = catalogSessions.map { it to true } + appCatalogSessions.map { it to false }
    val catalogs = entries.map { (id, listed) ->
      val daemon = byId[id]
      val host = sessions.peekHost(id)
      val bundle = host?.let { catalogBundleHost(it) }
      // Liveness from the resident snapshot only (never resume): absent ⇒ a suspended live
      // catalog; present-with-live-stream ⇒ its daemon is up; present-without ⇒ static baked host.
      val running = daemon?.hasLiveStream == true
      val live = daemon == null || daemon.hasLiveStream
      CatalogStat(
        id = id,
        listed = listed,
        title = bundle?.title?.takeIf { it.isNotBlank() } ?: host?.label,
        trust = bundle?.let { BundleVerifier.summary(it.trust) },
        previews = host?.previews?.size,
        live = live,
        running = running,
        degradation = host?.degradations?.firstOrNull()?.detail,
        provenance = bundle?.provenance,
      )
    }
    return StatusData(
      nowMillis = System.currentTimeMillis(),
      catalogs = catalogs,
      running = running,
      failures = daemonLog?.recent().orEmpty(),
    )
  }

  /**
   * Resolve a list of catalog [ids] into [ServeWeb.HomeSystem] cards for the front-page index.
   * Leases each in turn (they're cheap, pinned bundle hosts) to read its title, preview count,
   * trust verdict, and pick a meaningful hero preview. A catalog that can't be leased (e.g.
   * transiently unavailable) is skipped rather than sinking the whole page. Blocking — call inside
   * a `Dispatchers.IO` context.
   */
  private fun homeSystemsFor(ids: List<String>): List<ServeWeb.HomeSystem> =
    ids.mapNotNull { system ->
      val lease = sessions.lease(system) ?: return@mapNotNull null
      try {
        val host = lease.host
        val bundle = catalogBundleHost(host)
        // Prefer the catalog's declared hero (`display.hero`); fall back to the representative
        // pick.
        val heroId =
          bundle?.declaredHeroPreviewId ?: ServeWeb.representativePreviewId(host.previews)
        ServeWeb.HomeSystem(
          system = system,
          title = bundle?.title?.takeIf { it.isNotBlank() } ?: host.label,
          subtitle = bundle?.subtitle,
          previewCount = host.previews.size,
          trust = bundle?.let { BundleVerifier.summary(it.trust) },
          heroPreviewId = heroId,
          // Frame the hero to its component box too, so the front-page Wear card isn't a speck.
          heroCrop = heroId?.let { bundle?.contentCrop(it) },
          // Dark stage from the catalog's declared surface (`display.surface`), falling back to the
          // system-name heuristic — resolved in one place (ServeWeb.SystemDisplay) so the front
          // door
          // and the catalog grid agree.
          darkStage = ServeWeb.SystemDisplay.resolveDarkFirst(system, bundle?.stageSurface),
        )
      } finally {
        lease.close()
      }
    }

  /**
   * `GET /api/previews` (query) and `GET /{system}/api/previews` (path): the session's preview
   * JSON.
   */
  private suspend fun RoutingContext.handleApiPreviews(sessionInPath: Boolean) {
    if (rejectBadToken()) return
    withLeasedSession(selectedSessionId(sessionInPath)) { renderHost ->
      val dto =
        PreviewsResponse(
          module = renderHost.label,
          // Producer-trust verdict for a bundle/catalog session (signature / branch / provenance /
          // unverified); null for a live daemon-backed module session.
          trust = catalogBundleHost(renderHost)?.let { BundleVerifier.summary(it.trust) },
          // Why the session is snapshot-only (no live lane), when it is — read off the host so a
          // programmatic client sees the same reason the viewer banner shows.
          degradations = renderHost.degradations.map { DegradationDto(it.code, it.detail) },
          previews =
            renderHost.previews.map { p ->
              PreviewDto(
                id = p.id,
                label = p.label,
                modes = p.modes.map { it.wire },
                overrides = p.overrides,
              )
            },
        )
      call.respondText(
        JSON.encodeToString(PreviewsResponse.serializer(), dto),
        ContentType.Application.Json,
      )
    }
  }

  /**
   * `GET /index.json` (query) and `GET /{system}/index.json` (path): the session's previews as a
   * Storybook stories index ([StorybookCompat.Index]). This is the manifest a downstream visual
   * tool crawls to enumerate stories and their stable ids.
   */
  private suspend fun RoutingContext.handleStorybookIndex(sessionInPath: Boolean) {
    if (rejectBadToken()) return
    withLeasedSession(selectedSessionId(sessionInPath)) { renderHost ->
      call.respondText(
        JSON.encodeToString(
          StorybookCompat.Index.serializer(),
          StorybookCompat.index(renderHost.previews),
        ),
        ContentType.Application.Json,
      )
    }
  }

  /**
   * `GET /iframe.html?id=<storyId>` (query) and `GET /{system}/iframe.html?id=<storyId>` (path):
   * render one story in isolation. Answers with a chrome-free HTML page embedding the freshly-
   * rendered preview — a raster PNG `data:` URI by default ([StorybookCompat.iframePage]), or with
   * `&format=svg` the figma-svg export as an **inert `<img src="data:image/svg+xml">`**
   * ([StorybookCompat.iframeSvgPage]): a still-vector, resolution-independent render for
   * DOM-capture visual tools (Percy/Chromatic/Applitools), kept in the browser's non-scripting
   * `<img>` mode so an unverified catalog's untrusted SVG can't execute. SVG is daemon-only, so a
   * static bundle 404s that lane. Honours the same override query params as `/render` (e.g.
   * `&uiMode=dark`), and load-sheds through the shared render semaphore.
   */
  private suspend fun RoutingContext.handleStorybookIframe(sessionInPath: Boolean) {
    if (rejectBadToken()) return
    withLeasedSession(selectedSessionId(sessionInPath)) { renderHost ->
      val storyId = call.request.queryParameters["id"]
      if (storyId.isNullOrBlank()) {
        call.respondText("missing story id", status = HttpStatusCode.BadRequest)
        return@withLeasedSession
      }
      val previewId = StorybookCompat.resolvePreviewId(storyId, renderHost.previews)
      if (previewId == null) {
        call.respondText("no such story", status = HttpStatusCode.NotFound)
        return@withLeasedSession
      }
      val overrideParams =
        call.request.queryParameters
          .entries()
          .mapNotNull { (key, values) ->
            val value = values.firstOrNull() ?: return@mapNotNull null
            if (ServeOverrides.isOverrideParam(key)) key to value else null
          }
          .toMap()
      val knobKinds =
        ServeOverrides.declaredKnobKinds(renderHost.previews.firstOrNull { it.id == previewId })
      // `?format=svg` serves the figma-svg export as an inert svg <img> (vector, for DOM-capture
      // visual tools); default (png) inlines the raster. SVG is daemon-only, so a static bundle
      // 404s.
      val wantSvg = call.request.queryParameters["format"]?.lowercase() == "svg"
      when (val parsed = ServeOverrides.parse(overrideParams, knobKinds)) {
        is OverrideParse.Invalid ->
          call.respondText(parsed.message, status = HttpStatusCode.BadRequest)
        is OverrideParse.Ok ->
          if (wantSvg) {
            storybookIframeSvg(renderHost, storyId, previewId, parsed.overrides)
          } else {
            storybookIframePng(renderHost, storyId, previewId, parsed.overrides)
          }
      }
    }
  }

  /** PNG lane of [handleStorybookIframe]: render, then inline the raster in the isolation page. */
  private suspend fun RoutingContext.storybookIframePng(
    renderHost: ServeHost,
    storyId: String,
    previewId: String,
    overrides: PreviewOverrides,
  ) {
    val outcome =
      withContext(Dispatchers.IO) {
        if (!renderSemaphore.tryAcquire(RENDER_QUEUE_WAIT_SECONDS, TimeUnit.SECONDS)) {
          null
        } else {
          try {
            renderHost.render(previewId, overrides)
          } finally {
            renderSemaphore.release()
          }
        }
      }
    when (outcome) {
      null -> {
        call.response.headers.append(HttpHeaders.RetryAfter, "2")
        call.respondText(
          "render queue saturated; retry shortly",
          status = HttpStatusCode.ServiceUnavailable,
        )
      }
      is RenderOutcome.Ok ->
        call.respondText(StorybookCompat.iframePage(storyId, outcome.png), ContentType.Text.Html)
      RenderOutcome.NotFound -> call.respondText("no such story", status = HttpStatusCode.NotFound)
      is RenderOutcome.Failed ->
        call.respondText(outcome.reason, status = HttpStatusCode.InternalServerError)
    }
  }

  /**
   * SVG lane of [handleStorybookIframe]: render the figma-svg export and serve it as an inert svg
   * `<img>` — a vector render for DOM-capture visual tools, safe even for an untrusted catalog's
   * SVG (see [StorybookCompat.iframeSvgPage]). Daemon-only, so a static bundle host 404s (like
   * `/render.svg`).
   */
  private suspend fun RoutingContext.storybookIframeSvg(
    renderHost: ServeHost,
    storyId: String,
    previewId: String,
    overrides: PreviewOverrides,
  ) {
    val outcome =
      withContext(Dispatchers.IO) {
        if (!renderSemaphore.tryAcquire(RENDER_QUEUE_WAIT_SECONDS, TimeUnit.SECONDS)) {
          null
        } else {
          try {
            renderHost.renderSvg(previewId, overrides)
          } finally {
            renderSemaphore.release()
          }
        }
      }
    when (outcome) {
      null -> {
        call.response.headers.append(HttpHeaders.RetryAfter, "2")
        call.respondText(
          "render queue saturated; retry shortly",
          status = HttpStatusCode.ServiceUnavailable,
        )
      }
      is SvgOutcome.Ok ->
        call.respondText(StorybookCompat.iframeSvgPage(storyId, outcome.svg), ContentType.Text.Html)
      SvgOutcome.NotFound ->
        call.respondText(
          "svg unavailable for this story (no daemon-backed SVG export)",
          status = HttpStatusCode.NotFound,
        )
      is SvgOutcome.Failed ->
        call.respondText(outcome.reason, status = HttpStatusCode.InternalServerError)
    }
  }

  /**
   * `GET /bundle.zip` (query) and `GET /{system}/bundle.zip` (path): the session as a portable zip.
   */
  private suspend fun RoutingContext.handleBundleZip(sessionInPath: Boolean) {
    if (rejectBadToken()) return
    withLeasedSession(selectedSessionId(sessionInPath)) { renderHost ->
      // Render the whole module once (cache-backed) into the portable WebEmbed gallery and stream
      // it
      // as a zip — the same render output as the live links, downloadable offline.
      val zip =
        withContext(Dispatchers.IO) {
          val built =
            ServeBundle.build(
              previews = renderHost.previews,
              title = renderHost.label,
              modulePath = renderHost.label,
            ) { preview ->
              (renderHost.render(preview.id, PreviewOverrides()) as? RenderOutcome.Ok)?.png
            }
          ServeBundle.zip(built.files)
        }
      call.respondBytes(zip, ContentType.Application.Zip)
    }
  }

  /** `GET /p/{name}` (query) and `GET /{system}/p/{name}` (path): one preview's viewer page. */
  private suspend fun RoutingContext.handleViewer(sessionInPath: Boolean) {
    if (rejectBadToken()) return
    val sessionId = selectedSessionId(sessionInPath)
    val (webSessionId, basePath) = webSessionAndBase(sessionInPath)
    withLeasedSession(sessionId) { renderHost ->
      val previewId = call.parameters["name"]
      val preview = previewId?.let { id -> renderHost.previews.firstOrNull { it.id == id } }
      if (preview == null) {
        call.respondText("no such preview", status = HttpStatusCode.NotFound)
        return@withLeasedSession
      }
      // Offer the in-browser Wasm tier when this catalog session has a Wasm app registered.
      // ServeUrls.wasmAppSrc strips the variant to the component slug the Wasm registry keys by,
      // and
      // bakes the variant's theme into `uiMode` so the live render opens on the same theme as the
      // baked snapshot the visitor deep-linked to.
      val wasmSrc =
        if (wasmCatalogs.containsKey(sessionId)) ServeUrls.wasmAppSrc(sessionId, preview.id)
        else null
      // Grant the Wasm iframe its real origin only for a TRUSTED catalog's app — an unverified
      // catalog's `/wasm/` app stays opaque-origin sandboxed so it can't reach the parent viewer.
      // Fail-closed: any session without a verifiable trusted verdict gets opaque (false).
      val wasmSameOrigin =
        catalogBundleHost(renderHost)?.let { it.trust is BundleVerifier.Verdict.Trusted } ?: false
      call.respondText(
        ServeWeb.viewerPage(
          preview,
          token,
          webSessionId,
          canApplyOverrides = renderHost.canApplyOverrides,
          // Per-preview: a catalog-live host can only re-render an override on a daemon-twinned
          // preview, so an unaliased (Android-only) variant reports false and its override controls
          // (knobs, App theme) render disabled/informational rather than enabled-but-dead.
          canRenderOverrides = renderHost.canRenderOverridesFor(preview.id),
          // Per-preview: a catalog advertises SVG globally as soon as it carries a `figma/` dir,
          // but
          // a preview whose slug has no baked `figma/<slug>.svg` still 404s the `.svg` lane, so
          // gate
          // the SVG control on this preview's actual availability rather than the session-wide
          // flag.
          hasSvgExport = renderHost.hasSvgExportFor(preview.id),
          hasLiveStream = renderHost.hasLiveStream,
          trust = catalogBundleHost(renderHost)?.let { BundleVerifier.summary(it.trust) },
          wasmSrc = wasmSrc,
          wasmSameOrigin = wasmSameOrigin,
          basePath = basePath,
          isPublic = isPublic,
          declaredThemes = renderHost.declaredThemes,
          // Android-daemon-only: gates the "Show gesture hints" row so a `@GestureHintPreview`
          // doesn't show a toggle that would do nothing on a desktop-backed session.
          gesturesRenderable = renderHost.gesturesRenderable,
          // The session's full preview list feeds the left-hand component nav drawer.
          siblings = renderHost.previews,
          // The catalog's declared stage surface (`display.surface`), so an unthemed preview backs
          // on the dark stage for a dark-first system instead of the default white.
          declaredSurface = catalogBundleHost(renderHost)?.stageSurface,
          // Why this session is snapshot-only, when it is — the banner under the header explains
          // the
          // catalog-level reason (no live bundle, unverified, …) alongside the per-control note.
          degradations = renderHost.degradations,
        ),
        ContentType.Text.Html,
      )
    }
  }

  /**
   * `GET /render/{name}` (query) and `GET /{system}/render/{name}` (path): a preview's rendered
   * bytes — a PNG for `<id>.png` (or no suffix), the figma-svg export for `<id>.svg`, or the
   * declared preview slots as JSON for `<id>.slots`. All take the same override query params; SVG
   * and slots are only produced by a daemon-backed host (a static bundle 404s them).
   */
  private suspend fun RoutingContext.handleRender(sessionInPath: Boolean) {
    if (rejectBadToken()) return
    withLeasedSession(selectedSessionId(sessionInPath)) { renderHost ->
      val rawName = call.parameters["name"]
      if (rawName.isNullOrBlank()) {
        call.respondText("missing preview id", status = HttpStatusCode.BadRequest)
        return@withLeasedSession
      }
      val wantSvg = rawName.endsWith(".svg")
      val wantSlots = rawName.endsWith(".slots")
      val previewId = rawName.removeSuffix(".png").removeSuffix(".svg").removeSuffix(".slots")
      // Forward the fixed render axes plus any dynamic override params (`knob.<key>=…` knobs and
      // `rc.<name>=…` Remote Compose seeds, neither in SUPPORTED_KEYS) so a live knob / Remote
      // Compose edit reaches ServeOverrides.parse instead of being silently dropped.
      val overrideParams =
        call.request.queryParameters
          .entries()
          .mapNotNull { (key, values) ->
            val value = values.firstOrNull() ?: return@mapNotNull null
            if (ServeOverrides.isOverrideParam(key)) key to value else null
          }
          .toMap()
      // Type a bare `knob.<key>=<value>` from the preview's declared knobs (an explicit
      // `<kind>:<value>` still wins) so the viewer never has to spell the type in the URL.
      val knobKinds =
        ServeOverrides.declaredKnobKinds(renderHost.previews.firstOrNull { it.id == previewId })
      when (val parsed = ServeOverrides.parse(overrideParams, knobKinds)) {
        is OverrideParse.Invalid ->
          call.respondText(parsed.message, status = HttpStatusCode.BadRequest)
        is OverrideParse.Ok -> {
          if (wantSvg) {
            // `?scroll=long` (or `full`/`page`) asks for the full-page export of a scrolling
            // preview (compose/figma-svg-long) instead of the viewport-sized one.
            val scroll =
              call.request.queryParameters["scroll"]?.lowercase() in setOf("long", "full", "page")
            renderSvgResponse(renderHost, previewId, parsed.overrides, scroll = scroll)
            return@withLeasedSession
          }
          if (wantSlots) {
            renderSlotsResponse(renderHost, previewId, parsed.overrides)
            return@withLeasedSession
          }
          // The render is blocking (renderNow + await); keep it off the request dispatcher. Cap
          // concurrent renders (default = CPU count) so a small box sheds a storm instead of
          // thrashing: wait briefly for a slot, else 503 + Retry-After. A null outcome signals the
          // wait timed out.
          val outcome =
            withContext(Dispatchers.IO) {
              if (!renderSemaphore.tryAcquire(RENDER_QUEUE_WAIT_SECONDS, TimeUnit.SECONDS)) {
                null
              } else {
                try {
                  renderHost.render(previewId, parsed.overrides)
                } finally {
                  renderSemaphore.release()
                }
              }
            }
          when (outcome) {
            null -> {
              call.response.headers.append(HttpHeaders.RetryAfter, "2")
              call.respondText(
                "render queue saturated; retry shortly",
                status = HttpStatusCode.ServiceUnavailable,
              )
            }
            is RenderOutcome.Ok -> call.respondBytes(outcome.png, ContentType.Image.PNG)
            RenderOutcome.NotFound ->
              call.respondText("no such preview", status = HttpStatusCode.NotFound)
            is RenderOutcome.Failed ->
              call.respondText(outcome.reason, status = HttpStatusCode.InternalServerError)
          }
        }
      }
    }
  }

  /**
   * SVG lane of [handleRender]: load-shed like the PNG lane, then respond the figma-svg bytes. When
   * [scroll] is set, serves the full-page (`compose/figma-svg-long`) export of a scrolling preview
   * instead of the viewport-sized one.
   */
  private suspend fun RoutingContext.renderSvgResponse(
    renderHost: ServeHost,
    previewId: String,
    overrides: PreviewOverrides,
    scroll: Boolean = false,
  ) {
    val outcome =
      withContext(Dispatchers.IO) {
        if (!renderSemaphore.tryAcquire(RENDER_QUEUE_WAIT_SECONDS, TimeUnit.SECONDS)) {
          null
        } else {
          try {
            if (scroll) renderHost.renderScrollSvg(previewId, overrides)
            else renderHost.renderSvg(previewId, overrides)
          } finally {
            renderSemaphore.release()
          }
        }
      }
    when (outcome) {
      null -> {
        call.response.headers.append(HttpHeaders.RetryAfter, "2")
        call.respondText(
          "render queue saturated; retry shortly",
          status = HttpStatusCode.ServiceUnavailable,
        )
      }
      is SvgOutcome.Ok ->
        call.respondBytes(outcome.svg, ContentType.parse(ComposeFigmaSvgProduct.MEDIA_TYPE_SVG))
      SvgOutcome.NotFound -> call.respondText("no such preview", status = HttpStatusCode.NotFound)
      is SvgOutcome.Failed ->
        call.respondText(outcome.reason, status = HttpStatusCode.InternalServerError)
    }
  }

  /** Slots lane of [handleRender]: load-shed like the PNG lane, then respond the slots JSON. */
  private suspend fun RoutingContext.renderSlotsResponse(
    renderHost: ServeHost,
    previewId: String,
    overrides: PreviewOverrides,
  ) {
    val outcome =
      withContext(Dispatchers.IO) {
        if (!renderSemaphore.tryAcquire(RENDER_QUEUE_WAIT_SECONDS, TimeUnit.SECONDS)) {
          null
        } else {
          try {
            renderHost.renderSlots(previewId, overrides)
          } finally {
            renderSemaphore.release()
          }
        }
      }
    when (outcome) {
      null -> {
        call.response.headers.append(HttpHeaders.RetryAfter, "2")
        call.respondText(
          "render queue saturated; retry shortly",
          status = HttpStatusCode.ServiceUnavailable,
        )
      }
      is SlotsOutcome.Ok -> call.respondBytes(outcome.json, ContentType.Application.Json)
      SlotsOutcome.NotFound -> call.respondText("no such preview", status = HttpStatusCode.NotFound)
      is SlotsOutcome.Failed ->
        call.respondText(outcome.reason, status = HttpStatusCode.InternalServerError)
    }
  }

  /**
   * The persistent frame lane, shared by `WS /ws/{name}` (query `?session=`) and `WS
   * /{system}/ws/{name}` (path — the `{system}` segment IS the session). Token is checked
   * post-handshake (can't 404 after upgrade) — a bad token closes immediately.
   */
  private suspend fun DefaultWebSocketServerSession.serveStreamLane() {
    val provided = call.request.queryParameters["token"] ?: call.request.headers[TOKEN_HEADER]
    if (!isAuthorized(token, provided, isPublic)) {
      close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "unauthorized"))
      return
    }
    val sessionId =
      call.parameters["system"] ?: call.request.queryParameters["session"] ?: defaultSessionId
    // Reserve live-seat permits BEFORE opening the session: leasing resumes a suspended/forked
    // host,
    // which spawns the JVM render daemon, so a post-lease check would let an over-budget burst
    // spawn
    // the very daemons this budget bounds. A known-static (pinned bundle/catalog) session takes no
    // permit (weight 0); a daemon-backed one charges its backend weight (desktop 1, Android
    // heavier),
    // read from the session state without opening the daemon. A lazily-forked session (e.g.
    // --revisions), unregistered until its build runs, defaults to weight 1 — a desktop-cost
    // daemon.
    val weight = if (sessions.isKnownStatic(sessionId)) 0 else sessions.liveSeatWeight(sessionId)
    val seatTicket = liveSeats.acquire(weight)
    if (seatTicket == null) {
      close(CloseReason(1013.toShort(), "live preview at capacity — try again shortly"))
      return
    }
    try {
      // Lease (not just acquire) the tenant for the socket's whole life: a fallback-lane socket
      // opens
      // no stream, so without a lease the reaper could close its host mid-connection.
      val lease = withContext(Dispatchers.IO) { sessions.lease(sessionId) }
      if (lease == null) {
        close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "no such session"))
        return
      }
      try {
        val renderHost = lease.host
        val previewId = call.parameters["name"]
        if (previewId == null || renderHost.previews.none { it.id == previewId }) {
          close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "no such preview"))
          return
        }
        val initialOverrides =
          call.request.queryParameters
            .entries()
            .mapNotNull { (key, values) ->
              val value = values.firstOrNull() ?: return@mapNotNull null
              if (ServeOverrides.isOverrideParam(key)) key to value else null
            }
            .toMap()
        // Non-suspending hand-off to the socket; drop frames a slow client can't keep up with.
        val send: (String) -> Unit = { text -> outgoing.trySend(Frame.Text(text)) }
        // Optional stream tuning: codec (WebP is ~30–60% smaller; the daemon downgrades to PNG if
        // it
        // can't encode WebP) and an fps cap.
        val codec =
          when (call.request.queryParameters["codec"]?.lowercase()) {
            "webp" -> StreamCodec.WEBP
            "png" -> StreamCodec.PNG
            else -> null
          }
        val maxFps = call.request.queryParameters["maxFps"]?.toIntOrNull()?.takeIf { it > 0 }
        // Prefer the daemon's live stream lane (frames pushed, input dispatched); fall back to the
        // snapshot re-render lane when the backend doesn't support streaming. Capture the live
        // lane's original failure so the snapshot session can explain why input isn't live. The
        // callback fires synchronously inside tryStart (before it returns), so a plain var is safe.
        var liveUnavailableReason: String? = null
        val live =
          withContext(Dispatchers.IO) {
            ServeLiveSession.tryStart(
              renderHost,
              previewId,
              initialOverrides,
              codec,
              maxFps,
              send,
            ) { reason ->
              if (liveUnavailableReason == null) liveUnavailableReason = reason
            }
          }
        if (live != null) {
          try {
            for (frame in incoming) {
              if (frame is Frame.Text) {
                val text = frame.readText()
                withContext(Dispatchers.IO) { live.onClientMessage(text) }
              }
            }
          } finally {
            withContext(Dispatchers.IO) { live.close() }
          }
        } else {
          val session =
            ServeStreamSession(
              renderHost,
              previewId,
              initialOverrides,
              send,
              liveUnavailableReason = liveUnavailableReason,
            )
          // Renders block (renderNow + await); keep them off the socket's event-loop thread.
          withContext(Dispatchers.IO) { session.onOpen() }
          for (frame in incoming) {
            if (frame is Frame.Text) {
              val text = frame.readText()
              withContext(Dispatchers.IO) { session.onClientMessage(text) }
            }
          }
        }
      } finally {
        lease.close()
      }
    } finally {
      seatTicket.close()
    }
  }

  /**
   * Token gate: respond 404 (not 401 — don't confirm the server to a scanner) and return true when
   * the request's `?token=` / `X-Compose-Preview-Token` doesn't match. Constant-time compare.
   */
  private suspend fun RoutingContext.rejectBadToken(): Boolean {
    val provided = call.request.queryParameters["token"] ?: call.request.headers[TOKEN_HEADER]
    if (isAuthorized(token, provided, isPublic)) return false
    call.respondText("not found", status = HttpStatusCode.NotFound)
    return true
  }

  companion object {
    const val TOKEN_HEADER: String = "X-Compose-Preview-Token"
    private const val DEFAULT_PORT_RANGE = 32

    /**
     * How long the readiness prober waits between failed render attempts before retrying (short, so
     * a daemon that's still warming latches `ready` promptly once it can render). Only matters
     * while the latch is cold; the loop exits on first success.
     */
    private const val READINESS_PROBE_RETRY_MILLIS = 2000L

    /**
     * Authorisation decision for a request: open when [isPublic], otherwise the [provided] token
     * must match [token] (constant-time). Pure so the gate is unit-testable without standing up the
     * server. A bad/absent token in non-public mode is rejected (the caller 404s for obscurity).
     */
    fun isAuthorized(token: String, provided: String?, isPublic: Boolean): Boolean =
      isPublic || ServeUrls.tokensMatch(token, provided)

    /**
     * How long a `/render` request waits for a concurrency slot before getting 503 + Retry-After.
     */
    private const val RENDER_QUEUE_WAIT_SECONDS = 30L

    /** Max accepted upload-body size for `POST /bundles` (matches the store's extraction cap). */
    private const val MAX_UPLOAD_BYTES = 100L * 1024 * 1024

    private val JSON = Json { encodeDefaults = true }

    /**
     * A compact human duration for the status page (`3d 4h`, `12m 5s`, `42s`). Deterministic given
     * [seconds], so a fixture that passes fixed inputs renders a stable golden.
     */
    internal fun formatDuration(seconds: Long): String {
      val s = seconds.coerceAtLeast(0)
      val d = s / 86_400
      val h = (s % 86_400) / 3_600
      val m = (s % 3_600) / 60
      val sec = s % 60
      return when {
        d > 0 -> "${d}d ${h}h"
        h > 0 -> "${h}h ${m}m"
        m > 0 -> "${m}m ${sec}s"
        else -> "${sec}s"
      }
    }

    /** An epoch-millis instant as `YYYY-MM-DD HH:MM UTC` for the status page's failure table. */
    internal fun formatInstant(epochMillis: Long): String {
      val dt =
        java.time.OffsetDateTime.ofInstant(
          java.time.Instant.ofEpochMilli(epochMillis),
          java.time.ZoneOffset.UTC,
        )
      return java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").format(dt)
    }

    /**
     * Content type for a Wasm-app asset by extension. `application/wasm` matters: the browser's
     * `WebAssembly.instantiateStreaming` rejects a wasm served as `octet-stream`. `.mjs`/`.js` must
     * be a JS type so the ES-module loader runs.
     */
    internal fun wasmContentType(name: String): ContentType =
      when {
        name.endsWith(".html") -> ContentType.Text.Html
        name.endsWith(".mjs") || name.endsWith(".js") -> ContentType.parse("text/javascript")
        name.endsWith(".wasm") -> ContentType.parse("application/wasm")
        name.endsWith(".json") || name.endsWith(".map") -> ContentType.Application.Json
        else -> ContentType.Application.OctetStream
      }

    /**
     * Read [input] fully, or `null` once it exceeds [max] bytes (without buffering past the cap).
     */
    private fun readCapped(input: InputStream, max: Long): ByteArray? {
      val out = ByteArrayOutputStream()
      val buffer = ByteArray(64 * 1024)
      var total = 0L
      while (true) {
        val n = input.read(buffer)
        if (n < 0) break
        total += n
        if (total > max) return null
        out.write(buffer, 0, n)
      }
      return out.toByteArray()
    }

    /**
     * Pick a bindable port: try [requested], then increment up to [range] times when it's taken.
     * Probes with a short-lived [ServerSocket] on the target host; there's a small TOCTOU window
     * before Ktor binds, acceptable for a developer-facing local server. Falls back to an ephemeral
     * port (0 → OS-assigned) if nothing in the range is free.
     */
    private fun pickPort(host: String, requested: Int, range: Int): Int {
      val bindAddr = if (host == ServeUrls.ALL_INTERFACES) null else InetAddress.getByName(host)
      for (candidate in requested until (requested + range)) {
        try {
          ServerSocket(candidate, 0, bindAddr).use {
            return it.localPort
          }
        } catch (_: Exception) {
          // taken — try the next
        }
      }
      ServerSocket(0, 0, bindAddr).use {
        return it.localPort
      }
    }
  }
}

@Serializable
private data class VersionResponse(
  val schema: String = "compose-preview-serve/version/v1",
  /** The host CLI's released version ([BUNDLE_VERSION]). */
  val version: String,
  /**
   * The schema id the `/api/previews` + page surface speaks, so a client can feature-detect. `v2`
   * adds the per-preview `overrides` (author knob declarations) to `/api/previews`.
   */
  val serveSchema: String = "compose-preview-serve/v2",
  /** True when the box serves token-free (public preview server); false for a token-gated serve. */
  val public: Boolean,
)

/**
 * `GET /status.json` (and `GET /status?format=json`): the machine-readable server-status snapshot a
 * monitor or a Home Assistant REST sensor polls. Flat-ish on purpose so `status` and the grouped
 * counts (`catalogs`, `daemons`) map cleanly onto sensor states/attributes; the detail lives in the
 * `catalogList` / `runningServers` / `recentDaemonFailures` arrays.
 */
@Serializable
private data class StatusResponse(
  val schema: String = "compose-preview-serve/status/v1",
  /** The host CLI's released version ([BUNDLE_VERSION]). */
  val version: String,
  /** True when the box serves token-free (public preview server). */
  val public: Boolean,
  /** `ok` when there are no recent daemon startup failures, else `degraded`. */
  val status: String,
  /** Seconds since the server started. */
  val uptimeSeconds: Long,
  val catalogs: CatalogSummaryDto,
  val daemons: DaemonSummaryDto,
  val config: ConfigDto,
  val catalogList: List<CatalogDto>,
  val runningServers: List<RunningServerDto>,
  val recentDaemonFailures: List<FailureDto>,
)

@Serializable
private data class CatalogSummaryDto(
  val total: Int,
  val listed: Int,
  val unlisted: Int,
  val trusted: Int,
  val degraded: Int,
)

@Serializable
private data class DaemonSummaryDto(
  /** Total known sessions (resident + suspended). */
  val known: Int,
  /** Live (daemon-backed) render sessions up right now. */
  val running: Int,
  val activeStreams: Int,
  /** Live-seat permit budget; `0` ⇒ unbounded. */
  val liveSeatsTotal: Int,
  /** Free permits; `-1` ⇒ unbounded. */
  val liveSeatsAvailable: Int,
  val liveSeatsUnbounded: Boolean,
)

@Serializable
private data class ConfigDto(
  val host: String,
  val port: Int,
  val allowRenderTrusted: Boolean,
  val trustStore: Boolean,
  val acceptBundles: Boolean,
  /** Catalog auto-refresh interval; `0` ⇒ disabled. */
  val catalogRefreshSeconds: Long,
  val maxConcurrentRenders: Int,
  /** Live-seat permit budget; `0` ⇒ unbounded. */
  val liveSeats: Int,
)

@Serializable
private data class CatalogDto(
  val id: String,
  val listed: Boolean,
  val title: String? = null,
  /**
   * [BundleVerifier.summary] verdict, or null for a suspended live catalog / non-catalog session.
   */
  val trust: String? = null,
  val previews: Int? = null,
  /** Has a live daemon-backed render lane (running now, or a suspended live catalog). */
  val live: Boolean,
  /** A live daemon for this catalog is up right now. */
  val running: Boolean,
  val degradation: String? = null,
  val repo: String? = null,
  val branch: String? = null,
  val generatedAt: String? = null,
  /** Canonical catalog path (`/<id>/`). */
  val path: String,
)

@Serializable
private data class RunningServerDto(
  val id: String,
  val label: String,
  /** `desktop` / `android`, derived from the live-seat weight. */
  val backend: String,
  val seatWeight: Int,
  val activeStreams: Int,
  val uptimeSeconds: Long? = null,
)

@Serializable
private data class FailureDto(val atEpochMillis: Long, val session: String, val reason: String)

@Serializable
private data class PreviewsResponse(
  val schema: String = "compose-preview-serve/v2",
  val module: String,
  /**
   * Producer-trust verdict for this session ([BundleVerifier.summary]) — `signature:<keyId>`,
   * `branch:<repo>@<branch>`, `provenance:<id>`, or `unverified`. Null for a live daemon-backed
   * module (trust applies to detached bundles/catalogs, not the operator's own served module).
   */
  val trust: String? = null,
  /**
   * Why this session is snapshot-only, when it is — an interactive/live lane the viewer would
   * otherwise offer is unavailable and the server fell back to baked PNGs (e.g. the catalog
   * publishes no `liveBundle`). Empty for a fully-live session. Each entry carries a stable [code]
   * plus a human [detail]. Additive since `compose-preview-serve/v2`. See [ServeDegradation].
   */
  val degradations: List<DegradationDto> = emptyList(),
  val previews: List<PreviewDto>,
)

@Serializable private data class DegradationDto(val code: String, val detail: String)

@Serializable
private data class PreviewDto(
  val id: String,
  val label: String,
  val modes: List<String>,
  /**
   * The author-declared editable knobs this preview exposes (`compose/overrides`) — key, type,
   * label, default/current value, and repeat index. Lets a programmatic client (the Figma plugin's
   * override editor) present the controls without scraping the viewer HTML. Empty when the preview
   * declares none (or the host doesn't carry them). Additive since `compose-preview-serve/v2`.
   */
  val overrides: List<PreviewOverrideDeclaration> = emptyList(),
)

@Serializable
private data class BundleAcceptedResponse(
  val schema: String = "compose-preview-serve/bundle/v1",
  val session: String,
  val previews: Int,
  /** Relative viewer link for the new session (append your token). */
  val path: String,
  /**
   * Producer-trust verdict for the upload ([BundleVerifier.summary]): `signature:<keyId>`,
   * `branch:<repo>@<branch>`, `provenance:<id>`, or `unverified`. The data tiers serve either way;
   * this tells the uploader whether the server would treat the bundle as trusted.
   */
  val trust: String,
)
