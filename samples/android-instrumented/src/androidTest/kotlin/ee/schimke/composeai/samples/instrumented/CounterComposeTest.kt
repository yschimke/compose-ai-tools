package ee.schimke.composeai.samples.instrumented

import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CounterComposeTest {
  @get:Rule val compose = createAndroidComposeRule<MainActivity>()

  @Test
  fun incrementCounts() {
    compose.onNodeWithTag("count").assertTextEquals("Count: 0")
    compose.onNodeWithTag("increment").performClick()
    compose.onNodeWithTag("count").assertTextEquals("Count: 1")
  }

  @Test
  fun stateSurvivesRecreate() {
    compose.onNodeWithTag("increment").performClick()
    compose.activityRule.scenario.recreate()
    compose.onNodeWithTag("count").assertTextEquals("Count: 1")
  }
}
