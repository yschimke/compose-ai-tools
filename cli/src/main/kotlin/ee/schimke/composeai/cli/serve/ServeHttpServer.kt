package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.StreamCodec
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receive
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
import java.net.InetAddress
import java.net.ServerSocket
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
 * Endpoints (all token-gated except `/healthz`):
 * - `GET /` landing page, `GET /p/{id}` viewer page,
 * - `GET /render/{id}.png` PNG bytes, `GET /api/previews` JSON, `GET /healthz` liveness,
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
  portRange: Int = DEFAULT_PORT_RANGE,
) {
  /** The actual bound port — may differ from the requested one if it was taken (auto-picked). */
  val port: Int = pickPort(host, requestedPort, portRange)

  private val server: EmbeddedServer<*, *> =
    embeddedServer(CIO, host = host, port = port) {
      install(WebSockets)
      routing {
        // `/healthz` is the only ungated route — liveness only, leaks nothing.
        get("/healthz") { call.respondText("ok") }

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
            val body = if (url == null) call.receive<ByteArray>() else null
            val result =
              withContext(Dispatchers.IO) {
                if (url != null) store.addFromUrl(name, url) else store.add(name, body!!)
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
          if (!ServeUrls.tokensMatch(token, provided)) {
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
              ServeOverrides.SUPPORTED_KEYS.mapNotNull { key ->
                  call.request.queryParameters[key]?.let { key to it }
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
          withLeasedSession { renderHost ->
            val previewId = call.parameters["name"]
            val preview = previewId?.let { id -> renderHost.previews.firstOrNull { it.id == id } }
            if (preview == null) {
              call.respondText("no such preview", status = HttpStatusCode.NotFound)
              return@withLeasedSession
            }
            call.respondText(
              ServeWeb.viewerPage(preview, token, call.request.queryParameters["session"]),
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
            val overrideParams =
              ServeOverrides.SUPPORTED_KEYS.mapNotNull { key ->
                  call.request.queryParameters[key]?.let { key to it }
                }
                .toMap()
            when (val parsed = ServeOverrides.parse(overrideParams)) {
              is OverrideParse.Invalid ->
                call.respondText(parsed.message, status = HttpStatusCode.BadRequest)
              is OverrideParse.Ok -> {
                // The render is blocking (renderNow + await); keep it off the request dispatcher.
                val outcome =
                  withContext(Dispatchers.IO) { renderHost.render(previewId, parsed.overrides) }
                when (outcome) {
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
    if (ServeUrls.tokensMatch(token, provided)) return false
    call.respondText("not found", status = HttpStatusCode.NotFound)
    return true
  }

  companion object {
    const val TOKEN_HEADER: String = "X-Compose-Preview-Token"
    private const val DEFAULT_PORT_RANGE = 32

    private val JSON = Json { encodeDefaults = true }

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
private data class PreviewsResponse(
  val schema: String = "compose-preview-serve/v1",
  val module: String,
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
)
