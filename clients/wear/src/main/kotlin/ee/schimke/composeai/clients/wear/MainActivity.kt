package ee.schimke.composeai.clients.wear

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import ee.schimke.composeai.clients.KtorStreamTransport
import ee.schimke.composeai.clients.SessionLink
import ee.schimke.composeai.clients.StreamTransport
import ee.schimke.composeai.clients.discovery.DiscoveredSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/** The default transport: a Ktor WebSocket client over the OkHttp engine. */
fun defaultTransportFactory(): StreamTransport.Factory = KtorStreamTransport.factory

/** Single watch activity. A tapped/forwarded `composeai://` link opens straight into the session. */
class MainActivity : ComponentActivity() {

  private val link = MutableStateFlow<SessionLink?>(null)
  private val discovered = MutableStateFlow<List<DiscoveredSession>>(emptyList())
  private val discovery by lazy { NsdSessionDiscovery(applicationContext) }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    link.value = resolveLink(intent)
    discovery.sessions.onEach { discovered.value = it }.launchIn(lifecycleScope)

    setContent {
      val current by link.collectAsState()
      val sessions by discovered.collectAsState()
      SessionViewerApp(
        link = current,
        discoveredSessions = sessions,
        onConnect = { link.value = it },
        onDisconnect = { link.value = null },
      )
    }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    resolveLink(intent)?.let { link.value = it }
  }

  override fun onResume() {
    super.onResume()
    discovery.start()
  }

  override fun onPause() {
    discovery.stop()
    super.onPause()
  }

  private fun resolveLink(intent: Intent?): SessionLink? =
    if (intent?.action == Intent.ACTION_VIEW) SessionLink.parse(intent.dataString) else null
}
