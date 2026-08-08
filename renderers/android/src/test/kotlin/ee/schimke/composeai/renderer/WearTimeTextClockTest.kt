package ee.schimke.composeai.renderer

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.TimeText
import androidx.wear.compose.material3.timeTextCurvedText
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The end-to-end half of issue #3239: with [ShadowWearTimeSource] registered AND
 * `androidx.wear.compose.materialcore.ResourcesKt` instrumented, Wear's default `TimeText` paints
 * [PreviewClock.DEFAULT_TIME] instead of the host's wall clock — and `fixedTime=off` hands the host
 * clock back, rather than substituting some other fixed value.
 *
 * `shadows` / `instrumentedPackages` are spelled out on `@Config` to mirror what
 * `GenerateRobolectricPropertiesTask` writes into the generated `robolectric.properties`;
 * `GenerateRobolectricPropertiesTaskTest` pins that the two agree. Both are load-bearing —
 * Robolectric can't shadow a class it didn't rewrite — which is why the instrumentation is asserted
 * separately below.
 *
 * Robolectric matches `instrumentedPackages` entries as class-name prefixes, which is why naming a
 * *class* works, and is why the whole of `androidx.wear.compose.materialcore` does not have to be
 * rewritten to reach the one function that reads the clock.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
  sdk = [34],
  instrumentedPackages = ["androidx.wear.compose.materialcore.ResourcesKt"],
  shadows = [ShadowWearTimeSource::class],
)
class WearTimeTextClockTest {

  @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

  @After
  fun restoreGlobals() {
    System.clearProperty(PreviewClock.PROPERTY)
  }

  /**
   * Asserts the string `TimeText`'s content lambda receives — that is the value `DefaultTimeSource`
   * produced, and it is the same value the curved text paints. Reading it here rather than off the
   * semantics tree keeps the test about the clock rather than about how curved text reports itself.
   */
  @Test
  fun `default TimeText paints the pinned clock`() {
    assertEquals(EXPECTED, paintedTime())
  }

  /** The configured instant, not just *a* fixed one — proves the property is actually read. */
  @Test
  fun `an explicit fixedTime is what TimeText paints`() {
    System.setProperty(PreviewClock.PROPERTY, "09:41")

    assertEquals("9:41", paintedTime())
  }

  /**
   * The escape hatch has to mean what it says. Shadowing rather than moving Robolectric's
   * `SystemClock` is what makes this possible: with the clock pinned by mutating `SystemClock`,
   * `off` would still have painted the emulated clock's epoch-era time, because the instrumentation
   * is unconditional.
   */
  @Test
  fun `off hands back the host wall clock rather than a different fixed time`() {
    System.setProperty(PreviewClock.PROPERTY, "off")

    val before = System.currentTimeMillis()
    val wear = wearCurrentTimeMillis()

    assertTrue(
      "expected the host clock (~$before), got $wear",
      wear >= before && wear - before < 60_000,
    )
  }

  /**
   * The instrumentation half on its own. Without the `instrumentedPackages` entry Robolectric never
   * rewrites the class, the shadow is inert, and this reads the host clock — failing by ~55 years.
   */
  @Test
  fun `the wear clock class is instrumented, so the shadow is reachable`() {
    assertEquals(PreviewClock.pinnedTimeMillis()!!, wearCurrentTimeMillis())
  }

  private fun paintedTime(): String? {
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
    return painted
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
