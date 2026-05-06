package ee.schimke.composeai.renderer.uiautomator

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Demonstrates that the same [Selector] DSL targets Compose widgets via the `SemanticsNode`
 * backing — no `Button.textAllCaps` quirk, no View-tree dead-end at `AndroidComposeView`,
 * actions invoke `SemanticsActions` lambdas the same way `:daemon:android`'s host already
 * does for content-description-driven dispatches.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h800dp")
class UiAutomatorComposePrototypeTest {

  @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

  @Test
  fun `By text matches a Material3 Button literally without all caps coercion`() {
    var clicks = 0
    rule.setContent { Button(onClick = { clicks++ }) { Text("Submit") } }

    val submit = UiAutomator.findObject(rule, By.text("Submit"))
    assertNotNull("expected to find Submit button", submit)
    assertEquals("Submit", submit!!.text?.toString())
    assertTrue("OnClick should fire", submit.click())
    assertEquals(1, clicks)
  }

  @Test
  fun `clickable predicate selects only the OnClick-bearing node`() {
    rule.setContent {
      Column {
        Button(onClick = {}) { Text("Primary") }
        Text("Just a label")
      }
    }

    val matches = UiAutomator.findObjects(rule, By.clickable())
    val texts = matches.map { it.text?.toString() }
    assertTrue("expected Primary among matches, got $texts", texts.any { it == "Primary" })
    assertTrue("expected NO label among matches, got $texts", texts.none { it == "Just a label" })
  }

  @Test
  fun `By res matches a Modifier testTag`() {
    rule.setContent { Text("Hello", modifier = Modifier.testTag("greeting")) }

    val node = UiAutomator.findObject(rule, By.res("greeting"))
    assertNotNull(node)
    assertEquals("Hello", node!!.text?.toString())
    assertEquals("greeting", node.resourceName)
  }

  @Test
  fun `By desc matches a contentDescription set via Modifier semantics`() {
    rule.setContent {
      Button(onClick = {}, modifier = Modifier.semantics { contentDescription = "close-dialog" }) {
        Text("X")
      }
    }

    val close = UiAutomator.findObject(rule, By.desc("close-dialog"))
    assertNotNull(close)
    assertEquals("close-dialog", close!!.contentDescription?.toString())
  }

  @Test
  fun `inputText routes through SemanticsActions SetText on a BasicTextField`() {
    rule.setContent {
      var value by remember { mutableStateOf("before") }
      BasicTextField(
        value = value,
        onValueChange = { value = it },
        modifier = Modifier.testTag("field"),
      )
    }

    val field = UiAutomator.findObject(rule, By.res("field"))
    assertNotNull(field)
    val accepted = field!!.inputText("after")
    assertTrue("SetText should be accepted on BasicTextField", accepted)
    // The state hoisted into the test composable updated via onValueChange — re-fetch and check.
    val refetched = UiAutomator.findObject(rule, By.res("field"))
    assertEquals("after", refetched?.text?.toString())
  }

  @Test
  fun `click on a non-clickable Text returns false and reports unsupported`() {
    rule.setContent { Text("label") }

    val labelNode = UiAutomator.findObject(rule, By.text("label"))
    assertNotNull(labelNode)
    // SemanticsNode for plain Text has no OnClick action, so we report unsupported rather than
    // silently succeeding.
    assertEquals(false, labelNode!!.click())
  }

  @Test
  fun `negative match returns null`() {
    rule.setContent { Text("Hello") }
    assertNull(UiAutomator.findObject(rule, By.text("Goodbye")))
  }

  /**
   * Default (merged) tree puts text + OnClick on the same node — `.click()` fires. Unmerged
   * tree separates them — `By.text("Submit")` finds the inner `Text`, which has no `OnClick`,
   * so `.click()` reports unsupported. This pins the documented trade-off so it doesn't
   * silently change.
   */
  @Test
  fun `useUnmergedTree splits Button into separate text and click nodes`() {
    var clicks = 0
    rule.setContent { Button(onClick = { clicks++ }) { Text("Submit") } }

    // Merged (default) — one node with both text and OnClick.
    val merged = UiAutomator.findObject(rule, By.text("Submit"))
    assertNotNull(merged)
    assertTrue(merged!!.click())
    assertEquals(1, clicks)

    // Unmerged — finds the inner Text, which has no OnClick.
    val unmergedTextNode = UiAutomator.findObject(rule, By.text("Submit"), useUnmergedTree = true)
    assertNotNull(unmergedTextNode)
    assertEquals("Submit", unmergedTextNode!!.text?.toString())
    assertEquals(false, unmergedTextNode.click())
    assertEquals("clicks didn't change on the inner Text", 1, clicks)

    // Unmerged Button parent — clickable but text comes only from merging, so the unmerged
    // Button's own Text property is empty.
    val unmergedButton = UiAutomator.findObject(rule, By.clickable(), useUnmergedTree = true)
    assertNotNull(unmergedButton)
    assertTrue(unmergedButton!!.text.isNullOrEmpty())
    assertTrue(unmergedButton.click())
    assertEquals(2, clicks)
  }
}
