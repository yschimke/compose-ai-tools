package ee.schimke.composeai.clients.wear

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import ee.schimke.composeai.clients.SessionClient
import ee.schimke.composeai.clients.SessionLink
import ee.schimke.composeai.clients.SessionState
import ee.schimke.composeai.clients.SessionTarget
import ee.schimke.composeai.clients.discovery.DiscoveredSession

/** Wear app root — straight into [SessionScreen] for a tapped link, else the [ConnectScreen]. */
@Composable
fun SessionViewerApp(
  link: SessionLink?,
  discoveredSessions: List<DiscoveredSession>,
  onConnect: (SessionLink) -> Unit,
  onDisconnect: () -> Unit,
) {
  MaterialTheme {
    if (link == null) {
      ConnectScreen(discoveredSessions, onConnect)
    } else {
      SessionScreen(link, onDisconnect)
    }
  }
}

@Composable
fun SessionScreen(
  link: SessionLink,
  onDisconnect: () -> Unit,
  clientFactory: () -> SessionClient = { SessionClient(defaultTransportFactory()) },
) {
  val client = remember(link) { clientFactory() }
  val state by client.state.collectAsState()
  val frame by client.frame.collectAsState()

  LaunchedEffect(link) { client.connect(link) }
  // Close the WebSocket when the screen leaves or the link changes, so the session doesn't leak.
  DisposableEffect(link) { onDispose { client.close("left session screen") } }

  Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
    FrameCanvas(frame = frame, modifier = Modifier.fillMaxSize(), onInput = { client.send(it) })
    if (frame == null || state is SessionState.Failed || state is SessionState.Closed) {
      StatusOverlay(state = state, target = link.target, onDismiss = onDisconnect)
    }
  }
}

@Composable
private fun StatusOverlay(state: SessionState, target: SessionTarget, onDismiss: () -> Unit) {
  Box(
    Modifier.fillMaxSize().background(Color(0xCC000000)).padding(16.dp),
    contentAlignment = Alignment.Center,
  ) {
    Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      val (title, detail) =
        when (state) {
          is SessionState.Connecting,
          SessionState.Idle -> "Connecting…" to target.label
          is SessionState.Connected -> "Loading…" to target.label
          is SessionState.Failed -> "Failed" to state.message
          is SessionState.Closed -> "Closed" to state.reason
        }
      Text(title, color = Color.White, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
      Text(detail, color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
      Button(onClick = onDismiss) { Text("Back") }
    }
  }
}

@Composable
fun ConnectScreen(discoveredSessions: List<DiscoveredSession>, onConnect: (SessionLink) -> Unit) {
  Column(
    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 24.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Text("Session Viewer", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
    if (discoveredSessions.isEmpty()) {
      Text(
        "Open a session link from your phone, or wait for a server on this network.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
      )
    } else {
      discoveredSessions.forEach { session ->
        Card(onClick = {}, modifier = Modifier.fillMaxWidth()) {
          Text(session.moduleLabel ?: session.name, style = MaterialTheme.typography.labelMedium)
          Text("${session.host}:${session.port}", style = MaterialTheme.typography.bodySmall)
        }
      }
    }
  }
}

// --- @Preview surfaces (visual evidence for the watch chrome) ---

private val SAMPLE = SessionLink("192.168.1.20", 7341, "tok", SessionTarget.Preview("HomeTile"))

@Preview(name = "Wear connecting", showBackground = true, backgroundColor = 0xFF000000, widthDp = 220, heightDp = 220)
@Composable
private fun WearConnectingPreview() {
  MaterialTheme { StatusOverlay(SessionState.Connecting(SAMPLE), SAMPLE.target, onDismiss = {}) }
}

@Preview(name = "Wear failed", showBackground = true, backgroundColor = 0xFF000000, widthDp = 220, heightDp = 220)
@Composable
private fun WearFailedPreview() {
  MaterialTheme { StatusOverlay(SessionState.Failed("No route to host"), SAMPLE.target, onDismiss = {}) }
}

@Preview(name = "Wear connect", showBackground = true, backgroundColor = 0xFF000000, widthDp = 220, heightDp = 220)
@Composable
private fun WearConnectPreview() {
  MaterialTheme { ConnectScreen(discoveredSessions = emptyList(), onConnect = {}) }
}
