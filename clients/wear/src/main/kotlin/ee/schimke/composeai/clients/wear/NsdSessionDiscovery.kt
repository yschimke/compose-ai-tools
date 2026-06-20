package ee.schimke.composeai.clients.wear

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import ee.schimke.composeai.clients.discovery.DiscoveredSession
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Browses the LAN for `compose-preview serve` instances via Android NSD (mDNS/DNS-SD), surfacing
 * resolved [DiscoveredSession]s in [sessions]. Same contract as the phone app — the watch typically
 * receives a forwarded link, but on-network discovery lets a standalone watch find a server too.
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
      nsd.discoverServices(DiscoveredSession.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
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
        fun txt(key: String): String? = attrs[key]?.let { String(it, StandardCharsets.UTF_8) }
        found[serviceInfo.serviceName] =
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
        _sessions.value = found.values.toList()
      }
    }
}
