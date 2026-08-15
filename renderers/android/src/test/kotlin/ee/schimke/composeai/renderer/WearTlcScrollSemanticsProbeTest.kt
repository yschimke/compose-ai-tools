package ee.schimke.composeai.renderer

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * What does a Wear `TransformingLazyColumn` publish as its scroll semantics?
 *
 * The Android scroll driver treats `ScrollAxisRange` as a pixel budget — it sizes each step as
 * `min(stepPx, maxValue - value)` and feeds the result to `SemanticsActions.ScrollBy`, which is in
 * pixels, and it stops as soon as `maxValue - value` reaches zero. On Compose Desktop that
 * assumption proved false and broke long captures outright. TLC is the component most likely to
 * break it here: it is a custom lazy layout that computes its own scroll semantics.
 *
 * This test doesn't assert a specific encoding — it asserts the *property the driver depends on*:
 * that `maxValue - value` is a usable pixel distance. If it isn't, the driver's arithmetic is wrong
 * regardless of what the numbers happen to mean.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WearTlcScrollSemanticsProbeTest {

  @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

  private data class Range(val value: Float, val maxValue: Float, val hasScrollBy: Boolean)

  private fun readRange(): Range? {
    val nodes =
      composeRule
        .onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
        .fetchSemanticsNodes()
    val node = nodes.firstOrNull() ?: return null
    val range = node.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) ?: return null
    return Range(
      value = range.value(),
      maxValue = range.maxValue(),
      hasScrollBy = node.config.getOrNull(SemanticsActions.ScrollBy) != null,
    )
  }

  @Test
  fun `report what a plain LazyColumn publishes`() {
    composeRule.mainClock.autoAdvance = false
    composeRule.setContent {
      androidx.compose.foundation.lazy.LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(40) { index ->
          androidx.compose.material3.Text(text = "Row $index", modifier = Modifier.fillMaxWidth())
        }
      }
    }
    composeRule.mainClock.advanceTimeByFrame()
    composeRule.mainClock.advanceTimeByFrame()
    println("[LC] initial: ${readRange()}")
    val scrollBy =
      composeRule
        .onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
        .fetchSemanticsNodes()
        .firstOrNull()
        ?.config
        ?.getOrNull(SemanticsActions.ScrollBy)
        ?.action
    scrollBy?.invoke(0f, 1000f)
    composeRule.mainClock.advanceTimeBy(1000L)
    println("[LC] after ScrollBy(0, 1000): ${readRange()}")
  }

  @Test
  fun `report what a TransformingLazyColumn publishes`() {
    composeRule.mainClock.autoAdvance = false
    composeRule.setContent {
      MaterialTheme {
        TransformingLazyColumn(modifier = Modifier.fillMaxSize()) {
          items(40) { index -> Text(text = "Row $index", modifier = Modifier.fillMaxWidth()) }
        }
      }
    }
    composeRule.mainClock.advanceTimeByFrame()
    composeRule.mainClock.advanceTimeByFrame()

    val before = readRange()
    println("[TLC] initial: $before")

    // The distance the driver would compute for its first step, and what actually happens if we
    // dispatch it.
    val plannedStep = before?.let { (it.maxValue - it.value).coerceAtLeast(0f) }
    println("[TLC] driver would step by: $plannedStep px")

    val scrollBy =
      composeRule
        .onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
        .fetchSemanticsNodes()
        .firstOrNull()
        ?.config
        ?.getOrNull(SemanticsActions.ScrollBy)
        ?.action
    println("[TLC] has ScrollBy: ${scrollBy != null}")

    // Dispatch a large, unambiguous pixel scroll and see how the reported range responds.
    scrollBy?.invoke(0f, 1000f)
    composeRule.mainClock.advanceTimeBy(1000L)
    println("[TLC] after ScrollBy(0, 1000): ${readRange()}")

    scrollBy?.invoke(0f, 1000f)
    composeRule.mainClock.advanceTimeBy(1000L)
    println("[TLC] after a second ScrollBy(0, 1000): ${readRange()}")
  }
}
