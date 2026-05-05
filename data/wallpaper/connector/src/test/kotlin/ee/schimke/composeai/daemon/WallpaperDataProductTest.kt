package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.DataProductTransport
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.WallpaperOverride
import ee.schimke.composeai.data.render.PreviewContext
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
import org.junit.Assert.assertTrue
import org.junit.Test

class WallpaperDataProductTest {

  @Test
  fun wallpaper_override_extension_declares_around_composable_hook() {
    val extension = WallpaperOverrideExtension(WallpaperOverride(seedColor = "#FF3366"))
    val hook: AroundComposableHook = extension

    assertEquals(DataExtensionId("compose/wallpaper"), extension.id)
    assertEquals(setOf(DataExtensionHookKind.AroundComposable), extension.hooks)
    assertEquals(DataExtensionPhase.UserEnvironment, extension.constraints.phase)
    assertTrue(extension.hasAroundComposableHook)
    assertEquals(extension, hook)
  }

  @Test
  fun wallpaper_planner_returns_around_composable_when_seed_present() {
    val planner = WallpaperPreviewOverrideExtension()
    val planned =
      planner.plan(PreviewOverrides(wallpaper = WallpaperOverride(seedColor = "#FF3366")))
    assertTrue("expected planner to produce a hook", planned is AroundComposableHook)
    assertEquals(DataExtensionId("compose/wallpaper"), planned!!.id)
  }

  @Test
  fun wallpaper_planner_abstains_when_seed_absent() {
    val planner = WallpaperPreviewOverrideExtension()
    assertEquals(null, planner.plan(PreviewOverrides()))
    assertEquals(null, planner.plan(PreviewOverrides(widthPx = 64)))
  }

  @Test
  fun capabilities_advertise_compose_wallpaper_as_inline_no_rerender_product() {
    val registry = WallpaperDataProductRegistry()
    val cap = registry.capabilities.single()
    assertEquals("compose/wallpaper", cap.kind)
    assertEquals(1, cap.schemaVersion)
    assertEquals(DataProductTransport.INLINE, cap.transport)
    assertTrue(cap.attachable)
    assertTrue(cap.fetchable)
    assertEquals(false, cap.requiresRerender)
  }

  @Test
  fun fetch_before_any_render_returns_not_available() {
    val registry = WallpaperDataProductRegistry()
    val outcome = registry.fetch("preview-1", "compose/wallpaper", params = null, inline = true)
    assertEquals(DataProductRegistry.Outcome.NotAvailable, outcome)
  }

  @Test
  fun fetch_unknown_kind_returns_unknown() {
    val registry = WallpaperDataProductRegistry()
    val outcome = registry.fetch("preview-1", "compose/theme", params = null, inline = true)
    assertEquals(DataProductRegistry.Outcome.Unknown, outcome)
  }

  @Test
  fun on_render_with_override_captures_seed_for_subsequent_fetch() {
    val registry = WallpaperDataProductRegistry()
    val result = stubRenderResult()
    val overrides = PreviewOverrides(wallpaper = WallpaperOverride(seedColor = "#FF3366FF"))

    registry.onRender("preview-1", result, overrides, previewContext = null)

    val outcome = registry.fetch("preview-1", "compose/wallpaper", params = null, inline = true)
    assertTrue(outcome is DataProductRegistry.Outcome.Ok)
    val payload = (outcome as DataProductRegistry.Outcome.Ok).result.payload!!.jsonObject
    assertEquals("#FF3366FF", payload["seedColor"]!!.jsonPrimitive.content)
    assertEquals(false, payload["isDark"]!!.jsonPrimitive.boolean)
    val derived = payload["derivedColorScheme"]!!.jsonObject
    assertNotNull(derived["primary"])
    assertTrue(derived["primary"]!!.jsonPrimitive.content.matches(Regex("#[0-9A-F]{8}")))
  }

  @Test
  fun on_render_without_override_drops_previous_capture() {
    val registry = WallpaperDataProductRegistry()
    val result = stubRenderResult()

    registry.onRender(
      "preview-1",
      result,
      PreviewOverrides(wallpaper = WallpaperOverride(seedColor = "#FF3366FF")),
      previewContext = null,
    )
    assertTrue(
      registry.fetch("preview-1", "compose/wallpaper", null, true) is DataProductRegistry.Outcome.Ok
    )

    registry.onRender("preview-1", result, overrides = null, previewContext = null)

    assertEquals(
      DataProductRegistry.Outcome.NotAvailable,
      registry.fetch("preview-1", "compose/wallpaper", null, true),
    )
  }

  @Test
  fun on_render_with_explicit_isDark_flips_derived_brightness() {
    val registry = WallpaperDataProductRegistry()
    val result = stubRenderResult()

    registry.onRender(
      "preview-1",
      result,
      PreviewOverrides(wallpaper = WallpaperOverride(seedColor = "#FF3366FF", isDark = true)),
      previewContext = null,
    )

    val outcome =
      registry.fetch("preview-1", "compose/wallpaper", null, true) as DataProductRegistry.Outcome.Ok
    val payload = outcome.result.payload!!.jsonObject
    assertEquals(true, payload["isDark"]!!.jsonPrimitive.boolean)
  }

  @Test
  fun attachments_returned_for_subscribed_kind() {
    val registry = WallpaperDataProductRegistry()
    registry.onRender(
      "preview-1",
      stubRenderResult(),
      PreviewOverrides(wallpaper = WallpaperOverride(seedColor = "#FF3366FF")),
      previewContext = null,
    )

    val attachments = registry.attachmentsFor("preview-1", setOf("compose/wallpaper"))
    assertEquals(1, attachments.size)
    assertEquals("compose/wallpaper", attachments.single().kind)
    assertEquals(1, attachments.single().schemaVersion)
  }

  @Test
  fun attachments_empty_when_kind_not_in_set() {
    val registry = WallpaperDataProductRegistry()
    registry.onRender(
      "preview-1",
      stubRenderResult(),
      PreviewOverrides(wallpaper = WallpaperOverride(seedColor = "#FF3366FF")),
      previewContext = null,
    )

    assertEquals(emptyList<Any>(), registry.attachmentsFor("preview-1", setOf("compose/theme")))
  }

  private fun stubRenderResult(): RenderResult =
    RenderResult(
      id = 1L,
      classLoaderHashCode = 0,
      classLoaderName = "test",
      previewContext =
        PreviewContext(
          previewId = "preview-1",
          backend = "test",
          renderMode = null,
          outputBaseName = null,
        ),
    )
}
