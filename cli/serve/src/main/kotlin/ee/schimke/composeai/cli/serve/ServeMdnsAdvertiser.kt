package ee.schimke.composeai.cli.serve

import java.net.InetAddress
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo

/**
 * Advertises a running `compose-preview serve` instance on the local network over mDNS/DNS-SD, so
 * the mobile + wear session-viewer clients (`:clients:*`) can discover it without anyone typing a
 * URL. Publishes the `_composeai._tcp` service the clients browse for, carrying the module label /
 * preview ids / TLS flag in TXT records — **but never the token** (a broadcast token would defeat
 * the only gate on the served endpoints; the user still supplies it via the shared link / QR).
 *
 * Best-effort and self-contained: any failure to bring up the responder is swallowed (the server
 * still works, discovery just won't light up), and [close] is idempotent. Only meaningful when the
 * server is bound to a reachable interface (`serve --lan`); advertising loopback is pointless.
 *
 * The service type + TXT keys are the shared discovery contract — kept in lockstep with
 * `ee.schimke.composeai.clients.discovery.DiscoveredSession` in `:clients:core` (the client side),
 * inlined here so the published `:cli` doesn't take a dependency on a client-app module.
 */
class ServeMdnsAdvertiser
private constructor(private val jmdns: JmDNS, private val info: ServiceInfo) : AutoCloseable {

  override fun close() {
    runCatching { jmdns.unregisterService(info) }
    runCatching { jmdns.close() }
  }

  companion object {
    /** jmdns spells the service type with the trailing `.local.` domain the clients' NSD omits. */
    private const val JMDNS_SERVICE_TYPE = "_composeai._tcp.local."

    // TXT keys — mirror `DiscoveredSession.Txt` in `:clients:core`.
    private const val TXT_PROTOCOL = "proto"
    private const val TXT_PROTOCOL_VALUE = "composeai-stream/1"
    private const val TXT_MODULE = "module"
    private const val TXT_PREVIEWS = "previews"
    private const val TXT_SECURE = "secure"

    /**
     * Register and return an advertiser, or `null` if mDNS couldn't be started (no network, port
     * clash, sandbox without multicast). The caller [close]s it on shutdown.
     */
    fun start(
      moduleLabel: String,
      port: Int,
      previewIds: List<String>,
      secure: Boolean = false,
      bindAddress: InetAddress? = null,
      onLog: (String) -> Unit = {},
    ): ServeMdnsAdvertiser? =
      try {
        val jmdns = if (bindAddress != null) JmDNS.create(bindAddress) else JmDNS.create()
        val props = buildMap {
          put(TXT_PROTOCOL, TXT_PROTOCOL_VALUE)
          put(TXT_MODULE, moduleLabel)
          if (secure) put(TXT_SECURE, "true")
          // TXT records are size-bounded; cap the advertised preview list so the packet stays
          // small. Clients fetch the authoritative set from `/api/previews` once connected.
          val previews = previewIds.take(MAX_ADVERTISED_PREVIEWS).joinToString(",")
          if (previews.isNotEmpty()) put(TXT_PREVIEWS, previews)
        }
        val info =
          ServiceInfo.create(
            JMDNS_SERVICE_TYPE,
            instanceName(moduleLabel),
            port,
            /* weight = */ 0,
            /* priority = */ 0,
            props,
          )
        jmdns.registerService(info)
        onLog("mDNS: advertising ${info.name} on _composeai._tcp:$port")
        ServeMdnsAdvertiser(jmdns, info)
      } catch (e: Exception) {
        onLog("mDNS: advertising unavailable (${e.message})")
        null
      }

    private const val MAX_ADVERTISED_PREVIEWS = 12

    /** A stable, human-readable instance name; the module path is the obvious disambiguator. */
    private fun instanceName(moduleLabel: String): String = "compose-preview $moduleLabel"
  }
}
