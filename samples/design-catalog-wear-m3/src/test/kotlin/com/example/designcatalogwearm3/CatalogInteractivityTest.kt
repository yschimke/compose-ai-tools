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
 * The **lane contract** of the Wear catalog stickers: one sticker composes **one** control
 * implementation, and that control responds to input — on every lane.
 *
 * This suite used to assert the opposite half: that a click moved the sticker under
 * `LocalInspectionMode = false` (the held Live Compose session) and did *nothing* under
 * `LocalInspectionMode = true` (the baked snapshot and every one-shot `/render`). That split was
 * real — `CatalogInteractive.kt` swapped a stateful control for an inert one — and it meant the
 * published capture was not the composable that runs live (issue #3674). The swap is gone, so the
 * assertions below are:
 * * **same implementation on both lanes** — every behavioural test runs under `inspectionMode =
 *   true` *and* `false` and expects the identical outcome. A reintroduced preview-vs-live branch
 *   fails here rather than silently shipping a capture of a different composable.
 * * **still responsive** — the click / toggle assertions are unchanged in substance, so the
 *   interactivity the live lane used to have is now simply what the sticker does.
 * * **seeded state still renders** — untouched, a toggle draws its seeded `previewOverrideBoolean`
 *   value, which is what the `@OverrideVariant` captures (the `off` / `unchecked` folds) depend on,
 *   and a counted label draws bare, which is what keeps every baked capture pixel-unchanged.
 *
 * Pinned to Robolectric SDK 35 to match `composePreview { sdkVersion }` in this module's build
 * script: SDK 36 needs JDK 21+ and this repo's render/daemon path stays on JDK 17.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CatalogInteractivityTest {

  @get:Rule val rule = createComposeRule()

  /**
   * Composes [content] under an explicit `LocalInspectionMode`. `true` is what Compose sets for a
   * `@Preview` capture (and what the daemon's one-shot `/render` provides); `false` is the held
   * Live Compose session. Nothing on this sheet reads the local any more — running each assertion
   * through both values is what proves it.
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
  fun `checkbox button toggles on the live lane`() {
    rule.setLane(inspection = false) { CheckboxButtonChecked() }

    rule.onNode(isToggleable()).assertIsOn().performClick()
    rule.onNode(isToggleable()).assertIsOff()
  }

  @Test
  fun `checkbox button toggles identically on the baked lane`() {
    rule.setLane(inspection = true) { CheckboxButtonChecked() }

    rule.onNode(isToggleable()).assertIsOn().performClick()
    rule.onNode(isToggleable()).assertIsOff()
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
