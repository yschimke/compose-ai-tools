package ee.schimke.composeai.preview.splash

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
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
 * Robolectric test for [AnimatedSplashScreenSurface], with [SplashScreenSurfaceTest]'s SDK 33 +
 * `GraphicsMode.NATIVE` pin.
 *
 * The clock is driven manually (`mainClock.autoAdvance = false`), as `@AnimatedPreview` does, so a
 * pulse that ticks here ticks in the GIF. Assertions are on pixels ([probeArgb]), not bounds: the
 * pulse is a `graphicsLayer`, which doesn't affect layout bounds.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AnimatedSplashScreenSurfaceTest {

  @Suppress("DEPRECATION") @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

  /** The animated variant emits the same tagged layers as the static surface. */
  @Test
  fun `renders the same tagged layers as the static surface`() {
    composeRule.mainClock.autoAdvance = false
    composeRule.setContent {
      FixedSizeSplash {
        AnimatedSplashScreenSurface(
          icon = ColorPainter(Color.Red),
          iconBackground = Color.Blue,
          brandingImage = ColorPainter(Color.Green),
        )
      }
    }

    composeRule.onNodeWithTag(SPLASH_SURFACE_TEST_TAG).assertIsDisplayed()
    composeRule.onNodeWithTag(SPLASH_ICON_TEST_TAG).assertIsDisplayed()
    composeRule.onNodeWithTag(SPLASH_ICON_BACKGROUND_TEST_TAG).assertIsDisplayed()
    composeRule.onNodeWithTag(SPLASH_BRANDING_TEST_TAG).assertIsDisplayed()
  }

  /** Optional layers stay opt-in on the animated path too. */
  @Test
  fun `omits optional layers when their parameters default to null`() {
    composeRule.mainClock.autoAdvance = false
    composeRule.setContent {
      FixedSizeSplash { AnimatedSplashScreenSurface(icon = ColorPainter(Color.Red)) }
    }

    composeRule.onNodeWithTag(SPLASH_ICON_TEST_TAG).assertIsDisplayed()
    composeRule.onAllNodesWithTag(SPLASH_ICON_BACKGROUND_TEST_TAG).assertCountEquals(0)
    composeRule.onAllNodesWithTag(SPLASH_BRANDING_TEST_TAG).assertCountEquals(0)
  }

  /**
   * The pulse reaches the drawn output: a point 103dp from centre is outside the icon at rest
   * (radius 96dp) and inside it at peak scale (`1.15 × 96dp` ≈ 110dp), with ~7dp margin each side
   * for the anti-aliased rim.
   */
  @Test
  fun `pulse grows the rendered icon`() {
    composeRule.mainClock.autoAdvance = false
    var density = 0f
    composeRule.setContent {
      density = LocalDensity.current.density
      FixedSizeSplash {
        AnimatedSplashScreenSurface(
          icon = ColorPainter(Color.Red),
          pulse = SplashIconPulse(scaleTo = 1.15f, durationMs = PULSE_HALF_CYCLE_MS),
        )
      }
    }
    val probeOffsetPx = (PROBE_OFFSET_DP * density).toInt()

    val atRest = probeArgb(probeOffsetPx)
    // 780ms rather than the full 800: the reversing repeat flips direction at the boundary, and one
    // frame either side of the turn is a needlessly precise thing to depend on. FastOutSlowIn is
    // ~0.999 of the way to target here, so the scale is peak for our purposes.
    composeRule.mainClock.advanceTimeBy(780L)
    val atPeak = probeArgb(probeOffsetPx)

    assertEquals(
      "probe should sit on the white splash background before the icon grows",
      Color.White.toArgb(),
      atRest,
    )
    assertEquals("probe should sit on the icon at peak scale", Color.Red.toArgb(), atPeak)
  }

  /**
   * Growth is centred: probing symmetrically at peak scale catches a regression that moved
   * `transformOrigin` to a corner.
   */
  @Test
  fun `pulse grows symmetrically about the icon centre`() {
    composeRule.mainClock.autoAdvance = false
    var density = 0f
    composeRule.setContent {
      density = LocalDensity.current.density
      FixedSizeSplash {
        AnimatedSplashScreenSurface(
          icon = ColorPainter(Color.Red),
          pulse = SplashIconPulse(scaleTo = 1.15f, durationMs = PULSE_HALF_CYCLE_MS),
        )
      }
    }
    val probeOffsetPx = (PROBE_OFFSET_DP * density).toInt()
    composeRule.mainClock.advanceTimeBy(780L)

    assertEquals(
      "icon should cover the probe to the right of centre at peak scale",
      Color.Red.toArgb(),
      probeArgb(probeOffsetPx),
    )
    assertEquals(
      "icon should cover the probe to the left of centre at peak scale",
      Color.Red.toArgb(),
      probeArgb(-probeOffsetPx),
    )
  }

  /**
   * Samples one pixel [offsetPx] right of the surface's centre (negative is left), on the centre
   * row.
   *
   * Draws into a software `Canvas` rather than `captureToImage()`, whose `forceRedraw` busy-waits
   * for a draw callback that only fires when the (deliberately paused) test clock pumps the looper.
   */
  private fun probeArgb(offsetPx: Int): Int {
    val view: ViewGroup = composeRule.activity.findViewById(android.R.id.content)
    val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
    view.draw(Canvas(bitmap))
    // `boundsInRoot` is relative to the Compose root, which is the only child of the content view
    // we
    // just drew, so these coordinates index the bitmap directly.
    val bounds =
      composeRule.onNodeWithTag(SPLASH_SURFACE_TEST_TAG).fetchSemanticsNode().boundsInRoot
    val x = ((bounds.left + bounds.right) / 2f).toInt() + offsetPx
    val y = ((bounds.top + bounds.bottom) / 2f).toInt()
    return bitmap.getPixel(x, y)
  }

  /** Same fixed-size wrapper the static surface's test uses, for comparable geometry. */
  @Composable
  private fun FixedSizeSplash(content: @Composable () -> Unit) {
    Box(modifier = Modifier.size(400.dp, 800.dp)) { content() }
  }

  private companion object {
    const val PULSE_HALF_CYCLE_MS = 800

    /**
     * Probe distance from the icon's centre, in dp. Between the icon's resting radius (96dp) and
     * its radius at peak scale (~110dp), with margin on both sides.
     */
    const val PROBE_OFFSET_DP = 103f
  }
}
