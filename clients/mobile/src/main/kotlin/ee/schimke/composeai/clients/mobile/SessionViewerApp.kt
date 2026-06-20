package ee.schimke.composeai.clients.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.clients.SessionClient
import ee.schimke.composeai.clients.SessionLink
import ee.schimke.composeai.clients.SessionState
import ee.schimke.composeai.clients.SessionTarget
import ee.schimke.composeai.clients.discovery.DiscoveredSession

/**
 * The mobile app root. With a tapped [link] it goes straight into the live [SessionScreen]; with no
 * link it shows the [ConnectScreen] (paste a link / pick a discovered server). Material 3 throughout.
 */
@Composable
fun SessionViewerApp(
  link: SessionLink?,
  discoveredSessions: List<DiscoveredSession>,
  onConnect: (SessionLink) -> Unit,
  onDisconnect: () -> Unit,
) {
  Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
    if (link == null) {
      ConnectScreen(discoveredSessions = discoveredSessions, onConnect = onConnect)
    } else {
      SessionScreen(link = link, onDisconnect = onDisconnect)
    }
  }
}

/**
 * Drives one [SessionClient] for [link]: connects on entry, paints frames, forwards input, and tears
 * the session down when the user leaves. Overlays connection chrome until the first frame lands.
 */
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
  // Close the WebSocket when the screen leaves or the link changes (a new link `remember`s a fresh
  // client), so we don't leak the old session's coroutine + server-side hold.
  DisposableEffect(link) { onDispose { client.close("left session screen") } }

  Box(Modifier.fillMaxSize().background(Color.Black)) {
    FrameCanvas(frame = frame, modifier = Modifier.fillMaxSize(), onInput = { client.send(it) })

    // Connection chrome — shown until a frame is painted, or on failure.
    if (frame == null || state is SessionState.Failed || state is SessionState.Closed) {
      StatusOverlay(state = state, target = link.target, onDismiss = onDisconnect)
    }
  }
}

@Composable
private fun StatusOverlay(
  state: SessionState,
  target: SessionTarget,
  onDismiss: () -> Unit,
) {
  Box(
    Modifier.fillMaxSize().background(Color(0xCC101014)).padding(24.dp),
    contentAlignment = Alignment.Center,
  ) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
      when (state) {
        is SessionState.Connecting,
        SessionState.Idle -> {
          CircularProgressIndicator()
          Text("Connecting to ${target.label}…", color = Color.White, style = MaterialTheme.typography.titleMedium)
        }
        is SessionState.Connected -> {
          CircularProgressIndicator()
          Text("Waiting for first frame…", color = Color.White, style = MaterialTheme.typography.titleMedium)
        }
        is SessionState.Failed -> {
          Text("Couldn't connect", color = Color.White, style = MaterialTheme.typography.titleLarge)
          Text(state.message, color = Color(0xFFFFB4AB), style = MaterialTheme.typography.bodyMedium)
        }
        is SessionState.Closed -> {
          Text("Session closed", color = Color.White, style = MaterialTheme.typography.titleLarge)
          Text(state.reason, color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodyMedium)
        }
      }
      TextButton(onClick = onDismiss) { Text("Back") }
    }
  }
}

/**
 * The no-link landing screen: paste any session link, or tap a server discovered on the LAN via
 * mDNS. Discovery never carries the token, so a discovered server still needs a link/token to open —
 * the field is pre-filled with a `composeai://` skeleton for that server when one is tapped.
 */
@Composable
fun ConnectScreen(discoveredSessions: List<DiscoveredSession>, onConnect: (SessionLink) -> Unit) {
  var text by remember { mutableStateOf("") }
  val parsed = remember(text) { SessionLink.parse(text) }

  Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
    Text("Compose Session Viewer", style = MaterialTheme.typography.headlineSmall)
    Text(
      "Tap a session link to open it, or paste one below.",
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedTextField(
      value = text,
      onValueChange = { text = it },
      label = { Text("Session link") },
      placeholder = { Text("composeai://session?host=…") },
      modifier = Modifier.fillMaxWidth(),
      singleLine = true,
    )
    Button(onClick = { parsed?.let(onConnect) }, enabled = parsed != null, modifier = Modifier.fillMaxWidth()) {
      Text(if (parsed != null) "Connect to ${parsed.target.label}" else "Connect")
    }

    if (discoveredSessions.isNotEmpty()) {
      Text("On this network", style = MaterialTheme.typography.titleMedium)
      LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(discoveredSessions) { session ->
          DiscoveredServerCard(session = session, onTap = {
            // Seed the field with everything but the token, which the user fills from their link.
            text = "composeai://session?host=${session.host}&port=${session.port}&preview=&token="
          })
        }
      }
    }
  }
}

@Composable
private fun DiscoveredServerCard(session: DiscoveredSession, onTap: () -> Unit) {
  Card(modifier = Modifier.fillMaxWidth()) {
    Column(Modifier.padding(16.dp)) {
      Text(session.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
      Text(
        "${session.moduleLabel ?: "preview server"} · ${session.host}:${session.port}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      TextButton(onClick = onTap) { Text("Use this server") }
    }
  }
}

// ---------------------------------------------------------------------------
// @Preview surfaces — visual evidence for the connect screen + status chrome (the live frame canvas
// needs a running session, so it's covered by the verify/e2e path, not a static render).
// ---------------------------------------------------------------------------

@Preview(name = "Connect — empty", showBackground = true, widthDp = 360, heightDp = 720)
@Composable
private fun ConnectScreenEmptyPreview() {
  MaterialTheme { Surface { ConnectScreen(discoveredSessions = emptyList(), onConnect = {}) } }
}

@Preview(name = "Connect — discovered", showBackground = true, widthDp = 360, heightDp = 720)
@Composable
private fun ConnectScreenDiscoveredPreview() {
  val sessions =
    listOf(
      DiscoveredSession("compose-preview :samples:android", "192.168.1.20", 7341, ":samples:android"),
      DiscoveredSession("compose-preview :samples:wear", "192.168.1.21", 7342, ":samples:wear"),
    )
  MaterialTheme { Surface { ConnectScreen(discoveredSessions = sessions, onConnect = {}) } }
}

@Preview(name = "Status — connecting", showBackground = true, widthDp = 360, heightDp = 720)
@Composable
private fun ConnectingOverlayPreview() {
  MaterialTheme {
    StatusOverlay(
      state = SessionState.Connecting(SAMPLE_LINK),
      target = SAMPLE_LINK.target,
      onDismiss = {},
    )
  }
}

@Preview(name = "Status — failed", showBackground = true, widthDp = 360, heightDp = 720)
@Composable
private fun FailedOverlayPreview() {
  MaterialTheme {
    StatusOverlay(
      state = SessionState.Failed("Connection refused (is `serve` running?)"),
      target = SAMPLE_LINK.target,
      onDismiss = {},
    )
  }
}

private val SAMPLE_LINK =
  SessionLink("192.168.1.20", 7341, "tok", SessionTarget.Preview("com.example.HomeScreen"))
