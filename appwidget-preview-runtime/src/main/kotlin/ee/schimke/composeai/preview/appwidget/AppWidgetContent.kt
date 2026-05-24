package ee.schimke.composeai.preview.appwidget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
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
 * Composable helper that inflates a `RemoteViews` factory into the surrounding Compose tree and
 * auto-discovers `<appwidget-provider>` metadata for the inflated layout.
 *
 * Inflation path:
 * 1. Run [factory] to build the `RemoteViews` against the surrounding `LocalContext`.
 * 2. `RemoteViews.apply(context, parent)` inflates the tree into a `View` we host inside the
 *    `AndroidView` factory — the same path `AppWidgetHost.createView(...)` walks on-device.
 * 3. Look up `AppWidgetManager.installedProviders` for any registered AppWidget whose
 *    `initialLayout` matches the inflated `RemoteViews.layoutId`. If found, translate the
 *    matching `AppWidgetProviderInfo` (`min/maxResizeWidth/Height`, `targetCellWidth/Height`,
 *    `resizeMode`) into a [LauncherWidgetMetadata] snapshot and offer it to the connector's
 *    [LauncherWidgetMetadataChannel] so the launcher-widget data product surfaces it on its
 *    payload. Falls through silently when no match — picker behaviour is unchanged for
 *    one-off `RemoteViews` previews that aren't backed by a registered receiver.
 *
 * The inflated view is hosted inside `MATCH_PARENT × MATCH_PARENT` so a `@Preview(widthDp,
 * heightDp)` (or the `LauncherWidgetExtension` daemon-side override) controls the visible
 * footprint.
 *
 * @param factory consumer's `RemoteViews` factory. Typically
 *   `RemoteViews(context.packageName, R.layout.widget_x).apply { setTextViewText(...) }`.
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
 * Look up [remoteViews]'s `layoutId` against the consumer's registered AppWidget providers and
 * offer the matched metadata to [LauncherWidgetMetadataChannel]. No-op when no provider matches
 * (one-off `RemoteViews` previews not backed by a manifest receiver) or when running outside a
 * daemon render (the channel's ThreadLocal previewId is unset). Extracted as `internal` so the
 * connector's unit tests can exercise the translation against a fake [AppWidgetProviderInfo]
 * without standing up a full `AppWidgetManager`.
 */
internal fun offerAppWidgetMetadata(context: Context, remoteViews: RemoteViews) {
  if (LauncherWidgetMetadataChannel.currentPreviewId() == null) return
  val manager = AppWidgetManager.getInstance(context) ?: return
  val providers: List<AppWidgetProviderInfo> = manager.installedProviders
  val match = providers.firstOrNull { it.initialLayout == remoteViews.layoutId } ?: return
  LauncherWidgetMetadataChannel.offer(translate(context, match))
}

/**
 * Translate an `AppWidgetProviderInfo` into a [LauncherWidgetMetadata] snapshot. Cell counts
 * derive from `targetCellWidth/Height` (Android 12+) when set, otherwise from the
 * `min/maxResizeWidth/Height` dp range using the same `72dp` cell / `8dp` spacing arithmetic
 * the connector applies — `cells = round((dp + spacing) / (cell + spacing))`, clamped to ≥ 1.
 */
internal fun translate(
  context: Context,
  info: AppWidgetProviderInfo,
): LauncherWidgetMetadata {
  val density = context.resources.displayMetrics.density
  // Prefer the explicit `targetCellWidth/Height` (API 31+) when set. Fall back to the
  // px-based `minResizeWidth/Height` → dp → cells path otherwise. `maxResizeWidth/Height`
  // bound the supported-cells rectangle on the top end; missing values fall back to the
  // platform default (no upper cap → use `minResize` as the only declared size).
  val minWidthCells =
    if (info.targetCellWidth > 0) info.targetCellWidth else pxToCells(info.minResizeWidth, density)
  val minHeightCells =
    if (info.targetCellHeight > 0) info.targetCellHeight else pxToCells(info.minResizeHeight, density)
  val maxWidthCells =
    pxToCells(info.maxResizeWidth, density).coerceAtLeast(minWidthCells)
  val maxHeightCells =
    pxToCells(info.maxResizeHeight, density).coerceAtLeast(minHeightCells)

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
      LauncherResizeAxes.NONE ->
        listOf(LauncherWidgetSize(minWidthCells, minHeightCells))
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
