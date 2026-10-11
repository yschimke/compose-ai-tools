package ee.schimke.composeai.preview.appwidget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.RemoteViews
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import ee.schimke.composeai.daemon.LauncherWidgetMetadata
import ee.schimke.composeai.daemon.LauncherWidgetMetadataChannel
import ee.schimke.composeai.daemon.protocol.LauncherResizeAxes
import ee.schimke.composeai.daemon.protocol.LauncherWidgetSize
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Inflates a `RemoteViews` [factory] into the surrounding Compose tree (via `RemoteViews.apply`, as
 * `AppWidgetHost.createView(...)` does on-device) and offers matching `<appwidget-provider>`
 * metadata to the launcher-widget data product (see [offerAppWidgetMetadata]).
 *
 * The view is hosted `MATCH_PARENT × MATCH_PARENT`, so `@Preview(widthDp, heightDp)` (or
 * `LauncherWidgetExtension`) controls the footprint.
 *
 * @param factory the `RemoteViews` factory, e.g. `RemoteViews(context.packageName,
 *   R.layout.widget_x).apply { setTextViewText(...) }`.
 */
@Composable
fun AppWidgetContent(factory: (Context) -> RemoteViews) {
  val context = LocalContext.current
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
      val remoteViews = factory(context)
      offerAppWidgetMetadata(context, remoteViews)
      val view: View = remoteViews.apply(context, parent)
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
 * Match [remoteViews]'s `layoutId` against registered providers' `initialLayout` and offer the
 * translated metadata to [LauncherWidgetMetadataChannel]. No-op without a match or outside a daemon
 * render. Internal for unit tests.
 */
internal fun offerAppWidgetMetadata(context: Context, remoteViews: RemoteViews) {
  if (LauncherWidgetMetadataChannel.currentPreviewId() == null) return
  val manager = AppWidgetManager.getInstance(context) ?: return
  val providers: List<AppWidgetProviderInfo> = manager.installedProviders
  val match = providers.firstOrNull { it.initialLayout == remoteViews.layoutId } ?: return
  LauncherWidgetMetadataChannel.offer(translate(context, match))
}

/**
 * Translate an `AppWidgetProviderInfo` into [LauncherWidgetMetadata]. Cells come from
 * `targetCellWidth/Height` (Android 12+) when set, else from the `min/maxResize` dp range using the
 * connector's `72dp` cell / `8dp` spacing: `cells = round((dp + spacing) / (cell + spacing))`, ≥ 1.
 */
internal fun translate(context: Context, info: AppWidgetProviderInfo): LauncherWidgetMetadata {
  val density = context.resources.displayMetrics.density
  // `maxResize` bounds the supported-cells rectangle; when missing, `minResize` is the only size.
  val minWidthCells =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && info.targetCellWidth > 0) {
      info.targetCellWidth
    } else {
      pxToCells(info.minResizeWidth, density)
    }
  val minHeightCells =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && info.targetCellHeight > 0) {
      info.targetCellHeight
    } else {
      pxToCells(info.minResizeHeight, density)
    }
  val maxWidthPx =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) info.maxResizeWidth else info.minResizeWidth
  val maxHeightPx =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      info.maxResizeHeight
    } else {
      info.minResizeHeight
    }
  val maxWidthCells = pxToCells(maxWidthPx, density).coerceAtLeast(minWidthCells)
  val maxHeightCells = pxToCells(maxHeightPx, density).coerceAtLeast(minHeightCells)

  val resizeAxes =
    when {
      // `resizeMode` is a bitmask: NONE=0x0, HORIZONTAL=0x1, VERTICAL=0x2 — masked combinations
      // give us the four LauncherResizeAxes values 1-for-1.
      info.resizeMode == AppWidgetProviderInfo.RESIZE_NONE -> LauncherResizeAxes.NONE
      info.resizeMode == AppWidgetProviderInfo.RESIZE_HORIZONTAL -> LauncherResizeAxes.HORIZONTAL
      info.resizeMode == AppWidgetProviderInfo.RESIZE_VERTICAL -> LauncherResizeAxes.VERTICAL
      else -> LauncherResizeAxes.BOTH
    }

  // For `resizeMode = NONE` the only supported size is the minResize cell pair — emit it as a
  // singleton so a picker can render the read-only badge without inventing a range.
  val supportedCells: List<LauncherWidgetSize>? =
    when (resizeAxes) {
      LauncherResizeAxes.NONE -> listOf(LauncherWidgetSize(minWidthCells, minHeightCells))
      else -> {
        // Build a dense rectangle min..max along the resize axes; lock the non-resizable axis to
        // its `minResize` value.
        val widths =
          when (resizeAxes) {
            LauncherResizeAxes.VERTICAL -> listOf(minWidthCells)
            else -> (minWidthCells..maxWidthCells).toList()
          }
        val heights =
          when (resizeAxes) {
            LauncherResizeAxes.HORIZONTAL -> listOf(minHeightCells)
            else -> (minHeightCells..maxHeightCells).toList()
          }
        widths.flatMap { w -> heights.map { h -> LauncherWidgetSize(w, h) } }
      }
    }

  return LauncherWidgetMetadata(supportedCells = supportedCells, resizeAxes = resizeAxes)
}

private const val DEFAULT_CELL_SIZE_DP: Int = 72
private const val DEFAULT_CELL_SPACING_DP: Int = 8

private fun pxToCells(px: Int, density: Float): Int {
  if (px <= 0) return 1
  val dp = px / density
  val divisor = (DEFAULT_CELL_SIZE_DP + DEFAULT_CELL_SPACING_DP).toFloat()
  val raw = ((dp + DEFAULT_CELL_SPACING_DP) / divisor).roundToInt()
  return max(1, raw)
}
