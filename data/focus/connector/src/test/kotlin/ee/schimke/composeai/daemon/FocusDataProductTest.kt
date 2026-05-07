package ee.schimke.composeai.daemon

import ee.schimke.composeai.data.focus.Material3FocusProduct
import ee.schimke.composeai.daemon.protocol.AmbientOverride
import ee.schimke.composeai.daemon.protocol.AmbientStateOverride
import ee.schimke.composeai.daemon.protocol.FocusDirection
import ee.schimke.composeai.daemon.protocol.FocusOverride
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.data.render.extensions.DataExtensionHookKind
import ee.schimke.composeai.data.render.extensions.DataExtensionId
import ee.schimke.composeai.data.render.extensions.DataExtensionPhase
import ee.schimke.composeai.data.render.extensions.compose.AroundComposableHook
import ee.schimke.composeai.data.render.extensions.compose.hasAroundComposableHook
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the contract of `:data-focus-connector`'s extension surface. Mirrors the layout of the
 * ambient and wallpaper data-product tests: extension hook shape, planner plan/abstain semantics,
 * controller state machine.
 */
class FocusDataProductTest {

  @After fun tearDown() = FocusController.resetForNewSession()

  @Test
  fun `focus override extension declares around-composable hook in OuterEnvironment phase`() {
    val extension = FocusOverrideExtension(FocusOverride(tabIndex = 0))
    val hook: AroundComposableHook = extension

    assertEquals(DataExtensionId(Material3FocusProduct.KIND), extension.id)
    assertEquals(setOf(DataExtensionHookKind.AroundComposable), extension.hooks)
    assertEquals(DataExtensionPhase.OuterEnvironment, extension.constraints.phase)
    assertTrue(extension.hasAroundComposableHook)
    assertEquals(extension, hook)
  }

  @Test
  fun `planner returns extension when focus override present`() {
    val planner = FocusPreviewOverrideExtension()
    val planned = planner.plan(PreviewOverrides(focus = FocusOverride(direction = FocusDirection.Next)))
    assertTrue("expected planner to produce a hook", planned is AroundComposableHook)
    assertEquals(DataExtensionId(Material3FocusProduct.KIND), planned!!.id)
  }

  @Test
  fun `planner abstains when focus override absent`() {
    val planner = FocusPreviewOverrideExtension()
    assertNull(planner.plan(PreviewOverrides()))
    assertNull(planner.plan(PreviewOverrides(widthPx = 320)))
    // Sibling overrides (e.g. ambient) shouldn't trigger the focus planner.
    assertNull(
      planner.plan(PreviewOverrides(ambient = AmbientOverride(state = AmbientStateOverride.AMBIENT)))
    )
  }

  @Test
  fun `controller starts inactive`() {
    FocusController.resetForNewSession()
    assertNull(FocusController.current())
  }

  @Test
  fun `controller set propagates state and clear resets it`() {
    FocusController.set(FocusOverride(tabIndex = 2, overlay = true))
    assertEquals(FocusOverride(tabIndex = 2, overlay = true), FocusController.current())

    FocusController.set(null)
    assertNull(FocusController.current())
  }

  @Test
  fun `controller settle window matches the renderer's per-capture clock advance`() {
    // Sentinel: the renderer's per-capture loop advances the paused clock by this amount, and
    // changing it here has user-visible consequences (focus rings crossfade animation length).
    // If this assertion fires, audit `RobolectricRenderTest` and bump in lockstep.
    assertEquals(250L, FocusController.SETTLE_MS)
  }
}
