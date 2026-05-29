@file:OptIn(androidx.glance.ExperimentalGlanceApi::class)

package ee.schimke.composeai.preview.glance

import android.appwidget.AppWidgetProviderInfo
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.viewinterop.AndroidView
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.composeForPreview
import ee.schimke.composeai.daemon.LauncherWidgetMetadata
import ee.schimke.composeai.daemon.LauncherWidgetMetadataChannel
import ee.schimke.composeai.daemon.protocol.LauncherResizeAxes
import ee.schimke.composeai.daemon.protocol.LauncherWidgetSize
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.runBlocking

/**
 * Composable helper that materialises a [GlanceAppWidget] to `RemoteViews` and inflates the result
 * into the surrounding Compose tree.
 *
 * Pairs with a normal `@Preview` so authors can fan out variants of the same Glance widget across
 * the existing `@Preview` knobs (`uiMode`, `locale`, `widthDp`, `heightDp`, `fontScale`) without
 * the renderer needing a Glance-specific discovery branch — the fan-out is driven by Compose
 * tooling, the materialisation by Glance's own `composeForPreview(...)` runtime API.
 *
 * Inflation path mirrors the renderer-side `NotificationPreviewComposable` /
 * `NotificationContent` pair: `GlanceAppWidget.composeForPreview(context, widgetCategory, info)`
 * → `RemoteViews` → `RemoteViews.apply(context, parent)` → inflated `View` hosted inside
 * `AndroidView`. This is the same surface `AppWidgetHost.createView(...)` walks on-device, so the
 * captured PNG is the same pixel tree the launcher would draw if the widget were placed in a
 * cell of the configured size.
 *
 * **Sizing.** Glance reads the laid-out size from `LocalSize` inside the preview composition.
 * For the default `SizeMode.Single`, that size is `AppWidgetProviderInfo.minWidth /
 * minHeight` (in px). The helper synthesises a fresh [AppWidgetProviderInfo] from [size] when it
 * is non-null — `minWidth` / `minHeight` get `size × density`, scaled into pixels against the
 * caller's [LocalDensity]. Leaving [size] null hands `info = null` to Glance, which yields
 * `DpSize.Zero` — useful for `SizeMode.Responsive` widgets that drive their own size catalogue
 * but produces a blank capture for `SizeMode.Single`. Mirror the surrounding
 * `@Preview(widthDp, heightDp)` here when in doubt.
 *
 * `composeForPreview` is `suspend` because Glance's production path supports off-thread state
 * extraction. The helper drives it with [runBlocking] inside the [AndroidView] factory — the
 * factory is invoked once during initial layout, so the blocking happens during the first
 * measure pass and not on every recomposition.
 *
 * @param widget the `GlanceAppWidget` whose `providePreview(...)` content should render. The
 *   widget instance is short-lived — the helper calls `composeForPreview` once and discards the
 *   widget after the inflate completes.
 * @param size dp footprint plumbed into `LocalSize` for the preview composition. Defaults to
 *   `null` (Glance uses `DpSize.Zero`); set this to the surrounding `@Preview(widthDp,
 *   heightDp)` to size `SizeMode.Single` widgets correctly.
 * @param widgetCategory bitmask matching `AppWidgetProviderInfo.WIDGET_CATEGORY_*`. Defaults to
 *   `WIDGET_CATEGORY_HOME_SCREEN` — the same default Glance applies when the system invokes
 *   `setWidgetPreview` for a launcher-picker entry.
 */
@Composable
fun GlanceAppWidgetContent(
  widget: GlanceAppWidget,
  size: DpSize? = null,
  widgetCategory: Int = AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN,
) {
  val context = LocalContext.current
  val density = LocalDensity.current
  AndroidView(
    modifier = Modifier.fillMaxSize(),
    factory = { ctx ->
      val parent =
        FrameLayout(ctx).apply {
          layoutParams =
            ViewGroup.LayoutParams(
              ViewGroup.LayoutParams.MATCH_PARENT,
              ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
      val info =
        size?.let {
          AppWidgetProviderInfo().apply {
            minWidth = with(density) { it.width.toPx() }.toInt()
            minHeight = with(density) { it.height.toPx() }.toInt()
          }
        }
      // Offer the widget's declared size mode into the per-render metadata channel BEFORE the
      // compose call so `LauncherWidgetDataProductRegistry.onRender` (which runs after the
      // render completes) picks up the supported-cells + resize-axes constraints. No-op when
      // running outside a daemon render (the channel's ThreadLocal previewId is unset).
      offerSizeModeMetadata(widget)
      val remoteViews =
        runBlocking { widget.composeForPreview(context, widgetCategory, info) }
      val view = remoteViews.apply(context, parent)
      parent.addView(
        view,
        FrameLayout.LayoutParams(
          ViewGroup.LayoutParams.MATCH_PARENT,
          ViewGroup.LayoutParams.MATCH_PARENT,
        ),
      )
      parent
    },
  )
}

/**
 * Reads the [GlanceAppWidget.previewSizeMode] and translates it into a [LauncherWidgetMetadata]
 * snapshot the channel transports to the connector's registry post-render. Cell counts derive
 * from each declared [DpSize] using the same `72dp` cell / `8dp` spacing arithmetic the
 * connector applies — `widthCells = round((widthDp + spacing) / (cell + spacing))`, clamped to
 * at least 1.
 *
 * `previewSizeMode` describes how Glance generates **preview** compositions — not whether the
 * installed widget is actually resizable. Resizability is a property of the launcher widget's
 * provider metadata XML (`android:resizeMode`, `android:targetCellWidth`, etc.) and is not
 * recoverable from `SizeMode` alone. So:
 *
 * Translation table:
 * - [SizeMode.Single] → `supportedCells = null`, `resizeAxes = Both`. Glance composes a single
 *   preview at the default size, but that says nothing about the installed widget; defer the
 *   constraint fields to provider metadata.
 * - [SizeMode.Responsive] → `supportedCells = set.toCells()`, `resizeAxes = Both`. The widget
 *   author explicitly enumerated a size catalogue here, so we surface it; the picker should
 *   show only these sizes.
 * - [SizeMode.Exact] (and any future SizeMode the connector doesn't recognise) →
 *   `supportedCells = null`, `resizeAxes = Both`. Widget composes at whatever size it's given;
 *   no constraint to surface.
 */
private fun offerSizeModeMetadata(widget: GlanceAppWidget) {
  if (LauncherWidgetMetadataChannel.currentPreviewId() == null) return
  val mode = widget.previewSizeMode
  val metadata =
    when (mode) {
      is SizeMode.Responsive ->
        LauncherWidgetMetadata(
          supportedCells = mode.sizes.map { it.toCells() },
          resizeAxes = LauncherResizeAxes.BOTH,
        )
      else ->
        // `SizeMode.Single`, `SizeMode.Exact`, and any future SizeMode the connector doesn't
        // recognise — leave supportedCells null so the picker falls back to provider metadata
        // (or its default rectangle), and signal that resizing is allowed. `SizeMode.Single`
        // is a preview-generation directive, not a "widget is non-resizable" advertisement —
        // mapping it to `resizeAxes = NONE` falsely disabled drag handles for widgets whose
        // provider metadata says they're resizable.
        LauncherWidgetMetadata(supportedCells = null, resizeAxes = LauncherResizeAxes.BOTH)
    }
  LauncherWidgetMetadataChannel.offer(metadata)
}

private const val DEFAULT_CELL_SIZE_DP: Int = 72
private const val DEFAULT_CELL_SPACING_DP: Int = 8

private fun DpSize.toCells(): LauncherWidgetSize =
  LauncherWidgetSize(
    width = dpToCells(width.value),
    height = dpToCells(height.value),
  )

/**
 * Inverse of the connector's `widthDp = cellSize * cells + spacing * (cells - 1)` arithmetic:
 * `cells = round((dp + spacing) / (cell + spacing))`, floored at 1. Matches what a launcher's
 * cell-snap logic does when laying out a Glance widget at a Responsive `DpSize`.
 */
private fun dpToCells(dp: Float): Int {
  val divisor = (DEFAULT_CELL_SIZE_DP + DEFAULT_CELL_SPACING_DP).toFloat()
  val raw = ((dp + DEFAULT_CELL_SPACING_DP) / divisor).roundToInt()
  return max(1, raw)
}
