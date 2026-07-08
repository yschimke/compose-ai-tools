package com.example.sampleandroid

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.widget.RemoteViews

/**
 * Minimal legacy `AppWidgetProvider` for the weather widget — registered in the manifest so
 * `AppWidgetManager.installedProviders` returns a matching entry for `R.layout.widget_weather`. The
 * auto-discovery path in `:appwidget-preview-runtime`'s `AppWidgetContent` looks up the inflated
 * `RemoteViews.layoutId` against this provider's `initialLayout` to surface `<appwidget-provider>`
 * metadata (`min/maxResizeWidth/Height`, `targetCellWidth/Height`, `resizeMode`) on the
 * launcher-widget data product's payload.
 *
 * The `onUpdate(...)` body intentionally just mirrors the same render path the preview uses — the
 * production widget pushes the same `RemoteViews` shape via `appWidgetManager.updateAppWidget`. The
 * body isn't exercised by the preview pipeline (which never registers an `AppWidgetHost`); it's
 * here so a consumer running the sample app on a real device sees the widget work.
 */
class WeatherAppWidgetReceiver : AppWidgetProvider() {
  override fun onUpdate(
    context: Context,
    appWidgetManager: AppWidgetManager,
    appWidgetIds: IntArray,
  ) {
    val views =
      RemoteViews(context.packageName, R.layout.widget_weather).apply {
        setTextViewText(R.id.widget_title, "San Francisco")
        setTextViewText(R.id.widget_temperature, "67°")
        setTextViewText(R.id.widget_condition, "Partly cloudy · H 70° / L 55°")
      }
    for (id in appWidgetIds) {
      appWidgetManager.updateAppWidget(id, views)
    }
  }
}
