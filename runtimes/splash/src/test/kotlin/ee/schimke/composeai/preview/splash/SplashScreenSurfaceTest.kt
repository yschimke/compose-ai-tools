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
 * Robolectric smoke test for [SplashScreenSurface]. The composable is layout-only — no async
 * inflation, no platform calls — so the test is shallow on purpose: prove the surface composes
 * without throwing, prove the icon is centred inside the bounding box (so a regression that
 * moves the icon off-centre fails here), and prove the optional `iconBackground` /
 * `brandingImage` parameters are honoured (present when supplied, absent when omitted).
 *
 * Pinned to SDK 33 + `GraphicsMode.NATIVE` to match the rest of the preview-runtime test suite
 * — Robolectric SDK 36 requires JDK 21 and this repo's daemon stays on JDK 17 (see the comment
 * in `:samples:android`'s build script). The Compose UI test deps come from
 * `compose-bom-compat`, the same older BOM the main source set compiles against.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SplashScreenSurfaceTest {

  @Suppress("DEPRECATION")
  @get:Rule
  val composeRule = createAndroidComposeRule<ComponentActivity>()

  /**
   * Minimum-viable invocation — just an [icon] on the default white background. Asserts the
   * surface and the icon are both laid out, and the optional layers (icon backdrop ring and
   * branding image) are NOT present when their parameters default to `null`.
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
   * `iconBackground` non-null — the circular backdrop layer composes. Repeats the surface +
   * icon assertions because the optional layer must not displace the main two.
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
   * Icon must be centred horizontally and vertically inside the surface. We fetch each node's
   * `boundsInRoot` (in px) and compare centre coordinates — a regression that displaces the
   * icon (e.g. anchoring to `TopStart` instead of `Center`) fails this assertion within a
   * generous 2px tolerance to absorb sub-pixel rounding from the layout pass.
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
   * Wraps the surface in a fixed-size box so the tests have a predictable bounding rectangle
   * for the centring assertion regardless of the surrounding ComponentActivity's window size
   * under Robolectric. 400 × 800 dp approximates the phone-shaped `@Preview` callers typically
   * use; the size doesn't have to match a real device — the assertion is about relative
   * centring, not absolute pixel coordinates.
   */
  @Composable
  private fun FixedSizeSplash(content: @Composable () -> Unit) {
    Box(modifier = Modifier.size(400.dp, 800.dp)) { content() }
  }
}
