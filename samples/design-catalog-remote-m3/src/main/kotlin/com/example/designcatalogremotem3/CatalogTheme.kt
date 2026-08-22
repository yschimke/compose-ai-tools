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
import androidx.wear.compose.remote.material3.RemoteMaterialTheme
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
 *
 * **The recorded documents are default-themed**, which is what lets a theme be applied to them
 * afterwards by overriding named values (`USER:WearM3.<role>`) — see `RemoteThemeCatalogs.kt`. That
 * falls out of how they are recorded rather than needing enforcement here:
 * `composePreviewRenderAll` renders each `@Preview` with no provider, so [LocalRemoteCatalogTheme]
 * is null and the branch below takes the un-themed path. A capture with a theme baked in would
 * carry that theme's colours as constants, so every theme would need its own capture and a
 * published catalog could only show the one it was packed with.
 *
 * The themed branch is for the other case: a **recomposing** session asked for `?themeProvider=`,
 * so the renderer wraps the preview in a provider and this installs the scheme for that render. It
 * reads the same [remoteCatalogThemeColors] map the replay path seeds, so the two lanes cannot
 * disagree about what a theme is.
 *
 * Only the colour scheme is installed. The typeface is not a document property — the stickers emit
 * the built-in default family id and the player resolves it at draw time — so it stays data for a
 * lane to configure its resolver with rather than something captured.
 */
@Composable
fun RemoteSticker(content: @Composable @RemoteComposable () -> Unit) {
  val themeName = LocalRemoteCatalogTheme.current
  RemoteOverridablePreview(profile = RcPlatformProfiles.ANDROIDX) {
    if (themeName == null) {
      RemoteBox(
        modifier = RemoteModifier.fillMaxSize(),
        contentAlignment = RemoteAlignment.Center,
        content = content,
      )
    } else {
      RemoteMaterialTheme(
        colorScheme = remoteCatalogColorScheme(themeName, RemoteMaterialTheme.colorScheme)
      ) {
        RemoteBox(
          modifier = RemoteModifier.fillMaxSize(),
          contentAlignment = RemoteAlignment.Center,
          content = content,
        )
      }
    }
  }
}

/**
 * The catalog's Remote Compose **component** multipreview. A single 227×100 capture. Remote Compose
 * has no light/dark theme split of its own — the document carries explicit colours — so this is the
 * one primary mode. Those colours come from `RemoteMaterialTheme`, the dark-first Wear Compose
 * Material 3 scheme, so the one mode is **dark**: like the Wear stickers these rasterise onto
 * transparency (`showBackground = false`), and the content is light-on-nothing. The catalog is
 * tagged to match — `modes: ["dark"]` + `display.surface: "dark"` in `catalog.spec.json` — so the
 * preview server backs the sheet on a dark stage instead of washing a white `RemoteIcon` /
 * `RemoteText` out on the default white one.
 *
 * **The frame is component-shaped, and that is load-bearing.** It was 200×200 — a square, for
 * components that are mostly 150×52dp buttons — which published each sticker as ~15% content and
 * ~85% transparency, and handed the cross-system compare page a 1:1 capture to set beside a kit
 * cell that is 172×52. The comparison squashes one into the other's aspect before it diffs, so the
 * frame alone was a difference on every row. 227dp wide is the Wear canvas the `wear-m3-catalog`
 * sibling renders on (its device-less previews are retargeted to 227dp @ 2.0x), so a component that
 * fills its width — a card, a line of text — now measures the same as its parallel instead of being
 * stretched to an arbitrary 320. 100dp tall clears the tallest thing in this class (`IconRemote`,
 * at 76dp) with room to spare.
 *
 * Bump the height if a component needs more room than that — and check the render, because this
 * frame is a *measuring bound*: a width-filling component re-wraps when it changes, so a taller
 * frame is free but a narrower one is not.
 *
 * A component that positions itself against the display edge wants [CatalogRemoteDisplay] instead,
 * and one whose content IS the canvas wants [CatalogRemoteCanvas]: what decides is the shape of the
 * kit cell the render is compared against.
 *
 * The render density is declared here in the **preview configuration** rather than left to the
 * default (~2.625, a phone density). A Remote Compose document is authored for a target density,
 * and this catalog mirrors **Wear** Compose Material 3, so `dpi=320` pins it to **density 2.0** —
 * the same scale as the `design-catalog-wear-m3` sibling (`227dp → 454px`). A `spec:` device sets
 * size + density with no device frame, so the transparent centred-sticker contract is
 * unchanged; #2760 stamps this density into the captured `.rc` so the player replays the dp-typed
 * size modifiers at the same scale.
 */
@Preview(showBackground = false, device = "spec:width=227dp,height=100dp,dpi=320")
annotation class CatalogRemoteModes

/**
 * A taller single-capture multipreview for the components that need more room than a single button
 * — cards, the app card, a button group, the TimeText strip, and the theme (typography / colour)
 * specimens. Same transparent, single-dark-mode contract as [CatalogRemoteModes] (including the
 * `dpi=320` density-2.0 pin and the 227dp Wear canvas width); only the height differs.
 *
 * 200dp tall, against a measured tallest of 182dp (`AppCardRemote`, once it carries the kit's card
 * copy). That is deliberately not much headroom: these are the width-filling components, so a
 * taller frame costs transparency on every card while an 18dp margin is enough for the copy this
 * catalog publishes. Lengthen a card's strings and re-render before assuming it still fits.
 */
@Preview(showBackground = false, device = "spec:width=227dp,height=200dp,dpi=320")
annotation class CatalogRemoteLarge

/**
 * The **screen** canvas: a full Wear watch face's worth of room (227×227dp — the `largeRound`
 * breakpoint the `design-catalog-wear-m3` sibling fans its templates across), at the same `dpi=320`
 * density-2.0 pin as the component stickers.
 *
 * Unlike the component stickers, a screen template paints its own surface (see
 * [com.example.designcatalogremotem3.WatchScreenRemote]) rather than rasterising onto transparency:
 * a screen IS a background plus its content, and the whole point of the capture is to read as a
 * real watch screen rather than a floating component. `showBackground = false` therefore still
 * holds — the fill comes from the document, not the preview frame.
 */
@Preview(showBackground = false, device = "spec:width=227dp,height=227dp,dpi=320")
annotation class CatalogRemoteScreen

/**
 * The frame for a sticker whose content **is** the canvas rather than something centred on it —
 * today just the document-level shader fill, which paints the whole document and therefore takes
 * the shape of whatever frame it is given.
 *
 * It exists so the component frame can be sized to the components. [CatalogRemoteModes] is 100dp
 * tall because the tallest thing it holds is 91dp; a canvas-filling sticker in that frame would
 * simply be published as a 227×100 letterbox, which says nothing about the shader and is not a
 * shape any kit cell has either. Square keeps it reading as a swatch.
 */
@Preview(showBackground = false, device = "spec:width=200dp,height=200dp,dpi=320")
annotation class CatalogRemoteCanvas

/**
 * The **display-cell** frame: a full round watch face's worth of square canvas (227×227dp at the
 * same density-2.0 pin), for a component that positions itself against the display edge rather than
 * wrapping its content.
 *
 * This is the Remote counterpart of the Wear sibling's `FullScreenSticker`, and what decides it is
 * the same thing: the SHAPE of the kit cell the render is compared against. The kit publishes its
 * indicators — the page indicators, the circular progress rail — as `192×192` *display* cells, the
 * round face whole, and `wear-m3-catalog` accordingly renders them 384×384 square where it renders
 * a button 272×136. A rail is a curve struck against the bezel: give it a squat component frame and
 * it does not merely sit in too much space, it lays itself out against the wrong edge and shrinks.
 * That is measurable — moving the interactive page indicator into a 227×100 frame collapsed it from
 * 8.2% of its capture to 1.6%.
 *
 * Distinct from [CatalogRemoteScreen], which is the same size for a different reason: a screen
 * template paints its own surface and IS the watch face, where these rasterise onto transparency
 * like every other component sticker.
 */
@Preview(showBackground = false, device = "spec:width=227dp,height=227dp,dpi=320")
annotation class CatalogRemoteDisplay
