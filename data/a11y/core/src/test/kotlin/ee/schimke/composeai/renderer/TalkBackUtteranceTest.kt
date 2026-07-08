package ee.schimke.composeai.renderer

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Acceptance coverage for [TalkBackUtterance] (issue #1956 Phase 2): button, checkbox
 * checked/unchecked, disabled, heading, slider with `stateDescription`, plus the affix/ordering
 * rules. Plain JUnit — the composer is pure.
 */
class TalkBackUtteranceTest {

  private fun node(
    label: String = "",
    role: String? = null,
    states: List<String> = emptyList(),
  ): AccessibilityNode =
    AccessibilityNode(label = label, role = role, states = states, boundsInScreen = "0,0,10,10")

  @Test
  fun button() {
    assertEquals(
      "Buy now, button, double-tap to activate",
      TalkBackUtterance.compose(
        node(label = "Buy now", role = "Button", states = listOf("clickable"))
      ),
    )
  }

  @Test
  fun checkboxChecked() {
    // A clickable + checkable control activates via "double-tap to toggle", and its checked state
    // is spoken as the word "checked".
    assertEquals(
      "Remember me, checkbox, checked, double-tap to toggle",
      TalkBackUtterance.compose(
        node(label = "Remember me", role = "CheckBox", states = listOf("checked", "clickable"))
      ),
    )
  }

  @Test
  fun checkboxUnchecked() {
    // TalkBack speaks the unchecked state as "not checked", not the raw "unchecked" token.
    assertEquals(
      "Remember me, checkbox, not checked, double-tap to toggle",
      TalkBackUtterance.compose(
        node(label = "Remember me", role = "CheckBox", states = listOf("unchecked", "clickable"))
      ),
    )
  }

  @Test
  fun disabledSuppressesUsageHint() {
    // A disabled control is announced as "disabled" and does NOT offer "double-tap to activate" —
    // you can't operate it.
    assertEquals(
      "Submit, button, disabled",
      TalkBackUtterance.compose(
        node(label = "Submit", role = "Button", states = listOf("clickable", "disabled"))
      ),
    )
  }

  @Test
  fun heading() {
    assertEquals(
      "Settings, heading",
      TalkBackUtterance.compose(node(label = "Settings", role = "Heading")),
    )
  }

  @Test
  fun headingFromStateWhenRoleless() {
    // If ATF can only surface heading as a state token, it still reads as "heading".
    assertEquals(
      "Settings, heading",
      TalkBackUtterance.compose(node(label = "Settings", states = listOf("heading"))),
    )
  }

  @Test
  fun sliderWithStateDescription() {
    // A slider's verbatim getStateDescription() ("70%") is read as-is after the role.
    assertEquals(
      "Volume, seekbar, 70%",
      TalkBackUtterance.compose(node(label = "Volume", role = "SeekBar", states = listOf("70%"))),
    )
  }

  @Test
  fun rolelessLabelOnly() {
    // A plain text node — no role, no states — is just its label. TalkBack doesn't say "text view".
    assertEquals(
      "Workouts this week",
      TalkBackUtterance.compose(node(label = "Workouts this week")),
    )
  }

  @Test
  fun longClickableAddsLongPressHint() {
    assertEquals(
      "Open, button, double-tap to activate, double-tap and hold to long press",
      TalkBackUtterance.compose(
        node(label = "Open", role = "Button", states = listOf("clickable", "long-clickable"))
      ),
    )
  }

  @Test
  fun explicitHintTextIsAppendedLast() {
    assertEquals(
      "Email, edit box, double-tap to activate, Enter your work address",
      TalkBackUtterance.compose(
        node(
          label = "Email",
          states = listOf("editable", "clickable", "hint: Enter your work address"),
        )
      ),
    )
  }

  @Test
  fun blankLabelStartsWithRole() {
    assertEquals("image", TalkBackUtterance.compose(node(label = "  ", role = "Image")))
  }
}
