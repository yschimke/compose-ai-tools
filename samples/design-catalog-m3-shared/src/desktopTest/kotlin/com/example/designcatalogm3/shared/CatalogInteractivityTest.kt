package com.example.designcatalogm3.shared

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import ee.schimke.composeai.data.overrides.PreviewOverrideValue
import ee.schimke.composeai.overrides.PreviewOverrideController
import kotlin.test.Test

/**
 * The **click contract** of the shared catalog component set, on both lanes.
 *
 * The catalog serves two surfaces from one body, split on `interactive`:
 * * `interactive = false` — the baked sticker sheet and every one-shot `/render`. A click must do
 *   nothing, or the published PNG would depend on whether something happened to tap it.
 * * `interactive = true` — the in-browser wasm tier and the held Live Compose session. A click must
 *   visibly change the component, which is exactly what a pixel-comparing render test can't check.
 *
 * Before this suite every one of these components held a literal `onClick = {}`, so the whole
 * interactive column below was silently false: buttons, the radio and the text fields did nothing
 * at all when tapped in a live preview. Each test therefore asserts **both** columns — the
 * interactive one is the feature, the baked one is the regression guard on the published sheet.
 */
@OptIn(ExperimentalTestApi::class)
class CatalogInteractivityTest {

  @Test
  fun `a filled button tallies its clicks when interactive`() = runComposeUiTest {
    setContent { CatalogComponent("button-filled", interactive = true) }

    onNodeWithText("Filled").performClick()
    onNodeWithText("Filled (1)").assertExists()

    onNodeWithText("Filled (1)").performClick()
    onNodeWithText("Filled (2)").assertExists()
  }

  @Test
  fun `a filled button is inert when baked`() = runComposeUiTest {
    setContent { CatalogComponent("button-filled", interactive = false) }

    onNodeWithText("Filled").performClick()

    // Still the bare label — the published sticker can't be moved by a stray tap.
    onNodeWithText("Filled").assertExists()
  }

  @Test
  fun `every stateless action component responds to a click when interactive`() {
    // The FAB and the assist chip route their clicks through `counted` exactly as the buttons do.
    for ((id, label) in listOf("fab" to "+", "chip-assist" to "Assist", "button-text" to "Text")) {
      runComposeUiTest {
        setContent { CatalogComponent(id, interactive = true) }
        onNodeWithText(label).performClick()
        onNodeWithText("$label (1)").assertExists()
      }
    }
  }

  @Test
  fun `a disabled button stays inert on both lanes`() {
    // The disabled state is no longer a slug of its own — it rides `button-filled` with the
    // `enabled` knob seeded false, which is what `@OverrideVariant(name = "disabled")` bakes. So
    // the
    // test seeds the same knob through the same controller the renderer does, rather than asserting
    // on a duplicate component that no longer exists.
    //
    // Not an oversight that the handler stays wired: unresponsiveness IS the documented state, and
    // `enabled = false` is what makes the click a no-op. On the interactive lane a live click would
    // otherwise tally into the label (see `counted`), so "the label did not move" is the assertion.
    for (interactive in listOf(true, false)) {
      try {
        PreviewOverrideController.set(mapOf("enabled" to PreviewOverrideValue.BooleanValue(false)))
        runComposeUiTest {
          setContent { CatalogComponent("button-filled", interactive = interactive) }
          onNodeWithText("Filled").performClick()
          onNodeWithText("Filled").assertExists()
        }
      } finally {
        PreviewOverrideController.set(null)
      }
    }
  }

  @Test
  fun `the plain cards count their clicks when interactive`() {
    // M3 cards ship both a plain and a clickable overload; the interactive lane takes the clickable
    // one. `card-slots` is deliberately absent — see its branch for why a slot host stays inert.
    for ((id, label) in
      listOf(
        "card-elevated" to "Elevated card",
        "card-outlined" to "Outlined card",
        "card-filled" to "Filled card",
      )) {
      runComposeUiTest {
        setContent { CatalogComponent(id, interactive = true) }
        onNodeWithText(label).performClick()
        onNodeWithText("$label (1)").assertExists()
      }
    }
  }

  @Test
  fun `the plain cards are inert when baked`() {
    for ((id, label) in
      listOf(
        "card-elevated" to "Elevated card",
        "card-outlined" to "Outlined card",
        "card-filled" to "Filled card",
      )) {
      runComposeUiTest {
        setContent { CatalogComponent(id, interactive = false) }
        onNodeWithText(label).performClick()
        onNodeWithText(label).assertExists()
      }
    }
  }

  @Test
  fun `a switch toggles when interactive and holds its seeded value when baked`() {
    runComposeUiTest {
      setContent { CatalogComponent("switch-on", interactive = true) }
      onNode(isToggleable()).assertIsOn().performClick()
      onNode(isToggleable()).assertIsOff()
    }
    runComposeUiTest {
      setContent { CatalogComponent("switch-on", interactive = false) }
      onNode(isToggleable()).assertIsOn().performClick()
      onNode(isToggleable()).assertIsOn()
    }
  }

  @Test
  fun `a checkbox toggles when interactive`() = runComposeUiTest {
    setContent { CatalogComponent("checkbox-unchecked", interactive = true) }

    onNode(isToggleable()).assertIsOff().performClick()
    onNode(isToggleable()).assertIsOn()
  }

  @Test
  fun `a radio button flips its selection when interactive`() = runComposeUiTest {
    setContent { CatalogComponent("radiobutton-unselected", interactive = true) }

    onNode(isSelectable()).performClick()
    onNode(isSelectable()).assertIsSelected()
  }

  @Test
  fun `a text field accepts typing when interactive`() = runComposeUiTest {
    setContent { CatalogComponent("textfield-filled", interactive = true) }

    onNodeWithText("Filled").performTextInput("Z")

    // Asserted on the inserted character rather than a full string, so the test doesn't also pin
    // where the caret happens to start — the point is that the field's value moved at all.
    onNodeWithText("Z", substring = true).assertExists()
  }

  @Test
  fun `a text field ignores typing when baked`() = runComposeUiTest {
    setContent { CatalogComponent("textfield-filled", interactive = false) }

    onNodeWithText("Filled").performTextInput("Z")

    onNodeWithText("Z", substring = true).assertDoesNotExist()
    onNodeWithText("Filled").assertExists()
  }

  @Test
  fun `the shape morph slider moves only on the interactive lane`() {
    runComposeUiTest {
      setContent { CatalogComponent("shape-morph", interactive = true) }
      onNodeWithText("50%", substring = true).assertExists()
      onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress)).performSemanticsAction(
        SemanticsActions.SetProgress
      ) {
        it(1f)
      }
      onNodeWithText("100%", substring = true).assertExists()
    }
    runComposeUiTest {
      setContent { CatalogComponent("shape-morph", interactive = false) }
      onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress)).performSemanticsAction(
        SemanticsActions.SetProgress
      ) {
        it(1f)
      }
      onNodeWithText("50%", substring = true).assertExists()
    }
  }
}
