package ee.schimke.composeai.clients.mobile

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import ee.schimke.composeai.clients.discovery.DiscoveredSession
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Browses the LAN for `compose-preview serve` instances via Android's NSD (`NsdManager`), the
 * platform's mDNS/DNS-SD stack. Found-and-resolved services are surfaced as [DiscoveredSession]s in
 * [sessions]; the connect screen lists them so a user can pick a nearby server without typing a URL
 * (they still supply the token, via the tapped link — discovery never carries it).
 *
 * Lifecycle: [start] in `onResume`, [stop] in `onPause`. Resolving one service at a time keeps
 * within the pre-API-31 `resolveService` constraint; the registered-services map de-dups repeats.
 */
class NsdSessionDiscovery(context: Context) {
  private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager

  private val _sessions = MutableStateFlow<List<DiscoveredSession>>(emptyList())
  val sessions: StateFlow<List<DiscoveredSession>> = _sessions.asStateFlow()

  private val found = LinkedHashMap<String, DiscoveredSession>()
  private var discoveryListener: NsdManager.DiscoveryListener? = null

  fun start() {
    if (discoveryListener != null) return
    val listener =
      object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) {}

        override fun onServiceFound(service: NsdServiceInfo) {
          // Resolve to fetch host/port/TXT — name alone isn't connectable.
          runCatching { nsd.resolveService(service, resolveListener()) }
        }

        override fun onServiceLost(service: NsdServiceInfo) {
          found.remove(service.serviceName)
          _sessions.value = found.values.toList()
        }

        override fun onDiscoveryStopped(serviceType: String) {}

        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
          discoveryListener = null
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
      }
    discoveryListener = listener
    runCatching {
      nsd.discoverServices(
        DiscoveredSession.SERVICE_TYPE,
        NsdManager.PROTOCOL_DNS_SD,
        listener,
      )
    }
  }

  fun stop() {
    discoveryListener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
    discoveryListener = null
    found.clear()
    _sessions.value = emptyList()
  }

  private fun resolveListener() =
    object : NsdManager.ResolveListener {
      override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}

      override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
        val host = serviceInfo.host?.hostAddress ?: return
        val attrs = serviceInfo.attributes ?: emptyMap()
        fun txt(key: String): String? =
          attrs[key]?.let { String(it, StandardCharsets.UTF_8) }
        val session =
          DiscoveredSession(
            name = serviceInfo.serviceName,
            host = host,
            port = serviceInfo.port,
            moduleLabel = txt(DiscoveredSession.Txt.MODULE),
            previews =
              txt(DiscoveredSession.Txt.PREVIEWS)
                ?.split(',')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                .orEmpty(),
            secure = txt(DiscoveredSession.Txt.SECURE).equals("true", ignoreCase = true),
          )
        found[serviceInfo.serviceName] = session
        _sessions.value = found.values.toList()
      }
    }
}
