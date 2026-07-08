package com.example.sampleandroid

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import ee.schimke.composeai.preview.NotificationPreview

private const val CHANNEL_ID = "sample"

private fun ensureChannel(context: Context) {
  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
    val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    if (nm.getNotificationChannel(CHANNEL_ID) == null) {
      nm.createNotificationChannel(
        NotificationChannel(CHANNEL_ID, "Sample", NotificationManager.IMPORTANCE_DEFAULT)
      )
    }
  }
}

@NotificationPreview
fun simpleNotificationPreview(context: Context): Notification {
  ensureChannel(context)
  return NotificationCompat.Builder(context, CHANNEL_ID)
    .setSmallIcon(android.R.drawable.ic_dialog_info)
    .setContentTitle("New message")
    .setContentText("Hello from compose-preview")
    .build()
}

@NotificationPreview
fun bigTextNotificationPreview(context: Context): Notification {
  ensureChannel(context)
  return NotificationCompat.Builder(context, CHANNEL_ID)
    .setSmallIcon(android.R.drawable.ic_dialog_email)
    .setContentTitle("Long-form update")
    .setContentText("Tap to read")
    .setStyle(
      NotificationCompat.BigTextStyle()
        .bigText(
          "Notification previews now render through the same Robolectric pipeline " +
            "Compose @Preview uses. This is the BigTextStyle variant — the expanded " +
            "shade layout, drawn at AOSP fidelity (no Pixel / OEM chrome)."
        )
    )
    .build()
}
