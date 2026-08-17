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
 * Robolectric test for [AnimatedSplashScreenSurface]. Mirrors [SplashScreenSurfaceTest]'s SDK 33 +
 * `GraphicsMode.NATIVE` pin, with two differences the static surface doesn't need.
 *
 * **The clock is driven manually.** `mainClock.autoAdvance = false` is the same mechanism
 * `@AnimatedPreview` uses at render time — the renderer advances a paused clock and captures each
 * frame — so a pulse that ticks here is a pulse that ticks in the GIF. That equivalence is the real
 * subject of these tests.
 *
 * **Assertions are on pixels, not bounds.** The pulse is applied through a `graphicsLayer`, which
 * by design does not participate in layout: semantics bounds are identical at rest and at peak
 * scale, so any bounds-based assertion about the scale would pass whether or not the scale was ever
 * applied. [probeArgb] reads the drawn output instead, sampling a point that the icon covers only
 * once it has grown.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AnimatedSplashScreenSurfaceTest {

  @Suppress("DEPRECATION") @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

  /**
   * Structural parity with the static surface — the animated variant emits the same tagged layers,
   * so anything keyed on those tags (downstream Compose UI tests, the semantics wireframe) keeps
   * working across both entry points.
   */
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
   * The pulse actually reaches the drawn output: a point 103dp from centre is outside the icon at
   * rest (the masked circle's radius is half of the 192dp icon, i.e. 96dp) and inside it at peak
   * scale (`1.15 × 96dp` ≈ 110dp). Sampling at rest and again a half-cycle later must therefore see
   * the background first and the icon second.
   *
   * The ~7dp margin either side of the probe is what keeps this off a knife-edge; it is wide enough
   * to absorb the mask's anti-aliased rim without being wide enough to pass on a scale that didn't
   * apply.
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
   * The growth is centred, not anchored to a corner. `graphicsLayer` scales about the layer's
   * centre by default; a regression that set `transformOrigin` to the top-left would grow the icon
   * down and to the right only, leaving the left-hand probe on the background. Sampling
   * symmetrically about the centre at peak scale catches that, where a bounds assertion cannot.
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
   * Samples one pixel of the drawn splash surface, [offsetPx] to the right of the surface's centre
   * (negative offsets sample to the left), on the centre row.
   *
   * Draws the content view straight into a software `Canvas` rather than going through
   * `captureToImage()`. The latter cannot be used here: it calls `forceRedraw`, which busy-waits on
   * real time for a draw callback that only fires when the main looper is pumped — and the looper
   * is only pumped by the test clock, which these tests keep paused on purpose. `View.draw` is
   * synchronous and reads the current animation frame, so it needs no clock at all.
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
