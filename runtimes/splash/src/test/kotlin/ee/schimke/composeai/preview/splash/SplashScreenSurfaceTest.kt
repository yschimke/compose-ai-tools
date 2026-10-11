package ee.schimke.composeai.preview.splash

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Robolectric smoke test for [SplashScreenSurface]: composes without throwing, centres the icon,
 * and honours the optional `iconBackground` / `brandingImage` layers. SDK 33 +
 * `GraphicsMode.NATIVE` like the other runtime tests (SDK 36 needs JDK 21); Compose test deps from
 * `compose-bom-compat`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SplashScreenSurfaceTest {

  @Suppress("DEPRECATION") @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

  /**
   * Just an [icon]: surface and icon laid out, optional layers absent.
   */
  @Test
  fun `renders surface and centered icon with no optional layers`() {
    composeRule.setContent {
      FixedSizeSplash { SplashScreenSurface(icon = ColorPainter(Color.Red)) }
    }
    composeRule.waitForIdle()

    composeRule.onNodeWithTag(SPLASH_SURFACE_TEST_TAG).assertIsDisplayed()
    composeRule.onNodeWithTag(SPLASH_ICON_TEST_TAG).assertIsDisplayed()
    composeRule.onAllNodesWithTag(SPLASH_ICON_BACKGROUND_TEST_TAG).assertCountEquals(0)
    composeRule.onAllNodesWithTag(SPLASH_BRANDING_TEST_TAG).assertCountEquals(0)
  }

  /**
   * `iconBackground` set: the backdrop layer composes without displacing surface or icon.
   */
  @Test
  fun `renders icon background ring when iconBackground is supplied`() {
    composeRule.setContent {
      FixedSizeSplash {
        SplashScreenSurface(icon = ColorPainter(Color.Red), iconBackground = Color.Blue)
      }
    }
    composeRule.waitForIdle()

    composeRule.onNodeWithTag(SPLASH_SURFACE_TEST_TAG).assertIsDisplayed()
    composeRule.onNodeWithTag(SPLASH_ICON_BACKGROUND_TEST_TAG).assertIsDisplayed()
    composeRule.onNodeWithTag(SPLASH_ICON_TEST_TAG).assertIsDisplayed()
  }

  /** `brandingImage` non-null — the bottom-centre branding layer composes alongside the icon. */
  @Test
  fun `renders branding image when brandingImage is supplied`() {
    composeRule.setContent {
      FixedSizeSplash {
        SplashScreenSurface(
          icon = ColorPainter(Color.Red),
          brandingImage = ColorPainter(Color.Green),
        )
      }
    }
    composeRule.waitForIdle()

    composeRule.onNodeWithTag(SPLASH_SURFACE_TEST_TAG).assertIsDisplayed()
    composeRule.onNodeWithTag(SPLASH_ICON_TEST_TAG).assertIsDisplayed()
    composeRule.onNodeWithTag(SPLASH_BRANDING_TEST_TAG).assertIsDisplayed()
  }

  /**
   * The icon's centre matches the surface's (from `boundsInRoot`), within 2px for rounding.
   */
  @Test
  fun `icon is centered inside the splash surface`() {
    composeRule.setContent {
      FixedSizeSplash { SplashScreenSurface(icon = ColorPainter(Color.Red)) }
    }
    composeRule.waitForIdle()

    val surfaceBounds =
      composeRule.onNodeWithTag(SPLASH_SURFACE_TEST_TAG).fetchSemanticsNode().boundsInRoot
    val iconBounds =
      composeRule.onNodeWithTag(SPLASH_ICON_TEST_TAG).fetchSemanticsNode().boundsInRoot

    val surfaceCenterX = (surfaceBounds.left + surfaceBounds.right) / 2f
    val surfaceCenterY = (surfaceBounds.top + surfaceBounds.bottom) / 2f
    val iconCenterX = (iconBounds.left + iconBounds.right) / 2f
    val iconCenterY = (iconBounds.top + iconBounds.bottom) / 2f

    val tolerancePx = 2f
    assertEquals(
      "icon horizontal centre should match the splash surface's horizontal centre",
      surfaceCenterX,
      iconCenterX,
      tolerancePx,
    )
    assertEquals(
      "icon vertical centre should match the splash surface's vertical centre",
      surfaceCenterY,
      iconCenterY,
      tolerancePx,
    )
  }

  /**
   * A fixed 400×800dp box, giving a predictable bounding rectangle for the centring check.
   */
  @Composable
  private fun FixedSizeSplash(content: @Composable () -> Unit) {
    Box(modifier = Modifier.size(400.dp, 800.dp)) { content() }
  }
}
