package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.DataProductTransport
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.RemoteComposeOverride
import ee.schimke.composeai.daemon.protocol.RemoteComposeProfile
import ee.schimke.composeai.daemon.protocol.RemoteHostAction
import ee.schimke.composeai.daemon.protocol.RemoteNamedValue
import ee.schimke.composeai.data.remotecompose.RemoteComposePayload
import ee.schimke.composeai.data.render.PreviewContext
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

class RemoteComposeDataProductTest {

  @After
  fun reset() {
    RemoteComposeController.resetForNewSession()
  }

  @Test
  fun extension_declares_around_composable_hook_in_outer_environment() {
    val extension = RemoteComposeOverrideExtension(RemoteComposeOverride())
    val hook: AroundComposableHook = extension

    assertEquals(DataExtensionId("compose/remotecompose"), extension.id)
    assertEquals(setOf(DataExtensionHookKind.AroundComposable), extension.hooks)
    assertEquals(DataExtensionPhase.OuterEnvironment, extension.constraints.phase)
    assertTrue(extension.hasAroundComposableHook)
    assertEquals(extension, hook)
  }

  @Test
  fun planner_always_returns_extension_even_without_override() {
    val planner = RemoteComposePreviewOverrideExtension()
    val planned = planner.plan(PreviewOverrides())
    assertTrue("expected planner to always produce a hook", planned is AroundComposableHook)
    assertEquals(DataExtensionId("compose/remotecompose"), planned.id)
  }

  @Test
  fun planner_threads_override_through_to_extension() {
    val override =
      RemoteComposeOverride(
        profile = RemoteComposeProfile.ANDROIDX,
        namedValues = mapOf("score" to RemoteNamedValue.FloatValue(0.75f)),
      )
    val planned = RemoteComposePreviewOverrideExtension().plan(PreviewOverrides(remoteCompose = override))
    assertTrue(planned is RemoteComposeOverrideExtension)
  }

  @Test
  fun capabilities_advertise_compose_remotecompose_as_inline_no_rerender_product() {
    val cap = RemoteComposeDataProductRegistry().capabilities.single()
    assertEquals("compose/remotecompose", cap.kind)
    assertEquals(1, cap.schemaVersion)
    assertEquals(DataProductTransport.INLINE, cap.transport)
    assertTrue(cap.attachable)
    assertTrue(cap.fetchable)
    assertEquals(false, cap.requiresRerender)
  }

  @Test
  fun fetch_before_any_render_returns_not_available() {
    val registry = RemoteComposeDataProductRegistry()
    assertEquals(
      DataProductRegistry.Outcome.NotAvailable,
      registry.fetch("preview-1", "compose/remotecompose", params = null, inline = true),
    )
  }

  @Test
  fun fetch_unknown_kind_returns_unknown() {
    val registry = RemoteComposeDataProductRegistry()
    assertEquals(
      DataProductRegistry.Outcome.Unknown,
      registry.fetch("preview-1", "compose/theme", params = null, inline = true),
    )
  }

  @Test
  fun controller_set_replaces_named_values_and_profile() {
    val controller = RemoteComposeController
    controller.set(
      RemoteComposeOverride(
        profile = RemoteComposeProfile.ANDROIDX,
        namedValues =
          mapOf(
            "score" to RemoteNamedValue.FloatValue(0.5f),
            "label" to RemoteNamedValue.StringValue("hello"),
          ),
      )
    )
    assertEquals(RemoteComposeProfile.ANDROIDX, controller.profile.value)
    assertEquals(RemoteNamedValue.FloatValue(0.5f), controller.valueOf("score"))
    assertEquals(RemoteNamedValue.StringValue("hello"), controller.valueOf("label"))

    controller.set(
      RemoteComposeOverride(namedValues = mapOf("score" to RemoteNamedValue.FloatValue(0.9f)))
    )
    assertNull("profile must clear when new override drops it", controller.profile.value)
    assertEquals(RemoteNamedValue.FloatValue(0.9f), controller.valueOf("score"))
    assertNull("dropped key must not linger", controller.valueOf("label"))
  }

  @Test
  fun controller_setNamedValue_merges_without_dropping_existing_keys() {
    val controller = RemoteComposeController
    controller.set(
      RemoteComposeOverride(
        namedValues =
          mapOf(
            "a" to RemoteNamedValue.IntValue(1),
            "b" to RemoteNamedValue.IntValue(2),
          )
      )
    )
    controller.setNamedValue("a", RemoteNamedValue.IntValue(42))
    controller.setNamedValue("c", RemoteNamedValue.BooleanValue(true))
    assertEquals(RemoteNamedValue.IntValue(42), controller.valueOf("a"))
    assertEquals(RemoteNamedValue.IntValue(2), controller.valueOf("b"))
    assertEquals(RemoteNamedValue.BooleanValue(true), controller.valueOf("c"))
  }

  @Test
  fun controller_recordHostAction_respects_accepted_list_filter() {
    val controller = RemoteComposeController
    controller.set(RemoteComposeOverride(acceptedHostActions = listOf("allowed")))
    controller.recordHostAction(RemoteHostAction(payload = "allowed", handlerId = 1f))
    controller.recordHostAction(RemoteHostAction(payload = "rejected", handlerId = 2f))
    val captured = controller.hostActions.value
    assertEquals(1, captured.size)
    assertEquals("allowed", captured.single().payload)
  }

  @Test
  fun controller_recordHostAction_caps_at_buffer_size() {
    val controller = RemoteComposeController
    val cap = RemoteComposePayload.HOST_ACTION_BUFFER_SIZE
    repeat(cap + 5) { controller.recordHostAction(RemoteHostAction(payload = "p$it", handlerId = it.toFloat())) }
    val captured = controller.hostActions.value
    assertEquals(cap, captured.size)
    assertEquals("p5", captured.first().payload)
    assertEquals("p${cap + 4}", captured.last().payload)
  }

  @Test
  fun on_render_captures_payload_after_override_applied() {
    val registry = RemoteComposeDataProductRegistry()
    val override =
      RemoteComposeOverride(
        profile = RemoteComposeProfile.WEAR_WIDGETS,
        namedValues = mapOf("brightness" to RemoteNamedValue.FloatValue(0.6f)),
      )
    RemoteComposeController.set(override)
    RemoteComposeController.recordHostAction(RemoteHostAction(payload = "tap", handlerId = 3f))

    registry.onRender(
      previewId = "preview-1",
      result = stubRenderResult(),
      overrides = PreviewOverrides(remoteCompose = override),
      previewContext = stubContext(),
    )

    val outcome = registry.fetch("preview-1", "compose/remotecompose", params = null, inline = true)
    assertTrue(outcome is DataProductRegistry.Outcome.Ok)
  }

  @Test
  fun on_render_clears_payload_when_controller_empty() {
    val registry = RemoteComposeDataProductRegistry()
    registry.capture(
      "preview-1",
      RemoteComposePayload(profile = RemoteComposeProfile.ANDROIDX),
    )
    registry.onRender(
      previewId = "preview-1",
      result = stubRenderResult(),
      overrides = null,
      previewContext = stubContext(),
    )
    assertEquals(
      DataProductRegistry.Outcome.NotAvailable,
      registry.fetch("preview-1", "compose/remotecompose", params = null, inline = true),
    )
  }

  private fun stubRenderResult(): RenderResult =
    RenderResult(
      id = 1L,
      classLoaderHashCode = 0,
      classLoaderName = "test",
      previewContext = stubContext(),
    )

  private fun stubContext(): PreviewContext =
    PreviewContext(
      previewId = "preview-1",
      backend = "test",
      renderMode = null,
      outputBaseName = null,
    )
}
