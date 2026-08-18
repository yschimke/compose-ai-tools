package ee.schimke.composeai.rcplayer.compose

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.ComposeUIViewController
import ee.schimke.composeai.rcplayer.protocol.RcDocumentCodec
import ee.schimke.composeai.rcplayer.protocol.RcOperationProfiles
import ee.schimke.composeai.rcplayer.runtime.RcPlayerEvent
import platform.UIKit.UIViewController

/**
 * Thin UIKit host for the common CMP player. The `.rc` bytes remain owned by the caller.
 *
 * The default was the wire constant `RcTheme.UNSPECIFIED` while the Compose entry point defaulted
 * to `RcTheme.SYSTEM`; both now spell the same thing as [RcPlayerTheme.System], which is what they
 * always resolved to — `rcResolveSystemTheme` answers both from `isSystemInDarkTheme()`.
 * `RcPlayerThemeRenderTest` asserts the three spellings resolve identically inside a real
 * composition rather than trusting that reading.
 *
 * **Reaching this from Swift needs an XCFramework, which is not published yet — see #4068.** This
 * entry point ships inside `ee.schimke.composeai:rc-player-compose`'s iOS klibs, so a Kotlin
 * Multiplatform consumer can call it today; a Swift-only project cannot until the framework is
 * packaged and distributed.
 */
public fun RcComposeViewController(
  bytes: ByteArray,
  theme: RcPlayerTheme = RcPlayerTheme.System,
  onEvent: (RcPlayerEvent) -> Unit = {},
  typefaces: RcTypefaceLoader = RcTypefaceLoader.Default,
  onError: (String) -> Unit = {},
): UIViewController {
  val document = runCatching {
    RcDocumentCodec.decode(bytes).also {
      it
        .composeSupportReport(
          RcOperationProfiles.CMP_IOS_ALPHA16,
          availableFontFamilies = typefaces.families,
        )
        .requireFullyRenderable()
    }
  }
    .getOrElse {
      onError(it.message ?: "Remote Compose document failed to load")
      return ComposeUIViewController {}
    }
  return ComposeUIViewController {
    RcComposePlayer(
      document,
      Modifier.fillMaxSize(),
      theme,
      onEvent = { event -> forwardIosPlayerEvent(onEvent, event) },
      typefaces = typefaces,
    )
  }
}

internal fun forwardIosPlayerEvent(onEvent: (RcPlayerEvent) -> Unit, event: RcPlayerEvent) {
  onEvent(event)
}
