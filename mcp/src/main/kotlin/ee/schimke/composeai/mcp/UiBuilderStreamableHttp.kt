package ee.schimke.composeai.mcp

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.header
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.util.AttributeKey
import io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal const val MCP_SESSION_ID_HEADER = "Mcp-Session-Id"

internal data class UiBuilderStreamableHttpConfig(
  val uiBuilderUrl: String,
  val host: String = "127.0.0.1",
  val port: Int = 8788,
  val path: String = "/ui-builder/mcp",
  val allowedHosts: List<String>? = null,
  val allowedOrigins: List<String>? = null,
) {
  init {
    require(uiBuilderUrl.isNotBlank()) { "--ui-builder-url is required for Streamable HTTP" }
    val upstream = URI(uiBuilderUrl)
    require(upstream.scheme == "http" || upstream.scheme == "https") {
      "--ui-builder-url must use http or https"
    }
    require(!upstream.host.isNullOrBlank()) { "--ui-builder-url must include a host" }
    require(upstream.userInfo == null && upstream.query == null && upstream.fragment == null) {
      "--ui-builder-url must not contain credentials, query, or fragment"
    }
    require(port in 0..65535) { "--http-port must be between 0 and 65535" }
    require(path.startsWith('/') && path.length > 1) {
      "--http-path must be an absolute non-root path"
    }
    require('\n' !in path && '\r' !in path) { "--http-path must not contain newlines" }
  }
}

internal data class AuthenticatedUiBuilderSession(
  val tokenBinding: String,
  val adapter: UiBuilderMcpAdapter,
)

internal fun interface UiBuilderSessionAuthenticator {
  fun authenticate(token: String): AuthenticatedUiBuilderSession
}

/**
 * Installs the stateful MCP Streamable HTTP endpoint for the shared UI builder.
 *
 * Authentication is deliberately outside the MCP SDK route so it applies to POST, GET/SSE and
 * DELETE alike. The first request validates the preview-server grant; the returned MCP session id
 * is then bound to that grant fingerprint. A different valid grant therefore cannot take over an
 * existing MCP session if its id leaks.
 */
internal fun Application.installUiBuilderStreamableHttp(
  config: UiBuilderStreamableHttpConfig,
  authenticator: UiBuilderSessionAuthenticator = UiBuilderSessionAuthenticator { token ->
    AuthenticatedUiBuilderSession(
      tokenBinding = tokenSessionBinding(token),
      adapter = authenticatedUiBuilderMcp(config.uiBuilderUrl, token),
    )
  },
) {
  val authenticatedCall = AttributeKey<AuthenticatedUiBuilderSession>("ui-builder-mcp-auth")
  val sessionGrants = ConcurrentHashMap<String, String>()

  intercept(ApplicationCallPipeline.Plugins) {
    if (call.request.path() != config.path) return@intercept

    val token = call.bearerToken()
    if (token == null) {
      call.response.header(HttpHeaders.WWWAuthenticate, "Bearer")
      call.respondText("Bearer authentication required", status = HttpStatusCode.Unauthorized)
      finish()
      return@intercept
    }

    val tokenBinding = tokenSessionBinding(token)
    val requestedSession = call.request.header(MCP_SESSION_ID_HEADER)
    val authenticated =
      if (requestedSession == null) {
        val result =
          withContext(Dispatchers.IO) { runCatching { authenticator.authenticate(token) } }
            .getOrElse {
              call.response.header(HttpHeaders.WWWAuthenticate, "Bearer")
              call.respondText(
                "Bearer token rejected by UI Builder server",
                status = HttpStatusCode.Unauthorized,
              )
              finish()
              return@intercept
            }
        if (result.tokenBinding != tokenBinding) {
          call.respondText(
            "Authenticator returned a mismatched identity",
            status = HttpStatusCode.Forbidden,
          )
          finish()
          return@intercept
        }
        call.attributes.put(authenticatedCall, result)
        result
      } else {
        val expected = sessionGrants[requestedSession]
        when {
          expected == null -> {
            call.respondText("MCP session not found", status = HttpStatusCode.NotFound)
            finish()
            return@intercept
          }
          expected != tokenBinding -> {
            call.respondText(
              "MCP session belongs to another grant",
              status = HttpStatusCode.Forbidden,
            )
            finish()
            return@intercept
          }
        }
        null
      }

    try {
      proceed()
    } finally {
      val establishedSession = call.response.headers[MCP_SESSION_ID_HEADER]
      if (establishedSession != null && authenticated != null) {
        sessionGrants.putIfAbsent(establishedSession, authenticated.tokenBinding)
      }
      if (call.request.httpMethod == HttpMethod.Delete && requestedSession != null) {
        sessionGrants.remove(requestedSession, tokenBinding)
      }
    }
  }

  mcpStreamableHttp(
    path = config.path,
    enableDnsRebindingProtection = true,
    allowedHosts = config.allowedHosts,
    // The SDK intentionally skips Origin checks for custom hosts unless origins are supplied.
    // Same-host is the safe default for the shared deployment; operators may narrow it further.
    allowedOrigins = config.allowedOrigins ?: config.allowedHosts,
  ) {
    call.attributes[authenticatedCall].adapter.sdkServer()
  }
}

internal fun runUiBuilderStreamableHttp(config: UiBuilderStreamableHttpConfig) {
  System.err.println(
    "compose-preview-mcp: UI Builder Streamable HTTP listening on " +
      "http://${config.host}:${config.port}${config.path}; upstream=${config.uiBuilderUrl}"
  )
  embeddedServer(CIO, host = config.host, port = config.port) {
      installUiBuilderStreamableHttp(config)
    }
    .start(wait = true)
}

private fun ApplicationCall.bearerToken(): String? {
  val value = request.header(HttpHeaders.Authorization)?.trim() ?: return null
  if (!value.startsWith("Bearer ", ignoreCase = true)) return null
  return value.substringAfter(' ').trim().takeIf(String::isNotEmpty)?.takeUnless {
    it.any(Char::isWhitespace)
  }
}

/**
 * Full token digest for an authorization binding; the short audit fingerprint is not sufficient.
 */
internal fun tokenSessionBinding(token: String): String =
  MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8)).joinToString("") {
    "%02x".format(it)
  }
