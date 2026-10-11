package com.example.sampleandroid

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import ee.schimke.composeai.preview.AnimatedPreview
import ee.schimke.composeai.preview.splash.AnimatedSplashScreenSurface
import ee.schimke.composeai.preview.splash.SplashIconPulse

/**
 * Motion counterparts to `SplashScreenGallery.kt`, through `AnimatedSplashScreenSurface`: the icon
 * pulsing inside the splash window, which the resource-preview path (a bare `<animated-vector>` at
 * intrinsic size) can't show. Being `@AnimatedPreview`s here, splash-helper changes get motion
 * evidence from the visual-diff bot automatically.
 *
 * `durationMs` is explicit: the pulse is an `InfiniteTransition`, so auto-detect would use 1500ms
 * and cut mid-cycle; `2 ×` the pulse duration loops seamlessly. `frameIntervalMs = 80` and no
 * curves, because the renderer holds every frame in memory and a full-screen splash is ~8MB per
 * frame.
 */
private const val SPLASH_PREVIEW_WIDTH_DP = 360
private const val SPLASH_PREVIEW_HEIGHT_DP = 800

/** One full out-and-back of the 800ms pulse the previews below use. */
private const val SPLASH_PULSE_HALF_CYCLE_MS = 800
private const val SPLASH_PULSE_FULL_CYCLE_MS = 2 * SPLASH_PULSE_HALF_CYCLE_MS

/** ~12.5fps — see the heap note in the file KDoc for why this isn't the 33ms default. */
private const val SPLASH_FRAME_INTERVAL_MS = 80

/**
 * Bare animated splash, the motion counterpart to `SplashIconOnlyPreview`: 1.0 → 1.15 over 800ms on
 * `fast_out_slow_in`, reversing, like a typical `windowSplashScreenAnimatedIcon` AVD.
 */
@Preview(
  name = "Splash animated — icon only",
  widthDp = SPLASH_PREVIEW_WIDTH_DP,
  heightDp = SPLASH_PREVIEW_HEIGHT_DP,
)
@AnimatedPreview(
  durationMs = SPLASH_PULSE_FULL_CYCLE_MS,
  frameIntervalMs = SPLASH_FRAME_INTERVAL_MS,
  showCurves = false,
)
@Composable
fun SplashAnimatedIconOnlyPreview() {
  AnimatedSplashScreenSurface(
    icon = painterResource(R.drawable.ic_compose_logo),
    pulse = SplashIconPulse(scaleTo = 1.15f, durationMs = SPLASH_PULSE_HALF_CYCLE_MS),
  )
}

/**
 * Pulse against a static backdrop ring, as the platform draws it; both scaling together would be a
 * regression.
 */
@Preview(
  name = "Splash animated — icon with background ring",
  widthDp = SPLASH_PREVIEW_WIDTH_DP,
  heightDp = SPLASH_PREVIEW_HEIGHT_DP,
)
@AnimatedPreview(
  durationMs = SPLASH_PULSE_FULL_CYCLE_MS,
  frameIntervalMs = SPLASH_FRAME_INTERVAL_MS,
  showCurves = false,
)
@Composable
fun SplashAnimatedWithBackgroundPreview() {
  AnimatedSplashScreenSurface(
    icon = painterResource(R.drawable.ic_compose_logo),
    background = Color(0xFFF1F1F1),
    iconBackground = Color(0xFF3DDC84),
    pulse = SplashIconPulse(scaleTo = 1.15f, durationMs = SPLASH_PULSE_HALF_CYCLE_MS),
  )
}

/**
 * Dark-theme branch with branding. Colours match `SplashDarkThemePreview`, since the helper doesn't
 * read `isSystemInDarkTheme()`.
 */
@Preview(
  name = "Splash animated — dark theme with branding",
  widthDp = SPLASH_PREVIEW_WIDTH_DP,
  heightDp = SPLASH_PREVIEW_HEIGHT_DP,
  uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@AnimatedPreview(
  durationMs = SPLASH_PULSE_FULL_CYCLE_MS,
  frameIntervalMs = SPLASH_FRAME_INTERVAL_MS,
  showCurves = false,
)
@Composable
fun SplashAnimatedDarkThemePreview() {
  AnimatedSplashScreenSurface(
    icon = painterResource(R.drawable.ic_compose_logo),
    background = Color(0xFF101418),
    iconBackground = Color(0xFF1F2A33),
    brandingImage = painterResource(R.drawable.ic_launcher_foreground),
    pulse = SplashIconPulse(scaleTo = 1.15f, durationMs = SPLASH_PULSE_HALF_CYCLE_MS),
  )
}
