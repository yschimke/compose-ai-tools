package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.DataProductTransport
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.data.overrides.PreviewOverrideDeclaration
import ee.schimke.composeai.data.overrides.PreviewOverrideType
import ee.schimke.composeai.data.overrides.PreviewOverrideValue
import ee.schimke.composeai.data.render.PreviewContext
import ee.schimke.composeai.data.render.extensions.DataExtensionHookKind
import ee.schimke.composeai.data.render.extensions.DataExtensionId
import ee.schimke.composeai.data.render.extensions.DataExtensionPhase
import ee.schimke.composeai.data.render.extensions.compose.AroundComposableHook
import ee.schimke.composeai.data.render.extensions.compose.hasAroundComposableHook
import ee.schimke.composeai.overrides.PreviewOverrideController
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewOverridesDataProductTest {

  @After
  fun reset() {
    PreviewOverrideController.resetForNewSession()
  }

  @Test
  fun extension_declares_around_composable_hook_in_outer_environment() {
    val extension = PreviewOverridesOverrideExtension()
    val hook: AroundComposableHook = extension

    assertEquals(DataExtensionId("compose/overrides"), extension.id)
    assertEquals(setOf(DataExtensionHookKind.AroundComposable), extension.hooks)
    assertEquals(DataExtensionPhase.OuterEnvironment, extension.constraints.phase)
    assertTrue(extension.hasAroundComposableHook)
    assertEquals(extension, hook)
  }

  @Test
  fun planner_always_returns_extension_even_without_override() {
    val planned = PreviewOverridesPreviewOverrideExtension().plan(PreviewOverrides())
    assertTrue("expected planner to always produce a hook", planned is AroundComposableHook)
    assertEquals(DataExtensionId("compose/overrides"), planned.id)
  }

  @Test
  fun planner_threads_named_overrides_through_to_extension() {
    val overrides =
      PreviewOverrides(
        namedOverrides = mapOf("label" to PreviewOverrideValue.StringValue("Tap me"))
      )
    val planned = PreviewOverridesPreviewOverrideExtension().plan(overrides)
    assertTrue(planned is PreviewOverridesOverrideExtension)
  }

  @Test
  fun capabilities_advertise_compose_overrides_as_inline_no_rerender_product() {
    val cap = PreviewOverridesDataProductRegistry().capabilities.single()
    assertEquals("compose/overrides", cap.kind)
    assertEquals(1, cap.schemaVersion)
    assertEquals(DataProductTransport.INLINE, cap.transport)
    assertTrue(cap.attachable)
    assertTrue(cap.fetchable)
    assertEquals(false, cap.requiresRerender)
  }

  @Test
  fun fetch_before_any_render_returns_not_available() {
    val registry = PreviewOverridesDataProductRegistry()
    assertEquals(
      DataProductRegistry.Outcome.NotAvailable,
      registry.fetch("preview-1", "compose/overrides", params = null, inline = true),
    )
  }

  @Test
  fun fetch_unknown_kind_returns_unknown() {
    val registry = PreviewOverridesDataProductRegistry()
    assertEquals(
      DataProductRegistry.Outcome.Unknown,
      registry.fetch("preview-1", "compose/theme", params = null, inline = true),
    )
  }

  @Test
  fun controller_set_replaces_seeded_values() {
    val c = PreviewOverrideController
    c.set(
      mapOf(
        "label" to PreviewOverrideValue.StringValue("hi"),
        "rowCount" to PreviewOverrideValue.IntValue(4),
      )
    )
    assertEquals(PreviewOverrideValue.StringValue("hi"), c.valueOf("label"))
    assertEquals(PreviewOverrideValue.IntValue(4), c.valueOf("rowCount"))

    c.set(mapOf("rowCount" to PreviewOverrideValue.IntValue(2)))
    assertNull("dropped key must not linger", c.valueOf("label"))
    assertEquals(PreviewOverrideValue.IntValue(2), c.valueOf("rowCount"))
  }

  @Test
  fun controller_record_dedupes_by_seed_key_and_preserves_order() {
    val c = PreviewOverrideController
    c.record(decl("rowCount", PreviewOverrideType.INT, PreviewOverrideValue.IntValue(3)))
    c.record(
      decl("rowLabel", PreviewOverrideType.STRING, PreviewOverrideValue.StringValue("a"), index = 0)
    )
    c.record(
      decl("rowLabel", PreviewOverrideType.STRING, PreviewOverrideValue.StringValue("b"), index = 1)
    )
    // Re-declare rowCount (a recomposition): replaces in place, no duplicate, order kept.
    c.record(decl("rowCount", PreviewOverrideType.INT, PreviewOverrideValue.IntValue(3)))

    val keys = c.declarations().map { it.seedKey }
    assertEquals(listOf("rowCount", "rowLabel[0]", "rowLabel[1]"), keys)
  }

  @Test
  fun controller_clearDeclarations_keeps_seeds() {
    val c = PreviewOverrideController
    c.set(mapOf("label" to PreviewOverrideValue.StringValue("seed")))
    c.record(decl("label", PreviewOverrideType.STRING, PreviewOverrideValue.StringValue("def")))
    c.clearDeclarations()
    assertTrue(c.declarations().isEmpty())
    assertEquals(PreviewOverrideValue.StringValue("seed"), c.valueOf("label"))
  }

  @Test
  fun on_render_captures_declarations_then_clears_when_empty() {
    val registry = PreviewOverridesDataProductRegistry()
    PreviewOverrideController.record(
      decl("label", PreviewOverrideType.STRING, PreviewOverrideValue.StringValue("Tap me"))
    )

    registry.onRender(
      "preview-1",
      stubRenderResult(),
      overrides = null,
      previewContext = stubContext(),
    )
    assertTrue(
      registry.fetch("preview-1", "compose/overrides", params = null, inline = true)
        is DataProductRegistry.Outcome.Ok
    )

    // A subsequent render that declared nothing must drop the stale payload.
    PreviewOverrideController.clearDeclarations()
    registry.onRender(
      "preview-1",
      stubRenderResult(),
      overrides = null,
      previewContext = stubContext(),
    )
    assertEquals(
      DataProductRegistry.Outcome.NotAvailable,
      registry.fetch("preview-1", "compose/overrides", params = null, inline = true),
    )
  }

  private fun decl(
    key: String,
    type: String,
    default: PreviewOverrideValue,
    index: Int? = null,
  ): PreviewOverrideDeclaration =
    PreviewOverrideDeclaration(
      key = key,
      type = type,
      default = default,
      current = default,
      index = index,
    )

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
