package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.AmbientOverride
import ee.schimke.composeai.daemon.protocol.AmbientStateOverride
import ee.schimke.composeai.daemon.protocol.DataProductTransport
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.data.ambient.AmbientPayload
import ee.schimke.composeai.data.ambient.Material3AmbientProduct
import ee.schimke.composeai.data.render.extensions.DataExtensionHookKind
import ee.schimke.composeai.data.render.extensions.DataExtensionId
import ee.schimke.composeai.data.render.extensions.DataExtensionPhase
import ee.schimke.composeai.data.render.extensions.compose.AroundComposableHook
import ee.schimke.composeai.data.render.extensions.compose.hasAroundComposableHook
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mirror of `WallpaperDataProductTest` for the `compose/ambient` data product. Covers the
 * extension's hook shape, the planner's plan/abstain semantics, the registry's capabilities + fetch
 * outcomes, and the on-render capture path.
 */
class AmbientDataProductTest {

  @Test
  fun `ambient override extension declares around-composable hook`() {
    val extension =
      AmbientOverrideExtension(AmbientOverride(state = AmbientStateOverride.AMBIENT))
    val hook: AroundComposableHook = extension

    assertEquals(DataExtensionId(Material3AmbientProduct.KIND), extension.id)
    assertEquals(setOf(DataExtensionHookKind.AroundComposable), extension.hooks)
    assertEquals(DataExtensionPhase.OuterEnvironment, extension.constraints.phase)
    assertTrue(extension.hasAroundComposableHook)
    assertEquals(extension, hook)
  }

  @Test
  fun `planner returns extension when ambient override present`() {
    val planner = AmbientPreviewOverrideExtension()
    val planned =
      planner.plan(PreviewOverrides(ambient = AmbientOverride(state = AmbientStateOverride.AMBIENT)))
    assertTrue("expected planner to produce a hook", planned is AroundComposableHook)
    assertEquals(DataExtensionId(Material3AmbientProduct.KIND), planned!!.id)
  }

  @Test
  fun `planner abstains when ambient override absent`() {
    val planner = AmbientPreviewOverrideExtension()
    assertNull(planner.plan(PreviewOverrides()))
    assertNull(planner.plan(PreviewOverrides(widthPx = 64)))
  }

  @Test
  fun `capabilities advertise compose ambient as inline no-rerender product`() {
    val registry = AmbientDataProductRegistry()
    val cap = registry.capabilities.single()
    assertEquals("compose/ambient", cap.kind)
    assertEquals(1, cap.schemaVersion)
    assertEquals(DataProductTransport.INLINE, cap.transport)
    assertTrue(cap.attachable)
    assertTrue(cap.fetchable)
    assertEquals(false, cap.requiresRerender)
  }

  @Test
  fun `fetch before any render returns NotAvailable`() {
    val registry = AmbientDataProductRegistry()
    val outcome = registry.fetch("preview-1", "compose/ambient", params = null, inline = true)
    assertEquals(DataProductRegistry.Outcome.NotAvailable, outcome)
  }

  @Test
  fun `fetch unknown kind returns Unknown`() {
    val registry = AmbientDataProductRegistry()
    val outcome = registry.fetch("preview-1", "compose/wallpaper", params = null, inline = true)
    assertEquals(DataProductRegistry.Outcome.Unknown, outcome)
  }

  @Test
  fun `capture surfaces payload via fetch and attachmentsFor`() {
    val registry = AmbientDataProductRegistry()
    val payload =
      AmbientPayload(
        state = "ambient",
        burnInProtectionRequired = true,
        deviceHasLowBitAmbient = false,
        updateTimeMillis = 99L,
      )
    registry.capture("preview-1", payload)

    val fetched = registry.fetch("preview-1", "compose/ambient", null, true)
    val ok = fetched as DataProductRegistry.Outcome.Ok
    val obj = ok.result.payload!!.jsonObject
    assertEquals("ambient", obj["state"]?.jsonPrimitive?.content)
    assertEquals(true, obj["burnInProtectionRequired"]?.jsonPrimitive?.boolean)
    assertEquals(false, obj["deviceHasLowBitAmbient"]?.jsonPrimitive?.boolean)

    val attachments = registry.attachmentsFor("preview-1", setOf("compose/ambient"))
    assertEquals(1, attachments.size)
    assertEquals("compose/ambient", attachments.single().kind)
  }

  @Test
  fun `attachmentsFor without matching kind returns empty`() {
    val registry = AmbientDataProductRegistry()
    registry.capture(
      "preview-1",
      AmbientPayload(
        state = "ambient",
        burnInProtectionRequired = false,
        deviceHasLowBitAmbient = false,
        updateTimeMillis = 0L,
      ),
    )
    assertEquals(emptyList<Any>(), registry.attachmentsFor("preview-1", setOf("compose/wallpaper")))
  }

  @Test
  fun `clear drops the captured payload`() {
    val registry = AmbientDataProductRegistry()
    registry.capture(
      "preview-1",
      AmbientPayload(
        state = "interactive",
        burnInProtectionRequired = false,
        deviceHasLowBitAmbient = false,
        updateTimeMillis = 0L,
      ),
    )
    registry.clear("preview-1")
    assertEquals(
      DataProductRegistry.Outcome.NotAvailable,
      registry.fetch("preview-1", "compose/ambient", null, true),
    )
  }

  @Test
  fun `interactive override surfaces non-ambient flags as false`() {
    val registry = AmbientDataProductRegistry()
    val overrides =
      PreviewOverrides(
        ambient =
          AmbientOverride(
            state = AmbientStateOverride.INTERACTIVE,
            burnInProtectionRequired = true,
            deviceHasLowBitAmbient = true,
          )
      )
    // Use a stub RenderResult — we only care that onRender captures something the fetch surfaces.
    val stubResult =
      RenderResult(id = 1L, classLoaderHashCode = 0, classLoaderName = "test")
    registry.onRender("preview-1", stubResult, overrides, null)
    val fetched = registry.fetch("preview-1", "compose/ambient", null, true)
    val ok = fetched as DataProductRegistry.Outcome.Ok
    val obj = ok.result.payload!!.jsonObject
    assertEquals("interactive", obj["state"]?.jsonPrimitive?.content)
    // Burn-in / low-bit flags only have meaning when state == ambient — we surface false otherwise
    // so a downstream consumer doesn't spuriously branch on them.
    assertEquals(false, obj["burnInProtectionRequired"]?.jsonPrimitive?.boolean)
    assertEquals(false, obj["deviceHasLowBitAmbient"]?.jsonPrimitive?.boolean)
    assertNotNull(obj["updateTimeMillis"]?.jsonPrimitive?.content)
  }
}
