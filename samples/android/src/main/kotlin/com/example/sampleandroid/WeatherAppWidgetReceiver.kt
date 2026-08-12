package com.example.sampleandroid

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.widget.RemoteViews

/**
 * The single place `widget_weather.xml` is populated (issue #3671).
 *
 * Both the production [WeatherAppWidgetReceiver.onUpdate] path and every `@Preview` in
 * `AppWidgetPreviews.kt` build their `RemoteViews` through this one factory. They used to each
 * inline their own `RemoteViews(packageName, R.layout.widget_weather).apply { … }` block, and the
 * copies drifted: the receiver said `"Partly cloudy · H 70° / L 55°"` while three of the previews
 * had been left on a bare `"Partly cloudy"`, so the committed renders no longer showed what the
 * shipped widget actually paints. A preview that silently disagrees with production is worse than
 * no preview, and a consumer copying this sample would inherit the same two-copies-of-the-truth
 * shape.
 *
 * The parameters are the widget's *data* — a real receiver would pass a freshly fetched forecast
 * here instead of taking the defaults. The defaults are this sample's canned San Francisco
 * forecast, which is what makes a call site that wants the production content a bare
 * `weatherRemoteViews(context)`. Preview variants that deliberately differ (a shorter title for a
 * `1×1` cell, a label naming the behaviour a preview demonstrates) pass those differences as
 * arguments, so the variation stays visible and intentional rather than being a divergent copy.
 */
fun weatherRemoteViews(
  context: Context,
  title: String = "San Francisco",
  temperature: String = "67°",
  condition: String = "Partly cloudy · H 70° / L 55°",
): RemoteViews =
  RemoteViews(context.packageName, R.layout.widget_weather).apply {
    setTextViewText(R.id.widget_title, title)
    setTextViewText(R.id.widget_temperature, temperature)
    setTextViewText(R.id.widget_condition, condition)
  }

/**
 * Minimal legacy `AppWidgetProvider` for the weather widget — registered in the manifest so
 * `AppWidgetManager.installedProviders` returns a matching entry for `R.layout.widget_weather`. The
 * auto-discovery path in `:appwidget-preview-runtime`'s `AppWidgetContent` looks up the inflated
 * `RemoteViews.layoutId` against this provider's `initialLayout` to surface `<appwidget-provider>`
 * metadata (`min/maxResizeWidth/Height`, `targetCellWidth/Height`, `resizeMode`) on the
 * launcher-widget data product's payload.
 *
 * The `onUpdate(...)` body pushes exactly what the previews render, because both go through
 * [weatherRemoteViews] — that shared factory is the point, not an incidental tidy-up. The body
 * isn't exercised by the preview pipeline (which never registers an `AppWidgetHost`); it's here so
 * a consumer running the sample app on a real device sees the widget work.
 */
class WeatherAppWidgetReceiver : AppWidgetProvider() {
  override fun onUpdate(
    context: Context,
    appWidgetManager: AppWidgetManager,
    appWidgetIds: IntArray,
  ) {
    val views = weatherRemoteViews(context)
    for (id in appWidgetIds) {
      appWidgetManager.updateAppWidget(id, views)
    }
  }
}
