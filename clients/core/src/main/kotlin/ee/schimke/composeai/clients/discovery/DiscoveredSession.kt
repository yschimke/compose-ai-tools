package ee.schimke.composeai.clients.discovery

import ee.schimke.composeai.clients.SessionLink
import ee.schimke.composeai.clients.SessionTarget

/**
 * A `compose-preview serve` instance found on the local network via mDNS/DNS-SD. Discovery answers
 * "which servers are here", not "give me a session": the **token is deliberately not advertised**
 * (a broadcast token would defeat the only gate on the served endpoints), so a discovered server is
 * paired with a token the user supplies — typically by tapping the shared `composeai://` link / QR,
 * which is why [toLink] takes the token explicitly.
 *
 * The advertiser (CLI `serve`) and the discoverers (the mobile/wear apps' `NsdManager`) agree on
 * the [SERVICE_TYPE] and the TXT keys in [Txt] so the two sides interoperate without a private
 * contract.
 */
data class DiscoveredSession(
  /** Friendly instance name from the mDNS record (e.g. "compose-preview :samples:android"). */
  val name: String,
  val host: String,
  val port: Int,
  /** The Gradle module the server is rendering, from TXT `module` — for display only. */
  val moduleLabel: String? = null,
  /** Preview ids the server advertises, from TXT `previews` (comma-separated), if present. */
  val previews: List<String> = emptyList(),
  val secure: Boolean = false,
) {
  /**
   * Build a connectable link for [previewId] on this server, gated by the user-supplied [token].
   */
  fun toLink(previewId: String, token: String): SessionLink =
    SessionLink(host, port, token, SessionTarget.Preview(previewId), secure)

  /** Build a link that starts [bundleSrc] on this server (the "server somewhere" bundle flow). */
  fun toBundleLink(bundleSrc: String, token: String, previewId: String? = null): SessionLink =
    SessionLink(host, port, token, SessionTarget.Bundle(bundleSrc, previewId), secure)

  companion object {
    /**
     * The DNS-SD service type both sides register/browse. `_composeai._tcp` over the local domain.
     */
    const val SERVICE_TYPE: String = "_composeai._tcp."
  }

  /** TXT record keys carried in the mDNS advertisement (values are short ASCII). */
  object Txt {
    /** Gradle module path being served, e.g. `:samples:android`. */
    const val MODULE = "module"

    /** Comma-separated preview ids (best-effort; may be truncated for long sets). */
    const val PREVIEWS = "previews"

    /** `"true"` when the server speaks TLS (wss/https). Absent/other ⇒ plaintext. */
    const val SECURE = "secure"

    /** Wire-protocol marker so a discoverer can ignore servers it can't talk to. */
    const val PROTOCOL = "proto"

    /** Current wire-protocol value for [PROTOCOL]. */
    const val PROTOCOL_VALUE = "composeai-stream/1"
  }
}
