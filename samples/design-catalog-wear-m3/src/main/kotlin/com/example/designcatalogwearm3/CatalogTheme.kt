package com.example.designcatalogwearm3

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.TimeText
import androidx.wear.compose.material3.Typography
import androidx.wear.compose.material3.timeTextCurvedText
import ee.schimke.composeai.overrides.previewOverrideFont
import ee.schimke.composeai.overrides.previewOverrideString

/**
 * The catalog's component sticker frame: one component in the stock Wear [MaterialTheme] on a
 * transparent background, cropped tight. Full-screen components use [FullScreenWear] instead.
 *
 * Deliberately no `fillMaxSize()` / centring: `PreviewDiscovery.retargetWearStickers` gives a
 * device-less Wear preview the 227dp watch screen as a measuring bound, and the renderer crops the
 * PNG to the component. Filling would put every sticker back on a full 454×454 canvas.
 *
 * TLC item scaling is shown separately in `CardScalingPreview.kt`.
 */
@Composable
fun WearSticker(content: @Composable () -> Unit) {
  WearCatalogTheme { Box(Modifier.padding(8.dp)) { content() } }
}

/**
 * The Wear catalog theme, with typeface and palette read from the override surface
 * (`knob.theme.font` / `knob.theme.colors`) so the preview server can re-skin any sticker; absent
 * an override both resolve to the Wear M3 default. Choices are the names in `WearCatalogFonts.kt`.
 *
 * The type scale comes from [wearCatalogTypography] (re-pointing each role explicitly, since
 * `Typography(defaultFontFamily = …)` is a no-op on Wear) and the palette re-tints the default
 * scheme.
 */
@Composable
fun WearCatalogTheme(content: @Composable () -> Unit) {
  // A server-selected @WearThemeCatalog provider already installed the requested theme outside this
  // preview; don't replace it with the catalog default (mirrors Confetti Wear's
  // PreviewThemeOverrideInstalled contract).
  if (LocalWearCatalogThemeOverride.current) {
    content()
    return
  }

  val font = previewOverrideFont("theme.font", "Roboto Flex", suggestions = WEAR_FONT_NAMES)
  val colorScheme =
    wearColorScheme(previewOverrideString("theme.colors", "M3"), MaterialTheme.colorScheme)
  MaterialTheme(typography = wearCatalogTypography(font), colorScheme = colorScheme) { content() }
}

/**
 * The declared typeface choices (`@TypographyCatalog` labels), shown first in the font-override
 * autocomplete before the full fonts.google.com list. Roboto Flex — the default — leads.
 */
val WEAR_FONT_NAMES: List<String> =
  listOf("Roboto Flex", "Google Sans Flex", "Lobster Two", "JetBrains Mono", "Inter")

/**
 * Resolves a selected typeface [name] (a declared `@TypographyCatalog` label) to its [FontFamily].
 */
fun wearCatalogFont(name: String): FontFamily =
  when (name) {
    "Google Sans Flex" -> GoogleSansFlex
    "Lobster Two" -> LobsterTwo
    "JetBrains Mono" -> JetBrainsMono
    "Inter" -> Inter
    else -> RobotoFlex
  }

/**
 * The Wear type scale for a selected theme [name] — the typographic half of a theme, alongside
 * [wearColorScheme]. Pairings are `display`/`body` so a two-face identity (e.g. Confetti's
 * KotlinConf "JetBrains Mono titles + Inter body") survives. Numerals ride with the display face.
 */
fun wearCatalogTypography(name: String): Typography =
  when {
    name == "KotlinConf" -> wearTypography(body = Inter, display = JetBrainsMono)
    // A single declared typeface — either the "Google Sans Flex" theme or a `knob.theme.font` pick.
    name != "Roboto Flex" && name in WEAR_FONT_NAMES -> wearTypography(body = wearCatalogFont(name))
    // Roboto Flex and the palette-only themes keep the stock scale: Roboto Flex is already the Wear
    // device font with per-role variable axes, which a GoogleFont re-point would drop.
    else -> Typography()
  }

/**
 * A Wear [Typography] on [body], with the display / title / numeral roles on [display].
 *
 * Every role is re-pointed explicitly: `Typography(defaultFontFamily = …)` is a no-op on Wear,
 * since it only fills styles with no family and every `TypographyTokens` role declares one.
 *
 * The three arc roles stay on the stock face: the only `CurvedTextStyle.copy` taking a `fontFamily`
 * is deprecated, and they draw system chrome, not app typography.
 */
private fun wearTypography(body: FontFamily, display: FontFamily = body): Typography {
  val base = Typography()
  return base.copy(
    displayLarge = base.displayLarge.copy(fontFamily = display),
    displayMedium = base.displayMedium.copy(fontFamily = display),
    displaySmall = base.displaySmall.copy(fontFamily = display),
    titleLarge = base.titleLarge.copy(fontFamily = display),
    titleMedium = base.titleMedium.copy(fontFamily = display),
    titleSmall = base.titleSmall.copy(fontFamily = display),
    numeralExtraLarge = base.numeralExtraLarge.copy(fontFamily = display),
    numeralLarge = base.numeralLarge.copy(fontFamily = display),
    numeralMedium = base.numeralMedium.copy(fontFamily = display),
    numeralSmall = base.numeralSmall.copy(fontFamily = display),
    numeralExtraSmall = base.numeralExtraSmall.copy(fontFamily = display),
    labelLarge = base.labelLarge.copy(fontFamily = body),
    labelMedium = base.labelMedium.copy(fontFamily = body),
    labelSmall = base.labelSmall.copy(fontFamily = body),
    bodyLarge = base.bodyLarge.copy(fontFamily = body),
    bodyMedium = base.bodyMedium.copy(fontFamily = body),
    bodySmall = base.bodySmall.copy(fontFamily = body),
    bodyExtraSmall = base.bodyExtraSmall.copy(fontFamily = body),
  )
}

/**
 * Resolves a selected palette [name] to a Wear [ColorScheme]: `"M3"` keeps the default [base]; the
 * brand palettes re-tint it. Copying [base] keeps every other Wear role intact.
 */
fun wearColorScheme(name: String, base: ColorScheme): ColorScheme =
  when (name) {
    // Confetti Wear's KotlinConf seed purple (#7F52FF) on Wear's dark role ramp's primary family.
    "KotlinConf" ->
      base.copy(
        primary = Color(0xFF7F52FF),
        primaryDim = Color(0xFF633BDB),
        primaryContainer = Color(0xFF3D247F),
        onPrimary = Color.White,
        onPrimaryContainer = Color(0xFFE8DDFF),
        secondary = Color(0xFFFF8DA1),
        secondaryDim = Color(0xFFD96C81),
        secondaryContainer = Color(0xFF652936),
        onSecondary = Color(0xFF3A0715),
        onSecondaryContainer = Color(0xFFFFD9E0),
      )
    "Coral" -> base.copy(primary = Color(0xFFFF6F61), secondary = Color(0xFFFFB4A9))
    "Teal" -> base.copy(primary = Color(0xFF4DD0E1), secondary = Color(0xFF80CBC4))
    else -> base
  }

/** True while a preview-server theme provider owns the Wear Material theme for the sticker. */
internal val LocalWearCatalogThemeOverride = compositionLocalOf { false }

/**
 * The catalog's component multipreview: a single transparent capture cropped to the component (no
 * device frame; full-screen components use [CatalogWearBreakpoints]).
 */
@Preview(showBackground = false) annotation class CatalogWearModes

/**
 * A curved [TimeText] frozen at "10:10", so renders are deterministic and the weekly bundle doesn't
 * churn.
 */
@Composable fun FixedTimeText() = TimeText { timeTextCurvedText("10:10") }

/**
 * Frame for full-screen Wear screens: the dark [MaterialTheme] filling the round display, and
 * [AppScaffold] with the [FixedTimeText] strip. The content supplies its own `ScreenScaffold`. The
 * clock is frozen rather than dropped because the strip reserves the curved top margin content lays
 * out around.
 */
@Composable
fun FullScreenWear(content: @Composable () -> Unit) {
  WearCatalogTheme { AppScaffold(timeText = { FixedTimeText() }) { content() } }
}

/**
 * Frame for scaffold templates (whole screen skeletons). Identical to [FullScreenWear]; a separate
 * name because the content is a skeleton rather than one component.
 */
@Composable
fun WearScaffoldTemplate(content: @Composable () -> Unit) {
  FullScreenWear(content)
}

/**
 * Full-screen size-breakpoint multipreview: 192 dp (small round), 227 dp (large round) and 240 dp
 * (extra-large round), black on the device shape.
 *
 * All direct `@Preview`s with Wear device ids rather than nested `@WearPreview*` aliases, because
 * `PreviewDiscovery.resolveMultiPreview` doesn't recurse into nested multipreviews.
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
