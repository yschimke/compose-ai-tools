package com.example.sampleandroid

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import ee.schimke.composeai.preview.splash.SplashScreenSurface

/**
 * Sample previews for the Android 12+ SplashScreen window appearance, routed through the
 * `SplashScreenSurface` helper from `:splash-preview-runtime`. Each `@Preview` is one variant of
 * the splash spec — icon only, icon + circular backdrop ring, icon + branding image, and a
 * dark-theme branch — so the rendered PNGs document each `windowSplashScreen*` attribute the
 * platform exposes.
 *
 * The phone-shaped `@Preview(widthDp = 360, heightDp = 800)` overrides match the canvas the real
 * splash uses on a typical handset; the helper sizes the icon at ~192dp visible (~75% of the
 * SplashScreen-spec icon canvas) so the rendered output reads the same proportions a fresh-install
 * launch animation would draw.
 *
 * Drawables are reused from the existing sample resource set:
 * - `ic_compose_logo` is the foreground icon (the same monochrome / adaptive-icon foreground
 *   `windowSplashScreenAnimatedIcon` would point at on Android 12+).
 * - `ic_launcher_foreground` doubles as the bottom-edge branding asset for the variant that sets
 *   `windowSplashScreenBrandingImage`.
 *
 * No new drawable resources are added for this gallery — the existing pair is enough to exercise
 * the four spec-documented variants below without bloating the sample's resource set.
 */
private const val SPLASH_PREVIEW_WIDTH_DP = 360
private const val SPLASH_PREVIEW_HEIGHT_DP = 800

/**
 * Bare splash — full-bleed white background with a centred icon. The minimum-viable SplashScreen
 * variant: `windowSplashScreenBackground` set to white, `windowSplashScreenIcon` set to the
 * foreground drawable, all other attributes left at platform defaults.
 */
@Preview(
  name = "Splash — icon only",
  widthDp = SPLASH_PREVIEW_WIDTH_DP,
  heightDp = SPLASH_PREVIEW_HEIGHT_DP,
)
@Composable
fun SplashIconOnlyPreview() {
  SplashScreenSurface(icon = painterResource(R.drawable.ic_compose_logo))
}

/**
 * Icon with a circular backdrop ring — `windowSplashScreenIconBackgroundColor` is the
 * spec-documented attribute that draws a coloured circle behind the icon. Used by apps whose
 * monochrome foreground would otherwise lose contrast against the splash background.
 */
@Preview(
  name = "Splash — icon with background ring",
  widthDp = SPLASH_PREVIEW_WIDTH_DP,
  heightDp = SPLASH_PREVIEW_HEIGHT_DP,
)
@Composable
fun SplashIconWithBackgroundPreview() {
  SplashScreenSurface(
    icon = painterResource(R.drawable.ic_compose_logo),
    background = Color(0xFFF1F1F1),
    iconBackground = Color(0xFF3DDC84), // Android green — same hue brand guidelines suggest
  )
}

/**
 * Icon + bottom-edge branding asset — `windowSplashScreenBrandingImage`. The spec bounds the
 * branding image at 200dp wide × 80dp tall and inset ~60dp from the bottom edge of the splash
 * window; the helper applies those bounds automatically.
 */
@Preview(
  name = "Splash — icon with branding",
  widthDp = SPLASH_PREVIEW_WIDTH_DP,
  heightDp = SPLASH_PREVIEW_HEIGHT_DP,
)
@Composable
fun SplashWithBrandingPreview() {
  SplashScreenSurface(
    icon = painterResource(R.drawable.ic_compose_logo),
    background = Color.White,
    brandingImage = painterResource(R.drawable.ic_launcher_foreground),
  )
}

/**
 * Dark-theme branch — the same splash variants the platform paints when the launching app declares
 * `Theme.SplashScreen.DayNight` with a night-mode override. The helper deliberately doesn't read
 * `isSystemInDarkTheme()` (see the doc on `SplashScreenSurface`); we drive the variant by
 * hand-picking colours and pairing the preview with `uiMode = UI_MODE_NIGHT_YES` so any surrounding
 * sample theming that reads the configuration stays on the dark branch for the screenshot.
 */
@Preview(
  name = "Splash — dark theme",
  widthDp = SPLASH_PREVIEW_WIDTH_DP,
  heightDp = SPLASH_PREVIEW_HEIGHT_DP,
  uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
fun SplashDarkThemePreview() {
  SplashScreenSurface(
    icon = painterResource(R.drawable.ic_compose_logo),
    background = Color(0xFF101418),
    iconBackground = Color(0xFF1F2A33),
    brandingImage = painterResource(R.drawable.ic_launcher_foreground),
  )
}
