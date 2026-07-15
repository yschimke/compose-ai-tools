package com.example.designcatalogwearm3

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Typography
import ee.schimke.composeai.overrides.previewOverrideString

/**
 * The catalog's **component** sticker frame: a single component wrapped in the stock Wear
 * [MaterialTheme] on a **transparent** background, cropped tight to the component. Transparency
 * lets a designer drop the sticker onto any canvas; the `compose/theme` tokens the renderer
 * extracts still come from the real Wear Material 3 system (read from the theme, not the pixels).
 * Full-screen components use [FullScreenWear] instead, which keeps the black round device shape.
 *
 * The content is **centred** in the pinned Wear canvas: a device-less Wear sticker is pinned to a
 * fixed 227dp square (by `PreviewDiscovery.retargetWearStickers`, so fill-width components size to
 * the watch screen and dp→px stays 2.0×), which means a wrap-content component (a button, the
 * progress ring) would otherwise sit at the frame's top-left. Centring places it mid-canvas — the
 * content-cropped figma-svg export and the content-bbox fidelity score are unaffected (both crop to
 * the component), so this only moves where the component lands in the full-frame render PNG.
 *
 * TLC item scaling is shown separately (see `CardScalingPreview.kt`), which hosts a component in a
 * real `TransformingLazyColumn` via `:wear-preview-runtime`; a plain sticker here is unchanged.
 */
@Composable
fun WearSticker(content: @Composable () -> Unit) {
  WearCatalogTheme {
    Box(Modifier.fillMaxSize().padding(8.dp), contentAlignment = Alignment.Center) { content() }
  }
}

/**
 * The Wear catalog theme, with the typeface and palette read from the override surface so the
 * preview server can re-skin any sticker (`knob.theme.font` / `knob.theme.colors`) without a preview
 * change — the previews stay clean. Absent an override both resolve to the Wear M3 default, so an
 * un-overridden render is pixel-identical. The choices are the declared `@TypographyCatalog` /
 * `@ColorCatalog` names in `WearCatalogFonts.kt`; this is the one place that maps a selected name to
 * its family / scheme.
 *
 * The whole Wear type scale is re-pointed in one call via `Typography(defaultFontFamily = …)`; the
 * palette re-tints the default Wear scheme.
 */
@Composable
fun WearCatalogTheme(content: @Composable () -> Unit) {
  val font = wearCatalogFont(previewOverrideString("theme.font", "Roboto Flex"))
  val colorScheme = wearColorScheme(previewOverrideString("theme.colors", "M3"), MaterialTheme.colorScheme)
  MaterialTheme(typography = Typography(defaultFontFamily = font), colorScheme = colorScheme) {
    content()
  }
}

/** Resolves a selected typeface [name] (a declared `@TypographyCatalog` label) to its [FontFamily]. */
fun wearCatalogFont(name: String): FontFamily =
  when (name) {
    "Google Sans Flex" -> GoogleSansFlex
    "Lobster Two" -> LobsterTwo
    else -> RobotoFlex
  }

/**
 * Resolves a selected palette [name] to a Wear [ColorScheme]: `"M3"` keeps the default [base]; the
 * brand palettes re-tint it. Copying [base] keeps every other Wear role intact.
 */
fun wearColorScheme(name: String, base: ColorScheme): ColorScheme =
  when (name) {
    "Coral" -> base.copy(primary = Color(0xFFFF6F61), secondary = Color(0xFFFFB4A9))
    "Teal" -> base.copy(primary = Color(0xFF4DD0E1), secondary = Color(0xFF80CBC4))
    else -> base
  }

/**
 * The catalog's **component** multipreview: a single transparent capture, cropped to the component
 * (no device frame — that's for full-screen components, see [CatalogWearBreakpoints]).
 * `showBackground = false` keeps the background transparent so the sticker carries alpha.
 */
@Preview(showBackground = false) annotation class CatalogWearModes

/**
 * Frame for **full-screen** Wear components (scaffolds, lists, the EdgeButton) — as opposed to the
 * centred component [WearSticker]. The Wear dark [MaterialTheme] fills the round display black and
 * [AppScaffold] supplies the screen structure; `timeText = {}` drops the status clock so the
 * capture is deterministic (a live clock would churn the weekly design-artifacts bundle). The
 * content supplies its own `ScreenScaffold`.
 */
@Composable
fun FullScreenWear(content: @Composable () -> Unit) {
  WearCatalogTheme { AppScaffold(timeText = {}) { content() } }
}

/**
 * Frame for the **scaffold templates** — full-screen skeletons an app copies whole (list screen
 * with a status strip, pager, edge-button screen). Unlike [FullScreenWear] it does *not* supply the
 * [AppScaffold]/`timeText`: a template composes its own `AppScaffold(timeText = { … })` so the
 * curved [TimeText] status strip it demonstrates is part of the capture. This wrapper is just the
 * Wear dark [MaterialTheme] filling the round display black (the [CatalogWearBreakpoints] device
 * previews paint the black background).
 */
@Composable
fun WearScaffoldTemplate(content: @Composable () -> Unit) {
  WearCatalogTheme { content() }
}

/**
 * Full-screen **size-breakpoint** multipreview: the three round Wear screen sizes a layout must
 * adapt to — 192 dp (small round), 227 dp (large round), and 240 dp (extra-large round) — each
 * black on the device shape. Stack on a full-screen component (placed via [FullScreenWear] +
 * `ScreenScaffold`) to capture it at each breakpoint, mirroring how the official Wear samples
 * verify a screen across sizes.
 *
 * All three are **direct** `@Preview`s rather than the nested `@WearPreviewSmallRound` /
 * `@WearPreviewLargeRound` aliases: `PreviewDiscovery.resolveMultiPreview` returns an annotation
 * class's direct previews without recursing into nested multipreviews, so a mix would silently drop
 * the nested 192/227 and render only the last. All three use the Wear tooling **device ids**
 * (192/227/240, round, 2.0×) — the render pipeline only exercises named-id devices, not custom
 * `spec:` strings.
 */
@Preview(
  name = "Small Round",
  group = "Devices - Small Round",
  device = "id:wearos_small_round",
  showBackground = true,
  backgroundColor = 0xFF000000,
)
@Preview(
  name = "Large Round",
  group = "Devices - Large Round",
  device = "id:wearos_large_round",
  showBackground = true,
  backgroundColor = 0xFF000000,
)
@Preview(
  name = "Extra Large Round",
  group = "Devices - Extra Large Round",
  device = "id:wearos_xl_round",
  showBackground = true,
  backgroundColor = 0xFF000000,
)
annotation class CatalogWearBreakpoints
