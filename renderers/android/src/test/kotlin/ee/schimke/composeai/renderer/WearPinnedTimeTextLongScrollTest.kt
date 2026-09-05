package ee.schimke.composeai.renderer

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.LocalReduceMotion
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * End-to-end regression for the horologist sectioned-list shape: a Wear Material
 * `ScalingLazyColumn` of section headers and chips under a `Scaffold` whose `TimeText` is
 * **pinned** — it stays drawn over the top of the viewport while the list scrolls under it, so the
 * head of every slice differs from the same content seen lower down in the previous one.
 *
 * Before the fix four of horologist's six `SectionedList*` LONG renders were broken the same way:
 * the matcher scored a 372 px shift over a 12-row all-black overlap at 0.3/px, beating the true 307
 * px shift (8.5/px, contaminated by the `TimeText`), and painted the next slice from its top row —
 * `10:10` and all — sixty rows too high, repeating a section header at the seam. The stitcher now
 * refuses candidates whose overlap carries no signal, the driver measures each stride off the
 * content's own semantics bounds, and every seam is verified and reported.
 *
 * Asserts on the three things a consumer can check: every stride landed, every seam verified, and
 * the pinned chrome band at the top of the output never recurs lower down.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w192dp-h192dp-round-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WearPinnedTimeTextLongScrollTest {

  @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

  @Before
  fun recordRoborazzi() {
    System.setProperty("roborazzi.test.record", "true")
  }

  @After
  fun clearRoborazzi() {
    System.clearProperty("roborazzi.test.record")
  }

  private val sections =
    listOf(
      "Today" to listOf("Meet with Sarah", "Pay internet bill", "Piano lessons"),
      "Tomorrow" to listOf("Book holidays", "Water plants"),
      "Later this week" to listOf("Hang paintings", "Call mom", "Buy new runners"),
      "Next week" to listOf("Dentist", "Renew passport", "Team offsite", "Car service"),
    )

  @Test
  fun `a ScalingLazyColumn under a pinned TimeText stitches without repeating a seam`() {
    composeRule.mainClock.autoAdvance = false
    composeRule.setContent {
      // The render loop flattens `ScalingLazyColumn` motion around every LONG capture
      // (`forceLongFlatten` in `RobolectricRenderTest`); this drives `handleLongCapture` directly,
      // so provide the same flattening here. Mid-scale items at the viewport edges are not a
      // translation of the previous slice and no matcher can align them.
      CompositionLocalProvider(LocalReduceMotion provides true) {
        MaterialTheme {
          val listState = rememberScalingLazyListState()
          Scaffold(
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colors.background),
            timeText = { TimeText() },
          ) {
            ScalingLazyColumn(modifier = Modifier.fillMaxSize(), state = listState) {
              item { Text("My tasks", modifier = Modifier.height(48.dp)) }
              for ((title, tasks) in sections) {
                item {
                  Row(
                    modifier = Modifier.height(48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                  ) {
                    Text(title)
                  }
                }
                for (task in tasks) {
                  item {
                    Chip(
                      label = { Text(task) },
                      onClick = {},
                      colors = ChipDefaults.secondaryChipColors(),
                      modifier = Modifier.fillMaxWidth(),
                    )
                  }
                }
              }
            }
          }
        }
      }
    }
    composeRule.mainClock.advanceTimeByFrame()
    composeRule.mainClock.advanceTimeByFrame()

    // Under `build/` rather than the JUnit temp dir so a failing run leaves the stitch (and, with
    // `COMPOSEAI_KEEP_SCROLL_SLICES=1`, its slices) behind for a human to look at.
    val outDir = File("build/wear-pinned-timetext-long").apply { mkdirs() }
    val out = File(outDir, "pinned_timetext_long.png")
    ScrollDriveDiagnostics.beginPreview()
    val written =
      handleLongCapture(
        rule = composeRule,
        scroll = ScrollCapture(mode = ScrollMode.LONG),
        previewId = "WearPinnedTimeTextLongScrollTest",
        heightDp = 192,
        isRound = true,
        outputFile = out,
      )
    assertTrue("LONG capture must write the stitched PNG", written)

    val unlanded = ScrollDriveDiagnostics.drainPreview()
    val unverified = ScrollDriveDiagnostics.drainSeams()
    assertEquals(
      "every stride must land on its planned offset: " +
        unlanded.joinToString("\n") { it.step.describe() },
      0,
      unlanded.size,
    )
    assertEquals(
      "every seam must verify: " + unverified.joinToString("\n") { it.seam.describe() },
      0,
      unverified.size,
    )

    val img = ImageIO.read(out)
    // 192 dp at xhdpi = 384 px; four sections of chips scroll well past two viewports.
    assertTrue("expected a multi-slice stitch, got ${img.height}px", img.height > 384 * 2)
    assertNoRepeatOfTopChrome(img, viewportPx = 384)
  }

  /**
   * The pinned `TimeText` band is the first rows of the output that carry any bright pixels. In a
   * clean stitch it appears exactly once, at the top; a seam painted from a slice's top row stamps
   * it again wherever that seam landed. Compare the band against every window lower down and fail
   * if one matches near-exactly.
   */
  private fun assertNoRepeatOfTopChrome(img: BufferedImage, viewportPx: Int) {
    val w = img.width
    val lum = Array(img.height) { y -> IntArray(w) { x -> luminance(img.getRGB(x, y)) } }
    val firstBright = (0 until viewportPx / 2).first { y -> lum[y].any { it > 128 } }
    val bandTop = firstBright
    val bandRows = 24
    val band = (0 until bandRows).map { lum[bandTop + it] }
    val perPixelTolerance = 2L * w * bandRows
    for (y in bandTop + bandRows until img.height - bandRows) {
      var sad = 0L
      for (k in 0 until bandRows) {
        val a = band[k]
        val b = lum[y + k]
        for (x in 0 until w) sad += kotlin.math.abs(a[x] - b[x])
        if (sad > perPixelTolerance) break
      }
      assertTrue(
        "the pinned TimeText band (rows $bandTop..${bandTop + bandRows}) recurs at row $y",
        sad > perPixelTolerance,
      )
    }
  }

  private fun luminance(argb: Int): Int {
    if ((argb ushr 24) and 0xFF == 0) return 0
    val r = (argb ushr 16) and 0xFF
    val g = (argb ushr 8) and 0xFF
    val b = argb and 0xFF
    return (r * 299 + g * 587 + b * 114) / 1000
  }
}
