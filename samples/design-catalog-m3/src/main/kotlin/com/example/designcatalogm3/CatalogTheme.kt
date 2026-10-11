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
import com.example.designcatalogm3.shared.CATALOG_COLORS_KNOB
import com.example.designcatalogm3.shared.CATALOG_FONTS_KNOB
import com.example.designcatalogm3.shared.CATALOG_FONT_GOOGLE_SANS_FLEX
import com.example.designcatalogm3.shared.CATALOG_FONT_KNOB
import com.example.designcatalogm3.shared.CATALOG_FONT_LOBSTER_TWO
import com.example.designcatalogm3.shared.CATALOG_FONT_ROBOTO_FLEX
import com.example.designcatalogm3.shared.CATALOG_PALETTE_M3
import com.example.designcatalogm3.shared.CATALOG_SHAPES_KNOB
import com.example.designcatalogm3.shared.CATALOG_TYPOGRAPHY_KNOB
import com.example.designcatalogm3.shared.LocalGenericFonts
import com.example.designcatalogm3.shared.LocalNamedFonts
import com.example.designcatalogm3.shared.catalogApplyFontFamilies
import com.example.designcatalogm3.shared.catalogApplyTypography
import com.example.designcatalogm3.shared.catalogColorScheme
import com.example.designcatalogm3.shared.catalogOverrideString
import com.example.designcatalogm3.shared.catalogShapes
import com.example.designcatalogm3.shared.catalogTypography
import com.example.designcatalogm3.shared.parseCatalogFontFamilies

/**
 * The catalog's theme wrapper: a stock [MaterialTheme] (so the extracted `compose/theme` tokens are
 * the real Material 3 system) with uniform 16dp [padding] around each sticker.
 *
 * The type scale uses [Roboto] and [LocalGenericFonts] supplies the generic families, so the Skiko
 * render uses the same faces as the wasm tier (Skiko's own default is not Roboto).
 */
@Composable
fun CatalogSticker(content: @Composable () -> Unit) {
  val dark = isSystemInDarkTheme()
  // Typeface and palette come from the override surface (`knob.theme.font` / `knob.theme.colors`),
  // so the preview server can re-skin any sticker without preview changes; absent an override they
  // resolve to Roboto Flex + M3. `@ThemeCatalog` + `themeProvider` (the wear catalog's route) needs
  // Compose 1.11's `PreviewWrapperProvider`, which this CMP 1.10 module lacks. TODO: switch to
  // `catalogOverrideFont(..., CATALOG_FONT_NAMES)` once `composeaiReleasedRuntimeVersion` ships
  // `previewOverrideFont` (the publish builds against the released runtime).
  val font = catalogFont(catalogOverrideString(CATALOG_FONT_KNOB, CATALOG_FONT_ROBOTO_FLEX))
  val colorScheme =
    catalogColorScheme(catalogOverrideString(CATALOG_COLORS_KNOB, CATALOG_PALETTE_M3), dark)
  // Shapes and typography-metrics overrides ride the same knob surface (`knob.theme.shapes` /
  // `knob.theme.typography`); absent, they resolve to stock M3.
  val shapes = catalogShapes(catalogOverrideString(CATALOG_SHAPES_KNOB, ""))
  // Type scale = the `theme.font` face, then per-role-group families from `theme.fonts` (resolved
  // against `CatalogNamedFonts`), then the `theme.typography` metrics overlay.
  val typography =
    catalogApplyTypography(
      catalogApplyFontFamilies(
        catalogTypography(font),
        parseCatalogFontFamilies(catalogOverrideString(CATALOG_FONTS_KNOB, "")),
        CatalogNamedFonts,
        font,
      ),
      catalogOverrideString(CATALOG_TYPOGRAPHY_KNOB, ""),
    )
  CatalogStickerFrame(
    colorScheme = colorScheme,
    typography = typography,
    shapes = shapes,
    content = content,
  )
}

/**
 * Resolves a typeface [name] (a declared `@TypographyCatalog` label) to its desktop [FontFamily],
 * defaulting to Roboto Flex. To offer a new face, add it here, to `CatalogCatalogs.kt`, and to
 * `cmp-wasm-catalog`'s `fonts.json`.
 */
fun catalogFont(name: String): FontFamily =
  when (name) {
    CATALOG_FONT_GOOGLE_SANS_FLEX -> GoogleSansFlex
    CATALOG_FONT_LOBSTER_TWO -> LobsterTwo
    else -> RobotoFlex
  }

/**
 * The sticker frame with caller-supplied [colorScheme] / [typography]; [CatalogSticker] resolves
 * them from the override surface. Generic and named font families are always provided. Stickers
 * render on a transparent surface (a silhouette on the viewer's backing) with
 * `contentColor = onSurface`.
 */
@Composable
fun CatalogStickerFrame(
  colorScheme: androidx.compose.material3.ColorScheme,
  typography: androidx.compose.material3.Typography,
  shapes: androidx.compose.material3.Shapes = androidx.compose.material3.Shapes(),
  content: @Composable () -> Unit,
) {
  CompositionLocalProvider(
    LocalGenericFonts provides CatalogGenericFonts,
    LocalNamedFonts provides CatalogNamedFonts,
  ) {
    MaterialTheme(colorScheme = colorScheme, typography = typography, shapes = shapes) {
      Surface(color = Color.Transparent, contentColor = MaterialTheme.colorScheme.onSurface) {
        Box(Modifier.padding(16.dp)) { content() }
      }
    }
  }
}

/**
 * The catalog's primary-mode multipreview: light and dark, the `· Light` / `· Dark` captures the
 * sticker sheet pairs. `uiMode = 32` is `Configuration.UI_MODE_NIGHT_YES`, written raw because the
 * CMP desktop source set has no `android.content.res.Configuration`.
 */
// No `showBackground`: component stickers stay transparent silhouettes on the viewer's checkerboard.
@Preview(name = "Light", group = "modes")
@Preview(name = "Dark", uiMode = 32, group = "modes")
annotation class CatalogModes

/**
 * Frame for full-screen scaffold templates: the stock [MaterialTheme] filling the device with the
 * `background` surface. The template drives system-bar spacing through window insets
 * ([SYSTEM_BAR_INSET]).
 */
@Composable
fun FullScreenM3(content: @Composable () -> Unit) {
  val dark = isSystemInDarkTheme()
  MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
    Surface(Modifier.fillMaxSize()) { content() }
  }
}

/**
 * Height of the renderer's synthetic status/navigation bars (24dp). There are no real insets behind
 * that overlay, so templates feed this to their `Scaffold`/`TopAppBar` `windowInsets` to reproduce
 * an edge-to-edge M3 scaffold.
 */
val SYSTEM_BAR_INSET = 24.dp

/**
 * Full-screen template multipreview: `id:pixel_8` with `showSystemUi = true` for the synthetic OS
 * chrome, in light and dark.
 */
@Preview(name = "Light", device = "id:pixel_8", showSystemUi = true, group = "template")
@Preview(name = "Dark", device = "id:pixel_8", showSystemUi = true, uiMode = 32, group = "template")
annotation class CatalogTemplate

// Fonts loaded once from src/main/resources/fonts/ — the same TTFs the wasm tier vendors.
// `androidx.compose.ui.text.platform.Font(identity, data)` is the desktop/Skiko overload.

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
 * Roboto Flex — the catalog's default typeface (see [CatalogDefaultFont]) and Material 3's default
 * sans. One variable TTF from fonts.google.com's `ofl/robotoflex` backs the whole type scale.
 */
val RobotoFlex: FontFamily =
  FontFamily(Font("RobotoFlex", fontBytes("RobotoFlex.ttf"), FontWeight.Normal, FontStyle.Normal))

/**
 * Lobster Two (fonts.google.com `ofl/lobstertwo`), a deliberately distinctive selectable typeface
 * so the font override is unmistakable.
 */
val LobsterTwo: FontFamily =
  FontFamily(
    Font(
      "LobsterTwo-Regular",
      fontBytes("LobsterTwo-Regular.ttf"),
      FontWeight.Normal,
      FontStyle.Normal,
    ),
    Font("LobsterTwo-Bold", fontBytes("LobsterTwo-Bold.ttf"), FontWeight.Bold, FontStyle.Normal),
  )

/**
 * Google Sans Flex, declared as a named GoogleFont typeface choice. It isn't distributed on
 * fonts.google.com, so it isn't vendored; [optionalGoogleFontFamily] falls back to the platform
 * sans until a `GoogleSansFlex.ttf` is dropped into `resources/fonts/`.
 */
val GoogleSansFlex: FontFamily =
  optionalGoogleFontFamily("Google Sans Flex", "GoogleSansFlex.ttf") ?: FontFamily.SansSerif

/** The catalog's default typeface: Roboto Flex, re-pointed onto the whole type scale. */
val CatalogDefaultFont: FontFamily = RobotoFlex

/**
 * Builds a named GoogleFont [FontFamily] from a vendored face, or null when the TTF is absent so
 * the caller can fall back.
 */
private fun optionalGoogleFontFamily(name: String, file: String): FontFamily? = runCatching {
  FontFamily(googleFontFace(name, file, FontWeight.Normal))
}
  .getOrNull()

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
 * Named GoogleFont substitutes keyed by the display name `namedFontFamily(…)` looks up, built from
 * branded TTFs vendored as `resources/fonts/<slug>-<weight>.ttf`.
 *
 * Each face's `identity` is the GoogleFont label (`Font(GoogleFont("Orbitron", …), …)`) because the
 * daemon's font-usage recorder reports that and the export's manifest generator parses the family
 * back out of it, producing a `role: "named"` entry the wasm tier fetches. Components use
 * `namedFontFamily(...)` instead of the Android-only `FontFamily(Font(GoogleFont(...), provider))`.
 */
val CatalogNamedFonts: Map<String, FontFamily> =
  mapOf(
    "Orbitron" to
      FontFamily(
        googleFontFace("Orbitron", "orbitron-400.ttf", FontWeight.Normal),
        googleFontFace("Orbitron", "orbitron-700.ttf", FontWeight.Bold),
      ),
    "Space Grotesk" to
      FontFamily(
        googleFontFace("Space Grotesk", "space-grotesk-400.ttf", FontWeight.Normal),
        googleFontFace("Space Grotesk", "space-grotesk-700.ttf", FontWeight.Bold),
      ),
    "JetBrains Mono" to
      FontFamily(
        googleFontFace("JetBrains Mono", "jetbrains-mono-400.ttf", FontWeight.Normal),
        googleFontFace("JetBrains Mono", "jetbrains-mono-700.ttf", FontWeight.Bold),
      ),
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
