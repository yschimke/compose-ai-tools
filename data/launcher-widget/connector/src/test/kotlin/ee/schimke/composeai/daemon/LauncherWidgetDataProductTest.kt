package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.LauncherResizeOrder
import ee.schimke.composeai.daemon.protocol.LauncherWidgetOverride
import ee.schimke.composeai.daemon.protocol.LauncherWidgetSize
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.WallpaperOverride
import ee.schimke.composeai.data.render.extensions.DataExtensionHookKind
import ee.schimke.composeai.data.render.extensions.DataExtensionId
import ee.schimke.composeai.data.render.extensions.DataExtensionPhase
import ee.schimke.composeai.data.render.extensions.compose.AroundComposableHook
import ee.schimke.composeai.data.render.extensions.compose.hasAroundComposableHook
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
}
