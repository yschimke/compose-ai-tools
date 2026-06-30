package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.cli.BUNDLE_VERSION
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.StreamCodec
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
 * Endpoints (all token-gated except `/healthz`, `/version`, and the `/wasm/` static assets):
 * - `GET /` landing page, `GET /p/{id}` viewer page,
 * - `GET /render/{id}.png` PNG bytes, `GET /api/previews` JSON, `GET /healthz` liveness,
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
  portRange: Int = DEFAULT_PORT_RANGE,
  /**
   * Max renders in flight across the HTTP `/render` lane. Defaults to the host's CPU count so a
   * small box (1–2 vCPU) sheds a render storm instead of thrashing; excess requests wait briefly
   * for a slot, then get `503 + Retry-After`. Renders also serialise inside [ServeRenderHost], so
   * this is a load-shedding bound on concurrent HTTP work, not a parallel-render knob.
   */
  maxConcurrentRenders: Int = Runtime.getRuntime().availableProcessors().coerceAtLeast(1),
) {
  /** The actual bound port — may differ from the requested one if it was taken (auto-picked). */
  val port: Int = pickPort(host, requestedPort, portRange)

  private val renderSemaphore = Semaphore(maxConcurrentRenders.coerceAtLeast(1))

  private val server: EmbeddedServer<*, *> =
    embeddedServer(CIO, host = host, port = port) {
      install(WebSockets)
      routing {
        // `/healthz` is the only ungated route — liveness only, leaks nothing.
        get("/healthz") { call.respondText("ok") }

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
        // checked post-handshake (can't 404 after upgrade) — a bad token closes immediately.
        webSocket("/ws/{name}") {
          val provided = call.request.queryParameters["token"] ?: call.request.headers[TOKEN_HEADER]
          if (!isAuthorized(token, provided, isPublic)) {
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "unauthorized"))
            return@webSocket
          }
          val sessionId = call.request.queryParameters["session"] ?: defaultSessionId
          // Lease (not just acquire) the tenant for the socket's whole life: a fallback-lane socket
          // opens no stream, so without a lease the reaper could close its host mid-connection.
          val lease = withContext(Dispatchers.IO) { sessions.lease(sessionId) }
          if (lease == null) {
            close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "no such session"))
            return@webSocket
          }
          try {
            val renderHost = lease.host
            val previewId = call.parameters["name"]
            if (previewId == null || renderHost.previews.none { it.id == previewId }) {
              close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "no such preview"))
              return@webSocket
            }
            val initialOverrides =
              call.request.queryParameters
                .entries()
                .mapNotNull { (key, values) ->
                  val value = values.firstOrNull() ?: return@mapNotNull null
                  if (
                    key in ServeOverrides.SUPPORTED_KEYS ||
                      key.startsWith(ServeOverrides.KNOB_PREFIX)
                  ) {
                    key to value
                  } else {
                    null
                  }
                }
                .toMap()
            // Non-suspending hand-off to the socket; drop frames a slow client can't keep up with.
            val send: (String) -> Unit = { text -> outgoing.trySend(Frame.Text(text)) }
            // Optional stream tuning: codec (WebP is ~30–60% smaller; the daemon downgrades to PNG
            // if
            // it can't encode WebP) and an fps cap.
            val codec =
              when (call.request.queryParameters["codec"]?.lowercase()) {
                "webp" -> StreamCodec.WEBP
                "png" -> StreamCodec.PNG
                else -> null
              }
            val maxFps = call.request.queryParameters["maxFps"]?.toIntOrNull()?.takeIf { it > 0 }
            // Prefer the daemon's live stream lane (frames pushed, input dispatched); fall back to
            // the
            // snapshot re-render lane when the backend doesn't support streaming.
            val live =
              withContext(Dispatchers.IO) {
                ServeLiveSession.tryStart(
                  renderHost,
                  previewId,
                  initialOverrides,
                  codec,
                  maxFps,
                  send,
                )
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
              val session = ServeStreamSession(renderHost, previewId, initialOverrides, send)
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
        }

        get("/") {
          if (rejectBadToken()) return@get
          withLeasedSession { renderHost ->
            call.respondText(
              ServeWeb.landingPage(
                renderHost.label,
                renderHost.previews,
                token,
                call.request.queryParameters["session"],
                trust = (renderHost as? ServeBundleHost)?.let { BundleVerifier.summary(it.trust) },
                isPublic = isPublic,
                catalogs = catalogSessions,
              ),
              ContentType.Text.Html,
            )
          }
        }

        get("/api/previews") {
          if (rejectBadToken()) return@get
          withLeasedSession { renderHost ->
            val dto =
              PreviewsResponse(
                module = renderHost.label,
                // Producer-trust verdict for a bundle/catalog session (signature / branch /
                // provenance / unverified); null for a live daemon-backed module session.
                trust = (renderHost as? ServeBundleHost)?.let { BundleVerifier.summary(it.trust) },
                previews =
                  renderHost.previews.map { p ->
                    PreviewDto(id = p.id, label = p.label, modes = p.modes.map { it.wire })
                  },
              )
            call.respondText(
              JSON.encodeToString(PreviewsResponse.serializer(), dto),
              ContentType.Application.Json,
            )
          }
        }

        get("/bundle.zip") {
          if (rejectBadToken()) return@get
          withLeasedSession { renderHost ->
            // Render the whole module once (cache-backed) into the portable WebEmbed gallery and
            // stream it as a zip — the same render output as the live links, downloadable offline.
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

        get("/p/{name}") {
          if (rejectBadToken()) return@get
          val sessionId = call.request.queryParameters["session"] ?: defaultSessionId
          withLeasedSession { renderHost ->
            val previewId = call.parameters["name"]
            val preview = previewId?.let { id -> renderHost.previews.firstOrNull { it.id == id } }
            if (preview == null) {
              call.respondText("no such preview", status = HttpStatusCode.NotFound)
              return@withLeasedSession
            }
            // Offer the in-browser Wasm tier when this catalog session has a Wasm app registered.
            // The catalog preview id is `<component-slug>__<variant>…`; the Wasm app keys its
            // registry by the component slug, so take the segment before the first `__`.
            val wasmSrc =
              if (wasmCatalogs.containsKey(sessionId)) {
                val componentId = preview.id.substringBefore("__")
                "/wasm/${WebEscaping.urlEncodeSegment(sessionId)}/" +
                  "?id=${WebEscaping.urlEncodeSegment(componentId)}"
              } else {
                null
              }
            call.respondText(
              ServeWeb.viewerPage(
                preview,
                token,
                call.request.queryParameters["session"],
                canApplyOverrides = renderHost.canApplyOverrides,
                trust = (renderHost as? ServeBundleHost)?.let { BundleVerifier.summary(it.trust) },
                wasmSrc = wasmSrc,
              ),
              ContentType.Text.Html,
            )
          }
        }

        get("/render/{name}") {
          if (rejectBadToken()) return@get
          withLeasedSession { renderHost ->
            val previewId = call.parameters["name"]?.removeSuffix(".png")
            if (previewId.isNullOrBlank()) {
              call.respondText("missing preview id", status = HttpStatusCode.BadRequest)
              return@withLeasedSession
            }
            // Forward the fixed render axes plus any author-declared knob params
            // (`knob.<key>=…`, dynamic keys not in SUPPORTED_KEYS) so a live knob edit reaches
            // ServeOverrides.parse instead of being silently dropped.
            val overrideParams =
              call.request.queryParameters
                .entries()
                .mapNotNull { (key, values) ->
                  val value = values.firstOrNull() ?: return@mapNotNull null
                  if (
                    key in ServeOverrides.SUPPORTED_KEYS ||
                      key.startsWith(ServeOverrides.KNOB_PREFIX)
                  ) {
                    key to value
                  } else {
                    null
                  }
                }
                .toMap()
            when (val parsed = ServeOverrides.parse(overrideParams)) {
              is OverrideParse.Invalid ->
                call.respondText(parsed.message, status = HttpStatusCode.BadRequest)
              is OverrideParse.Ok -> {
                // The render is blocking (renderNow + await); keep it off the request dispatcher.
                // Cap concurrent renders (default = CPU count) so a small box sheds a storm instead
                // of thrashing: wait briefly for a slot, else 503 + Retry-After. A null outcome
                // signals the wait timed out.
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
      }
    }

  /** Start listening. Non-blocking; the caller keeps the process alive separately. */
  fun start() {
    server.start(wait = false)
  }

  /** Stop with a short grace period. Idempotent enough for a shutdown hook. */
  fun stop() {
    server.stop(gracePeriodMillis = 500, timeoutMillis = 2000)
  }

  /**
   * Resolve the tenant for this request (`?session=`, else [defaultSessionId]) and run [block] with
   * its host while holding a [ServeSessionRegistry.Lease] for the request's whole duration — so the
   * reaper can't suspend the daemon mid-request (e.g. a long `/bundle.zip` that renders every
   * preview). Responds 404 when the session can't be created/opened. The lease is always released.
   */
  private suspend fun RoutingContext.withLeasedSession(block: suspend (ServeHost) -> Unit) {
    val sessionId = call.request.queryParameters["session"] ?: defaultSessionId
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
  /** The schema id the `/api/previews` + page surface speaks, so a client can feature-detect. */
  val serveSchema: String = "compose-preview-serve/v1",
  /** True when the box serves token-free (public preview server); false for a token-gated serve. */
  val public: Boolean,
)

@Serializable
private data class PreviewsResponse(
  val schema: String = "compose-preview-serve/v1",
  val module: String,
  /**
   * Producer-trust verdict for this session ([BundleVerifier.summary]) — `signature:<keyId>`,
   * `branch:<repo>@<branch>`, `provenance:<id>`, or `unverified`. Null for a live daemon-backed
   * module (trust applies to detached bundles/catalogs, not the operator's own served module).
   */
  val trust: String? = null,
  val previews: List<PreviewDto>,
)

@Serializable
private data class PreviewDto(val id: String, val label: String, val modes: List<String>)

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
