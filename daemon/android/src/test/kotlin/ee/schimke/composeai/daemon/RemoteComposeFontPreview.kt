@file:Suppress("RestrictedApiAndroidX")

package ee.schimke.composeai.daemon

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.remote.player.core.RemoteDocument
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import ee.schimke.composeai.rcembedded.player.ExperimentalRemoteDocumentPlayer
import java.util.Base64

/** Real Wear Material 3 Remote Compose document from the issue #4935 reproduction. */
@Composable
fun RemoteComposeRobotoFlexCard() {
  val bytes = remember {
    val encoded =
      checkNotNull(
          RemoteDocument::class
            .java
            .getResourceAsStream("/remote-compose/wear-m3-roboto-flex-card.rc.b64")
        )
        .bufferedReader()
        .use { it.readText() }
    Base64.getMimeDecoder().decode(encoded)
  }
  val document = remember(bytes) { RemoteDocument(bytes) }
  ExperimentalRemoteDocumentPlayer(document = document, modifier = Modifier.fillMaxSize())
}
