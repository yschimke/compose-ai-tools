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
 * The catalog's **component** sticker frame: a single component wrapped in the stock Wear
 * [MaterialTheme] on a **transparent** background, cropped tight to the component. Transparency
 * lets a designer drop the sticker onto any canvas; the `compose/theme` tokens the renderer
 * extracts still come from the real Wear Material 3 system (read from the theme, not the pixels).
 * Full-screen components use [FullScreenWear] instead, which keeps the black round device shape.
 *
 * Deliberately no `fillMaxSize()` / centring. `PreviewDiscovery.retargetWearStickers` hands a
 * device-less Wear preview the 227dp watch screen as its *measuring bound*, not as a fixed frame —
 * a fill-width component (Card, ListHeader) sizes to the watch, everything else wraps and the
 * renderer crops the PNG to it. Filling here would defeat that crop and put every sticker back on a
 * full 454×454 canvas, which is what #2404 did while the retarget still pinned the frame.
 *
 * TLC item scaling is shown separately (see `CardScalingPreview.kt`), which hosts a component in a
 * real `TransformingLazyColumn` via `:wear-preview-runtime`; a plain sticker here is unchanged.
 */
@Composable
fun WearSticker(content: @Composable () -> Unit) {
  WearCatalogTheme { Box(Modifier.padding(8.dp)) { content() } }
}

/**
 * The Wear catalog theme, with the typeface and palette read from the override surface so the
 * preview server can re-skin any sticker (`knob.theme.font` / `knob.theme.colors`) without a
 * preview change — the previews stay clean. Absent an override both resolve to the Wear M3 default,
 * so an un-overridden render is pixel-identical. The choices are the declared `@TypographyCatalog`
 * / `@ColorCatalog` names in `WearCatalogFonts.kt`; this is the one place that maps a selected name
 * to its family / scheme.
 *
 * The type scale comes from [wearCatalogTypography] — which re-points each role explicitly, because
 * `Typography(defaultFontFamily = …)` silently does nothing on Wear (see its KDoc) — and the
 * palette re-tints the default Wear scheme.
 */
@Composable
fun WearCatalogTheme(content: @Composable () -> Unit) {
  // A server-selected @WearThemeCatalog provider already installed the requested theme outside
  // this preview. Do not immediately replace it with the catalog default from inside the sticker.
  // This mirrors Confetti Wear's PreviewThemeOverrideInstalled contract and is what makes a theme
  // choice affect the component previews themselves, not only the generated theme specimen.
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
 * The Wear type scale for a selected theme [name] — the **typographic** half of a theme, alongside
 * [wearColorScheme]'s palette half.
 *
 * A theme that only re-tints is only half a theme: Confetti Wear's KotlinConf identity is a
 * *typeface pairing* as much as a seed colour (`design/STYLE_GUIDE.md` in joreilly/Confetti:
 * "JetBrains Mono titles + Inter body" for the terminal/IDE feel, Inter keeping session cards
 * readable), and this catalog's KotlinConf sheet used to render in the stock Wear face because the
 * `@WearThemeCatalog` providers passed a `colorScheme` and nothing else. Pairings are expressed as
 * `display`/`body` rather than a single family so a two-face identity survives the round trip.
 *
 * `Typography(defaultFontFamily = …)` re-points the whole scale — including the [CurvedTextStyle]
 * arc roles, which have no `TextStyle.copy(fontFamily = …)` path — so the body face is applied that
 * way and only the display/title/numeral roles are then re-pointed at the display face. Numerals
 * ride with the display face: they're the glanceable hero digits, and JetBrains Mono's tabular
 * figures are exactly what that role wants.
 */
fun wearCatalogTypography(name: String): Typography =
  when {
    name == "KotlinConf" -> wearTypography(body = Inter, display = JetBrainsMono)
    // A single declared typeface — either the "Google Sans Flex" theme or a `knob.theme.font` pick.
    name != "Roboto Flex" && name in WEAR_FONT_NAMES -> wearTypography(body = wearCatalogFont(name))
    // "Roboto Flex", and every palette-only theme ("M3" / "Coral" / "Teal"): the stock scale,
    // untouched. Roboto Flex already IS the Wear default face, and the stock tokens reach it as a
    // *device* font carrying per-role `variationSettings` — the expressive variable axes.
    // Re-pointing them at a downloadable GoogleFont family of the same name would drop those axes
    // to buy nothing, so don't; it also keeps an un-themed render pixel-identical.
    else -> Typography()
  }

/**
 * A Wear [Typography] on [body], with the display / title / numeral roles on [display].
 *
 * Every role is re-pointed **explicitly**. The `Typography(defaultFontFamily = …)` constructor
 * parameter looks like the one-liner for this and is in fact a **no-op** on Wear: it applies via
 * `TextStyle.withDefaultFontFamily`, which only fills in a family when the style has none, and
 * every `TypographyTokens` role already declares one (`Font(DeviceFontFamilyName("roboto-flex"),
 * variationSettings = …)`). That is why this catalog's KotlinConf sheet — and its `knob.theme.font`
 * override — rendered in the stock face no matter what was selected.
 *
 * The three **arc** (curved) roles are deliberately left on the stock face: the only
 * `CurvedTextStyle.copy` overload that takes a `fontFamily` is deprecated, and the arc roles draw
 * the curved status strip, which is system chrome rather than app typography.
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
    // Confetti Wear's KotlinConf identity uses the JetBrains seed purple (#7F52FF) to build a
    // dynamic dark scheme. This compact catalog keeps Wear's complete dark role ramp and applies
    // that same signature seed to its primary family.
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
 * The catalog's **component** multipreview: a single transparent capture, cropped to the component
 * (no device frame — that's for full-screen components, see [CatalogWearBreakpoints]).
 * `showBackground = false` keeps the background transparent so the sticker carries alpha.
 */
@Preview(showBackground = false) annotation class CatalogWearModes

/**
 * A frozen curved [TimeText]: the real Wear M3 status strip drawing a fixed "10:10" instead of the
 * system clock, so every render is deterministic and the weekly design-artifacts bundle doesn't
 * churn on wall-clock time.
 */
@Composable fun FixedTimeText() = TimeText { timeTextCurvedText("10:10") }

/**
 * Frame for **full-screen** Wear screens (scaffolds, lists, the EdgeButton) — as opposed to the
 * centred component [WearSticker]. The Wear dark [MaterialTheme] fills the round display black and
 * [AppScaffold] supplies the screen structure, including the curved [FixedTimeText] status strip
 * every real Wear screen carries. The content supplies its own `ScreenScaffold`.
 *
 * The clock is *frozen*, not dropped: a Wear screen without its status strip isn't the screen a
 * designer or app author is copying — the strip reserves the curved top margin the content has to
 * lay out around, so a capture without it under-reports the usable height. Determinism comes from
 * the fixed "10:10", not from omitting the clock.
 */
@Composable
fun FullScreenWear(content: @Composable () -> Unit) {
  WearCatalogTheme { AppScaffold(timeText = { FixedTimeText() }) { content() } }
}

/**
 * Frame for the **scaffold templates** — full-screen skeletons an app copies whole (list screen
 * with a status strip, pager, edge-button screen). Identical to [FullScreenWear]: both supply the
 * dark theme, the [AppScaffold], and the frozen [FixedTimeText] strip. Kept as its own name because
 * a template's *content* is a whole screen skeleton rather than a single full-screen component.
 */
@Composable
fun WearScaffoldTemplate(content: @Composable () -> Unit) {
  FullScreenWear(content)
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
