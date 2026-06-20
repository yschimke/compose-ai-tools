package ee.schimke.composeai.clients

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * A parsed "open this session" link — everything a client needs to connect to a `compose-preview
 * serve` instance and start painting frames for a [SessionTarget] (a running preview, or a portable
 * bundle the server starts).
 *
 * Link shapes that parse into this model (one tap, several sources):
 * - the app's custom scheme — `composeai://session?host=…&port=…&token=…&preview=<id>` (a running
 *   preview), or `composeai://session?host=…&port=…&token=…&bundle=<url>[&preview=<id>]` (a bundle
 *   to start);
 * - the serve viewer URL a human already has — `http(s)://host:port/p/{previewId}?token=…` (the
 *   exact shape `ServeUrls.viewerUrl` prints); and
 * - a raw WebSocket URL — `ws(s)://host:port/ws/{previewId}?token=…`.
 *
 * A **host-less** bundle link — `composeai://open?bundle=<url>&token=…[&preview=<id>]` — is the
 * "there's a server somewhere" case: it names a bundle but not where to run it, so the app pairs it
 * with its **configured default server** via [forBundle]. Parse those with [parseOpen].
 */
data class SessionLink(
  val host: String,
  val port: Int,
  val token: String,
  val target: SessionTarget,
  /** `true` → `wss`/`https` (TLS). Defaults to plaintext, the serve default on a trusted LAN. */
  val secure: Boolean = false,
) {
  init {
    require(host.isNotBlank()) { "host is blank" }
    require(port in 1..65535) { "port out of range: $port" }
    require(token.isNotBlank()) { "token is blank" }
  }

  /**
   * The WebSocket frame-lane URL this link points at:
   * - [SessionTarget.Preview] → `ws(s)://host:port/ws/{previewId}?token=…` (the serve spike route);
   * - [SessionTarget.Bundle] → `ws(s)://host:port/ws/bundle?src=<url>&token=…[&preview=<id>]` — the
   *   server fetches + starts the bundle, then streams it (forward-looking entrypoint).
   */
  fun webSocketUrl(): String {
    val scheme = if (secure) "wss" else "ws"
    val base = "$scheme://$host:$port"
    return when (val t = target) {
      is SessionTarget.Preview ->
        "$base/ws/${encodeSegment(t.previewId)}?token=${encodeQuery(token)}"
      is SessionTarget.Bundle ->
        buildString {
          append("$base/ws/bundle?src=").append(encodeQuery(t.src))
          append("&token=").append(encodeQuery(token))
          t.previewId?.let { append("&preview=").append(encodeQuery(it)) }
        }
    }
  }

  /** The canonical, shareable `composeai://` form (what a QR code or share-sheet hands out). */
  fun toUri(): String = buildString {
    append("composeai://session?host=").append(encodeQuery(host))
    append("&port=").append(port)
    append("&token=").append(encodeQuery(token))
    when (val t = target) {
      is SessionTarget.Preview -> append("&preview=").append(encodeQuery(t.previewId))
      is SessionTarget.Bundle -> {
        append("&bundle=").append(encodeQuery(t.src))
        t.previewId?.let { append("&preview=").append(encodeQuery(it)) }
      }
    }
    if (secure) append("&secure=true")
  }

  /**
   * A request to open a [target] whose server is not pinned in the link — resolve with [forBundle].
   */
  data class OpenRequest(val target: SessionTarget, val token: String)

  companion object {
    const val SCHEME: String = "composeai"
    const val DEFAULT_PORT: Int = 7341

    /** Build a link to start [target]'s bundle on the app's configured server. */
    fun forBundle(
      host: String,
      port: Int,
      token: String,
      bundleSrc: String,
      previewId: String? = null,
      secure: Boolean = false,
    ): SessionLink =
      SessionLink(host, port, token, SessionTarget.Bundle(bundleSrc, previewId), secure)

    /**
     * Parse a link that names its server. Returns `null` (never throws) on anything that isn't a
     * usable, fully-addressed session link.
     */
    fun parse(raw: String?): SessionLink? {
      val uri = toUri(raw) ?: return null
      return when (uri.scheme?.lowercase()) {
        SCHEME -> if (uri.host.equals("open", ignoreCase = true)) null else parseCustom(uri)
        "http",
        "https" -> parseViewer(uri, secure = uri.scheme.equals("https", ignoreCase = true))
        "ws",
        "wss" -> parseWs(uri, secure = uri.scheme.equals("wss", ignoreCase = true))
        else -> null
      }
    }

    /**
     * Parse a host-less open request — `composeai://open?bundle=<url>&token=…[&preview=<id>]` — for
     * the "server somewhere" flow. Returns `null` for links that *do* name a server (use [parse]).
     */
    fun parseOpen(raw: String?): OpenRequest? {
      val uri = toUri(raw) ?: return null
      if (!uri.scheme.equals(SCHEME, ignoreCase = true) || !uri.host.equals("open", true))
        return null
      val q = queryParams(uri.rawQuery)
      val token = q["token"]?.takeIf { it.isNotBlank() } ?: return null
      val bundle = q["bundle"]?.takeIf { it.isNotBlank() } ?: return null
      return OpenRequest(
        SessionTarget.Bundle(bundle, q["preview"]?.takeIf { it.isNotBlank() }),
        token,
      )
    }

    private fun toUri(raw: String?): URI? {
      val text = raw?.trim().orEmpty()
      if (text.isEmpty()) return null
      return runCatching { URI(text) }.getOrNull()
    }

    /** `composeai://session?host=…&port=…&token=…&(preview=…|bundle=…[&preview=…])`. */
    private fun parseCustom(uri: URI): SessionLink? {
      val q = queryParams(uri.rawQuery)
      val host = q["host"]?.takeIf { it.isNotBlank() } ?: return null
      val token = q["token"]?.takeIf { it.isNotBlank() } ?: return null
      val secure = q["secure"].equals("true", ignoreCase = true)
      val port = q["port"]?.toIntOrNull() ?: defaultPort(secure)
      val target = targetFrom(q) ?: return null
      return runCatching { SessionLink(host, port, token, target, secure) }.getOrNull()
    }

    /**
     * Serve viewer URL: `http(s)://host:port/p/{previewId}?token=…` (or `/b/{bundle}` for a
     * bundle).
     */
    private fun parseViewer(uri: URI, secure: Boolean): SessionLink? {
      val host = uri.host ?: return null
      val port = if (uri.port != -1) uri.port else if (secure) 443 else 80
      val q = queryParams(uri.rawQuery)
      val token = q["token"]?.takeIf { it.isNotBlank() } ?: return null
      val path = uri.rawPath.orEmpty()
      val target =
        pathSegmentAfter(path, "/p/")?.let { SessionTarget.Preview(it) }
          ?: pathSegmentAfter(path, "/ws/")
            ?.takeIf { it != "bundle" }
            ?.let { SessionTarget.Preview(it) }
          ?: q["bundle"]
            ?.takeIf { it.isNotBlank() }
            ?.let { SessionTarget.Bundle(it, q["preview"]?.takeIf { p -> p.isNotBlank() }) }
          ?: return null
      return runCatching { SessionLink(host, port, token, target, secure) }.getOrNull()
    }

    /** Raw WebSocket URL: `ws(s)://host:port/ws/{previewId}?token=…` or `…/ws/bundle?src=…`. */
    private fun parseWs(uri: URI, secure: Boolean): SessionLink? {
      val host = uri.host ?: return null
      val port = if (uri.port != -1) uri.port else defaultPort(secure)
      val q = queryParams(uri.rawQuery)
      val token = q["token"]?.takeIf { it.isNotBlank() } ?: return null
      val seg = pathSegmentAfter(uri.rawPath.orEmpty(), "/ws/") ?: return null
      val target =
        if (seg == "bundle") {
          val src = q["src"]?.takeIf { it.isNotBlank() } ?: return null
          SessionTarget.Bundle(src, q["preview"]?.takeIf { it.isNotBlank() })
        } else {
          SessionTarget.Preview(seg)
        }
      return runCatching { SessionLink(host, port, token, target, secure) }.getOrNull()
    }

    private fun targetFrom(q: Map<String, String>): SessionTarget? {
      val bundle = q["bundle"]?.takeIf { it.isNotBlank() }
      val preview = q["preview"]?.takeIf { it.isNotBlank() }
      return when {
        bundle != null -> SessionTarget.Bundle(bundle, preview)
        preview != null -> SessionTarget.Preview(preview)
        else -> null
      }
    }

    private fun defaultPort(secure: Boolean): Int = if (secure) 443 else DEFAULT_PORT

    /** The single path segment following [prefix], percent-decoded, or null if absent/empty. */
    private fun pathSegmentAfter(path: String, prefix: String): String? {
      val start = path.indexOf(prefix)
      if (start < 0) return null
      var rest = path.substring(start + prefix.length)
      val slash = rest.indexOf('/')
      if (slash >= 0) rest = rest.substring(0, slash)
      if (rest.endsWith(".png")) rest = rest.removeSuffix(".png")
      return decode(rest).takeIf { it.isNotBlank() }
    }

    private fun queryParams(rawQuery: String?): Map<String, String> {
      if (rawQuery.isNullOrEmpty()) return emptyMap()
      return rawQuery
        .split('&')
        .mapNotNull { pair ->
          if (pair.isEmpty()) return@mapNotNull null
          val eq = pair.indexOf('=')
          if (eq < 0) decode(pair) to ""
          else decode(pair.substring(0, eq)) to decode(pair.substring(eq + 1))
        }
        .toMap()
    }

    // The `String` charset overloads work on every Android API level; the `Charset` overloads are
    // API 33+ (java.net since Java 10), which would crash on older devices the apps still target.
    private fun encodeSegment(s: String): String =
      URLEncoder.encode(s, "UTF-8").replace("+", "%20").replace("%7E", "~")

    private fun encodeQuery(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun decode(s: String): String =
      runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)
  }
}
