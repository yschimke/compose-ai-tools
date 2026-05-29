package ee.schimke.composeai.preview.splash

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image

/**
 * Recreates the Android 12+ SplashScreen window appearance inside a regular `@Preview`.
 *
 * Pairs with a stacked `@Preview` (multi-preview meta-annotations) so authors can fan out the
 * splash across the existing knobs `@Preview` already owns — `uiMode`, `locale`, `widthDp`,
 * `fontScale`. The fan-out is driven by Compose tooling: discovery + the renderer's COMPOSE path
 * pick each `@Preview` up as a separate entry, so no splash-specific plumbing is required.
 *
 * Layout mirrors the SplashScreen spec
 * (https://developer.android.com/develop/ui/views/launch/splash-screen#elements):
 *
 *  - Full-bleed [background] fills the surrounding `@Preview` window so the rendered PNG looks
 *    like a single uninterrupted splash surface, the same way the platform paints
 *    `windowSplashScreenBackground` across the entire splash window.
 *  - [icon] is centred, masked to a circle, and sized to ~75% of the splash-icon canvas's short
 *    edge. The spec talks about a 240dp visible icon inside a 320dp canvas (≈ 75%); we apply the
 *    same ratio against the available footprint so the rendered icon reads the right size on a
 *    phone-shaped `@Preview(widthDp = 360, heightDp = 800)` window without us having to hard-code
 *    a px size that won't track preview dp overrides. Clipping to `CircleShape` matches the way
 *    the platform's `SplashScreenView` masks the icon for the Android 12+ visual.
 *  - When [iconBackground] is non-null the icon sits on top of a filled circle of that colour —
 *    this is the `windowSplashScreenIconBackgroundColor` attribute the spec describes as an
 *    optional "circular backdrop" behind the icon. The backdrop is slightly larger than the icon
 *    so the colour reads as a ring around the masked icon, matching the on-device appearance.
 *  - [brandingImage] is centred along the bottom edge, capped at ~200dp wide / ~80dp tall (the
 *    spec's documented branding footprint), with ~60dp of bottom inset so it doesn't crowd the
 *    canvas edge. Pass `null` to omit it entirely.
 *
 * Reproduction is qualitative — the rendered tree reads like the real splash on a phone-shaped
 * canvas; it does not byte-match what `SystemUI`'s splash compositor draws on-device (window
 * shadows, the icon-fade animation frames, OEM corner-radius chrome). Authors who need
 * pixel-accurate captures should snapshot the actual launch animation off a device.
 *
 * The composable deliberately doesn't read `isSystemInDarkTheme()` — the caller picks the
 * colours so a `@Preview(uiMode = UI_MODE_NIGHT_YES)` driver controls the variant entirely by
 * passing different [background] / [iconBackground] values per night-mode branch. This keeps the
 * helper symmetric with `NotificationContent`: it draws what you hand it and leaves the
 * theme-switching to the surrounding multi-preview meta-annotation.
 *
 * @param icon the foreground drawable rendered at the centre. Typically the app's
 *   `windowSplashScreenAnimatedIcon` (the same monochrome / adaptive-icon foreground the
 *   launcher uses on Android 12+).
 * @param background full-bleed colour drawn behind everything. Defaults to opaque white.
 * @param iconBackground optional colour for the circular backdrop behind the icon. `null`
 *   (default) skips the backdrop entirely so the icon sits directly on top of [background] —
 *   the SplashScreen attribute is itself opt-in on-device.
 * @param brandingImage optional bottom-centre branding asset. `null` (default) omits it.
 * @param modifier modifier applied to the outer full-bleed `Box`. Use this to override the size
 *   of the splash surface for an inset preview; by default the helper fills the available space.
 */
@Composable
fun SplashScreenSurface(
  icon: Painter,
  background: Color = Color.White,
  iconBackground: Color? = null,
  brandingImage: Painter? = null,
  modifier: Modifier = Modifier,
) {
  Box(
    modifier =
      modifier
        .fillMaxSize()
        .background(background)
        .semantics { testTag = SPLASH_SURFACE_TEST_TAG },
    contentAlignment = Alignment.Center,
  ) {
    SplashIcon(icon = icon, iconBackground = iconBackground)
    if (brandingImage != null) {
      SplashBranding(brandingImage)
    }
  }
}

/**
 * Centre icon — the optional [iconBackground] ring is drawn underneath via a sibling `Box` so
 * both layers share the [Alignment.Center] anchor without us having to compute an offset.
 *
 * Both the icon and the backdrop are sized in `dp` against a notional 320dp canvas (the
 * SplashScreen spec's icon canvas). Inside a phone-shaped `@Preview` (360×800dp) the icon ends
 * up at ~192dp visible, with a ~256dp backdrop — the same ~75% / 80% ratio the platform applies.
 */
@Composable
private fun BoxScope.SplashIcon(icon: Painter, iconBackground: Color?) {
  val backdropDiameter = 256.dp
  val iconDiameter = 192.dp
  if (iconBackground != null) {
    Box(
      modifier =
        Modifier.size(backdropDiameter)
          .clip(CircleShape)
          .background(iconBackground)
          .semantics { testTag = SPLASH_ICON_BACKGROUND_TEST_TAG }
    )
  }
  Image(
    painter = icon,
    contentDescription = null,
    modifier =
      Modifier.size(iconDiameter)
        .clip(CircleShape)
        .semantics {
          testTag = SPLASH_ICON_TEST_TAG
          contentDescription = "Splash icon"
        },
    contentScale = ContentScale.Fit,
  )
}

/**
 * Bottom-centre branding image. Capped at ~200dp × ~80dp per the SplashScreen spec; the 60dp
 * bottom inset matches the documented gap between the branding asset and the bottom edge of the
 * splash window so the rendered PNG reads the same as an on-device launch.
 */
@Composable
private fun BoxScope.SplashBranding(brandingImage: Painter) {
  Image(
    painter = brandingImage,
    contentDescription = null,
    modifier =
      Modifier.align(Alignment.BottomCenter)
        .padding(bottom = 60.dp)
        .sizeIn(maxWidth = 200.dp, maxHeight = 80.dp)
        .semantics {
          testTag = SPLASH_BRANDING_TEST_TAG
          contentDescription = "Splash branding"
        },
    contentScale = ContentScale.Fit,
  )
}

/**
 * Test tags exposed on the surface, icon, optional iconBackground ring, and optional branding
 * image. The renderer doesn't read them at all; they're here so unit tests
 * (`SplashScreenSurfaceTest`) and downstream Compose UI tests can locate the parts of the
 * splash without relying on string content descriptions, which are intentionally minimal so the
 * helper plays well with `@Preview(locale = ...)` fan-out.
 */
const val SPLASH_SURFACE_TEST_TAG: String = "SplashScreenSurface"
const val SPLASH_ICON_TEST_TAG: String = "SplashScreenSurface.icon"
const val SPLASH_ICON_BACKGROUND_TEST_TAG: String = "SplashScreenSurface.iconBackground"
const val SPLASH_BRANDING_TEST_TAG: String = "SplashScreenSurface.brandingImage"
