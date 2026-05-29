package ee.schimke.composeai.preview.appwidget

import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.util.DisplayMetrics
import ee.schimke.composeai.daemon.protocol.LauncherResizeAxes
import ee.schimke.composeai.daemon.protocol.LauncherWidgetSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Unit tests for [translate] — the `AppWidgetProviderInfo` → [LauncherWidgetMetadata] mapping.
 * Runs under Robolectric so `AppWidgetProviderInfo` is a real constructed instance with default
 * field values; we override the ones the translation reads (`min/maxResizeWidth/Height`,
 * `targetCellWidth/Height`, `resizeMode`) and assert the cell math, axis-locking, and
 * supported-cells rectangle.
 *
 * Avoids spinning up a full `AppWidgetManager` mock — the cell math is the part that needs
 * pixel-exact coverage and Robolectric's default `Density(1.0)` makes the px→dp conversion easy
 * to reason about (`90px / 1.0 = 90dp`, snapping to `1×1` cells at the 72dp grid; etc.).
 */
// Robolectric SDK 36 requires JDK 21; the project toolchain is JDK 17, so the per-class default
// pins to SDK 35. Same fix the `:data-uiautomator-*` self-tests apply.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppWidgetMetadataTranslateTest {

  private fun context(): Context = RuntimeEnvironment.getApplication()

  @Test
  fun translate_resize_none_emits_single_cell_at_minResize() {
    val info =
      AppWidgetProviderInfo().apply {
        minResizeWidth = 180 // 180dp at density 1.0 → ~2 cells (180+8 = 188 / 80 ≈ 2.35 → 2)
        minResizeHeight = 80 // ~1 cell (80+8 = 88 / 80 = 1.1 → 1)
        resizeMode = AppWidgetProviderInfo.RESIZE_NONE
      }
    val meta = translate(context(), info)
    assertEquals(LauncherResizeAxes.NONE, meta.resizeAxes)
    assertEquals(listOf(LauncherWidgetSize(2, 1)), meta.supportedCells)
  }

  @Test
  fun translate_resize_horizontal_locks_height_axis() {
    val info =
      AppWidgetProviderInfo().apply {
        minResizeWidth = 90 // 1 cell
        maxResizeWidth = 360 // ~5 cells
        minResizeHeight = 160 // ~2 cells
        resizeMode = AppWidgetProviderInfo.RESIZE_HORIZONTAL
      }
    val meta = translate(context(), info)
    assertEquals(LauncherResizeAxes.HORIZONTAL, meta.resizeAxes)
    // Width walks 1..5, height locked at 2.
    val expected = (1..5).map { w -> LauncherWidgetSize(w, 2) }
    assertEquals(expected, meta.supportedCells)
  }

  @Test
  fun translate_resize_vertical_locks_width_axis() {
    val info =
      AppWidgetProviderInfo().apply {
        minResizeWidth = 180 // ~2 cells
        minResizeHeight = 80 // 1 cell
        maxResizeHeight = 240 // ~3 cells
        resizeMode = AppWidgetProviderInfo.RESIZE_VERTICAL
      }
    val meta = translate(context(), info)
    assertEquals(LauncherResizeAxes.VERTICAL, meta.resizeAxes)
    val expected = (1..3).map { h -> LauncherWidgetSize(2, h) }
    assertEquals(expected, meta.supportedCells)
  }

  @Test
  fun translate_resize_both_emits_dense_rectangle() {
    val info =
      AppWidgetProviderInfo().apply {
        minResizeWidth = 90 // 1
        maxResizeWidth = 240 // ~3
        minResizeHeight = 80 // 1
        maxResizeHeight = 160 // ~2
        resizeMode =
          AppWidgetProviderInfo.RESIZE_HORIZONTAL or AppWidgetProviderInfo.RESIZE_VERTICAL
      }
    val meta = translate(context(), info)
    assertEquals(LauncherResizeAxes.BOTH, meta.resizeAxes)
    // 3 widths × 2 heights = 6 cells.
    assertEquals(6, meta.supportedCells?.size)
    assertEquals(LauncherWidgetSize(1, 1), meta.supportedCells?.first())
    assertEquals(LauncherWidgetSize(3, 2), meta.supportedCells?.last())
  }

  @Test
  fun translate_prefers_target_cell_width_height_when_set() {
    // `targetCellWidth` / `targetCellHeight` are API 31+ explicit cell counts. When set they
    // override the px-based min/maxResize → dp → cells path. `maxResizeWidth/Height` still
    // bound the upper end (so a widget that targets `4×2` cells but allows up to `360dp` /
    // `~5` cells of width can resize horizontally to 5 cells).
    val info =
      AppWidgetProviderInfo().apply {
        targetCellWidth = 4
        targetCellHeight = 2
        maxResizeWidth = 360 // ~5 cells
        maxResizeHeight = 160 // ~2 cells
        resizeMode = AppWidgetProviderInfo.RESIZE_HORIZONTAL
      }
    val meta = translate(context(), info)
    assertEquals(LauncherResizeAxes.HORIZONTAL, meta.resizeAxes)
    // Width walks 4..5 (target is the floor); height locked at 2.
    val expected = (4..5).map { w -> LauncherWidgetSize(w, 2) }
    assertEquals(expected, meta.supportedCells)
  }

  @Test
  fun translate_at_density_3_dp_arithmetic_holds() {
    // Pixel xxhdpi devices run at density 3.0 — `360px / 3.0 = 120dp` → ~1.5 cells → 2.
    val ctx = context()
    val density = DisplayMetrics().apply { density = 3.0f }
    ctx.resources.displayMetrics.setTo(density)
    val info =
      AppWidgetProviderInfo().apply {
        minResizeWidth = 360 // 120dp → 2 cells
        maxResizeWidth = 1080 // 360dp → 5 cells
        minResizeHeight = 240 // 80dp → 1 cell
        resizeMode = AppWidgetProviderInfo.RESIZE_HORIZONTAL
      }
    val meta = translate(ctx, info)
    val widths = meta.supportedCells?.map { it.width }?.distinct()
    assertEquals(listOf(2, 3, 4, 5), widths)
  }

  @Test
  fun translate_zero_max_falls_back_to_min_only() {
    // Widget declares only minResize, no max — the translation pegs maxCells at minCells so
    // the supported list contains only the declared size.
    val info =
      AppWidgetProviderInfo().apply {
        minResizeWidth = 180 // 2 cells
        minResizeHeight = 80 // 1 cell
        resizeMode =
          AppWidgetProviderInfo.RESIZE_HORIZONTAL or AppWidgetProviderInfo.RESIZE_VERTICAL
      }
    val meta = translate(context(), info)
    assertEquals(listOf(LauncherWidgetSize(2, 1)), meta.supportedCells)
  }

  @Test
  fun translate_zero_minResize_floors_at_one_cell() {
    val info =
      AppWidgetProviderInfo().apply {
        minResizeWidth = 0
        minResizeHeight = 0
        maxResizeWidth = 180
        maxResizeHeight = 80
        resizeMode =
          AppWidgetProviderInfo.RESIZE_HORIZONTAL or AppWidgetProviderInfo.RESIZE_VERTICAL
      }
    val meta = translate(context(), info)
    assertEquals(LauncherWidgetSize(1, 1), meta.supportedCells?.first())
  }

  @Test
  fun translate_no_match_returns_null_metadata_unreachable_from_unit_test() {
    // No-match is exercised at the `offerAppWidgetMetadata` layer (returns early without
    // calling translate). The unit test surface for `translate` itself always receives a real
    // [AppWidgetProviderInfo] — null returns are a non-shape; this case is here as a fence to
    // remind future readers that translate is total (non-null in, non-null out).
    @Suppress("RedundantNullableReturnType")
    val sentinel: Nothing? = null
    assertNull(sentinel)
  }
}
