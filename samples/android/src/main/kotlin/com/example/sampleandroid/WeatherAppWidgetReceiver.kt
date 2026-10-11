package com.example.sampleandroid

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.widget.RemoteViews

/**
 * The single place `widget_weather.xml` is populated: both [WeatherAppWidgetReceiver.onUpdate] and
 * every preview in `AppWidgetPreviews.kt` use it, so previews show what the shipped widget paints.
 *
 * Parameters are the widget's data (a real receiver would pass a fetched forecast); the defaults
 * are the sample's canned forecast. Previews that deliberately differ pass those differences as
 * arguments.
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
 * Minimal `AppWidgetProvider`, registered in the manifest so `AppWidgetContent` can match
 * `R.layout.widget_weather` against its `initialLayout` and surface the provider metadata.
 * `onUpdate` uses [weatherRemoteViews], like the previews; it only runs on a real device.
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
