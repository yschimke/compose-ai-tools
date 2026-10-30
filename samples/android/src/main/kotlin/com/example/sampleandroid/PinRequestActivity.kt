package com.example.sampleandroid

import android.app.Activity
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.os.Bundle

/**
 * Asks the launcher to pin something, then finishes: the weather widget (`requestPinAppWidget`,
 * with a success callback to [WeatherAppWidgetReceiver]), or with [EXTRA_SHORTCUT] a Now Playing
 * shortcut (`requestPinShortcut`). No UI of its own: reached from the "Pin weather widget" dynamic
 * shortcut [MainActivity] publishes, or `startActivity`.
 */
class PinRequestActivity : Activity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    if (intent.getBooleanExtra(EXTRA_SHORTCUT, false)) {
      val shortcut =
        ShortcutInfo.Builder(this, "now_playing_pinned")
          .setShortLabel("Now Playing")
          .setIntent(Intent(this, NowPlayingActivity::class.java).setAction(Intent.ACTION_VIEW))
          .build()
      getSystemService(ShortcutManager::class.java).requestPinShortcut(shortcut, null)
    } else {
      val callback =
        PendingIntent.getBroadcast(
          this,
          0,
          Intent(this, WeatherAppWidgetReceiver::class.java)
            .setAction(WeatherAppWidgetReceiver.ACTION_PINNED),
          PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
      AppWidgetManager.getInstance(this)
        .requestPinAppWidget(
          ComponentName(this, WeatherAppWidgetReceiver::class.java),
          null,
          callback,
        )
    }
    finish()
  }

  companion object {
    const val EXTRA_SHORTCUT = "shortcut"
  }
}

/** The dynamic shortcut [MainActivity] publishes: it pins the weather widget. */
fun publishPinShortcut(activity: Activity) {
  val shortcut =
    ShortcutInfo.Builder(activity, "pin_weather")
      .setShortLabel("Pin weather")
      .setLongLabel("Pin the weather widget")
      .setIntent(Intent(activity, PinRequestActivity::class.java).setAction(Intent.ACTION_VIEW))
      .build()
  activity.getSystemService(ShortcutManager::class.java)?.dynamicShortcuts = listOf(shortcut)
}
