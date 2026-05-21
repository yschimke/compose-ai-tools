package ee.schimke.composeai.daemon

import ee.schimke.composeai.data.keyboard.Material3KeyboardProduct
import ee.schimke.composeai.data.render.extensions.DataExtensionHookKind
import ee.schimke.composeai.data.render.extensions.DataExtensionId
import ee.schimke.composeai.data.render.extensions.DataExtensionPhase
import ee.schimke.composeai.data.render.extensions.compose.AroundComposableHook
import ee.schimke.composeai.data.render.extensions.compose.hasAroundComposableHook
import ee.schimke.composeai.daemon.protocol.AmbientOverride
import ee.schimke.composeai.daemon.protocol.AmbientStateOverride
import ee.schimke.composeai.daemon.protocol.KeyboardOverride
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the contract of `:data-keyboard-connector`'s extension surface. Mirrors
 * [FocusDataProductTest] in shape, with the planner difference called out: the keyboard planner
 * always returns a non-null extension so the around-composable's observer is in place for every
 * render — without that, a `BasicTextField.show()` from app code wouldn't have anyone listening.
 */
class KeyboardDataProductTest {

  @After fun tearDown() = KeyboardController.resetForNewSession()

  @Test
  fun `keyboard override extension declares around-composable hook in OuterEnvironment phase`() {
    val extension = KeyboardOverrideExtension(KeyboardOverride(visible = true))
    val hook: AroundComposableHook = extension

    assertEquals(DataExtensionId(Material3KeyboardProduct.KIND), extension.id)
    assertEquals(setOf(DataExtensionHookKind.AroundComposable), extension.hooks)
    assertEquals(DataExtensionPhase.OuterEnvironment, extension.constraints.phase)
    assertTrue(extension.hasAroundComposableHook)
    assertEquals(extension, hook)
  }

  @Test
  fun `planner returns extension even when keyboard override absent`() {
    val planner = KeyboardPreviewOverrideExtension()
    val planned = planner.plan(PreviewOverrides())
    assertNotNull(
      "planner must always emit the extension so the observer can react to natural app-side IME " +
        "state, not just to explicit overrides",
      planned,
    )
    assertEquals(DataExtensionId(Material3KeyboardProduct.KIND), planned.id)
    // Sibling overrides shouldn't change the always-on shape.
    assertNotNull(
      planner.plan(
        PreviewOverrides(ambient = AmbientOverride(state = AmbientStateOverride.AMBIENT))
      )
    )
    assertNotNull(planner.plan(PreviewOverrides(keyboard = KeyboardOverride(visible = false))))
  }

  @Test
  fun `controller starts hidden with no key pressed`() {
    KeyboardController.resetForNewSession()
    assertFalse(KeyboardController.softInputVisible.value)
    assertNull(KeyboardController.pressedKey.value)
  }

  @Test
  fun `notifyImeVisibility flips the natural state`() {
    KeyboardController.notifyImeVisibility(true)
    assertTrue(KeyboardController.softInputVisible.value)

    KeyboardController.notifyImeVisibility(false)
    assertFalse(KeyboardController.softInputVisible.value)
  }

  @Test
  fun `seed with visible true forces band visible regardless of natural state`() {
    KeyboardController.notifyImeVisibility(false)
    KeyboardController.seed(KeyboardOverride(visible = true))
    assertTrue(
      "forced visibility wins over natural=false",
      KeyboardController.softInputVisible.value,
    )

    KeyboardController.clearOverride()
    assertFalse(
      "clearing the override falls back to the natural false state",
      KeyboardController.softInputVisible.value,
    )
  }

  @Test
  fun `seed with visible false forces band hidden regardless of natural state`() {
    KeyboardController.notifyImeVisibility(true)
    KeyboardController.seed(KeyboardOverride(visible = false))
    assertFalse(
      "forced hidden wins over natural=true",
      KeyboardController.softInputVisible.value,
    )
  }

  @Test
  fun `key press implies band visible without explicit show`() {
    KeyboardController.notifyImeVisibility(false)
    assertFalse(KeyboardController.softInputVisible.value)

    // Interactive `KEY_DOWN` from a daemon client without an app `show()` first — the band still
    // raises so the agent's typing isn't shown against a hidden keyboard.
    KeyboardController.notifyKeyDown("h")
    assertTrue(
      "pressing a key without explicit show() should raise the band",
      KeyboardController.softInputVisible.value,
    )
    assertEquals("h", KeyboardController.pressedKey.value)

    KeyboardController.notifyKeyUp("h")
    assertNull(KeyboardController.pressedKey.value)
    assertFalse(
      "releasing the only held key should lower the band when there's no natural visibility",
      KeyboardController.softInputVisible.value,
    )
  }

  @Test
  fun `notifyKeyUp with mismatched label leaves the press intact`() {
    KeyboardController.notifyKeyDown("a")
    KeyboardController.notifyKeyUp("z")
    assertEquals(
      "an out-of-order release for a different key shouldn't clear the held cap",
      "a",
      KeyboardController.pressedKey.value,
    )

    KeyboardController.notifyKeyUp()
    assertNull(
      "explicit null release force-clears the held cap",
      KeyboardController.pressedKey.value,
    )
  }
}
