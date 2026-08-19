package ee.schimke.composeai.renderer

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * What does ATF see for a Wear `Button(icon = …, label = { Text(…) })` — issue #4253.
 *
 * The reported hierarchy had the click on one element and the word on another, and nothing carrying
 * both, so the inspection layer announced the button as `(unlabelled)`. Every rule that fixes that
 * rests on a claim about the shape ATF actually produces here, and the `:data-a11y-core` unit tests
 * assert against hand-written node lists — which prove the projection, not the shape.
 *
 * This is the shape, measured: run the real ATF walk over a real Wear button and report what comes
 * out. It asserts the two properties the roll-up depends on — that the stop the user reaches
 * carries the click, and that the word is on a node underneath it — plus the announcement the walk
 * now produces end to end.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WearButtonA11yHierarchyProbeTest {

  @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

  @Test
  fun `the stop carries the click and the label its child holds`() {
    composeRule.setContent {
      MaterialTheme {
        Button(
          onClick = {},
          // The kit's `Icon=Yes` cell draws a decorative icon (`contentDescription = null`), so
          // the icon slot contributes nothing to the announcement — an unlabelled box stands in.
          icon = { Box(Modifier.size(24.dp)) },
          label = { Text("Filled") },
        )
      }
    }
    composeRule.waitForIdle()

    val nodes =
      AccessibilityChecker.analyze("wear-button", composeRule.activity.window.decorView).nodes
    println("[a11y-probe] " + nodes.joinToString("\n              "))

    val stops = nodes.filter { it.merged }
    val clickable = stops.filter { it.states.contains("clickable") }
    assertEquals("expected exactly one clickable focus stop: $nodes", 1, clickable.size)
    // The point of the fix: the stop a screen reader lands on announces the button's word, whether
    // ATF put that word on the stop itself or on a child it merges.
    assertEquals("Filled", clickable.single().label)
  }
}
