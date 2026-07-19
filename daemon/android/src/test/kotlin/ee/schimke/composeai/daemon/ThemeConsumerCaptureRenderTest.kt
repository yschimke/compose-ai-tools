package ee.schimke.composeai.daemon

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import ee.schimke.composeai.data.theme.ResolvedThemeTokens
import ee.schimke.composeai.data.theme.ThemeConsumerAttribution
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * End-to-end render check on the Robolectric (Android) backend: a `Surface` clipped to
 * `MaterialTheme.shapes.medium` must have that shape captured off its rendered modifiers by
 * [ThemeConsumerCapture] and attributed to the `medium` scale role by [ThemeConsumerAttribution] —
 * the capture leg that lights up the shape (third M3-triad) attribution. Runs through the same
 * held-rule sandbox path the daemon uses, so a pass here matches production capture.
 *
 * Pinned to `sdk = 35` like the other `:daemon:android` self-tests: Robolectric SDK 36 needs a JDK
 * 21 test JVM, and the repo toolchain is JDK 17.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h800dp")
class ThemeConsumerCaptureRenderTest {

  @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

  @Test
  fun `captures a rendered Surface's Material shape and attributes it to medium`() {
    // The theme's resolved `medium` value, captured from inside composition so the assertion
    // doesn't hard-code a version-specific `Shape.toString()`.
    var mediumShape = ""
    rule.setContent {
      MaterialTheme {
        mediumShape = MaterialTheme.shapes.medium.toString()
        // Clickable Surface → a real SemanticsNode carrying the clip-shape modifier.
        Surface(onClick = {}, shape = MaterialTheme.shapes.medium) { Text("Hi") }
      }
    }

    val root = rule.onRoot(useUnmergedTree = true).fetchSemanticsNode()
    val facts = ThemeConsumerCapture.extractFacts(root)

    assertTrue(
      "expected a node whose captured shape is the theme's medium ($mediumShape); " +
        "got ${facts.map { it.shape }}",
      facts.any { it.shape == mediumShape },
    )

    val resolved =
      ResolvedThemeTokens(
        colorScheme = emptyMap(),
        typography = emptyMap(),
        shapes = linkedMapOf("medium" to mediumShape),
      )
    val consumers = ThemeConsumerAttribution.attribute(facts, resolved)
    assertTrue(
      "expected a consumer attributed to the medium shape role; got $consumers",
      consumers.any { "medium" in it.tokens },
    )
  }
}
