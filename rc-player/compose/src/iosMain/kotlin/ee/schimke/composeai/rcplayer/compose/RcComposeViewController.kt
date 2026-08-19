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
 * **From Swift this is a file-facade call, not a global function.** Kotlin/Native exports top-level
 * declarations as static members of a class named after their file, so a Swift consumer writes
 * `RcComposeViewControllerKt.RcComposeViewController(bytes:theme:onEvent:typefaces:onError:)` —
 * with all five arguments, since Objective-C has no defaults — and has to copy its `Data` into a
 * `KotlinByteArray` by hand. docs/design/RC_PLAYER_SWIFT.md writes both out. The framework ships as
 * `RcComposePlayer.xcframework.zip` on each GitHub Release, addressed by `Package.swift` (#4068);
 * Kotlin Multiplatform consumers reach the same function through the published iOS klibs.
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
