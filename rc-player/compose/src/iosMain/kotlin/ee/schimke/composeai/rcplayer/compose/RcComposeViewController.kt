package ee.schimke.composeai.rcplayer.compose

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.ComposeUIViewController
import ee.schimke.composeai.rcplayer.protocol.RcTheme
import platform.UIKit.UIViewController

/** Thin UIKit host for the common CMP player. The `.rc` bytes remain owned by the caller. */
public fun RcComposeViewController(
  bytes: ByteArray,
  theme: Int = RcTheme.UNSPECIFIED,
): UIViewController = ComposeUIViewController {
  RcComposePlayer(bytes, Modifier.fillMaxSize(), theme)
}
