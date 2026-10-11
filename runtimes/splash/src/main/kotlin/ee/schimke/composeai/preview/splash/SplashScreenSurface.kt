package ee.schimke.composeai.preview.splash

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.dp

/**
 * Recreates the Android 12+ SplashScreen window appearance inside a regular `@Preview`; stack
 * `@Preview`s to fan out over `uiMode`, `locale`, `widthDp`, `fontScale`.
 *
 * Layout follows the SplashScreen spec
 * (https://developer.android.com/develop/ui/views/launch/splash-screen#elements):
 * - [background] fills the whole window, like `windowSplashScreenBackground`.
 * - [icon] is centred and circle-masked at ~75% of the icon canvas (the spec's 240dp-in-320dp),
 *   computed against the available footprint so it tracks preview dp overrides.
 * - [iconBackground], if set, is a slightly larger circle behind the icon
 *   (`windowSplashScreenIconBackgroundColor`).
 * - [brandingImage] is bottom-centred, capped at ~200×80dp with ~60dp bottom inset.
 *
 * Qualitative, not a byte-match of SystemUI's compositor. The caller picks colours (no
 * `isSystemInDarkTheme()`), so night-mode variants come from the surrounding multipreview.
 *
 * @param icon the centre drawable, typically the app's `windowSplashScreenAnimatedIcon`.
 * @param background full-bleed colour behind everything. Defaults to opaque white.
 * @param iconBackground optional circular backdrop colour; `null` omits it, as on-device.
 * @param brandingImage optional bottom-centre branding asset; `null` omits it.
 * @param modifier applied to the outer full-bleed `Box`; by default it fills the available space.
 */
@Composable
fun SplashScreenSurface(
  icon: Painter,
  background: Color = Color.White,
  iconBackground: Color? = null,
  brandingImage: Painter? = null,
  modifier: Modifier = Modifier,
) {
  SplashSurfaceLayout(
    icon = icon,
    background = background,
    iconBackground = iconBackground,
    brandingImage = brandingImage,
    modifier = modifier,
    iconScale = null,
  )
}

/**
 * Shared layout behind [SplashScreenSurface] and [AnimatedSplashScreenSurface]. A null [iconScale]
 * skips the `graphicsLayer` entirely (rather than scale 1f), since a layer can shift anti-aliasing
 * and would change static PNGs.
 */
@Composable
internal fun SplashSurfaceLayout(
  icon: Painter,
  background: Color,
  iconBackground: Color?,
  brandingImage: Painter?,
  modifier: Modifier,
  iconScale: (() -> Float)?,
) {
  Box(
    modifier =
      modifier.fillMaxSize().background(background).semantics { testTag = SPLASH_SURFACE_TEST_TAG },
    contentAlignment = Alignment.Center,
  ) {
    SplashIcon(icon = icon, iconBackground = iconBackground, iconScale = iconScale)
    if (brandingImage != null) {
      SplashBranding(brandingImage)
    }
  }
}

/**
 * Centre icon over the optional [iconBackground] ring, sized against the spec's 320dp icon canvas.
 *
 * [iconScale] scales only the icon, not the ring, as on-device the animated icon moves over a
 * static backdrop. Read inside `graphicsLayer` so per-frame changes skip recomposition.
 */
@Composable
private fun BoxScope.SplashIcon(
  icon: Painter,
  iconBackground: Color?,
  iconScale: (() -> Float)?,
) {
  val backdropDiameter = 256.dp
  val iconDiameter = 192.dp
  if (iconBackground != null) {
    Box(
      modifier =
        Modifier.size(backdropDiameter).clip(CircleShape).background(iconBackground).semantics {
          testTag = SPLASH_ICON_BACKGROUND_TEST_TAG
        }
    )
  }
  val scaleModifier =
    if (iconScale == null) {
      Modifier
    } else {
      Modifier.graphicsLayer {
        val scale = iconScale()
        scaleX = scale
        scaleY = scale
      }
    }
  Image(
    painter = icon,
    contentDescription = null,
    modifier =
      Modifier.size(iconDiameter).then(scaleModifier).clip(CircleShape).semantics {
        testTag = SPLASH_ICON_TEST_TAG
        contentDescription = "Splash icon"
      },
    contentScale = ContentScale.Fit,
  )
}

/**
 * Bottom-centre branding image, capped at ~200dp × ~80dp with the spec's 60dp bottom inset.
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
 * Test tags for the surface, icon, ring and branding image, so tests can locate parts without
 * relying on (intentionally minimal) content descriptions.
 */
const val SPLASH_SURFACE_TEST_TAG: String = "SplashScreenSurface"
const val SPLASH_ICON_TEST_TAG: String = "SplashScreenSurface.icon"
const val SPLASH_ICON_BACKGROUND_TEST_TAG: String = "SplashScreenSurface.iconBackground"
const val SPLASH_BRANDING_TEST_TAG: String = "SplashScreenSurface.brandingImage"
