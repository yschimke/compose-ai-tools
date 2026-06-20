package ee.schimke.composeai.cli.serve

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import java.net.InetAddress
import java.net.ServerSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The embedded Ktor (CIO) HTTP server fronting a [ServeRenderHost]. Thin IO shell: a token gate,
 * the five routes, and the query-param → [ServeOverrides] → render → PNG glue. All shared,
 * concurrency safe state lives in [ServeRenderHost]; this class adds none of its own per-request
 * state, so it serves any number of clients.
 *
 * Endpoints (all token-gated except `/healthz`):
 * - `GET /` landing page, `GET /p/{id}` viewer page,
 * - `GET /render/{id}.png` PNG bytes, `GET /api/previews` JSON, `GET /healthz` liveness.
 *
 * A bad/missing token returns **404** (not 401) so the server's existence isn't confirmed to a
 * scanner; the token is compared in constant time ([ServeUrls.tokensMatch]).
 */
class ServeHttpServer(
  private val host: String,
  requestedPort: Int,
  private val token: String,
  private val renderHost: ServeRenderHost,
  private val moduleLabel: String,
  portRange: Int = DEFAULT_PORT_RANGE,
) {
  /** The actual bound port — may differ from the requested one if it was taken (auto-picked). */
  val port: Int = pickPort(host, requestedPort, portRange)

  private val server: EmbeddedServer<*, *> =
    embeddedServer(CIO, host = host, port = port) {
      routing {
        // `/healthz` is the only ungated route — liveness only, leaks nothing.
        get("/healthz") { call.respondText("ok") }

        get("/") {
          if (rejectBadToken()) return@get
          call.respondText(
            ServeWeb.landingPage(moduleLabel, renderHost.previews, token),
            ContentType.Text.Html,
          )
        }

        get("/api/previews") {
          if (rejectBadToken()) return@get
          val dto =
            PreviewsResponse(
              module = moduleLabel,
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

        get("/p/{name}") {
          if (rejectBadToken()) return@get
          val previewId = call.parameters["name"]
          val preview = previewId?.let { id -> renderHost.previews.firstOrNull { it.id == id } }
          if (preview == null) {
            call.respondText("no such preview", status = HttpStatusCode.NotFound)
            return@get
          }
          call.respondText(ServeWeb.viewerPage(preview, token), ContentType.Text.Html)
        }

        get("/render/{name}") {
          if (rejectBadToken()) return@get
          val previewId = call.parameters["name"]?.removeSuffix(".png")
          if (previewId.isNullOrBlank()) {
            call.respondText("missing preview id", status = HttpStatusCode.BadRequest)
            return@get
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

  /** Start listening. Non-blocking; the caller keeps the process alive separately. */
  fun start() {
    server.start(wait = false)
  }

  /** Stop with a short grace period. Idempotent enough for a shutdown hook. */
  fun stop() {
    server.stop(gracePeriodMillis = 500, timeoutMillis = 2000)
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
