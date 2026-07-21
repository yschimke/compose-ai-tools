package com.example.designcatalogconfetti

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.overrides.previewOverrideString

// ---------------------------------------------------------------------------
// Confetti brand palette. These are the real tones from the Confetti app's
// default (Purple / Orange / Blue) scheme — androidApp `ui/Color.kt` — so the
// `compose/theme` token set the renderer extracts is Confetti's actual brand,
// not a generic M3 palette. A conference brands the app by re-seeding `primary`
// (KotlinConf purple, DevFest blue, …); the catalog exposes a few of those seeds
// as a `theme.colors` override knob (see [confettiColorScheme]) so the preview
// server can re-skin every sticker without a preview change.
// ---------------------------------------------------------------------------

private val Purple40 = Color(0xFF8C4190)
private val Purple80 = Color(0xFFFFA8FF)
private val Purple20 = Color(0xFF560A5E)
private val Purple30 = Color(0xFF702776)
private val Purple90 = Color(0xFFFFD5FC)
private val Purple95 = Color(0xFFFFEBFB)

private val Orange40 = Color(0xFFA23F16)
private val Orange80 = Color(0xFFFFB599)
private val Orange30 = Color(0xFF5D1900)

private val Blue40 = Color(0xFF006781)
private val Blue80 = Color(0xFF5DD4FB)
private val Blue30 = Color(0xFF004D61)
private val Blue90 = Color(0xFFB5EAFF)

private val Red40 = Color(0xFFBA1B1B)
private val Red80 = Color(0xFFFFB4A9)

private val Gray99 = Color(0xFFFCFCFC)
private val Gray10 = Color(0xFF201A1B)
private val OnSurfaceLight = Color(0xFF201A1B)
private val OnSurfaceDark = Color(0xFFECDFE0)
private val SurfaceVariantLight = Color(0xFFEDDEE8)
private val SurfaceVariantDark = Color(0xFF4E444C)
private val OnSurfaceVariantLight = Color(0xFF4E444C)
private val OnSurfaceVariantDark = Color(0xFFD0C2CC)
private val OutlineLight = Color(0xFF7F747C)
private val OutlineDark = Color(0xFF998D96)

/** Confetti's default light scheme — the stock M3 light scheme re-pointed onto the brand tones. */
val ConfettiLightColors: ColorScheme =
  lightColorScheme(
    primary = Purple40,
    onPrimary = Color.White,
    primaryContainer = Purple90,
    onPrimaryContainer = Purple20,
    secondary = Orange40,
    onSecondary = Color.White,
    secondaryContainer = Purple95,
    onSecondaryContainer = Orange30,
    tertiary = Blue40,
    onTertiary = Color.White,
    tertiaryContainer = Blue90,
    onTertiaryContainer = Blue30,
    error = Red40,
    background = Gray99,
    onBackground = OnSurfaceLight,
    surface = Gray99,
    onSurface = OnSurfaceLight,
    surfaceVariant = SurfaceVariantLight,
    onSurfaceVariant = OnSurfaceVariantLight,
    outline = OutlineLight,
  )

/** Confetti's default dark scheme — the stock M3 dark scheme re-pointed onto the brand tones. */
val ConfettiDarkColors: ColorScheme =
  darkColorScheme(
    primary = Purple80,
    onPrimary = Purple20,
    primaryContainer = Purple30,
    onPrimaryContainer = Purple90,
    secondary = Orange80,
    onSecondary = Orange30,
    secondaryContainer = Orange30,
    onSecondaryContainer = Orange80,
    tertiary = Blue80,
    onTertiary = Blue30,
    tertiaryContainer = Blue30,
    onTertiaryContainer = Blue90,
    error = Red80,
    background = Gray10,
    onBackground = OnSurfaceDark,
    surface = Gray10,
    onSurface = OnSurfaceDark,
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = OnSurfaceVariantDark,
    outline = OutlineDark,
  )

/**
 * The conference brands Confetti ships. Each conference re-skins the whole app from a single
 * `themeColor` **seed** — the app derives a full dynamic scheme from it via MaterialKolor's
 * `rememberDynamicColorScheme(seed)` in `ConferenceMaterialTheme`. `"Confetti"` is the default
 * (its own hand-authored purple scheme, seed `null`); the rest are the real per-conference seeds
 * (KotlinConf's JetBrains purple, DevFest's Google blue, droidcon green, Android Makers amber).
 */
val ConfettiConferences: List<Pair<String, Color?>> =
  listOf(
    "Confetti" to null,
    "KotlinConf" to Color(0xFF7F52FF),
    "DevFest" to Color(0xFF4285F4),
    "droidcon" to Color(0xFF00D775),
    "Android Makers" to Color(0xFFE59A4F),
  )

/** The declared palette choices, shown first in the `theme.colors` override autocomplete. */
val CONFETTI_PALETTE_NAMES: List<String> = ConfettiConferences.map { it.first }

private fun conferenceSeed(name: String): Color? =
  ConfettiConferences.firstOrNull { it.first == name }?.second

/**
 * Derives a conference [ColorScheme] from a single brand [seed], approximating the tonal derivation
 * Confetti's `ConferenceMaterialTheme` gets from MaterialKolor's `rememberDynamicColorScheme(seed)`
 * without pulling that dependency onto the render classpath (this repo pins the Compose version the
 * renderer aligns to). The seed drives `primary`, `tertiary`, and the
 * `primaryContainer`/`onPrimaryContainer` pair — the roles a session card's bookmark tint, its
 * lightning pill, and the day-tab indicator read — blended toward the [base] surface so the rest of
 * the scheme stays coherent. Every other role is inherited from [base].
 */
fun confettiSeedScheme(base: ColorScheme, seed: Color, dark: Boolean): ColorScheme =
  base.copy(
    primary = seed,
    onPrimary = if (dark) lerp(seed, Color.Black, 0.72f) else Color.White,
    primaryContainer = if (dark) lerp(seed, Color.Black, 0.55f) else lerp(seed, Color.White, 0.82f),
    onPrimaryContainer = if (dark) lerp(seed, Color.White, 0.85f) else lerp(seed, Color.Black, 0.55f),
    secondary = lerp(seed, base.onSurfaceVariant, 0.35f),
    tertiary = seed,
    inversePrimary = if (dark) lerp(seed, Color.White, 0.55f) else lerp(seed, Color.Black, 0.30f),
  )

/**
 * Resolves a selected palette [name] to a Confetti [ColorScheme]: `"Confetti"` keeps the default
 * hand-authored brand [base]; a conference name derives a full scheme from its seed (see
 * [confettiSeedScheme]), exactly as the app re-brands per conference.
 */
fun confettiColorScheme(name: String, base: ColorScheme, dark: Boolean): ColorScheme {
  val seed = conferenceSeed(name) ?: return base
  return confettiSeedScheme(base, seed, dark)
}

/**
 * Confetti's type scale: the stock Material 3 [Typography] with the app's own tweaks (androidApp
 * `ui/Type.kt`) — the title styles bumped to bold (`titleLarge`/`titleMedium` W700, `titleSmall`
 * W500) and `labelSmall` set in a monospace face. No custom font family: the phone app rides the
 * platform default (Roboto), which the Android/Robolectric renderer already rasterises.
 */
val ConfettiTypography: Typography =
  Typography().run {
    copy(
      titleLarge = titleLarge.copy(fontWeight = FontWeight.Bold),
      titleMedium = titleMedium.copy(fontWeight = FontWeight.Bold),
      titleSmall = titleSmall.copy(fontWeight = FontWeight.Medium),
      labelSmall = labelSmall.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium),
    )
  }

/**
 * The catalog theme. Wraps the stock M3 [MaterialTheme] with Confetti's brand [ColorScheme] and
 * [ConfettiTypography]. The palette is read from the `theme.colors` override surface (defaulting to
 * the Confetti brand), so the preview server can re-skin every sticker onto a conference seed with
 * no preview change — an un-overridden render stays pixel-identical.
 */
@Composable
fun ConfettiCatalogTheme(palette: String? = null, content: @Composable () -> Unit) {
  val dark = isSystemInDarkTheme()
  val base = if (dark) ConfettiDarkColors else ConfettiLightColors
  // A `palette` argument pins the sticker to a specific conference brand (the "Conference themes"
  // group); otherwise the palette is read from the override surface (defaulting to Confetti), so the
  // preview server can re-skin every other sticker onto any conference seed with no preview change.
  val selected = palette ?: previewOverrideString("theme.colors", "Confetti")
  val colorScheme = confettiColorScheme(selected, base, dark)
  MaterialTheme(colorScheme = colorScheme, typography = ConfettiTypography, content = content)
}

/**
 * The catalog's **component** sticker frame: a single component wrapped in the Confetti theme on a
 * **transparent** surface, framed by a uniform 16dp [padding]. Transparency lets a designer drop the
 * sticker onto any canvas; the interactive viewers paint their own backing behind the PNG, so the
 * sticker reads as a component silhouette. `contentColor = onSurface` keeps text/icons themed.
 */
@Composable
fun ConfettiSticker(content: @Composable () -> Unit) {
  ConfettiCatalogTheme {
    Surface(color = Color.Transparent, contentColor = MaterialTheme.colorScheme.onSurface) {
      Box(Modifier.padding(16.dp), contentAlignment = Alignment.CenterStart) { content() }
    }
  }
}

/**
 * A component sticker pinned to a specific conference brand [palette] (ignoring the `theme.colors`
 * override), for the **Conference themes** group that shows each conference's branding as its own
 * sticker — the visible payoff of the app's per-conference seeding. Same transparent frame as
 * [ConfettiSticker], just with the palette fixed.
 */
@Composable
fun ConferenceSticker(palette: String, content: @Composable () -> Unit) {
  ConfettiCatalogTheme(palette = palette) {
    Surface(color = Color.Transparent, contentColor = MaterialTheme.colorScheme.onSurface) {
      Box(Modifier.padding(16.dp), contentAlignment = Alignment.CenterStart) { content() }
    }
  }
}

/**
 * The catalog's primary-mode multipreview: every component rendered in both light and dark — the two
 * modes M3 (and Confetti) ship. `showBackground = false` keeps the sticker transparent so it carries
 * alpha. Further modes (states) are added per-component with extra `@Preview`s.
 */
@Preview(name = "Light", showBackground = false, group = "modes")
@Preview(name = "Dark", showBackground = false, uiMode = 32, group = "modes")
annotation class CatalogModes

/**
 * Frame for **full-screen scaffold templates** (the Confetti schedule screen) — as opposed to the
 * centred component [ConfettiSticker]. Just the Confetti [MaterialTheme] filling the device with the
 * `background` surface; the template supplies its own `Scaffold` and drives the system-bar spacing
 * through window insets (see [SYSTEM_BAR_INSET]).
 */
@Composable
fun FullScreenConfetti(content: @Composable () -> Unit) {
  ConfettiCatalogTheme { Surface(Modifier.fillMaxSize()) { content() } }
}

/**
 * Height of the renderer's synthetic status / navigation bars (`SystemBarsFrame` draws both at
 * 24dp). The render environment has no real window insets behind that overlay, so a template feeds
 * this height to its `Scaffold`/`TopAppBar` `windowInsets` — reproducing a real edge-to-edge M3
 * scaffold rather than an outer padding that pushes the scaffold into a band.
 */
val SYSTEM_BAR_INSET = 24.dp

/**
 * Full-screen template multipreview: a phone (`id:pixel_8`) with `showSystemUi = true` so the
 * capture carries the synthetic OS status + nav chrome, in both light and dark — framing the
 * schedule screen exactly as a real device screenshot would.
 */
@Preview(name = "Light", device = "id:pixel_8", showSystemUi = true, group = "template")
@Preview(name = "Dark", device = "id:pixel_8", showSystemUi = true, uiMode = 32, group = "template")
annotation class CatalogTemplate
