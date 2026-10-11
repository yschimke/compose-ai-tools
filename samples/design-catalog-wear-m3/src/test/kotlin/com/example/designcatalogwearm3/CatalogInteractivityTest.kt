package com.example.designcatalogwearm3

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The lane contract of the Wear catalog stickers: one sticker composes one control, responsive on
 * every lane.
 * * Every behavioural test runs with `inspectionMode` true and false and expects the same outcome,
 *   so a preview-vs-live branch fails here.
 * * Clicks and toggles still work.
 * * Untouched, a toggle draws its seeded `previewOverrideBoolean` (what the `@OverrideVariant`
 *   folds depend on) and a counted label draws bare.
 *
 * Robolectric SDK 35, matching `composePreview { sdkVersion }` (SDK 36 needs JDK 21).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CatalogInteractivityTest {

  @get:Rule val rule = createComposeRule()

  /**
   * Composes [content] under an explicit `LocalInspectionMode`: `true` for a `@Preview` capture /
   * one-shot `/render`, `false` for a live session.
   */
  private fun ComposeContentTestRule.setLane(
    inspection: Boolean,
    content: @Composable () -> Unit,
  ) = setContent { CompositionLocalProvider(LocalInspectionMode provides inspection) { content() } }

  @Test
  fun `switch button toggles on the live lane`() {
    rule.setLane(inspection = false) { SwitchButtonOn() }

    rule.onNode(isToggleable()).assertIsOn().performClick()
    rule.onNode(isToggleable()).assertIsOff()
  }

  @Test
  fun `switch button toggles identically on the baked lane`() {
    // The assertion that used to read `assertIsOn()` after the click. Same sticker, same control,
    // same behaviour — the capture the catalog publishes is now the composable that runs live.
    rule.setLane(inspection = true) { SwitchButtonOn() }

    rule.onNode(isToggleable()).assertIsOn().performClick()
    rule.onNode(isToggleable()).assertIsOff()
  }

  @Test
  fun `switch button renders its seeded state untouched`() {
    // Nothing taps a render, so the frame the catalog bakes is the seeded `checked` knob — the
    // `@OverrideVariant(name = "off")` fold depends on exactly this.
    rule.setLane(inspection = true) { SwitchButtonOn() }

    rule.onNode(isToggleable()).assertIsOn()
  }

  @Test
  fun `filled button tallies its clicks on the live lane`() {
    rule.setLane(inspection = false) { FilledButton() }

    rule.onNodeWithText("Filled").performClick()
    rule.onNodeWithText("Filled (1)").assertExists()

    rule.onNodeWithText("Filled (1)").performClick()
    rule.onNodeWithText("Filled (2)").assertExists()
  }

  @Test
  fun `filled button tallies its clicks identically on the baked lane`() {
    rule.setLane(inspection = true) { FilledButton() }

    rule.onNodeWithText("Filled").performClick()
    rule.onNodeWithText("Filled (1)").assertExists()
  }

  @Test
  fun `an untouched filled button draws the bare label`() {
    // `wearCounted` folds a `0` tally back to the bare label, which is why deleting the inert
    // branch moved no published pixel: this IS the baked frame.
    rule.setLane(inspection = true) { FilledButton() }

    rule.onNodeWithText("Filled").assertExists()
    rule.onNodeWithText("Filled (1)").assertDoesNotExist()
  }

  @Test
  fun `compact button tallies its clicks`() {
    rule.setLane(inspection = true) { CompactButtonSticker() }

    rule.onNodeWithText("Compact").performClick()
    rule.onNodeWithText("Compact (1)").assertExists()
  }

  @Test
  fun `both halves of the button group count independently`() {
    rule.setLane(inspection = false) { ButtonGroupSticker() }

    rule.onNodeWithText("Yes").performClick()

    rule.onNodeWithText("Yes (1)").assertExists()
    // The other half is untouched — each member holds its own counter.
    rule.onNodeWithText("No").assertExists()
  }

  @Test
  fun `a card is clickable`() {
    rule.setLane(inspection = true) { CardSticker() }

    rule.onNodeWithText("Card").performClick()
    rule.onNodeWithText("Card (1)").assertExists()
  }
}
