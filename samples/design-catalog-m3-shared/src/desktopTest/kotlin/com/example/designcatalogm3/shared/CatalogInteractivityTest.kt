package com.example.designcatalogm3.shared

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import ee.schimke.composeai.daemon.protocol.PreviewOverrideValue
import ee.schimke.composeai.overrides.PreviewOverrideController
import kotlin.test.Test

/**
 * The lane contract of the shared catalog component set: one id composes one control, and it
 * responds to input on every lane.
 * * Each behavioural assertion runs with `LocalInspectionMode` true and false and expects the same
 *   outcome, so a reintroduced preview-vs-live branch fails here.
 * * Clicks, toggles and typing still work.
 * * A control's untouched initial state is its `catalogOverride*` seed (what the `@OverrideVariant`
 *   folds bake).
 */
@OptIn(ExperimentalTestApi::class)
class CatalogInteractivityTest {

  /**
   * Runs [body] with `inspection` true (a `@Preview` capture / one-shot `/render`) and false (a
   * live session / the wasm tier); outcomes must match.
   */
  private fun onBothLanes(id: String, body: ComposeUiTest.() -> Unit) {
    for (inspection in listOf(true, false)) {
      runComposeUiTest {
        setContent {
          CompositionLocalProvider(LocalInspectionMode provides inspection) { CatalogComponent(id) }
        }
        body()
      }
    }
  }

  /**
   * As [onBothLanes], but with a `catalogOverride*` knob seeded the way a render's variant does.
   */
  private fun onBothLanesSeeded(
    id: String,
    overrides: Map<String, PreviewOverrideValue>,
    body: ComposeUiTest.() -> Unit,
  ) {
    try {
      PreviewOverrideController.set(overrides)
      onBothLanes(id, body)
    } finally {
      PreviewOverrideController.set(null)
    }
  }

  @Test
  fun `a filled button tallies its clicks on both lanes`() =
    onBothLanes("button-filled") {
      onNodeWithText("Filled").performClick()
      onNodeWithText("Filled (1)").assertExists()

      onNodeWithText("Filled (1)").performClick()
      onNodeWithText("Filled (2)").assertExists()
    }

  @Test
  fun `an untouched button draws the bare label`() =
    onBothLanes("button-filled") {
      // The counter folds `0` back to the bare label, which is why dropping the inert branch moved
      // no published pixel: this IS the baked frame.
      onNodeWithText("Filled").assertExists()
      onNodeWithText("Filled (1)").assertDoesNotExist()
    }

  @Test
  fun `a disabled button stays inert on both lanes`() {
    // The disabled state is `button-filled` with `enabled` seeded false, as `@OverrideVariant(name
    // = "disabled")` bakes it. The handler stays wired; `enabled = false` is what makes the click a
    // no-op, so "the label didn't move" is the assertion.
    onBothLanesSeeded(
      "button-filled",
      mapOf("enabled" to PreviewOverrideValue.BooleanValue(false)),
    ) {
      onNodeWithText("Filled").performClick()
      onNodeWithText("Filled").assertExists()
    }
  }

  @Test
  fun `the slotted card composes the non-clickable overload on both lanes`() {
    // The catalog composes the plain (non-clickable) card overload on every lane: `card-slots` is a
    // slot host, and a clickable card would both swallow children's taps and add a clickable node
    // to the published semantics data products. So assert no click action.
    onBothLanes("card-slots") { onNode(hasClickAction()).assertDoesNotExist() }
  }

  @Test
  fun `a switch starts at its seeded value and toggles on both lanes`() =
    onBothLanes("switch-on") {
      onNode(isToggleable()).assertIsOn().performClick()
      onNode(isToggleable()).assertIsOff()
    }

  @Test
  fun `the off switch variant renders its seeded value`() =
    // The off state is `switch-on` with `checked` seeded false (`@OverrideVariant(name = "off")`);
    // it must draw the seed.
    onBothLanesSeeded(
      "switch-on",
      mapOf("checked" to PreviewOverrideValue.BooleanValue(false)),
    ) {
      onNode(isToggleable()).assertIsOff()
    }

  @Test
  fun `a checkbox toggles on both lanes`() =
    onBothLanesSeeded(
      "checkbox-checked",
      mapOf("checked" to PreviewOverrideValue.BooleanValue(false)),
    ) {
      onNode(isToggleable()).assertIsOff().performClick()
      onNode(isToggleable()).assertIsOn()
    }

  @Test
  fun `a text field accepts typing on both lanes`() =
    onBothLanes("textfield-filled") {
      onNodeWithText("Filled").performTextInput("Z")

      // Asserted on the inserted character rather than a full string, so the test doesn't also pin
      // where the caret happens to start — the point is that the field's value moved at all.
      onNodeWithText("Z", substring = true).assertExists()
    }

  @Test
  fun `the shape morph slider moves on both lanes`() =
    onBothLanes("shape-morph") {
      onNodeWithText("50%", substring = true).assertExists()
      onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress)).performSemanticsAction(
        SemanticsActions.SetProgress
      ) {
        it(1f)
      }
      onNodeWithText("100%", substring = true).assertExists()
    }

  @Test
  fun `a text field renders its seeded value`() =
    onBothLanesSeeded(
      "textfield-filled",
      mapOf("value" to PreviewOverrideValue.StringValue("Seeded")),
    ) {
      onNodeWithText("Seeded").assertExists()
    }
}
