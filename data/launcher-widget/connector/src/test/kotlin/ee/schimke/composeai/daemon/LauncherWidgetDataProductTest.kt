package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.DataProductTransport
import ee.schimke.composeai.daemon.protocol.LauncherResizeAxes
import ee.schimke.composeai.daemon.protocol.LauncherResizeOrder
import ee.schimke.composeai.daemon.protocol.LauncherWidgetOverride
import ee.schimke.composeai.daemon.protocol.LauncherWidgetSize
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.WallpaperOverride
import ee.schimke.composeai.data.render.PreviewContext
import ee.schimke.composeai.data.render.extensions.DataExtensionHookKind
import ee.schimke.composeai.data.render.extensions.DataExtensionId
import ee.schimke.composeai.data.render.extensions.DataExtensionPhase
import ee.schimke.composeai.data.render.extensions.compose.AroundComposableHook
import ee.schimke.composeai.data.render.extensions.compose.hasAroundComposableHook
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherWidgetDataProductTest {

  @Test
  fun extension_declares_around_composable_hook() {
    val extension =
      LauncherWidgetExtension(LauncherWidgetOverride(cells = LauncherWidgetSize(2, 3)))
    val hook: AroundComposableHook = extension

    assertEquals(DataExtensionId("compose/launcher-widget"), extension.id)
    assertEquals(setOf(DataExtensionHookKind.AroundComposable), extension.hooks)
    assertEquals(DataExtensionPhase.OuterEnvironment, extension.constraints.phase)
    assertTrue(extension.hasAroundComposableHook)
    assertEquals(extension, hook)
  }

  @Test
  fun planner_returns_around_composable_when_override_present() {
    val planner = LauncherWidgetPreviewOverrideExtension()
    val planned =
      planner.plan(
        PreviewOverrides(launcherWidget = LauncherWidgetOverride(cells = LauncherWidgetSize(4, 2)))
      )
    assertTrue("expected planner to produce a hook", planned is AroundComposableHook)
    assertEquals(DataExtensionId("compose/launcher-widget"), planned!!.id)
  }

  @Test
  fun planner_abstains_when_override_absent() {
    val planner = LauncherWidgetPreviewOverrideExtension()
    assertEquals(null, planner.plan(PreviewOverrides()))
    assertEquals(null, planner.plan(PreviewOverrides(widthPx = 64)))
    // Unrelated overrides on the same bag don't accidentally trigger the planner.
    assertEquals(
      null,
      planner.plan(PreviewOverrides(wallpaper = WallpaperOverride(seedColor = "#FF3366"))),
    )
  }

  @Test
  fun resolve_clamps_cells_into_configured_bounds() {
    val resolved =
      LauncherWidgetOverride(
          cells = LauncherWidgetSize(7, 0),
          minCells = LauncherWidgetSize(1, 3),
          maxCells = LauncherWidgetSize(4, 5),
        )
        .resolve()
    assertEquals(LauncherWidgetSize(4, 3), resolved.cells)
  }

  @Test
  fun resolve_applies_default_bounds_when_none_specified() {
    // Bare override with no min/max — defaults clamp 7×7 into 1×1..5×5.
    val resolved = LauncherWidgetOverride(cells = LauncherWidgetSize(7, 7)).resolve()
    assertEquals(LauncherWidgetSize(5, 5), resolved.cells)
    assertEquals(72, resolved.cellSizeDp)
    assertEquals(8, resolved.cellSpacingDp)
  }

  @Test
  fun resolve_honours_caller_cell_size_and_spacing() {
    val resolved =
      LauncherWidgetOverride(cells = LauncherWidgetSize(2, 2), cellSizeDp = 96, cellSpacingDp = 12)
        .resolve()
    assertEquals(96, resolved.cellSizeDp)
    assertEquals(12, resolved.cellSpacingDp)
  }

  @Test
  fun resolve_launcher_mode_defaults_off_and_honours_the_flag() {
    assertEquals(
      false,
      LauncherWidgetOverride(cells = LauncherWidgetSize(2, 2)).resolve().launcherMode,
    )
    assertEquals(
      true,
      LauncherWidgetOverride(cells = LauncherWidgetSize(2, 2), launcherMode = true)
        .resolve()
        .launcherMode,
    )
  }

  @Test
  fun resolve_rejects_inverted_bounds() {
    val override =
      LauncherWidgetOverride(
        cells = LauncherWidgetSize(2, 2),
        minCells = LauncherWidgetSize(4, 4),
        maxCells = LauncherWidgetSize(1, 1),
      )
    assertThrows(IllegalArgumentException::class.java) { override.resolve() }
  }

  @Test
  fun resolve_clamps_negative_dimensions_to_zero() {
    // `cellSizeDp = -5` is nonsensical but the protocol allows any Int; coerceAtLeast(0)
    // protects the dp-multiply arithmetic from producing negative `widthDp`.
    val resolved =
      LauncherWidgetOverride(cells = LauncherWidgetSize(1, 1), cellSizeDp = -5, cellSpacingDp = -2)
        .resolve()
    assertEquals(0, resolved.cellSizeDp)
    assertEquals(0, resolved.cellSpacingDp)
  }

  @Test
  fun stops_diagonal_walks_the_spec_example() {
    // 1×1 → 4×2 under DIAGONAL must visit exactly 1×1, 2×1, 3×2, 4×2.
    val stops =
      launcherWidgetStops(
        LauncherWidgetSize(1, 1),
        LauncherWidgetSize(4, 2),
        LauncherResizeOrder.DIAGONAL,
      )
    assertEquals(
      listOf(
        LauncherWidgetSize(1, 1),
        LauncherWidgetSize(2, 1),
        LauncherWidgetSize(3, 2),
        LauncherWidgetSize(4, 2),
      ),
      stops,
    )
  }

  @Test
  fun stops_width_first_walks_one_axis_to_completion() {
    val stops =
      launcherWidgetStops(
        LauncherWidgetSize(1, 1),
        LauncherWidgetSize(4, 2),
        LauncherResizeOrder.WIDTH_FIRST,
      )
    assertEquals(
      listOf(
        LauncherWidgetSize(1, 1),
        LauncherWidgetSize(2, 1),
        LauncherWidgetSize(3, 1),
        LauncherWidgetSize(4, 1),
        LauncherWidgetSize(4, 2),
      ),
      stops,
    )
  }

  @Test
  fun stops_height_first_walks_height_to_completion() {
    val stops =
      launcherWidgetStops(
        LauncherWidgetSize(1, 1),
        LauncherWidgetSize(4, 2),
        LauncherResizeOrder.HEIGHT_FIRST,
      )
    assertEquals(
      listOf(
        LauncherWidgetSize(1, 1),
        LauncherWidgetSize(1, 2),
        LauncherWidgetSize(2, 2),
        LauncherWidgetSize(3, 2),
        LauncherWidgetSize(4, 2),
      ),
      stops,
    )
  }

  @Test
  fun stops_default_order_is_width_first() {
    assertEquals(
      launcherWidgetStops(
        LauncherWidgetSize(1, 1),
        LauncherWidgetSize(4, 2),
        LauncherResizeOrder.WIDTH_FIRST,
      ),
      launcherWidgetStops(LauncherWidgetSize(1, 1), LauncherWidgetSize(4, 2)),
    )
  }

  @Test
  fun stops_zero_delta_collapses_to_single_stop_in_every_order() {
    for (order in LauncherResizeOrder.entries) {
      assertEquals(
        listOf(LauncherWidgetSize(2, 3)),
        launcherWidgetStops(LauncherWidgetSize(2, 3), LauncherWidgetSize(2, 3), order),
      )
    }
  }

  @Test
  fun stops_pure_horizontal_grow_is_independent_of_order() {
    val expected =
      listOf(
        LauncherWidgetSize(1, 2),
        LauncherWidgetSize(2, 2),
        LauncherWidgetSize(3, 2),
        LauncherWidgetSize(4, 2),
      )
    for (order in LauncherResizeOrder.entries) {
      assertEquals(
        expected,
        launcherWidgetStops(LauncherWidgetSize(1, 2), LauncherWidgetSize(4, 2), order),
      )
    }
  }

  @Test
  fun stops_shrink_walks_the_diagonal_path_in_reverse() {
    val forward =
      launcherWidgetStops(
        LauncherWidgetSize(1, 1),
        LauncherWidgetSize(4, 2),
        LauncherResizeOrder.DIAGONAL,
      )
    val reverse =
      launcherWidgetStops(
        LauncherWidgetSize(4, 2),
        LauncherWidgetSize(1, 1),
        LauncherResizeOrder.DIAGONAL,
      )
    assertEquals(forward.reversed(), reverse)
  }

  // -----------------------------------------------------------------------
  // LauncherWidgetDataProductRegistry — captures the resolved cells + dp
  // footprint per preview for `data/fetch?kind=compose/launcher-widget`.
  // -----------------------------------------------------------------------

  @Test
  fun registry_capabilities_advertise_inline_no_rerender_product() {
    val registry = LauncherWidgetDataProductRegistry()
    val cap = registry.capabilities.single()
    assertEquals("compose/launcher-widget", cap.kind)
    assertEquals(1, cap.schemaVersion)
    assertEquals(DataProductTransport.INLINE, cap.transport)
    assertTrue(cap.attachable)
    assertTrue(cap.fetchable)
    assertEquals(false, cap.requiresRerender)
  }

  @Test
  fun registry_fetch_before_any_render_returns_not_available() {
    val registry = LauncherWidgetDataProductRegistry()
    val outcome =
      registry.fetch("preview-1", "compose/launcher-widget", params = null, inline = true)
    assertEquals(DataProductRegistry.Outcome.NotAvailable, outcome)
  }

  @Test
  fun registry_on_render_with_override_captures_resolved_size_and_dp_footprint() {
    val registry = LauncherWidgetDataProductRegistry()
    val result = stubRenderResult()
    val overrides =
      PreviewOverrides(launcherWidget = LauncherWidgetOverride(cells = LauncherWidgetSize(4, 2)))

    registry.onRender("preview-1", result, overrides, previewContext = null)

    val outcome =
      registry.fetch("preview-1", "compose/launcher-widget", params = null, inline = true)
    assertTrue(outcome is DataProductRegistry.Outcome.Ok)
    val payload = (outcome as DataProductRegistry.Outcome.Ok).result.payload!!.jsonObject
    val cells = payload["cells"]!!.jsonObject
    assertEquals(4, cells["width"]!!.jsonPrimitive.content.toInt())
    assertEquals(2, cells["height"]!!.jsonPrimitive.content.toInt())
    // 4*72 + 3*8 = 312, 2*72 + 1*8 = 152 at the connector defaults
    assertEquals(72, payload["cellSizeDp"]!!.jsonPrimitive.content.toInt())
    assertEquals(8, payload["cellSpacingDp"]!!.jsonPrimitive.content.toInt())
    assertEquals(312, payload["widthDp"]!!.jsonPrimitive.content.toInt())
    assertEquals(152, payload["heightDp"]!!.jsonPrimitive.content.toInt())
  }

  @Test
  fun registry_on_render_echoes_launcher_mode_flag() {
    val registry = LauncherWidgetDataProductRegistry()
    registry.onRender(
      "preview-1",
      stubRenderResult(),
      PreviewOverrides(
        launcherWidget =
          LauncherWidgetOverride(cells = LauncherWidgetSize(4, 2), launcherMode = true)
      ),
      previewContext = null,
    )
    val outcome =
      registry.fetch("preview-1", "compose/launcher-widget", null, true)
        as DataProductRegistry.Outcome.Ok
    assertEquals(
      true,
      outcome.result.payload!!.jsonObject["launcherMode"]!!.jsonPrimitive.content.toBoolean(),
    )

    // Default render without the flag reports false.
    registry.onRender(
      "preview-2",
      stubRenderResult(),
      PreviewOverrides(launcherWidget = LauncherWidgetOverride(cells = LauncherWidgetSize(2, 2))),
      previewContext = null,
    )
    val plain =
      registry.fetch("preview-2", "compose/launcher-widget", null, true)
        as DataProductRegistry.Outcome.Ok
    assertEquals(
      false,
      plain.result.payload!!.jsonObject["launcherMode"]!!.jsonPrimitive.content.toBoolean(),
    )
  }

  @Test
  fun registry_on_render_with_clamping_captures_post_clamp_cells() {
    val registry = LauncherWidgetDataProductRegistry()
    val overrides =
      PreviewOverrides(
        launcherWidget =
          LauncherWidgetOverride(
            cells = LauncherWidgetSize(7, 7),
            minCells = LauncherWidgetSize(1, 3),
            maxCells = LauncherWidgetSize(4, 5),
          )
      )

    registry.onRender("preview-1", stubRenderResult(), overrides, previewContext = null)

    val outcome =
      registry.fetch("preview-1", "compose/launcher-widget", null, true)
        as DataProductRegistry.Outcome.Ok
    val cells = outcome.result.payload!!.jsonObject["cells"]!!.jsonObject
    assertEquals(4, cells["width"]!!.jsonPrimitive.content.toInt())
    assertEquals(5, cells["height"]!!.jsonPrimitive.content.toInt())
  }

  @Test
  fun registry_on_render_without_override_drops_previous_capture() {
    val registry = LauncherWidgetDataProductRegistry()
    val result = stubRenderResult()

    registry.onRender(
      "preview-1",
      result,
      PreviewOverrides(launcherWidget = LauncherWidgetOverride(cells = LauncherWidgetSize(2, 2))),
      previewContext = null,
    )
    assertTrue(
      registry.fetch("preview-1", "compose/launcher-widget", null, true)
        is DataProductRegistry.Outcome.Ok
    )

    registry.onRender("preview-1", result, overrides = null, previewContext = null)

    assertEquals(
      DataProductRegistry.Outcome.NotAvailable,
      registry.fetch("preview-1", "compose/launcher-widget", null, true),
    )
  }

  @Test
  fun registry_attachments_returned_for_subscribed_kind() {
    val registry = LauncherWidgetDataProductRegistry()
    registry.onRender(
      "preview-1",
      stubRenderResult(),
      PreviewOverrides(launcherWidget = LauncherWidgetOverride(cells = LauncherWidgetSize(2, 2))),
      previewContext = null,
    )

    val attachments = registry.attachmentsFor("preview-1", setOf("compose/launcher-widget"))
    assertEquals(1, attachments.size)
    assertEquals("compose/launcher-widget", attachments.single().kind)
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

  // -----------------------------------------------------------------------
  // LauncherWidgetMetadataChannel — per-render carrier for widget-source
  // metadata (Glance previewSizeMode reflection, future XML auto-discovery).
  // Verified via the registry's onRender path since the channel's consume is
  // internal and the registry is the only intended reader.
  // -----------------------------------------------------------------------

  @Test
  fun channel_offer_no_op_when_current_preview_id_unset() {
    val registry = LauncherWidgetDataProductRegistry()
    LauncherWidgetMetadataChannel.setCurrentPreviewId(null)
    // Should silently no-op — no id to key on.
    LauncherWidgetMetadataChannel.offer(
      LauncherWidgetMetadata(resizeAxes = LauncherResizeAxes.NONE)
    )
    registry.onRender("preview-x", stubRenderResult(), overrides = null, previewContext = null)
    assertEquals(
      DataProductRegistry.Outcome.NotAvailable,
      registry.fetch("preview-x", "compose/launcher-widget", null, true),
    )
  }

  @Test
  fun registry_on_render_picks_up_channel_metadata_for_glance_responsive() {
    val registry = LauncherWidgetDataProductRegistry()
    val responsive =
      LauncherWidgetMetadata(
        supportedCells = listOf(LauncherWidgetSize(2, 1), LauncherWidgetSize(4, 2)),
        resizeAxes = LauncherResizeAxes.BOTH,
      )
    LauncherWidgetMetadataChannel.setCurrentPreviewId("preview-1")
    try {
      LauncherWidgetMetadataChannel.offer(responsive)
    } finally {
      LauncherWidgetMetadataChannel.setCurrentPreviewId(null)
    }
    // No override — the registry should still surface a payload from the channel alone.
    registry.onRender("preview-1", stubRenderResult(), overrides = null, previewContext = null)

    val outcome =
      registry.fetch("preview-1", "compose/launcher-widget", null, true)
        as DataProductRegistry.Outcome.Ok
    val payload = outcome.result.payload!!.jsonObject
    val supported = payload["supportedCells"]!!.jsonArray
    assertEquals(2, supported.size)
    assertEquals("both", payload["resizeAxes"]!!.jsonPrimitive.content)
  }

  @Test
  fun registry_on_render_glance_single_signals_no_resize() {
    val registry = LauncherWidgetDataProductRegistry()
    LauncherWidgetMetadataChannel.setCurrentPreviewId("preview-1")
    try {
      LauncherWidgetMetadataChannel.offer(
        LauncherWidgetMetadata(supportedCells = emptyList(), resizeAxes = LauncherResizeAxes.NONE)
      )
    } finally {
      LauncherWidgetMetadataChannel.setCurrentPreviewId(null)
    }
    registry.onRender("preview-1", stubRenderResult(), overrides = null, previewContext = null)

    val outcome =
      registry.fetch("preview-1", "compose/launcher-widget", null, true)
        as DataProductRegistry.Outcome.Ok
    val payload = outcome.result.payload!!.jsonObject
    assertEquals(0, payload["supportedCells"]!!.jsonArray.size)
    assertEquals("none", payload["resizeAxes"]!!.jsonPrimitive.content)
  }
}
