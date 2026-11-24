package ee.schimke.composeai.samples.instrumented

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EspressoTest {
  @Test
  fun tapChangesTheLabel() {
    ActivityScenario.launch(ViewActivity::class.java).use {
      onView(withId(ViewActivity.LABEL)).check(matches(withText("Waiting")))
      onView(withId(ViewActivity.BUTTON)).perform(click())
      onView(withId(ViewActivity.LABEL)).check(matches(withText("Tapped")))
    }
  }
}
