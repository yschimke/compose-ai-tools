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
 * `RemoteDocument` into the bundle's `.rc` sidecar for replay. With no seeded overrides (the
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
 * The catalog's Remote Compose **component** multipreview. A single 200×200 capture. Remote Compose
 * has no light/dark theme split of its own — the document carries explicit colours — so this is the
 * one primary mode. Those colours come from `RemoteMaterialTheme`, the dark-first Wear Compose
 * Material 3 scheme, so the one mode is **dark**: like the Wear stickers these rasterise onto
 * transparency (`showBackground = false`), and the content is light-on-nothing. The catalog is
 * tagged to match — `modes: ["dark"]` + `display.surface: "dark"` in `catalog.spec.json` — so the
 * preview server backs the sheet on a dark stage instead of washing a white `RemoteIcon` /
 * `RemoteText` out on the default white one. Bump the device-spec `width` / `height` if a component
 * needs more room than a single button.
 *
 * The render density is declared here in the **preview configuration** rather than left to the
 * default (~2.625, a phone density). A Remote Compose document is authored for a target density, and
 * this catalog mirrors **Wear** Compose Material 3, so `dpi=320` pins it to **density 2.0** — the
 * same scale as the `design-catalog-wear-m3` sibling (`227dp → 454px`). A `spec:` device sets size +
 * density with no device frame, so the transparent centred-sticker contract is unchanged; #2760
 * stamps this density into the captured `.rc` so the player replays the dp-typed size modifiers at
 * the same scale.
 */
@Preview(showBackground = false, device = "spec:width=200dp,height=200dp,dpi=320")
annotation class CatalogRemoteModes

/**
 * A larger single-capture multipreview for the components that need more room than a single button —
 * cards, the app card, a button group, the TimeText strip, and the theme (typography / colour)
 * specimens. Same transparent, single-dark-mode contract as [CatalogRemoteModes] (including the
 * `dpi=320` density-2.0 pin, matching Wear); only the canvas is bigger so the content isn't clipped
 * by the 200×200 frame.
 */
@Preview(showBackground = false, device = "spec:width=320dp,height=240dp,dpi=320")
annotation class CatalogRemoteLarge
