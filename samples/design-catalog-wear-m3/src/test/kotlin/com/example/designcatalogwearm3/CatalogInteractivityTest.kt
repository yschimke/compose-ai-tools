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
 * The **click contract** of the Wear catalog stickers, on both render lanes.
 *
 * Every handler on this sheet used to be a literal `onClick = {}` / `onCheckedChange = {}`, so a tap
 * in a held Live Compose session did nothing at all — including on `SwitchButton` and
 * `CheckboxButton`, the two components a viewer is most likely to try. `CatalogInteractive.kt` split
 * the sheet the way the Compose M3 catalog is split, and these tests pin both halves:
 * * `LocalInspectionMode = false` (the live session) — a click must visibly change the sticker.
 * * `LocalInspectionMode = true` (the baked snapshot and every one-shot `/render`) — a click must do
 *   nothing, or the published PNG would depend on whether something happened to tap it.
 *
 * Pinned to Robolectric SDK 35 to match `composePreview { sdkVersion }` in this module's build
 * script: SDK 36 needs JDK 21+ and this repo's render/daemon path stays on JDK 17.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CatalogInteractivityTest {

  @get:Rule val rule = createComposeRule()

  /** Composes [content] on the lane a held Live Compose session runs (`inspectionMode = false`). */
  private fun ComposeContentTestRule.setLive(content: @Composable () -> Unit) = setContent {
    CompositionLocalProvider(LocalInspectionMode provides false) { content() }
  }

  /** Composes [content] on the lane the baked sticker sheet renders (`inspectionMode = true`). */
  private fun ComposeContentTestRule.setBaked(content: @Composable () -> Unit) = setContent {
    CompositionLocalProvider(LocalInspectionMode provides true) { content() }
  }

  @Test
  fun `switch button toggles in a live session`() {
    rule.setLive { SwitchButtonOn() }

    rule.onNode(isToggleable()).assertIsOn().performClick()
    rule.onNode(isToggleable()).assertIsOff()
  }

  @Test
  fun `switch button holds its seeded state in a baked render`() {
    rule.setBaked { SwitchButtonOn() }

    rule.onNode(isToggleable()).assertIsOn().performClick()
    rule.onNode(isToggleable()).assertIsOn()
  }

  @Test
  fun `checkbox button toggles in a live session`() {
    rule.setLive { CheckboxButtonChecked() }

    rule.onNode(isToggleable()).assertIsOn().performClick()
    rule.onNode(isToggleable()).assertIsOff()
  }

  @Test
  fun `filled button tallies its clicks in a live session`() {
    rule.setLive { FilledButton() }

    rule.onNodeWithText("Filled").performClick()
    rule.onNodeWithText("Filled (1)").assertExists()

    rule.onNodeWithText("Filled (1)").performClick()
    rule.onNodeWithText("Filled (2)").assertExists()
  }

  @Test
  fun `filled button is inert in a baked render`() {
    rule.setBaked { FilledButton() }

    rule.onNodeWithText("Filled").performClick()

    rule.onNodeWithText("Filled").assertExists()
  }

  @Test
  fun `compact button tallies its clicks in a live session`() {
    rule.setLive { CompactButtonSticker() }

    rule.onNodeWithText("Compact").performClick()
    rule.onNodeWithText("Compact (1)").assertExists()
  }

  @Test
  fun `both halves of the button group count independently`() {
    rule.setLive { ButtonGroupSticker() }

    rule.onNodeWithText("Yes").performClick()

    rule.onNodeWithText("Yes (1)").assertExists()
    // The other half is untouched — each member holds its own counter.
    rule.onNodeWithText("No").assertExists()
  }

  @Test
  fun `a card is clickable in a live session`() {
    rule.setLive { CardSticker() }

    rule.onNodeWithText("Card").performClick()
    rule.onNodeWithText("Card (1)").assertExists()
  }
}
