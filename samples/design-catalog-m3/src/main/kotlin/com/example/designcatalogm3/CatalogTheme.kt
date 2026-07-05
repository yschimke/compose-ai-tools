package com.example.designcatalogm3

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.designcatalogm3.shared.LocalGenericFonts
import com.example.designcatalogm3.shared.catalogTypography

/**
 * The catalog's theme wrapper. Each sticker is a stock [MaterialTheme] — the default light/dark
 * `colorScheme` — so the `compose/theme` token set the renderer extracts is the **real** Material 3
 * system, not a bespoke palette. A uniform 16dp [padding] frames every sticker so the sheet reads
 * cleanly and the layout (semantics) variant has breathing room around the component.
 *
 * The type scale is re-pointed at [Roboto] and the generic families are supplied via
 * [LocalGenericFonts], so the desktop (Skiko) render rasterises the **same faces** the Android
 * Robolectric render used to — and the same the in-browser wasm tier fetches — keeping the baked
 * stickers stable across the Android→CMP renderer switch (Skiko's own default is not Roboto).
 */
@Composable
fun CatalogSticker(content: @Composable () -> Unit) {
  val dark = isSystemInDarkTheme()
  CompositionLocalProvider(LocalGenericFonts provides CatalogGenericFonts) {
    MaterialTheme(
      colorScheme = if (dark) darkColorScheme() else lightColorScheme(),
      typography = catalogTypography(Roboto),
    ) {
      Surface { Box(Modifier.padding(16.dp)) { content() } }
    }
  }
}

/**
 * The catalog's primary-mode multipreview: every component is rendered in both light and dark, the
 * two modes M3 ships. Stacking this annotation on a composable yields the `· Light` / `· Dark`
 * captures the sticker sheet pairs. Further modes (states, breakpoints) are added per-component
 * with extra `@Preview`s where they matter.
 *
 * `uiMode = 32` is the raw value of Android's `Configuration.UI_MODE_NIGHT_YES` — the CMP desktop
 * source set has no `android.content.res.Configuration`, so (as `:samples:cmp` does) the bit is
 * written directly; the renderer treats `uiMode` as an int and flips `isSystemInDarkTheme()`.
 */
@Preview(name = "Light", showBackground = true, group = "modes")
@Preview(name = "Dark", showBackground = true, uiMode = 32, group = "modes")
annotation class CatalogModes

/**
 * Frame for **full-screen scaffold templates** — as opposed to the centred component
 * [CatalogSticker]. Just the stock [MaterialTheme] filling the device with the `background`
 * surface; the template supplies its own `Scaffold` and drives the system-bar spacing through
 * window insets (see [SYSTEM_BAR_INSET]).
 */
@Composable
fun FullScreenM3(content: @Composable () -> Unit) {
  val dark = isSystemInDarkTheme()
  MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
    Surface(Modifier.fillMaxSize()) { content() }
  }
}

/**
 * Height of the renderer's synthetic status / navigation bars (`SystemBarsFrame` draws both at
 * 24dp). The render environment has no real window insets behind that overlay, so a template feeds
 * this height to its `Scaffold`/`TopAppBar` `windowInsets` — reproducing a real edge-to-edge M3
 * scaffold (the app bar paints under the status bar with its title below the OS clock; content and
 * the FAB clear the gesture pill) rather than an outer padding that pushes the scaffold into a
 * band.
 */
val SYSTEM_BAR_INSET = 24.dp

/**
 * Full-screen template multipreview: a phone (`id:pixel_8`) with `showSystemUi = true` so the
 * capture carries the synthetic OS status + nav chrome, in both light and dark. The desktop
 * renderer honours `device` + `showSystemUi` (see `:samples:cmp`'s Pixel-8 preview), so this frames
 * the CMP template exactly as the Android render did.
 */
@Preview(name = "Light", device = "id:pixel_8", showSystemUi = true, group = "template")
@Preview(name = "Dark", device = "id:pixel_8", showSystemUi = true, uiMode = 32, group = "template")
annotation class CatalogTemplate

// --- Fonts, loaded once from the bundled faces under src/main/resources/fonts/. ---
// The same TTFs the wasm tier vendors, so desktop render + in-browser tier + the historical Android
// baked stickers all share one typeface. `androidx.compose.ui.text.platform.Font(identity, data)`
// is the desktop/Skiko overload (the same one the wasm app uses).

private fun fontBytes(name: String): ByteArray =
  object {}.javaClass.getResourceAsStream("/fonts/$name")?.readBytes()
    ?: error("catalog font resource missing: fonts/$name")

/** Roboto — the M3 default sans, re-pointed onto the whole type scale via [catalogTypography]. */
val Roboto: FontFamily =
  FontFamily(
    Font("Roboto-Regular", fontBytes("Roboto-Regular.ttf"), FontWeight.Normal, FontStyle.Normal),
    Font("Roboto-Medium", fontBytes("Roboto-Medium.ttf"), FontWeight.Medium, FontStyle.Normal),
  )

/**
 * Generic-family substitutes keyed by the name `genericFontFamily(...)` looks up — the same files
 * the platform's system font table maps `serif` / `monospace` to (Noto Serif / Droid Sans Mono).
 */
val CatalogGenericFonts: Map<String, FontFamily> =
  mapOf(
    "serif" to FontFamily(Font("NotoSerif-Regular", fontBytes("NotoSerif-Regular.ttf"))),
    "monospace" to FontFamily(Font("DroidSansMono", fontBytes("DroidSansMono.ttf"))),
  )
