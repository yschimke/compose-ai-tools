package com.example.sampleandroid

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import ee.schimke.composeai.preview.splash.SplashScreenSurface

/**
 * Android 12+ SplashScreen variants through `SplashScreenSurface` (`:splash-preview-runtime`): icon
 * only, icon + backdrop ring, icon + branding, and dark theme — one per `windowSplashScreen*`
 * attribute. 360×800dp matches a typical handset's splash canvas. Reuses `ic_compose_logo` as the
 * icon and `ic_launcher_foreground` as the branding image.
 */
private const val SPLASH_PREVIEW_WIDTH_DP = 360
private const val SPLASH_PREVIEW_HEIGHT_DP = 800

/**
 * Bare splash: white `windowSplashScreenBackground` and a centred icon; everything else default.
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
 * Icon with `windowSplashScreenIconBackgroundColor`, a circle behind the icon for contrast.
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
 * Icon + `windowSplashScreenBrandingImage`, bounded at 200×80dp and inset ~60dp from the bottom.
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
 * Dark-theme branch. The helper doesn't read `isSystemInDarkTheme()`, so colours are picked by
 * hand; `uiMode = UI_MODE_NIGHT_YES` keeps any configuration-reading theming on the dark branch.
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
