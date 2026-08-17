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
 * Motion counterparts to the stills in `SplashScreenGallery.kt`, routed through
 * `AnimatedSplashScreenSurface` so the splash *window* is captured in motion — icon pulsing at
 * splash proportions, over the splash background, with the backdrop ring and branding in frame.
 *
 * This is the surface the resource-preview path can't reach. An `<animated-vector>` used as
 * `windowSplashScreenAnimatedIcon` is already captured as a GIF + keyframe filmstrip, but only as a
 * bare drawable at intrinsic size; nothing rendered the icon animating inside the window until
 * these previews existed. Registering them here is what makes that coverage automatic — the
 * visual-diff bot picks up any `@AnimatedPreview` in this module, so a future change to the splash
 * helper arrives with before/after motion evidence without anyone remembering to capture it.
 *
 * `durationMs` is set explicitly on every capture below rather than left to auto-detect. The pulse
 * is an `InfiniteTransition` with no inherent duration, so auto-detect would fall back to the
 * generic 1500ms window and cut the GIF mid-cycle; `2 ×` the pulse duration is one full
 * out-and-back and loops seamlessly.
 *
 * `frameIntervalMs` and `showCurves` are set for the same reason the other full-canvas animated
 * previews in this module set them (the shader gallery, `NowPlayingSharedElementPreviews`): the
 * renderer holds every captured frame in memory before encoding, so frame count × canvas area is
 * charged against the render JVM's heap. A splash is a whole-screen surface — 360 × 800dp at
 * preview density is ~8MB per ARGB frame — and the 30fps default plus the stacked curve panel
 * overruns that heap outright. 80ms (12.5fps) over one cycle is 20 frames, which is ample for
 * motion this slow and smooth. See the note in the PR that added this file for the underlying
 * limitation.
 */
private const val SPLASH_PREVIEW_WIDTH_DP = 360
private const val SPLASH_PREVIEW_HEIGHT_DP = 800

/** One full out-and-back of the 800ms pulse the previews below use. */
private const val SPLASH_PULSE_HALF_CYCLE_MS = 800
private const val SPLASH_PULSE_FULL_CYCLE_MS = 2 * SPLASH_PULSE_HALF_CYCLE_MS

/** ~12.5fps — see the heap note in the file KDoc for why this isn't the 33ms default. */
private const val SPLASH_FRAME_INTERVAL_MS = 80

/**
 * Bare animated splash — the motion counterpart to `SplashIconOnlyPreview`. Pulse values mirror the
 * `<animated-vector>` idiom an app would author for `windowSplashScreenAnimatedIcon`: paired
 * `scaleX`/`scaleY` object animators running 1.0 → 1.15 over 800ms on `fast_out_slow_in`, reversing
 * forever while the app initialises.
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
 * Pulse against a static backdrop ring — the case the ring's static-by-design behaviour exists for.
 * The icon breathes inside a `windowSplashScreenIconBackgroundColor` circle that holds still, which
 * is what the platform draws; a capture where both scale together would look like the whole badge
 * inflating and is the regression this preview is here to catch.
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
 * Dark-theme branch with branding, so the night-mode splash gets the same motion coverage the
 * stills have. Colours are hand-picked to match `SplashDarkThemePreview` — the helper deliberately
 * doesn't read `isSystemInDarkTheme()`, so pairing with `uiMode` alone would not switch the
 * palette.
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
