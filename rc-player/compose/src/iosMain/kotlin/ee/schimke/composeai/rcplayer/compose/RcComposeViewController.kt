package ee.schimke.composeai.rcplayer.compose

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.ComposeUIViewController
import ee.schimke.composeai.rcplayer.protocol.RcDocumentCodec
import ee.schimke.composeai.rcplayer.protocol.RcOperationProfiles
import ee.schimke.composeai.rcplayer.protocol.RcTheme
import ee.schimke.composeai.rcplayer.runtime.RcPlayerEvent
import platform.UIKit.UIViewController

/** Thin UIKit host for the common CMP player. The `.rc` bytes remain owned by the caller. */
public fun RcComposeViewController(
  bytes: ByteArray,
  theme: Int = RcTheme.UNSPECIFIED,
  onEvent: (RcPlayerEvent) -> Unit = {},
): UIViewController {
  val document =
    RcDocumentCodec.decode(bytes).also {
      it.composeSupportReport(RcOperationProfiles.CMP_IOS_ALPHA16).requireFullyRenderable()
    }
  return ComposeUIViewController {
    RcComposePlayer(document, Modifier.fillMaxSize(), theme, onEvent = onEvent)
  }
}
