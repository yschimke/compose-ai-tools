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
import ee.schimke.composeai.data.overrides.PreviewOverrideValue
import ee.schimke.composeai.overrides.PreviewOverrideController
import kotlin.test.Test

/**
 * The **lane contract** of the shared catalog component set: one component id composes **one**
 * control implementation, and that control responds to input — on every lane.
 *
 * This suite used to assert the opposite: that a click did something when `interactive = true` (the
 * wasm tier / the held Live Compose session) and *nothing* when `interactive = false` (the baked
 * sticker sheet and every one-shot `/render`). That flag was derived from `LocalInspectionMode`, so
 * the published capture was not always the composable that runs live — issue #3674. The flag is
 * gone, and the tests below pin what replaced it:
 * * **same implementation on both lanes** — each behavioural assertion runs under
 *   `LocalInspectionMode = true` *and* `= false` and expects the same outcome. A reintroduced
 *   preview-vs-live branch fails here rather than silently shipping a capture of a different
 *   composable.
 * * **still responsive** — the click / toggle / typing assertions are unchanged in substance, so
 *   the interactivity the `interactive = true` lane used to have is now simply what the component
 *   does.
 * * **seeded state still renders** — a control's initial value comes from its `catalogOverride*`
 *   knob, which is what the `@OverrideVariant` folds (the `off` / `unchecked` / `disabled`
 *   captures) seed. Untouched, the component must draw exactly that seed.
 */
@OptIn(ExperimentalTestApi::class)
class CatalogInteractivityTest {

  /**
   * Runs [body] on both render lanes. `inspection = true` is what Compose sets for a `@Preview`
   * capture (and what the daemon's one-shot `/render` provides); `false` is the held Live Compose
   * session and the in-browser tier. Nothing in the catalog reads the local any more, so an
   * assertion that passes on one lane must pass on the other — which is the point of running it
   * twice.
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
    // The disabled state is no longer a slug of its own — it rides `button-filled` with the
    // `enabled` knob seeded false, which is what `@OverrideVariant(name = "disabled")` bakes. So
    // the test seeds the same knob through the same controller the renderer does, rather than
    // asserting on a duplicate component that no longer exists.
    //
    // Not an oversight that the handler stays wired: unresponsiveness IS the documented state, and
    // `enabled = false` is what makes the click a no-op. With the handler live everywhere, a click
    // would otherwise tally into the label (see `counted`), so "the label did not move" is the
    // assertion.
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
    // M3 cards ship both a plain and a clickable overload, and the catalog composes the **plain**
    // one on every surface — the same constant in both lanes, rather than the old
    // `LocalInspectionMode` branch that picked clickable live and plain when baked (issue #3674).
    //
    // Plain is the deliberate choice twice over here: `card-slots` is a slot HOST, so a card-wide
    // click target would swallow the taps meant for the children a structured-screen builder drops
    // in — and the clickable overload would add a clickable node to the sticker's semantics tree,
    // which is itself published (`a11y/touchTargets` greenlines and the
    // `compose/semantics-wireframe` layout variant are both derived from it). So the assertion is
    // the absence of a click action, on both lanes; a regression that "upgrades" this to the
    // clickable overload silently changes two published data products and must fail here.
    //
    // This used to sweep the three plain cards (`card-elevated` / `-outlined` / `-filled`). They
    // were by-component duplicates of Material's own surface treatments — m3-catalog's job, not
    // this harness's — so the slot host is the card that remains.
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
    // The off state is `switch-on` with `checked` seeded false — exactly what
    // `@OverrideVariant(name = "off")` bakes, seeded here through the same controller the renderer
    // uses. Untouched it must draw the seed, not some remembered default. (It used to be a
    // `switch-off` id of its own; the duplicate slug is gone, so the test seeds the knob.)
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
