package ee.schimke.composeai.renderer

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import ee.schimke.composeai.scroll.ScrollAxis
import ee.schimke.composeai.scroll.ScrollDriveResult
import ee.schimke.composeai.scroll.driveScrollByViewport
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Android LONG driver has to take **viewport-sized** strides.
 *
 * `driveScrollByViewport` sizes each step as `min(stepPx, maxValue - value, headroom)` and reports
 * the running total to `onSlice`, which is what positions each slice in the stitched image. That
 * arithmetic assumes `maxValue - value` is a pixel distance. It isn't always: a plain `LazyColumn`
 * publishes a **placeholder** `maxValue` of `100.0` before its extent is known (measured in
 * `WearTlcScrollSemanticsProbeTest`, where it jumps to `5010.0` once a real scroll has happened).
 * Clamping a 400 px stride against that placeholder yields a 100 px step, and the stitched slices
 * are spaced at a distance the content never actually travelled.
 *
 * Wear's `TransformingLazyColumn` publishes a true pixel extent from the first frame, so it is
 * covered here as the case that must keep working unchanged.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AndroidScrollDriverStepTest {

  @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

  private val stepPx = 400f

  /** Runs the LONG driver over the composed content and returns the per-slice offsets. */
  private fun driveAndCollect(): Pair<ScrollDriveResult, List<Float>> {
    composeRule.mainClock.advanceTimeByFrame()
    composeRule.mainClock.advanceTimeByFrame()
    val offsets = mutableListOf<Float>()
    val result =
      driveScrollByViewport(
        rule = composeRule,
        axis = ScrollAxis.VERTICAL,
        stepPx = stepPx,
        maxScrollPx = 0,
      ) {
        offsets += it
      }
    println("[driver] result=$result offsets=$offsets")
    return result to offsets
  }

  /** The gap between consecutive slices — what the stitcher uses to place them. */
  private fun strides(offsets: List<Float>): List<Float> =
    offsets.zipWithNext { a, b -> b - a }.filter { it > 0f }

  @Test
  fun `a plain LazyColumn is driven in viewport-sized strides`() {
    composeRule.mainClock.autoAdvance = false
    composeRule.setContent {
      LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(40) { index ->
          Text(text = "Row $index", modifier = Modifier.fillMaxWidth().height(60.dp))
        }
      }
    }
    val (_, offsets) = driveAndCollect()
    val strides = strides(offsets)
    assertTrue("the driver must take at least one stride (got $offsets)", strides.isNotEmpty())
    // The placeholder `maxValue` is 100.0, so a clamped driver's first stride is 100 px. A driver
    // that steps in real pixels takes the full 400 px.
    assertTrue(
      "the first stride must be the requested ${stepPx}px, not a placeholder-clamped one " +
        "(strides=$strides)",
      strides.first() >= stepPx - 1f,
    )
  }

  /**
   * Wear's `TransformingLazyColumn` publishes a genuine pixel extent from the first frame, so it
   * was never affected by the placeholder — this pins that so the fix can't regress it.
   */
  @Test
  fun `a TransformingLazyColumn is driven in viewport-sized strides`() {
    composeRule.mainClock.autoAdvance = false
    composeRule.setContent {
      androidx.wear.compose.material3.MaterialTheme {
        TransformingLazyColumn(modifier = Modifier.fillMaxSize()) {
          items(40) { index ->
            androidx.wear.compose.material3.Text(
              text = "Row $index",
              modifier = Modifier.fillMaxWidth().height(60.dp),
            )
          }
        }
      }
    }
    val (_, offsets) = driveAndCollect()
    val strides = strides(offsets)
    assertTrue("the driver must take at least one stride (got $offsets)", strides.isNotEmpty())
    assertTrue(
      "the first stride must be the requested ${stepPx}px (strides=$strides)",
      strides.first() >= stepPx - 1f,
    )
  }
}
