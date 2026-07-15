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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.designcatalogm3.shared.LocalGenericFonts
import com.example.designcatalogm3.shared.LocalNamedFonts
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
  CompositionLocalProvider(
    LocalGenericFonts provides CatalogGenericFonts,
    LocalNamedFonts provides CatalogNamedFonts,
  ) {
    MaterialTheme(
      colorScheme = if (dark) darkColorScheme() else lightColorScheme(),
      typography = catalogTypography(CatalogDefaultFont),
    ) {
      // Component stickers render on a TRANSPARENT surface, so each one reads as a component
      // silhouette on whatever the viewer paints behind the transparent PNG (the preview server /
      // catalog index checkerboard, or the preview server's solid-surface backing). `contentColor =
      // onSurface` keeps text/icons themed so they stay readable against that backing.
      Surface(color = Color.Transparent, contentColor = MaterialTheme.colorScheme.onSurface) {
        Box(Modifier.padding(16.dp)) { content() }
      }
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
// No `showBackground` — the harness background stays transparent so a component sticker is a
// silhouette on the viewer's checkerboard. The sticker's own [CatalogSticker] surface is
// transparent too. The full-screen [CatalogTemplate] keeps its device background.
@Preview(name = "Light", group = "modes")
@Preview(name = "Dark", uiMode = 32, group = "modes")
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
 * Roboto Flex — the catalog's **default** typeface (see [CatalogDefaultFont]), the variable-font
 * evolution of [Roboto] and the face Material 3 now ships as its default sans. One variable TTF
 * (`RobotoFlex.ttf`, vendored under `resources/fonts/` from fonts.google.com's `ofl/robotoflex`)
 * carries the whole weight axis, so a single face backs the entire type scale; Skiko rasterises the
 * default (400) instance and synthesises the heavier scale steps.
 */
val RobotoFlex: FontFamily =
  FontFamily(Font("RobotoFlex", fontBytes("RobotoFlex.ttf"), FontWeight.Normal, FontStyle.Normal))

/**
 * Google Sans Flex — offered as a **named** downloadable-GoogleFont family (the same `role:
 * "named"` tier as [CatalogNamedFonts]'s Orbitron), so the catalog declares it as a selectable
 * typeface. It is **not** distributed on fonts.google.com (Google Sans is a Google-brand font), so
 * the branded TTF isn't vendored here; [optionalGoogleFontFamily] therefore degrades gracefully to
 * the platform sans until a `GoogleSansFlex.ttf` is dropped into `resources/fonts/`, at which point
 * the real face round-trips into the fonts manifest exactly like any other named GoogleFont. The
 * declaration (the choice) exists regardless — the point of the theme-override typeface catalog.
 */
val GoogleSansFlex: FontFamily =
  optionalGoogleFontFamily("Google Sans Flex", "GoogleSansFlex.ttf") ?: FontFamily.SansSerif

/** The catalog's default typeface: Roboto Flex, re-pointed onto the whole type scale. */
val CatalogDefaultFont: FontFamily = RobotoFlex

/**
 * Builds a named downloadable-GoogleFont [FontFamily] from a vendored face, or returns null when
 * the TTF isn't present (so a not-yet-vendored brand face degrades to a caller-chosen fallback
 * rather than failing the build — the same graceful degradation the fonts-manifest generator
 * assumes).
 */
private fun optionalGoogleFontFamily(name: String, file: String): FontFamily? =
  runCatching { FontFamily(googleFontFace(name, file, FontWeight.Normal)) }.getOrNull()

/**
 * Generic-family substitutes keyed by the name `genericFontFamily(...)` looks up — the same files
 * the platform's system font table maps `serif` / `monospace` to (Noto Serif / Droid Sans Mono).
 */
val CatalogGenericFonts: Map<String, FontFamily> =
  mapOf(
    "serif" to FontFamily(Font("NotoSerif-Regular", fontBytes("NotoSerif-Regular.ttf"))),
    "monospace" to FontFamily(Font("DroidSansMono", fontBytes("DroidSansMono.ttf"))),
  )

/**
 * Named downloadable-GoogleFont substitutes keyed by the GoogleFont display name
 * `namedFontFamily(…)` looks up — the desktop-render counterpart to the wasm tier's `role: "named"`
 * families, built from the branded TTFs vendored under `resources/fonts/` (`<slug>-<weight>.ttf`,
 * the exact filenames the fonts manifest expects).
 *
 * The face `identity` is deliberately the downloadable-GoogleFont label string
 * (`Font(GoogleFont("Orbitron", …), …)`) rather than a plain id: the daemon's font-usage recorder
 * reports a resolved face by its `identity`, and the export's manifest generator regexes the
 * GoogleFont display name back out of exactly that shape. So this desktop face round-trips into a
 * `role: "named"` manifest entry at export — the same family the wasm tier then fetches — with no
 * daemon-pipeline change. `namedFontFamily(...)` (sealed-resolver-safe lookup) is what a catalog
 * component says in place of the Android-only `FontFamily(Font(GoogleFont("Orbitron"), provider))`.
 */
val CatalogNamedFonts: Map<String, FontFamily> =
  mapOf(
    "Orbitron" to
      FontFamily(
        googleFontFace("Orbitron", "orbitron-400.ttf", FontWeight.Normal),
        googleFontFace("Orbitron", "orbitron-700.ttf", FontWeight.Bold),
      )
  )

/**
 * A desktop [Font] for a vendored downloadable-GoogleFont face, tagged with the GoogleFont label
 * `identity` the daemon recorder / manifest generator round-trip on (see [CatalogNamedFonts]).
 */
private fun googleFontFace(name: String, file: String, weight: FontWeight) =
  Font(
    identity =
      "Font(GoogleFont(\"$name\", bestEffort=true), weight=${weight.weight}, style=Normal)",
    data = fontBytes(file),
    weight = weight,
    style = FontStyle.Normal,
  )
