package ee.schimke.composeai.renderer.uiautomator

import android.app.Activity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Demonstrates that the prototype's selectors + ANI-driven actions work end-to-end on a real
 * Android View tree under Robolectric — no Compose, no `UiAutomation`, no `adb`.
 *
 * The matcher walks the View tree (because `AccessibilityNodeInfo.getChild()` requires a
 * connected `AccessibilityInteractionClient`, which Robolectric doesn't provide). Actions
 * dispatch through `view.performAccessibilityAction(...)` rather than
 * `AccessibilityNodeInfo.performAction(...)` for the same reason — see
 * [UiAutomator.findObject]'s KDoc for the gory details.
 *
 * **Note** about `Button`: the platform Button style applies `textAllCaps=true`, which rewrites
 * the displayed text (and the ANI's `text` field) to upper case. Tests use plain `TextView`
 * + an `OnClickListener` instead, which keeps the literal text. On-device this same caveat
 * applies to real UIAutomator runs — the selector for a Button labelled "Submit" needs to
 * either match the displayed `SUBMIT` or set `textAllCaps=false` upstream.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UiAutomatorPrototypeTest {

  @Test
  fun `findObject by text returns the matching node and click invokes its OnClickListener`() {
    var submitClicked = 0
    val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    val root =
      LinearLayout(activity).apply {
        layoutParams =
          ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
          )
        addView(
          TextView(activity).apply {
            text = "Submit"
            isClickable = true
            setOnClickListener { submitClicked++ }
          }
        )
        addView(TextView(activity).apply { text = "Cancel" })
      }
    activity.setContentView(root)

    val submit = UiAutomator.findObject(root, By.text("Submit"))
    assertNotNull("expected to find Submit", submit)
    assertEquals("Submit", submit!!.text?.toString())
    assertTrue("ACTION_CLICK should be accepted", submit.click())
    assertEquals(1, submitClicked)
  }

  @Test
  fun `enabled-state predicate distinguishes the two clickable rows`() {
    val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    val root =
      LinearLayout(activity).apply {
        addView(
          TextView(activity).apply {
            text = "Submit"
            isClickable = true
          }
        )
        addView(
          TextView(activity).apply {
            text = "Cancel"
            isClickable = true
            isEnabled = false
          }
        )
      }
    activity.setContentView(root)

    val enabled = UiAutomator.findObjects(root, By.clickable().enabled(true))
    val disabled = UiAutomator.findObjects(root, By.clickable().enabled(false))

    assertEquals(listOf("Submit"), enabled.map { it.text?.toString() })
    assertEquals(listOf("Cancel"), disabled.map { it.text?.toString() })
  }

  @Test
  fun `regex text match`() {
    val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    val root =
      LinearLayout(activity).apply {
        addView(TextView(activity).apply { text = "Item 1" })
        addView(TextView(activity).apply { text = "Item 23" })
        addView(TextView(activity).apply { text = "Other" })
      }
    activity.setContentView(root)

    val items = UiAutomator.findObjects(root, By.textMatches("Item \\d+"))
    assertEquals(listOf("Item 1", "Item 23"), items.map { it.text.toString() })
  }

  @Test
  fun `hasDescendant chain selects a row by its label`() {
    val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    val root =
      LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        addView(
          LinearLayout(activity).apply {
            contentDescription = "row-1"
            addView(TextView(activity).apply { text = "Alice" })
          }
        )
        addView(
          LinearLayout(activity).apply {
            contentDescription = "row-2"
            addView(TextView(activity).apply { text = "Bob" })
          }
        )
      }
    activity.setContentView(root)

    val match = UiAutomator.findObject(root, By.desc("row-2").hasDescendant(By.text("Bob")))
    assertNotNull(match)
    assertEquals("row-2", match!!.contentDescription?.toString())

    // Negative case: row-1 has no "Bob".
    assertNull(UiAutomator.findObject(root, By.desc("row-1").hasDescendant(By.text("Bob"))))
  }

  @Test
  fun `inputText on an editable node routes through ACTION_SET_TEXT`() {
    val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    val edit =
      android.widget.EditText(activity).apply {
        setText("before")
        contentDescription = "field"
      }
    val root = LinearLayout(activity).apply { addView(edit) }
    activity.setContentView(root)

    val field = UiAutomator.findObject(root, By.desc("field"))
    assertNotNull(field)
    val accepted = field!!.inputText("after")
    assertTrue("ACTION_SET_TEXT should be accepted on EditText", accepted)
    assertEquals("after", edit.text.toString())
  }

  @Test
  fun `click on a non-clickable view returns false`() {
    val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    val tv = TextView(activity).apply { text = "label" }
    val root = LinearLayout(activity).apply { addView(tv) }
    activity.setContentView(root)

    val labelNode = UiAutomator.findObject(root, By.text("label"))
    assertNotNull(labelNode)
    // ACTION_CLICK is rejected on a non-clickable view: the host can surface this as
    // `unsupported(reason="ACTION_CLICK not exposed")` evidence.
    assertFalse(labelNode!!.click())
  }

  /**
   * Sanity check — selectors should still see real `Button` instances even though we don't use
   * them above. Documents the textAllCaps caveat so anyone reading the suite knows it's
   * intentional, not an accident.
   */
  @Test
  fun `Button textAllCaps is reflected in the ANI text field`() {
    val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    val button = Button(activity).apply { text = "Submit" }
    val root = LinearLayout(activity).apply { addView(button) }
    activity.setContentView(root)

    // Literal text doesn't match — Button uppercased it.
    assertNull(UiAutomator.findObject(root, By.text("Submit")))
    // Upper-case form does match.
    assertNotNull(UiAutomator.findObject(root, By.text("SUBMIT")))
  }
}
