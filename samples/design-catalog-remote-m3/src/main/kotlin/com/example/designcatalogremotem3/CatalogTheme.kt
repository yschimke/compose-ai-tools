@file:Suppress("RestrictedApiAndroidX")

package com.example.designcatalogremotem3

import androidx.compose.remote.creation.compose.layout.RemoteAlignment
import androidx.compose.remote.creation.compose.layout.RemoteBox
import androidx.compose.remote.creation.compose.layout.RemoteComposable
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.fillMaxSize
import androidx.compose.remote.creation.profile.RcPlatformProfiles
import androidx.compose.remote.tooling.preview.RemotePreview
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview

/**
 * The catalog's Remote Compose **component** sticker frame: the remote content,
 * centred inside a full-size `RemoteBox`, built into a `RemoteDocument` by
 * [RemotePreview] and rasterised by the Remote Compose player — the same byte
 * stream path a watch face / tile / widget takes on-device.
 * `RcPlatformProfiles.ANDROIDX` is the render profile the AndroidX tooling uses.
 *
 * Uses the `RemotePreview { … }`-inside-the-preview shape (Approach 1 in
 * `:samples:remotecompose`) so it renders today without the `@PreviewWrapper`
 * tooling annotation, which only exists in compose-ui 1.11.0-beta+ and isn't yet
 * understood by the discovery pipeline paired with stable Compose.
 */
@Composable
fun RemoteSticker(content: @Composable @RemoteComposable () -> Unit) {
  RemotePreview(profile = RcPlatformProfiles.ANDROIDX) {
    RemoteBox(
      modifier = RemoteModifier.fillMaxSize(),
      contentAlignment = RemoteAlignment.Center,
      content = content,
    )
  }
}

/**
 * The catalog's Remote Compose **component** multipreview. A single 200×200
 * capture on a solid background: unlike the M3 / Wear stickers (transparent,
 * `showBackground = false`), the Remote Compose player paints onto its own
 * opaque canvas and has no light/dark theme split of its own (the document
 * carries explicit colours), so this is the one primary mode — matching the
 * `remote-m3` spec's single `light` mode. Bump `widthDp` / `heightDp` if a
 * component needs more room than a single button.
 */
@Preview(showBackground = true, widthDp = 200, heightDp = 200)
annotation class CatalogRemoteModes
