@file:Suppress("RestrictedApiAndroidX")

package com.example.designcatalogremotem3

import androidx.compose.remote.creation.compose.layout.RemoteAlignment
import androidx.compose.remote.creation.compose.layout.RemoteBox
import androidx.compose.remote.creation.compose.layout.RemoteComposable
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.fillMaxSize
import androidx.compose.remote.creation.profile.RcPlatformProfiles
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import ee.schimke.composeai.daemon.RemoteOverridablePreview

/**
 * The catalog's Remote Compose **component** sticker frame: the remote content, centred inside a
 * full-size `RemoteBox`, built into a `RemoteDocument` and rasterised by the Remote Compose player
 * — the same byte-stream path a watch face / tile / widget takes on-device.
 * `RcPlatformProfiles.ANDROIDX` is the render profile the AndroidX tooling uses.
 *
 * Captures through the connector's [RemoteOverridablePreview] rather than raw upstream
 * `RemotePreview`. It keeps the `RemotePreview { … }`-inside-the-preview shape (Approach 1 in
 * `:samples:remotecompose`, so it renders today without the `@PreviewWrapper` tooling annotation),
 * but additionally (a) applies any `renderNow.overrides.remoteCompose.namedValues` the daemon seeds
 * — so the named-value stickers ([com.example.designcatalogremotem3.NamedLabelRemoteButton],
 * [com.example.designcatalogremotem3.ShaderGradientSticker]) actually flip in trusted live
 * re-renders, matching what the spec/captions advertise — and (b) offers the captured
 * `RemoteDocument` into the bundle's `.rcdoc` sidecar for replay. With no seeded overrides (the
 * vanilla `composePreviewRenderAll` and the weekly design-artifacts render) it is the same output
 * as plain `RemotePreview`.
 */
@Composable
fun RemoteSticker(content: @Composable @RemoteComposable () -> Unit) {
  RemoteOverridablePreview(profile = RcPlatformProfiles.ANDROIDX) {
    RemoteBox(
      modifier = RemoteModifier.fillMaxSize(),
      contentAlignment = RemoteAlignment.Center,
      content = content,
    )
  }
}

/**
 * The catalog's Remote Compose **component** multipreview. A single 200×200 capture on a solid
 * background: unlike the M3 / Wear stickers (transparent, `showBackground = false`), the Remote
 * Compose player paints onto its own opaque canvas and has no light/dark theme split of its own
 * (the document carries explicit colours), so this is the one primary mode — matching the
 * `remote-m3` spec's single `light` mode. Bump `widthDp` / `heightDp` if a component needs more
 * room than a single button.
 */
@Preview(showBackground = true, widthDp = 200, heightDp = 200) annotation class CatalogRemoteModes
