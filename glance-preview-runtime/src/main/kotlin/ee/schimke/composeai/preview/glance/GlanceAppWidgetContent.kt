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
import androidx.glance.appwidget.composeForPreview
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
