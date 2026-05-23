package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.DataProductTransport
import ee.schimke.composeai.daemon.protocol.KeyboardOverride
import ee.schimke.composeai.daemon.protocol.PermissionGrantStateOverride
import ee.schimke.composeai.daemon.protocol.PermissionsOverride
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.data.permissions.Material3PermissionsProduct
import ee.schimke.composeai.data.permissions.PermissionsPayload
import ee.schimke.composeai.data.render.extensions.DataExtensionHookKind
import ee.schimke.composeai.data.render.extensions.DataExtensionId
import ee.schimke.composeai.data.render.extensions.DataExtensionPhase
import ee.schimke.composeai.data.render.extensions.compose.AroundComposableHook
import ee.schimke.composeai.data.render.extensions.compose.hasAroundComposableHook
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the contract of `:data-permissions-connector`'s extension + registry surface. Mirrors
 * [KeyboardDataProductTest] — the planner is always-on so the controller seed + the shadow
 * tracker's recordQuery path are wired on every render, even when the client hasn't sent an
 * explicit override.
 */
class PermissionsDataProductTest {

  @After fun tearDown() = PermissionsController.resetForNewSession()

  @Test
  fun `permissions override extension declares around-composable hook in OuterEnvironment phase`() {
    val extension =
      PermissionsOverrideExtension(
        PermissionsOverride(
          grants =
            mapOf("android.permission.CAMERA" to PermissionGrantStateOverride.GRANTED)
        )
      )
    val hook: AroundComposableHook = extension

    assertEquals(DataExtensionId(Material3PermissionsProduct.KIND), extension.id)
    assertEquals(setOf(DataExtensionHookKind.AroundComposable), extension.hooks)
    assertEquals(DataExtensionPhase.OuterEnvironment, extension.constraints.phase)
    assertTrue(extension.hasAroundComposableHook)
    assertEquals(extension, hook)
  }

  @Test
  fun `planner always returns an extension so the tracker is wired even without an override`() {
    val planner = PermissionsPreviewOverrideExtension()
    val planned = planner.plan(PreviewOverrides())
    assertNotNull(
      "planner must always emit the extension so the controller seed + shadow tracker recordQuery " +
        "path are in place for every render",
      planned,
    )
    assertEquals(DataExtensionId(Material3PermissionsProduct.KIND), planned.id)
    // Sibling overrides shouldn't change the always-on shape.
    assertNotNull(planner.plan(PreviewOverrides(keyboard = KeyboardOverride(visible = true))))
    assertNotNull(
      planner.plan(
        PreviewOverrides(
          permissions =
            PermissionsOverride(
              grants =
                mapOf("android.permission.RECORD_AUDIO" to PermissionGrantStateOverride.DENIED)
            )
        )
      )
    )
  }

  @Test
  fun `capabilities advertise compose permissions as inline no-rerender product`() {
    val registry = PermissionsDataProductRegistry()
    val cap = registry.capabilities.single()
    assertEquals("compose/permissions", cap.kind)
    assertEquals(1, cap.schemaVersion)
    assertEquals(DataProductTransport.INLINE, cap.transport)
    assertTrue(cap.attachable)
    assertTrue(cap.fetchable)
    assertFalse(cap.requiresRerender)
  }

  @Test
  fun `fetch before any render returns NotAvailable`() {
    val registry = PermissionsDataProductRegistry()
    val outcome = registry.fetch("preview-1", "compose/permissions", params = null, inline = true)
    assertEquals(DataProductRegistry.Outcome.NotAvailable, outcome)
  }

  @Test
  fun `fetch unknown kind returns Unknown`() {
    val registry = PermissionsDataProductRegistry()
    val outcome = registry.fetch("preview-1", "compose/wallpaper", params = null, inline = true)
    assertEquals(DataProductRegistry.Outcome.Unknown, outcome)
  }

  @Test
  fun `capture surfaces grants and queried lists via fetch and attachmentsFor`() {
    val registry = PermissionsDataProductRegistry()
    val payload =
      PermissionsPayload(
        grants =
          mapOf(
            "android.permission.CAMERA" to
              ee.schimke.composeai.data.permissions.PermissionGrantWire.GRANTED,
            "android.permission.RECORD_AUDIO" to
              ee.schimke.composeai.data.permissions.PermissionGrantWire.DENIED,
          ),
        queried = listOf("android.permission.CAMERA", "android.permission.ACCESS_FINE_LOCATION"),
      )
    registry.capture("preview-1", payload)

    val fetched = registry.fetch("preview-1", "compose/permissions", null, true)
    val ok = fetched as DataProductRegistry.Outcome.Ok
    val obj = ok.result.payload!!.jsonObject
    val grants = obj["grants"]!!.jsonObject
    assertEquals(
      "granted",
      grants["android.permission.CAMERA"]?.jsonPrimitive?.contentOrNull,
    )
    assertEquals(
      "denied",
      grants["android.permission.RECORD_AUDIO"]?.jsonPrimitive?.contentOrNull,
    )
    val queried = obj["queried"]!!.jsonArray.map { it.jsonPrimitive.content }
    assertEquals(
      listOf("android.permission.CAMERA", "android.permission.ACCESS_FINE_LOCATION"),
      queried,
    )

    val attachments = registry.attachmentsFor("preview-1", setOf("compose/permissions"))
    assertEquals(1, attachments.size)
    assertEquals("compose/permissions", attachments.single().kind)
  }

  @Test
  fun `clear drops the captured payload`() {
    val registry = PermissionsDataProductRegistry()
    registry.capture(
      "preview-1",
      PermissionsPayload(grants = emptyMap(), queried = listOf("android.permission.CAMERA")),
    )
    registry.clear("preview-1")
    assertEquals(
      DataProductRegistry.Outcome.NotAvailable,
      registry.fetch("preview-1", "compose/permissions", null, true),
    )
  }

  @Test
  fun `onRender captures override grants plus controller queries`() {
    val registry = PermissionsDataProductRegistry()
    // Seed the controller as if the around-composable had applied the override and a screen had
    // queried two permissions during composition.
    PermissionsController.set(
      PermissionsOverride(
        grants =
          mapOf(
            "android.permission.CAMERA" to PermissionGrantStateOverride.GRANTED,
            "android.permission.RECORD_AUDIO" to PermissionGrantStateOverride.DENIED,
          )
      )
    )
    PermissionsController.recordQuery("android.permission.CAMERA")
    PermissionsController.recordQuery("android.permission.RECORD_AUDIO")

    val stubResult = RenderResult(id = 1L, classLoaderHashCode = 0, classLoaderName = "test")
    registry.onRender(
      "preview-1",
      stubResult,
      PreviewOverrides(
        permissions =
          PermissionsOverride(
            grants =
              mapOf(
                "android.permission.CAMERA" to PermissionGrantStateOverride.GRANTED,
                "android.permission.RECORD_AUDIO" to PermissionGrantStateOverride.DENIED,
              )
          )
      ),
      previewContext = null,
    )

    val fetched = registry.fetch("preview-1", "compose/permissions", null, true)
    val ok = fetched as DataProductRegistry.Outcome.Ok
    val obj = ok.result.payload!!.jsonObject
    assertEquals(
      "granted",
      obj["grants"]!!.jsonObject["android.permission.CAMERA"]?.jsonPrimitive?.contentOrNull,
    )
    val queried = obj["queried"]!!.jsonArray.map { it.jsonPrimitive.content }
    assertEquals(listOf("android.permission.CAMERA", "android.permission.RECORD_AUDIO"), queried)
  }

  @Test
  fun `onRender with empty controller state and no overrides clears the payload`() {
    val registry = PermissionsDataProductRegistry()
    registry.capture(
      "preview-1",
      PermissionsPayload(grants = emptyMap(), queried = listOf("android.permission.CAMERA")),
    )
    val stubResult = RenderResult(id = 1L, classLoaderHashCode = 0, classLoaderName = "test")
    registry.onRender("preview-1", stubResult, PreviewOverrides(), previewContext = null)
    assertEquals(
      DataProductRegistry.Outcome.NotAvailable,
      registry.fetch("preview-1", "compose/permissions", null, true),
    )
  }
}
