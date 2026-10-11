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
 * Materialises a [GlanceAppWidget] to `RemoteViews` and inflates it into the surrounding Compose
 * tree, so a normal `@Preview` can fan a Glance widget out over `uiMode`, `locale`, sizes and
 * `fontScale`.
 *
 * `GlanceAppWidget.composeForPreview(context, widgetCategory, info)` → `RemoteViews` →
 * `RemoteViews.apply(...)` inside `AndroidView` — the same path `AppWidgetHost.createView(...)`
 * walks on-device. `composeForPreview` is `suspend`; it runs via [runBlocking] in the factory,
 * once.
 *
 * @param widget the widget whose `providePreview(...)` content renders. @param size dp footprint
 * for `LocalSize`, synthesised into an [AppWidgetProviderInfo]. Null gives
 *   `DpSize.Zero`, which blanks `SizeMode.Single` widgets; mirror the `@Preview` size when in doubt.
 * @param widgetCategory `AppWidgetProviderInfo.WIDGET_CATEGORY_*`; defaults to the home screen.
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
      val info = size?.let {
        AppWidgetProviderInfo().apply {
          minWidth = with(density) { it.width.toPx() }.toInt()
          minHeight = with(density) { it.height.toPx() }.toInt()
        }
      }
      // Offer the size mode before composing so the launcher-widget registry sees it post-render.
      // No-op outside a daemon render.
      offerSizeModeMetadata(widget)
      val remoteViews = runBlocking { widget.composeForPreview(context, widgetCategory, info) }
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
 * Translates [GlanceAppWidget.previewSizeMode] into [LauncherWidgetMetadata] for the connector's
 * registry. `previewSizeMode` describes preview generation, not installed resizability (that's the
 * provider XML), so only [SizeMode.Responsive]'s explicit catalogue becomes `supportedCells`;
 * everything else leaves it null with `resizeAxes = Both`.
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
        // Not `resizeAxes = NONE`: `SizeMode.Single` doesn't mean non-resizable, and that disabled
        // drag handles for resizable widgets.
        LauncherWidgetMetadata(supportedCells = null, resizeAxes = LauncherResizeAxes.BOTH)
    }
  LauncherWidgetMetadataChannel.offer(metadata)
}

private const val DEFAULT_CELL_SIZE_DP: Int = 72
private const val DEFAULT_CELL_SPACING_DP: Int = 8

private fun DpSize.toCells(): LauncherWidgetSize =
  LauncherWidgetSize(width = dpToCells(width.value), height = dpToCells(height.value))

/**
 * Inverse of the connector's `widthDp = cellSize * cells + spacing * (cells - 1)`, rounded and
 * floored at 1, as launcher cell snapping does.
 */
private fun dpToCells(dp: Float): Int {
  val divisor = (DEFAULT_CELL_SIZE_DP + DEFAULT_CELL_SPACING_DP).toFloat()
  val raw = ((dp + DEFAULT_CELL_SPACING_DP) / divisor).roundToInt()
  return max(1, raw)
}
