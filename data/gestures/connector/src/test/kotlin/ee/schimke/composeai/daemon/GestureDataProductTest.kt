package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.DataProductTransport
import ee.schimke.composeai.daemon.protocol.GestureKindOverride
import ee.schimke.composeai.daemon.protocol.GestureOverride
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.data.gestures.Material3GestureProduct
import ee.schimke.composeai.data.render.extensions.DataExtensionHookKind
import ee.schimke.composeai.data.render.extensions.DataExtensionId
import ee.schimke.composeai.data.render.extensions.DataExtensionPhase
import ee.schimke.composeai.data.render.extensions.compose.AroundComposableHook
import ee.schimke.composeai.data.render.extensions.compose.hasAroundComposableHook
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Mirror of `AmbientDataProductTest` for the `compose/gestures` data product. Covers the extension's
 * hook shape, the planner's plan/abstain semantics, the registry capabilities + fetch outcomes, and
 * the on-render capture path that snapshots [GestureStateController].
 */
class GestureDataProductTest {

  @Before fun reset() = GestureStateController.resetForNewSession()

  @After fun tearDown() = GestureStateController.resetForNewSession()

  @Test
  fun `gesture override extension declares around-composable hook`() {
    val extension = GestureOverrideExtension(GestureOverride(showHints = true))
    val hook: AroundComposableHook = extension

    assertEquals(DataExtensionId(Material3GestureProduct.KIND), extension.id)
    assertEquals(setOf(DataExtensionHookKind.AroundComposable), extension.hooks)
    assertEquals(DataExtensionPhase.OuterEnvironment, extension.constraints.phase)
    assertTrue(extension.hasAroundComposableHook)
    assertEquals(extension, hook)
  }

  @Test
  fun `planner returns extension when gestures override present`() {
    val planner = GesturePreviewOverrideExtension()
    val planned = planner.plan(PreviewOverrides(gestures = GestureOverride(showHints = true)))
    assertTrue("expected planner to produce a hook", planned is AroundComposableHook)
    assertEquals(DataExtensionId(Material3GestureProduct.KIND), planned!!.id)
  }

  @Test
  fun `planner abstains when gestures override absent`() {
    val planner = GesturePreviewOverrideExtension()
    assertNull(planner.plan(PreviewOverrides()))
    assertNull(planner.plan(PreviewOverrides(widthPx = 64)))
  }

  @Test
  fun `capabilities advertise compose gestures as inline no-rerender product`() {
    val cap = GestureDataProductRegistry().capabilities.single()
    assertEquals("compose/gestures", cap.kind)
    assertEquals(1, cap.schemaVersion)
    assertEquals(DataProductTransport.INLINE, cap.transport)
    assertTrue(cap.attachable)
    assertTrue(cap.fetchable)
    assertEquals(false, cap.requiresRerender)
  }

  @Test
  fun `fetch before any render returns NotAvailable`() {
    val registry = GestureDataProductRegistry()
    assertEquals(
      DataProductRegistry.Outcome.NotAvailable,
      registry.fetch("preview-1", "compose/gestures", params = null, inline = true),
    )
  }

  @Test
  fun `fetch unknown kind returns Unknown`() {
    val registry = GestureDataProductRegistry()
    assertEquals(
      DataProductRegistry.Outcome.Unknown,
      registry.fetch("preview-1", "compose/ambient", params = null, inline = true),
    )
  }

  @Test
  fun `onRender with gestures override snapshots the controller registry`() {
    GestureStateController.register(GestureKindOverride.PRIMARY, "Play", hintAvailable = true, enabled = true) {}
    GestureStateController.register(GestureKindOverride.DISMISS, "Back", hintAvailable = false, enabled = true) {}
    GestureStateController.set(GestureOverride(showHints = true))

    val registry = GestureDataProductRegistry()
    val overrides = PreviewOverrides(gestures = GestureOverride(showHints = true))
    val stubResult = RenderResult(id = 1L, classLoaderHashCode = 0, classLoaderName = "test")
    registry.onRender("preview-1", stubResult, overrides, null)

    val ok =
      registry.fetch("preview-1", "compose/gestures", null, true) as DataProductRegistry.Outcome.Ok
    val obj = ok.result.payload!!.jsonObject
    assertEquals(true, obj["enabled"]?.jsonPrimitive?.boolean)
    assertEquals(true, obj["hintsShown"]?.jsonPrimitive?.boolean)
    val registered = obj["registered"]!!.jsonArray
    assertEquals(2, registered.size)
    assertEquals("primary", registered[0].jsonObject["type"]?.jsonPrimitive?.content)
    assertEquals("Play", registered[0].jsonObject["label"]?.jsonPrimitive?.content)

    val attachments = registry.attachmentsFor("preview-1", setOf("compose/gestures"))
    assertEquals(1, attachments.size)
    assertEquals("compose/gestures", attachments.single().kind)
  }

  @Test
  fun `onRender without gestures override clears the payload`() {
    val registry = GestureDataProductRegistry()
    GestureStateController.register(GestureKindOverride.PRIMARY, "Play", hintAvailable = true, enabled = true) {}
    registry.onRender(
      "preview-1",
      RenderResult(id = 1L, classLoaderHashCode = 0, classLoaderName = "test"),
      PreviewOverrides(gestures = GestureOverride(showHints = true)),
      null,
    )
    // A later render with no gesture override drops the captured payload.
    registry.onRender(
      "preview-1",
      RenderResult(id = 2L, classLoaderHashCode = 0, classLoaderName = "test"),
      PreviewOverrides(),
      null,
    )
    assertEquals(
      DataProductRegistry.Outcome.NotAvailable,
      registry.fetch("preview-1", "compose/gestures", null, true),
    )
  }
}
