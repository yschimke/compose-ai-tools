package ee.schimke.composeai.renderer

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.TimeText
import androidx.wear.compose.material3.timeTextCurvedText
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The end-to-end half of issue #3239: with the clock pinned AND
 * `androidx.wear.compose.materialcore.ResourcesKt` instrumented, Wear's default `TimeText` paints
 * [PreviewClock.DEFAULT_TIME] instead of the host's wall clock.
 *
 * Both halves are asserted, because either one alone is silently useless — the pin is invisible to
 * Wear without the instrumentation, and the instrumentation alone just moves `TimeText` onto
 * Robolectric's un-pinned clock, which sits 100ms past the epoch. `instrumentedPackages` is spelled
 * out on `@Config` to mirror what `GenerateRobolectricPropertiesTask` writes into the generated
 * `robolectric.properties`; `GenerateRobolectricPropertiesTaskTest` pins that the two agree.
 *
 * Robolectric matches `instrumentedPackages` entries as class-name prefixes, which is why naming a
 * *class* here works, and is why the whole of `androidx.wear.compose.materialcore` does not have to
 * be rewritten to reach the one function that reads the clock.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], instrumentedPackages = ["androidx.wear.compose.materialcore.ResourcesKt"])
class WearTimeTextClockTest {

  @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

  /**
   * Asserts the string `TimeText`'s content lambda receives — that is the value `DefaultTimeSource`
   * produced, and it is the same value the curved text paints. Reading it here rather than off the
   * semantics tree keeps the test about the clock rather than about how curved text reports itself.
   */
  @Test
  fun `default TimeText paints the pinned clock`() {
    PreviewClock.pin()
    var painted: String? = null

    rule.setContent {
      MaterialTheme {
        TimeText { time ->
          painted = time
          timeTextCurvedText(time)
        }
      }
    }
    rule.waitForIdle()

    assertEquals(EXPECTED, painted)
  }

  /**
   * The instrumentation half on its own: an un-pinned render already reads Robolectric's emulated
   * clock rather than the host's, which is only true because the class was rewritten. Without the
   * `instrumentedPackages` entry this reads the wall clock and the assertion fails by ~55 years.
   */
  @Test
  fun `wear reads the emulated clock, not the host wall clock`() {
    assertEquals(android.os.SystemClock.uptimeMillis(), wearCurrentTimeMillis())

    PreviewClock.pin()

    assertEquals(PreviewClock.pinnedTimeMillis()!!, wearCurrentTimeMillis())
  }

  /**
   * Calls the exact function `DefaultTimeSource` calls. By reflection because it is `@RestrictTo`,
   * so a direct call from outside Wear's library group doesn't compile.
   */
  private fun wearCurrentTimeMillis(): Long =
    Class.forName("androidx.wear.compose.materialcore.ResourcesKt")
      .getDeclaredMethod("currentTimeMillis")
      .also { it.isAccessible = true }
      .invoke(null) as Long

  private companion object {
    /** [PreviewClock.DEFAULT_TIME] as Wear formats it — `10:10` reads the same 12h or 24h. */
    const val EXPECTED = "10:10"
  }
}
