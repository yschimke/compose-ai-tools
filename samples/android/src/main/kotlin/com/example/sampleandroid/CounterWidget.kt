package com.example.sampleandroid

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.glance.Button
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionStartActivity
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider

private val tapCount = intPreferencesKey("taps")

/**
 * A Glance widget whose button counts taps through an `actionRunCallback`: the callback runs in the
 * app's process (Glance's action receiver, then [IncrementAction]), bumps the widget's state and
 * calls `update`, which recomposes the widget in a Glance session worker and hands the new
 * `RemoteViews` to `AppWidgetManager`. `compose-preview launch --launcher` drives this end to end.
 */
class CounterWidget : GlanceAppWidget() {
  override suspend fun provideGlance(context: Context, id: GlanceId) {
    provideContent { CounterContent(currentState(tapCount) ?: 0) }
  }
}

@Composable
private fun CounterContent(count: Int) {
  Column(
    modifier =
      GlanceModifier.fillMaxSize().background(ColorProvider(Color(0xFF00695C))).padding(12.dp)
  ) {
    Text(
      text = "Glance counter",
      style = TextStyle(color = ColorProvider(Color.White), fontWeight = FontWeight.Bold),
    )
    Text(
      text = "$count taps",
      style = TextStyle(color = ColorProvider(Color.White), fontSize = 28.sp),
    )
    Row {
      Button(text = "+1", onClick = actionRunCallback<IncrementAction>())
      Spacer(GlanceModifier.width(8.dp))
      Button(text = "Open app", onClick = actionStartActivity<MainActivity>())
    }
  }
}

/** Counts a tap in the widget's state and redraws it. */
class IncrementAction : ActionCallback {
  override suspend fun onAction(
    context: Context,
    glanceId: GlanceId,
    parameters: ActionParameters,
  ) {
    updateAppWidgetState(context, glanceId) { prefs ->
      prefs[tapCount] = (prefs[tapCount] ?: 0) + 1
    }
    CounterWidget().update(context, glanceId)
  }
}

class CounterWidgetReceiver : GlanceAppWidgetReceiver() {
  override val glanceAppWidget: GlanceAppWidget = CounterWidget()
}
