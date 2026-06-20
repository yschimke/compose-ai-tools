package ee.schimke.composeai.cli.serve

import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Pure helpers for the `compose-preview serve` link surface: minting the session token, assembling
 * shareable URLs (with preview ids percent-encoded), constant-time token comparison, and LAN IPv4
 * discovery for the startup banner. Kept free of ktor / IO types so the URL + token logic is
 * unit-testable; the only environment touch is [siteLocalIpv4Addresses], isolated in its own fn.
 */
object ServeUrls {

  /** A host bound to all interfaces — the value we treat as "exposed to the LAN". */
  const val ALL_INTERFACES: String = "0.0.0.0"

  /** Loopback host; the safe default bind. */
  const val LOOPBACK: String = "127.0.0.1"

  /**
   * Mint a URL-safe, unguessable session token: 32 bytes from [SecureRandom], base64url-encoded
   * without padding. This is the only gate on the served endpoints, so it must be high-entropy and
   * never derived from anything predictable.
   */
  fun generateToken(random: SecureRandom = SecureRandom()): String {
    val bytes = ByteArray(32)
    random.nextBytes(bytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
  }

  /** True when [host] means "bound to every interface", i.e. reachable from other machines. */
  fun isExposed(host: String): Boolean = host == ALL_INTERFACES || host == "::"

  /**
   * Base origin (`http://host:port`) a browser uses. When [host] is the wildcard bind, callers
   * substitute a concrete reachable address (loopback for the Local line, a
   * [siteLocalIpv4Addresses] entry for the Network line) — the wildcard itself is not a usable URL
   * host.
   */
  fun origin(host: String, port: Int): String = "http://$host:$port"

  /** Landing-page URL (preview list) carrying the token. */
  fun landingUrl(origin: String, token: String): String =
    "$origin/?token=${WebEscaping.urlEncodeSegment(token)}"

  /** Viewer-page URL for one preview, id percent-encoded as a path segment, token in the query. */
  fun viewerUrl(origin: String, previewId: String, token: String): String =
    "$origin/p/${WebEscaping.urlEncodeSegment(previewId)}?token=${WebEscaping.urlEncodeSegment(token)}"

  /**
   * Render (PNG) URL for one preview at the given overrides. [overrides] is an already-validated
   * map of `ServeOverrides.SUPPORTED_KEYS` → value; the token and each value are percent-encoded.
   */
  fun renderUrl(
    origin: String,
    previewId: String,
    token: String,
    overrides: Map<String, String> = emptyMap(),
  ): String {
    val query = buildString {
      append("token=").append(WebEscaping.urlEncodeSegment(token))
      for ((k, v) in overrides) {
        if (v.isBlank()) continue
        append('&').append(k).append('=').append(WebEscaping.urlEncodeSegment(v))
      }
    }
    return "$origin/render/${WebEscaping.urlEncodeSegment(previewId)}.png?$query"
  }

  /**
   * Constant-time token comparison — avoids leaking how many leading characters matched via timing.
   * Both sides are compared as UTF-8 bytes; length mismatches short-circuit safely inside
   * [MessageDigest.isEqual] (which is itself constant-time for equal-length inputs).
   */
  fun tokensMatch(expected: String, provided: String?): Boolean {
    if (provided == null) return false
    return MessageDigest.isEqual(
      expected.toByteArray(Charsets.UTF_8),
      provided.toByteArray(Charsets.UTF_8),
    )
  }

  /**
   * Site-local IPv4 addresses on up, non-loopback interfaces, for the "Network:" banner line. Best
   * effort: returns empty when enumeration fails or nothing qualifies (e.g. loopback-only host).
   */
  fun siteLocalIpv4Addresses(): List<String> =
    try {
      NetworkInterface.getNetworkInterfaces()
        .asSequence()
        .filter { it.isUp && !it.isLoopback && !it.isVirtual }
        .flatMap { it.inetAddresses.asSequence() }
        .filterIsInstance<Inet4Address>()
        .filter { it.isSiteLocalAddress }
        .map { it.hostAddress }
        .distinct()
        .toList()
    } catch (_: Exception) {
      emptyList()
    }
}
